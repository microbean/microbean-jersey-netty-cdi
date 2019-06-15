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

import java.lang.reflect.Type;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

import java.nio.channels.spi.SelectorProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import java.util.function.BiFunction;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.BeforeDestroyed;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.Initialized;

import javax.enterprise.event.Observes;

import javax.enterprise.inject.Instance;

import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;

import javax.enterprise.util.TypeLiteral;

import javax.inject.Singleton;

import javax.ws.rs.ApplicationPath;

import javax.ws.rs.core.Application;
import javax.ws.rs.core.SecurityContext;

import io.netty.bootstrap.ServerBootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SelectStrategyFactory;
import io.netty.channel.ServerChannel;

import io.netty.channel.nio.NioEventLoopGroup;

import io.netty.channel.socket.nio.NioServerSocketChannel;

import io.netty.handler.codec.http.HttpRequest;

import io.netty.handler.ssl.SslContext;

import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.DefaultEventExecutorChooserFactory;

import org.glassfish.jersey.server.ApplicationHandler;

import org.microbean.jaxrs.cdi.JaxRsExtension;

import org.microbean.jersey.netty.JerseyChannelInitializer;

public class JerseyNettyExtension implements Extension {

  private static final Annotation[] EMPTY_ANNOTATION_ARRAY = new Annotation[0];

  private volatile CountDownLatch shutdownLatch;

  public JerseyNettyExtension() {
    super();
  }

  // TODO: prioritize to run after JaxRsExtension
  private final void afterBeanDiscovery(@Observes final AfterBeanDiscovery event,
                                        final BeanManager beanManager) {
    final JaxRsExtension extension = beanManager.getExtension(JaxRsExtension.class);
    if (extension != null) {
      final Set<Set<Annotation>> qualifierSets = extension.getAllApplicationQualifiers();
      if (qualifierSets != null && !qualifierSets.isEmpty()) {
        for (final Set<Annotation> qualifiers : qualifierSets) {
          final Annotation[] qualifiersArray;
          if (qualifiers == null) {
            qualifiersArray = null;
          } else if (qualifiers.isEmpty()) {
            qualifiersArray = EMPTY_ANNOTATION_ARRAY;
          } else {
            qualifiersArray = qualifiers.toArray(new Annotation[qualifiers.size()]);
          }

          if (noBeans(beanManager, ServerBootstrap.class, qualifiers)) {
            event.addBean()
              .scope(Dependent.class)
              .addTransitiveTypeClosure(ServerBootstrap.class)
              .addQualifiers(qualifiersArray)
              .produceWith(instance -> getServerBootstrap(beanManager, instance, qualifiersArray, false));
          }

        }

      }
    }
  }

