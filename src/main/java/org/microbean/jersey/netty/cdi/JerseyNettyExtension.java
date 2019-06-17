/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2019 microBean™.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.jersey.netty.cdi;

import java.lang.annotation.Annotation;

import java.net.URI;
import java.net.URISyntaxException;

import java.nio.channels.spi.SelectorProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import java.util.function.BiFunction;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.BeforeDestroyed;
import javax.enterprise.context.Initialized;

import javax.enterprise.event.Observes;

import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.Instance;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.DeploymentException;
import javax.enterprise.inject.spi.Extension;

import javax.enterprise.util.TypeLiteral;

import javax.inject.Named;

import javax.ws.rs.ApplicationPath;

import javax.ws.rs.core.Application;
import javax.ws.rs.core.SecurityContext;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;

import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SelectStrategyFactory;
import io.netty.channel.ServerChannel;

import io.netty.channel.nio.NioEventLoopGroup;

import io.netty.channel.socket.nio.NioServerSocketChannel;

import io.netty.handler.codec.http.HttpRequest;

import io.netty.handler.ssl.SslContext;

import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.DefaultEventExecutorChooserFactory;

import org.glassfish.jersey.server.ApplicationHandler;

import org.microbean.configuration.api.Configurations;

import org.microbean.jaxrs.cdi.JaxRsExtension;

import org.microbean.jersey.netty.JerseyChannelInitializer;

public class JerseyNettyExtension implements Extension {

  private static final Annotation[] EMPTY_ANNOTATION_ARRAY = new Annotation[0];

  private final Collection<Throwable> shutdownProblems;

  private volatile Collection<EventExecutorGroup> eventExecutorGroups;

  private volatile CountDownLatch bindLatch;

  private volatile CountDownLatch runLatch;

  private volatile CountDownLatch shutdownLatch;