  // TODO: prioritize
  private final void onStartup(@Observes @Initialized(ApplicationScoped.class)
                               final Object event,
                               final BeanManager beanManager)
    throws URISyntaxException {
    if (event != null && beanManager != null) {
      final JaxRsExtension extension = beanManager.getExtension(JaxRsExtension.class);
      if (extension != null) {
        final Set<Set<Annotation>> qualifierSets = extension.getAllApplicationQualifiers();
        if (qualifierSets != null && !qualifierSets.isEmpty()) {

          final Instance<Object> instance = beanManager.createInstance();
          assert instance != null;

          final int size = qualifierSets.size();
          assert size > 0;
          this.shutdownLatch = new CountDownLatch(size);
          final CountDownLatch startupLatch = new CountDownLatch(size);

          for (final Set<Annotation> qualifiers : qualifierSets) {

            final Annotation[] qualifiersArray;
            if (qualifiers == null) {
              qualifiersArray = null;
            } else if (qualifiers.isEmpty()) {
              qualifiersArray = EMPTY_ANNOTATION_ARRAY;
            } else {
              qualifiersArray = qualifiers.toArray(new Annotation[qualifiers.size()]);
            }

            final Set<Bean<?>> beans = beanManager.getBeans(Application.class, qualifiersArray);
            if (beans == null || beans.isEmpty()) {

              // Pretend that our initial count was one smaller since
              // this server won't even start.
              startupLatch.countDown();
              assert startupLatch.getCount() >= 0L : "Unexpected startup count: " + startupLatch.getCount();

              // Same with shutdowns.
              this.shutdownLatch.countDown();
              assert this.shutdownLatch.getCount() >= 0L : "Unexpected shutdown count: " + this.shutdownLatch.getCount();

            } else {

              try {

                @SuppressWarnings("unchecked")
                final Bean<Application> applicationBean = (Bean<Application>)beanManager.resolve(beans);
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

                final ServerBootstrap serverBootstrap = getServerBootstrap(beanManager, instance, qualifiersArray, true);
                assert serverBootstrap != null;

                final SslContext sslContext = getSslContext(beanManager, instance, qualifiersArray, true);

                final URI baseUri;
                if (sslContext == null) {
                  baseUri = new URI("http",
                                    null /* no userInfo */,
                                    "0.0.0.0", // TODO config
                                    8080, // TODO config
                                    applicationPath,
                                    null /* no query */,
                                    null /* no fragment */);
                } else {
                  baseUri = new URI("https",
                                    null /* no userInfo */,
                                    "0.0.0.0", // TODO config
                                    443, // TODO config
                                    applicationPath,
                                    null /* no query */,
                                    null /* no fragment */);
                }

                final BiFunction<? super ChannelHandlerContext, ? super HttpRequest, ? extends SecurityContext> securityContextBiFunction = null; // TODO

                serverBootstrap.childHandler(new JerseyChannelInitializer(baseUri, sslContext, new ApplicationHandler(application), securityContextBiFunction));

                serverBootstrap.validate();

                final ChannelFuture bindFuture = serverBootstrap.bind(baseUri.getHost(), baseUri.getPort());
                assert bindFuture != null;
                bindFuture.addListener(c -> startupLatch.countDown());

                final ChannelFuture closeFuture = bindFuture.channel().closeFuture();
                assert closeFuture != null;
                closeFuture.addListener(c -> this.shutdownLatch.countDown());

                try {
                  startupLatch.await();
                } catch (final InterruptedException interruptedException) {
                  Thread.currentThread().interrupt();
                }
                
              } catch (final RuntimeException | URISyntaxException throwMe) {
                zeroOut(startupLatch);
                zeroOut(this.shutdownLatch);
                assert startupLatch.getCount() == 0L : "Unexpected startupLatch count: " + startupLatch.getCount();
                assert this.shutdownLatch.getCount() == 0L : "Unexpected shutdownLatch count: " + this.shutdownLatch.getCount();
                throw throwMe;
              }
            }
          }
        }
      }
    }
  }

  private final void waitForAllServersToStop(@Observes @BeforeDestroyed(ApplicationScoped.class)
                                             final Object event) {
    if (event != null) {
      try {
        this.shutdownLatch.await();
      } catch (final InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private final void zeroOut(final CountDownLatch latch) {
    if (latch != null) {
      while (latch.getCount() > 0L) {
        latch.countDown();
      }
      assert latch.getCount() == 0L;
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
                     // TODO: this is probably overkill; just need some config
                     final Instance<InetSocketAddress> inetSocketAddressInstance;
                     if (qa == null || qa.length <= 0) {
                       inetSocketAddressInstance = i.select(InetSocketAddress.class);
                     } else {
                       inetSocketAddressInstance = i.select(InetSocketAddress.class, qa);
                     }
                     final InetSocketAddress inetSocketAddress;
                     if (inetSocketAddressInstance == null || inetSocketAddressInstance.isUnsatisfied()) {
                       inetSocketAddress = null;
                     } else {
                       inetSocketAddress = inetSocketAddressInstance.get();
                     }

                     final ServerBootstrap returnValue = new ServerBootstrap();
                     // See https://stackoverflow.com/a/28342821/208288
                     returnValue.group(getEventLoopGroup(bm, i, qa, true));
                     returnValue.channelFactory(getChannelFactory(bm, i, qa, true));

                     if (inetSocketAddress != null) {
                       returnValue.localAddress(inetSocketAddress);
                     }

                     // TODO: channel initialization

                     // Permit arbitrary customization
                     beanManager.getEvent().select(ServerBootstrap.class, qualifiersArray).fire(returnValue);
                     returnValue.validate();
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


  private static final boolean noBeans(final BeanManager beanManager, final Type type, final Set<? extends Annotation> qualifiers) {
    final Collection<?> beans;
    if (beanManager != null && type != null) {
      if (qualifiers == null || qualifiers.isEmpty()) {
        beans = beanManager.getBeans(type);
      } else {
        beans = beanManager.getBeans(type, qualifiers.toArray(new Annotation[qualifiers.size()]));
      }
    } else {
      beans = null;
    }
    return beans == null || beans.isEmpty();
  }

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