  public JerseyNettyExtension() {
    super();
    this.shutdownProblems = new ArrayList<>();
    final Thread containerThread = Thread.currentThread();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          zeroOut(this.runLatch);
          zeroOut(this.bindLatch);
          containerThread.interrupt();
          try {
            containerThread.join();
          } catch (final InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
          }
    }));
  }

  // TODO: prioritize
  private final void onStartup(@Observes @Initialized(ApplicationScoped.class)
                               final Object event,
                               final BeanManager beanManager)
    throws URISyntaxException {
    if (beanManager != null) {
      final JaxRsExtension extension = beanManager.getExtension(JaxRsExtension.class);
      if (extension != null) {
        final Set<Set<Annotation>> applicationQualifierSets = extension.getAllApplicationQualifiers();
        if (applicationQualifierSets != null && !applicationQualifierSets.isEmpty()) {

          final Instance<Object> instance = beanManager.createInstance();
          assert instance != null;

          final Configurations configurations = instance.select(Configurations.class).get();

          final Map<String, String> baseConfigurationCoordinates = configurations.getConfigurationCoordinates();

          final int size = applicationQualifierSets.size();
          assert size > 0;
          this.shutdownLatch = new CountDownLatch(size);
          this.bindLatch = new CountDownLatch(size);
          final Collection<Throwable> bindProblems = new ArrayList<>();

          for (final Set<Annotation> applicationQualifiers : applicationQualifierSets) {

            // Quick check to bail out if someone CTRL-Ced and caused
            // Weld's shutdown hook to fire.  That hook fires
            // a @BeforeDestroyed event on a Thread that is not this
            // Thread, so it's possible to be processing
            // an @Initialized(ApplicationScoped.class) event on the
            // container thread while also processing
            // a @BeforeDestroyed(ApplicationScoped.class) event.  We
            // basically want to skip bootstrapping a bunch of things
            // if we're going down anyway.
            //
            // Be particularly mindful later on of the state of the
            // latches.
            if (Thread.currentThread().isInterrupted()) {
              zeroOut(this.bindLatch);
              this.bindLatch = null;
              break;
            }

            final Annotation[] applicationQualifiersArray;
            if (applicationQualifiers == null) {
              applicationQualifiersArray = null;
            } else if (applicationQualifiers.isEmpty()) {
              applicationQualifiersArray = EMPTY_ANNOTATION_ARRAY;
            } else {
              applicationQualifiersArray = applicationQualifiers.toArray(new Annotation[applicationQualifiers.size()]);
            }

            final Set<Bean<?>> applicationBeans = beanManager.getBeans(Application.class, applicationQualifiersArray);
            assert applicationBeans != null;
            assert !applicationBeans.isEmpty();

            try {

              @SuppressWarnings("unchecked")
              final Bean<Application> applicationBean = (Bean<Application>)beanManager.resolve(applicationBeans);
              assert applicationBean != null;

              // TODO: need to somehow squirrel away creationalContext
              // and release it after the application goes out of
              // scope; kind of doesn't really matter because
              // Applications are long-lived but still.
              final Application application =
                (Application)beanManager.getReference(applicationBean,
                                                      Application.class,
                                                      beanManager.createCreationalContext(applicationBean));
              assert application != null;

              final ApplicationPath applicationPathAnnotation = application.getClass().getAnnotation(ApplicationPath.class);
              final String applicationPath;
              if (applicationPathAnnotation == null) {
                applicationPath = "/";
              } else {
                applicationPath = applicationPathAnnotation.value();
              }
              assert applicationPath != null;

              final ServerBootstrap serverBootstrap = getServerBootstrap(beanManager, instance, applicationQualifiersArray, true);
              assert serverBootstrap != null;

              final SslContext sslContext = getSslContext(beanManager, instance, applicationQualifiersArray, true);

              final Map<String, String> qualifierCoordinates = toConfigurationCoordinates(applicationQualifiers);
              final Map<String, String> configurationCoordinates;
              if (baseConfigurationCoordinates == null || baseConfigurationCoordinates.isEmpty()) {
                if (qualifierCoordinates == null || qualifierCoordinates.isEmpty()) {
                  configurationCoordinates = baseConfigurationCoordinates;
                } else {
                  configurationCoordinates = qualifierCoordinates;
                }
              } else if (qualifierCoordinates == null || qualifierCoordinates.isEmpty()) {
                configurationCoordinates = baseConfigurationCoordinates;
              } else {
                configurationCoordinates = new HashMap<>(baseConfigurationCoordinates);
                configurationCoordinates.putAll(qualifierCoordinates);
              }

              final URI baseUri;
              if (sslContext == null) {
                baseUri = new URI("http",
                                  null /* no userInfo */,
                                  configurations.getValue(configurationCoordinates, "host", "0.0.0.0"),
                                  configurations.getValue(configurationCoordinates, "port", Integer.TYPE, "8080"),
                                  applicationPath,
                                  null /* no query */,
                                  null /* no fragment */);
              } else {
                baseUri = new URI("https",
                                  null /* no userInfo */,
                                  configurations.getValue(configurationCoordinates, "host", "0.0.0.0"),
                                  configurations.getValue(configurationCoordinates, "port", Integer.TYPE, "443"),
                                  applicationPath,
                                  null /* no query */,
                                  null /* no fragment */);
              }
              assert baseUri != null;

              final BiFunction<? super ChannelHandlerContext, ? super HttpRequest, ? extends SecurityContext> securityContextBiFunction = null; // TODO

              serverBootstrap.childHandler(new JerseyChannelInitializer(baseUri,
                                                                        sslContext,
                                                                        new ApplicationHandler(application),
                                                                        securityContextBiFunction));
              serverBootstrap.validate();

              final ServerBootstrapConfig config = serverBootstrap.config();
              assert config != null;

              final EventLoopGroup group = config.group();
              assert group != null; // see validate() above
              group.terminationFuture()
                .addListener(f -> {
                    try {
                      if (!f.isSuccess()) {
                        final Throwable throwable = f.cause();
                        if (throwable != null) {
                          synchronized (this.shutdownProblems) {
                            this.shutdownProblems.add(throwable);
                          }
                        }
                      }
                    } finally {
                      this.shutdownLatch.countDown();
                    }
                  });

              Collection<EventExecutorGroup> eventExecutorGroups = this.eventExecutorGroups;
              if (eventExecutorGroups == null) {
                eventExecutorGroups = new ArrayList<>();
                this.eventExecutorGroups = eventExecutorGroups;
              }
              synchronized (eventExecutorGroups) {
                eventExecutorGroups.add(group);
              }

              final Future<?> bindFuture;
              if (config.localAddress() == null) {
                bindFuture = serverBootstrap.bind(baseUri.getHost(), baseUri.getPort());
              } else {
                bindFuture = serverBootstrap.bind();
              }
              bindFuture.addListener(f -> {
                    try {
                      if (!f.isSuccess()) {
                        final Throwable throwable = f.cause();
                        if (throwable != null) {
                          synchronized (bindProblems) {
                            bindProblems.add(throwable);
                          }
                        }
                      }
                    } finally {
                      final CountDownLatch latch = this.bindLatch;
                      if (latch != null) {
                        latch.countDown();
                      }
                    }
                  });

            } catch (final RuntimeException | URISyntaxException throwMe) {
              zeroOut(this.bindLatch);
              zeroOut(this.shutdownLatch);
              synchronized (bindProblems) {
                for (final Throwable bindProblem : bindProblems) {
                  throwMe.addSuppressed(bindProblem);
                }
              }
              throw throwMe;
            }

          }

          final CountDownLatch bindLatch = this.bindLatch;
          if (bindLatch != null) {
            try {
              bindLatch.await();
            } catch (final InterruptedException interruptedException) {
              Thread.currentThread().interrupt();
            }
            assert bindLatch.getCount() <= 0;
            this.bindLatch = null;
          }

          DeploymentException throwMe = null;
          synchronized (bindProblems) {
            for (final Throwable bindProblem : bindProblems) {
              if (throwMe == null) {
                throwMe = new DeploymentException(bindProblem);
              } else {
                throwMe.addSuppressed(bindProblem);
              }
            }
            bindProblems.clear();
          }
          if (throwMe != null) {
            zeroOut(this.shutdownLatch);
            throw throwMe;
          }

          this.runLatch = new CountDownLatch(1);

        }
      }
    }
    assert this.bindLatch == null;
  }

  private final void waitForAllServersToStop(@Observes @BeforeDestroyed(ApplicationScoped.class)
                                             final Object event) {

    // Note: somewhat interestingly, Weld can fire a @BeforeDestroyed
    // event on a thread that is not the container thread: a shutdown
    // hook that it installs.  So we have to take care to be thread
    // safe.

    final CountDownLatch runLatch = this.runLatch;
    if (runLatch != null) {
      try {
        runLatch.await();
      } catch (final InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
      }
      assert runLatch.getCount() <= 0;
      this.runLatch = null;
    }

    final Collection<EventExecutorGroup> eventExecutorGroups = this.eventExecutorGroups;
    if (eventExecutorGroups != null) {
      synchronized (eventExecutorGroups) {
        for (final EventExecutorGroup group : eventExecutorGroups) {
          // idempotent
          group.shutdownGracefully();
        }
        eventExecutorGroups.clear();
      }
      this.eventExecutorGroups = null;
    }

    final CountDownLatch shutdownLatch = this.shutdownLatch;
    if (shutdownLatch != null) {
      try {
        shutdownLatch.await();
      } catch (final InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
      }
      assert shutdownLatch.getCount() <= 0;
      this.shutdownLatch = null;
    }

    DeploymentException throwMe = null;
    synchronized (this.shutdownProblems) {
      for (final Throwable shutdownProblem : this.shutdownProblems) {
        if (throwMe == null) {
          throwMe = new DeploymentException(shutdownProblem);
        } else {
          throwMe.addSuppressed(shutdownProblem);
        }
      }
      this.shutdownProblems.clear();
    }
    if (throwMe != null) {
      throw throwMe;
    }

  }


  /*
   * Production and lookup methods.
   */


  private static final SslContext getSslContext(final BeanManager beanManager,
                                                          final Instance<Object> instance,
                                                          final Annotation[] qualifiersArray,
                                                          final boolean lookup) {
    return acquire(beanManager,
                   instance,
                   SslContext.class,
                   qualifiersArray,
                   lookup,
                   (bm, i, qa) -> null);
  }

  private static final ServerBootstrap getServerBootstrap(final BeanManager beanManager,
                                                          final Instance<Object> instance,
                                                          final Annotation[] qualifiersArray,
                                                          final boolean lookup) {
    return acquire(beanManager,
                   instance,
                   ServerBootstrap.class,
                   qualifiersArray,
                   lookup,
                   (bm, i, qa) -> {
                     final ServerBootstrap returnValue = new ServerBootstrap();
                     // See https://stackoverflow.com/a/28342821/208288
                     returnValue.group(getEventLoopGroup(bm, i, qa, true));
                     returnValue.channelFactory(getChannelFactory(bm, i, qa, true));

                     // Permit arbitrary customization
                     beanManager.getEvent().select(ServerBootstrap.class, qualifiersArray).fire(returnValue);
                     return returnValue;
                   });
  }

  private static final ChannelFactory<? extends ServerChannel> getChannelFactory(final BeanManager beanManager,
                                                                                 final Instance<Object> instance,
                                                                                 final Annotation[] qualifiersArray,
                                                                                 final boolean lookup) {
    return acquire(beanManager,
                   instance,
                   new TypeLiteral<ChannelFactory<? extends ServerChannel>>() {
                     private static final long serialVersionUID = 1L;
                   },
                   qualifiersArray,
                   lookup,
                   (bm, i, qa) -> {
                     final SelectorProvider selectorProvider = getSelectorProvider(bm, i, qa, true);
                     assert selectorProvider != null;
                     return () -> new NioServerSocketChannel(selectorProvider);
                   });
  }

  private static final EventLoopGroup getEventLoopGroup(final BeanManager beanManager,
                                                        final Instance<Object> instance,
                                                        final Annotation[] qualifiersArray,
                                                        final boolean lookup) {
    return acquire(beanManager,
                   instance,
                   EventLoopGroup.class,
                   qualifiersArray,
                   lookup,
                   (bm, i, qa) -> {
                     final EventLoopGroup returnValue =
                       new NioEventLoopGroup(0 /* 0 == default number of threads */,
                                             getExecutor(bm, i, qa, true), // null is OK
                                             getEventExecutorChooserFactory(bm, i, qa, true),
                                             getSelectorProvider(bm, i, qa, true),
                                             getSelectStrategyFactory(bm, i, qa, true),
                                             getRejectedExecutionHandler(bm, i, qa, true));
                     // Permit arbitrary customization.  (Not much you can do here
                     // except call setIoRatio(int).)
                     beanManager.getEvent().select(EventLoopGroup.class, qa).fire(returnValue);
                     return returnValue;
                   });
  }

  private static final Executor getExecutor(final BeanManager beanManager,
                                            final Instance<Object> instance,
                                            final Annotation[] qualifiersArray,
                                            final boolean lookup) {
    return acquire(beanManager,
                   instance,
                   Executor.class,
                   qualifiersArray,
                   lookup,
                   (bm, i, qa) -> null);
  }

  private static final RejectedExecutionHandler getRejectedExecutionHandler(final BeanManager beanManager,
                                                                            final Instance<Object> instance,
                                                                            final Annotation[] qualifiersArray,
                                                                            final boolean lookup) {
    return acquire(beanManager,
                   instance,
                   RejectedExecutionHandler.class,
                   qualifiersArray,
                   lookup,
                   (bm, i, qa) -> RejectedExecutionHandlers.reject());
  }

  private static final SelectorProvider getSelectorProvider(final BeanManager beanManager,
                                                            final Instance<Object> instance,
                                                            final Annotation[] qualifiersArray,
                                                            final boolean lookup) {
    return acquire(beanManager,
                   instance,
                   SelectorProvider.class,
                   qualifiersArray,
                   lookup,
                   (bm, i, qa) -> SelectorProvider.provider());
  }

  private static final SelectStrategyFactory getSelectStrategyFactory(final BeanManager beanManager,
                                                                      final Instance<Object> instance,
                                                                      final Annotation[] qualifiersArray,
                                                                      final boolean lookup) {
    return acquire(beanManager,
                   instance,
                   SelectStrategyFactory.class,
                   qualifiersArray,
                   lookup,
                   (bm, i, qa) -> DefaultSelectStrategyFactory.INSTANCE);
  }

  private static final EventExecutorChooserFactory getEventExecutorChooserFactory(final BeanManager beanManager,
                                                                                  final Instance<Object> instance,
                                                                                  final Annotation[] qualifiersArray,
                                                                                  final boolean lookup) {
    return acquire(beanManager,
                   instance,
                   EventExecutorChooserFactory.class,
                   qualifiersArray,
                   lookup,
                   (bm, i, qa) -> DefaultEventExecutorChooserFactory.INSTANCE);
  }


  /*
   * Static utility methods.
   */


  private static final <T> T acquire(final BeanManager beanManager,
                                     final Instance<Object> instance,
                                     final TypeLiteral<T> typeLiteral,
                                     final Annotation[] qualifiersArray,
                                     final boolean lookup,
                                     final DefaultValueFunction<? extends T> defaultValueFunction) {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(instance);
    Objects.requireNonNull(typeLiteral);
    Objects.requireNonNull(defaultValueFunction);

    final T returnValue;
    final Instance<? extends T> tInstance;
    if (lookup) {
      if (qualifiersArray == null || qualifiersArray.length <= 0) {
        tInstance = instance.select(typeLiteral);
      } else {
        tInstance = instance.select(typeLiteral, qualifiersArray);
      }
    } else {
      tInstance = null;
    }
    if (tInstance == null || tInstance.isUnsatisfied()) {
      returnValue = defaultValueFunction.getDefaultValue(beanManager, instance, qualifiersArray);
    } else {
      returnValue = tInstance.get();
    }
    return returnValue;
  }

  private static final <T> T acquire(final BeanManager beanManager,
                                     final Instance<Object> instance,
                                     final Class<T> cls,
                                     final Annotation[] qualifiersArray,
                                     final boolean lookup,
                                     final DefaultValueFunction<? extends T> defaultValueFunction) {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(instance);
    Objects.requireNonNull(cls);
    Objects.requireNonNull(defaultValueFunction);

    final T returnValue;
    final Instance<? extends T> tInstance;
    if (lookup) {
      if (qualifiersArray == null || qualifiersArray.length <= 0) {
        tInstance = instance.select(cls);
      } else {
        tInstance = instance.select(cls, qualifiersArray);
      }
    } else {
      tInstance = null;
    }
    if (tInstance == null || tInstance.isUnsatisfied()) {
      returnValue = defaultValueFunction.getDefaultValue(beanManager, instance, qualifiersArray);
    } else {
      returnValue = tInstance.get();
    }
    return returnValue;
  }

  private static final void zeroOut(final CountDownLatch latch) {
    if (latch != null) {
      while (latch.getCount() > 0L) {
        latch.countDown();
      }
      assert latch.getCount() == 0L;
    }
  }

  private static final Map<String, String> toConfigurationCoordinates(final Set<? extends Annotation> qualifiers) {
    final Map<String, String> returnValue = new HashMap<>();
    if (qualifiers != null && !qualifiers.isEmpty()) {
      for (final Annotation qualifier : qualifiers) {
        if (qualifier instanceof Named) {
          returnValue.put("name", ((Named)qualifier).value());
        } else if (!(qualifier instanceof Default) && !(qualifier instanceof Any)) {
          returnValue.put(qualifier.toString(), "");
        }
      }
    }
    return returnValue;
  }


  /*
   * Inner and nested classes.
   */


  @FunctionalInterface
  private static interface DefaultValueFunction<T> {

    T getDefaultValue(final BeanManager beanManager,
                      final Instance<Object> instance,
                      final Annotation[] qualifiersArray);

  }

}
