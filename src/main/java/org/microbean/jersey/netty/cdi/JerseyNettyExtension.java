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

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.BeforeDestroyed;
import javax.enterprise.context.Initialized;

import javax.enterprise.context.spi.CreationalContext;

import javax.enterprise.event.Observes;

import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;

import javax.enterprise.inject.spi.Annotated;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.DeploymentException;
import javax.enterprise.inject.spi.Extension;

import javax.enterprise.util.TypeLiteral;

import javax.inject.Named;

import javax.ws.rs.ApplicationPath;

import javax.ws.rs.core.Application;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;

import io.netty.channel.ChannelFactory;
import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SelectStrategyFactory;
import io.netty.channel.ServerChannel;

import io.netty.channel.nio.NioEventLoopGroup;

import io.netty.channel.socket.nio.NioServerSocketChannel;

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

/**
 * A CDI {@linkplain Extension portable extension} that effectively
 * puts a <a href="https://netty.io/"
 * target="_parent">Netty</a>-fronted <a
 * href="https://jersey.github.io/" target="_parent">Jersey</a>
 * container inside the {@linkplain
 * javax.enterprise.inject.se.SeContainer CDI container}.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 */
public class JerseyNettyExtension implements Extension {


  /*
   * Static fields.
   */


  private static final Annotation[] EMPTY_ANNOTATION_ARRAY = new Annotation[0];


  /*
   * Instance fields.
   */


  private final Logger logger;

  private final Collection<Throwable> shutdownProblems;

  private volatile Collection<EventExecutorGroup> eventExecutorGroups;

  private volatile CountDownLatch bindLatch;

  private volatile CountDownLatch runLatch;

  private volatile CountDownLatch shutdownLatch;

  private volatile CreationalContext<?> cc;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link JerseyNettyExtension}.
   *
   * <p>Instances of this class are normally created by CDI, not by an
   * end user.</p>
   */
  public JerseyNettyExtension() {
    super();

    final Thread containerThread = Thread.currentThread();

    // Laboriously work around
    // https://bugs.openjdk.java.net/browse/JDK-8029834.
    //
    // In the code that follows, we install a NoOpHandler (a private
    // nested class defined at the bottom of this file) as the first
    // Handler on the root Logger.  This special Handler's close()
    // method joins with the container thread, and so when that
    // close() method is called by the LogManager cleaner as a result
    // of a shutdown hook, it will block actual closing of that
    // handler and all others until the main thread has completed.
    //
    // See the
    // following links for more details:
    // * https://bugs.openjdk.java.net/browse/JDK-8161253
    // * https://bugs.openjdk.java.net/browse/JDK-8029834
    // * https://github.com/openjdk/jdk/blob/6734ec66c34642920788c86b62a36e4195676b6d/src/java.logging/share/classes/java/util/logging/LogManager.java#L256-L284
    final Logger rootLogger = Logger.getLogger("");
    assert rootLogger != null;
    final Handler[] rootHandlers = rootLogger.getHandlers();
    final boolean rootHandlersIsEmpty = rootHandlers == null || rootHandlers.length <= 0;
    if (!rootHandlersIsEmpty) {
      for (final Handler rootHandler : rootHandlers) {
        rootLogger.removeHandler(rootHandler);
      }
    }
    rootLogger.addHandler(new NoOpHandler(containerThread));
    if (!rootHandlersIsEmpty) {
      for (final Handler rootHandler : rootHandlers) {
        rootLogger.addHandler(rootHandler);
      }
    }

    final String cn = this.getClass().getName();
    final Logger logger = this.createLogger();
    if (logger == null) {
      this.logger = Logger.getLogger(cn);
    } else {
      this.logger = logger;
    }
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.entering(cn, "<init>");
    }

    // If we are running on Weld, prevent its shutdown hook from being
    // installed.  We install a shutdown hook and do some blocking
    // stuff in @BeforeDestroyed(ApplicationScoped.class).
    System.setProperty("org.jboss.weld.se.shutdownHook", "false");

    this.shutdownProblems = new ArrayList<>();

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          if (logger.isLoggable(Level.FINER)) {
            logger.entering(JerseyNettyExtension.this.getClass().getName(), "<shutdown hook>");
          }

          // Logging once a shutdown hook has been invoked can just
          // stop working because the LogManager actually runs its own
          // shutdown hook in parallel that starts closing handlers.
          // See the following links for more details:
          // * https://bugs.openjdk.java.net/browse/JDK-8161253
          // * https://bugs.openjdk.java.net/browse/JDK-8029834
          // * https://github.com/openjdk/jdk/blob/6734ec66c34642920788c86b62a36e4195676b6d/src/java.logging/share/classes/java/util/logging/LogManager.java#L256-L284
          // There is nothing we can do about this.

          // Release any current or future block in
          // waitForAllServersToStop().
          zeroOut(this.runLatch);

          // Release any current or future block on the bind latch
          // (maybe we've been CTRL-Ced while we're still starting
          // up).
          zeroOut(this.bindLatch);

          // We deliberately do not touch the shutdownLatch.  That
          // latch unblocks once Netty has shut down gracefully.

          // Join this shutdown hook thread with the container thread
          // now that the container has begun its graceful shutdown
          // process.  When a signal is received (which will have
          // triggered this shutdown hook), then the rules for the VM
          // exiting change a little bit.  Specifically, the VM will
          // exit when all non-daemon shutdown hook threads are done,
          // not when the main thread is done (after all, normally
          // CTRL-C will prematurely terminate the main thread).  So
          // to preserve graceful shutdown we need to hang around
          // until Netty has shut down gracefully.
          try {
            if (logger.isLoggable(Level.FINE)) {
              logger.logp(Level.FINE,
                          JerseyNettyExtension.this.getClass().getName(),
                          "<shutdown hook>",
                          "Waiting for CDI container thread to complete");
            }
            containerThread.join();
          } catch (final InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
          }

          if (logger.isLoggable(Level.FINER)) {
            logger.exiting(JerseyNettyExtension.this.getClass().getName(), "<shutdown hook>");
          }
    }));

    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.exiting(cn, "<init>");
    }
  }


  /*
   * Instance methods.
   */


  /**
   * Creates a {@link Logger} when invoked.
   *
   * <p>The default implementation of this method returns the result
   * of invoking {@link Logger#getLogger(String)
   * Logger.getLogger(this.getClass().getName())}.</p>
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * @return a non-{@code null} {@link Logger}
   */
  protected Logger createLogger() {
    return Logger.getLogger(this.getClass().getName());
  }

  // TODO: prioritize; should "come up" last so traffic isn't accepted
  // until all "onStartup" observer methods have run
  private final void onStartup(@Observes
                               @Initialized(ApplicationScoped.class)
                               final Object event,
                               final BeanManager beanManager)
    throws URISyntaxException {
    final String cn = this.getClass().getName();
    final String mn = "onStartup";
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.entering(cn, mn, new Object[] { event, beanManager });
    }
    Objects.requireNonNull(beanManager);

    final JaxRsExtension extension = beanManager.getExtension(JaxRsExtension.class);
    assert extension != null; // ...or an IllegalArgumentException will have been thrown per contract

    final Set<Set<Annotation>> applicationQualifierSets = extension.getAllApplicationQualifiers();
    if (applicationQualifierSets != null && !applicationQualifierSets.isEmpty()) {

      final CreationalContext<?> cc = beanManager.createCreationalContext(null);
      assert cc != null;
      this.cc = cc;

      final Configurations configurations = acquire(beanManager, cc, Configurations.class);
      assert configurations != null;

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
        // basically want to skip bootstrapping a bunch of things if
        // we're going down anyway.
        //
        // Be particularly mindful later on of the state of the
        // latches.
        if (Thread.currentThread().isInterrupted()) {
          if (this.logger.isLoggable(Level.FINE)) {
            this.logger.logp(Level.FINE, cn, mn, "Not binding because the current thread has been interrupted");
          }
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

          final Application application = (Application)beanManager.getReference(applicationBean, Application.class, cc);
          assert application != null;

          final Annotated applicationType = beanManager.createAnnotatedType(application.getClass());
          assert applicationType != null;
          
          final ApplicationPath applicationPathAnnotation = applicationType.getAnnotation(ApplicationPath.class);
          final String applicationPath;
          if (applicationPathAnnotation == null) {
            applicationPath = "/";
          } else {
            applicationPath = applicationPathAnnotation.value();
          }
          assert applicationPath != null;

          final ServerBootstrap serverBootstrap = getServerBootstrap(beanManager, cc, applicationQualifiersArray);
          assert serverBootstrap != null;

          final SslContext sslContext = getSslContext(beanManager, cc, applicationQualifiersArray);

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

          serverBootstrap.childHandler(new JerseyChannelInitializer(baseUri,
                                                                    sslContext,
                                                                    new ApplicationHandler(application)));
          serverBootstrap.validate();

          final ServerBootstrapConfig serverBootstrapConfig = serverBootstrap.config();
          assert serverBootstrapConfig != null;

          final EventLoopGroup eventLoopGroup = serverBootstrapConfig.group();
          assert eventLoopGroup != null; // see serverBootstrap.validate() above, which guarantees this
          eventLoopGroup.terminationFuture()
            .addListener(f -> {
                try {
                  if (f.isSuccess()) {
                    if (logger.isLoggable(Level.FINE)) {
                      logger.logp(Level.FINE, cn, "<ChannelFuture listener>", "EventLoopGroup terminated successfully");
                    }
                  } else {
                    if (logger.isLoggable(Level.FINE)) {
                      logger.logp(Level.FINE, cn, "<ChannelFuture listener>", "EventLoopGroup terminated with problems: {0}", f.cause());
                    }
                    final Throwable throwable = f.cause();
                    if (throwable != null) {
                      synchronized (this.shutdownProblems) {
                        this.shutdownProblems.add(throwable);
                      }
                    }
                  }
                } finally {
                  if (logger.isLoggable(Level.FINE)) {
                    logger.logp(Level.FINE, cn, "<ChannelFuture listener>", "Counting down shutdownLatch");
                  }
                  // See the waitForAllServersToStop() method
                  // below which calls await() on this latch.
                  this.shutdownLatch.countDown();
                }
              });

          Collection<EventExecutorGroup> eventExecutorGroups = this.eventExecutorGroups;
          if (eventExecutorGroups == null) {
            eventExecutorGroups = new ArrayList<>();
            this.eventExecutorGroups = eventExecutorGroups;
          }
          synchronized (eventExecutorGroups) {
            eventExecutorGroups.add(eventLoopGroup);
          }

          final Future<?> bindFuture;
          if (Thread.currentThread().isInterrupted()) {
            // Don't bother binding.
            bindFuture = null;
            final CountDownLatch bindLatch = this.bindLatch;
            if (bindLatch != null) {
              zeroOut(bindLatch);
              this.bindLatch = null;
            }
          } else if (serverBootstrapConfig.localAddress() == null) {
            bindFuture = serverBootstrap.bind(baseUri.getHost(), baseUri.getPort());
          } else {
            bindFuture = serverBootstrap.bind();
          }
          if (bindFuture != null) {
            bindFuture.addListener(f -> {
                try {
                  if (f.isSuccess()) {
                    if (logger.isLoggable(Level.FINE)) {
                      logger.logp(Level.FINE, cn, "<ChannelFuture listener>", "ServerBootstrap bound successfully");
                    }
                  } else {
                    final Throwable throwable = f.cause();
                    if (logger.isLoggable(Level.WARNING)) {
                      logger.logp(Level.WARNING, cn, "<ChannelFuture listener>", "ServerBootstrap binding failed: {0}", throwable);
                    }
                    if (throwable != null) {
                      synchronized (bindProblems) {
                        bindProblems.add(throwable);
                      }
                    }
                  }
                } finally {
                  final CountDownLatch bindLatch = this.bindLatch;
                  if (bindLatch != null) {
                    if (logger.isLoggable(Level.FINE)) {
                      logger.logp(Level.FINE, cn, "<ChannelFuture listener>", "Counting down bindLatch");
                    }
                    bindLatch.countDown();
                  }
                }
              });
          }

        } catch (final RuntimeException | URISyntaxException throwMe) {
          zeroOut(this.bindLatch);
          zeroOut(this.shutdownLatch);
          synchronized (bindProblems) {
            for (final Throwable bindProblem : bindProblems) {
              throwMe.addSuppressed(bindProblem);
            }
          }
          cc.release();
          throw throwMe;
        }

      }

      CountDownLatch bindLatch = this.bindLatch;
      if (!Thread.currentThread().isInterrupted() && bindLatch != null && bindLatch.getCount() > 0) {
        try {
          if (logger.isLoggable(Level.FINE)) {
            logger.logp(Level.FINE, cn, mn, "Awaiting bindLatch");
          }
          bindLatch.await();
          if (logger.isLoggable(Level.FINE)) {
            logger.logp(Level.FINE, cn, mn, "bindLatch released");
          }
        } catch (final InterruptedException interruptedException) {
          if (logger.isLoggable(Level.FINE)) {
            logger.logp(Level.FINE, cn, mn, "bindLatch.await() interrupted");
          }
          Thread.currentThread().interrupt();
          zeroOut(bindLatch);
          bindLatch = null;
          this.bindLatch = null;
        }
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
        cc.release();
        throw throwMe;
      }

      if (bindLatch != null) {
        // If we get here with a non-null bindLatch, then a binding
        // attempt was made and allowed to run to completion.  The
        // completion was successful, because otherwise we would have
        // thrown an exception.
        assert bindLatch.getCount() <= 0;
        this.bindLatch = null;

        // Since we know that binding happened and wasn't interrupted
        // and didn't fail, we can now create the runLatch, which will
        // be awaited in the waitForAllServersToStop() method below.
        if (this.logger.isLoggable(Level.FINE)) {
          this.logger.logp(Level.FINE, cn, mn, "Creating runLatch");
        }
        this.runLatch = new CountDownLatch(1);
      }

    }
    assert this.bindLatch == null;

    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.exiting(cn, mn);
    }
  }

  private final void waitForAllServersToStop(@Observes @BeforeDestroyed(ApplicationScoped.class)
                                             final Object event) {
    final String cn = this.getClass().getName();
    final String mn = "waitForAllServersToStop";
    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.entering(cn, mn, event);
    }

    // Note: Weld can fire a @BeforeDestroyed event on a thread that
    // is not the container thread: a shutdown hook that it installs.
    // So we have to take care to be thread safe.

    CountDownLatch runLatch = this.runLatch;
    if (runLatch != null) {
      if (Thread.currentThread().isInterrupted()) {
        zeroOut(runLatch);
      } else {
        try {
          if (logger.isLoggable(Level.FINE)) {
            logger.logp(Level.FINE, cn, mn, "Awaiting runLatch");
          }
          runLatch.await();
          if (logger.isLoggable(Level.FINE)) {
            logger.logp(Level.FINE, cn, mn, "runLatch released");
          }
        } catch (final InterruptedException interruptedException) {
          if (logger.isLoggable(Level.FINE)) {
            logger.logp(Level.FINE, cn, mn, "runLatch.await() interrupted");
          }
          Thread.currentThread().interrupt();
          if (logger.isLoggable(Level.FINE)) {
            logger.logp(Level.FINE, cn, mn, "Zeroing out runLatch");
          }
          zeroOut(runLatch);
        }
      }
      assert runLatch.getCount() <= 0;
      runLatch = null;
      this.runLatch = null;
    }

    final Collection<? extends EventExecutorGroup> eventExecutorGroups = this.eventExecutorGroups;
    if (eventExecutorGroups != null) {
      synchronized (eventExecutorGroups) {
        if (!eventExecutorGroups.isEmpty()) {
          for (final EventExecutorGroup eventExecutorGroup : eventExecutorGroups) {
            if (logger.isLoggable(Level.FINE)) {
              logger.logp(Level.FINE, cn, mn, "Shutting down {0} gracefully", eventExecutorGroup);
            }
            // Sends a request to the Netty threads to wait for a
            // configurable period of time and then shut down
            // carefully.  This call itself does not block.
            eventExecutorGroup.shutdownGracefully();
          }
          eventExecutorGroups.clear();
        }
      }
      this.eventExecutorGroups = null;
    }

    final CountDownLatch shutdownLatch = this.shutdownLatch;
    if (shutdownLatch != null) {
      try {
        if (logger.isLoggable(Level.FINE)) {
          logger.logp(Level.FINE, cn, mn, "Awaiting shutdownLatch");
        }
        // Wait for the EventExecutorGroup ChannelFuture to complete
        // and call shutdownLatch.countDown().
        shutdownLatch.await();
        if (logger.isLoggable(Level.FINE)) {
          logger.logp(Level.FINE, cn, mn, "shutdownLatch released");
        }
      } catch (final InterruptedException interruptedException) {
        if (logger.isLoggable(Level.FINE)) {
          logger.logp(Level.FINE, cn, mn, "shutdownLatch.await() interrupted");
        }
        Thread.currentThread().interrupt();
      }
      assert shutdownLatch.getCount() <= 0;
      this.shutdownLatch = null;
    }

    final CreationalContext<?> cc = this.cc;
    if (cc != null) {
      cc.release();
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

    if (this.logger.isLoggable(Level.FINER)) {
      this.logger.exiting(cn, mn);
    }
  }


  /*
   * Production and lookup methods.
   */


  private static final SslContext getSslContext(final BeanManager beanManager,
                                                final CreationalContext<?> cc,
                                                final Annotation[] qualifiersArray) {
    return acquire(beanManager,
                   cc,
                   SslContext.class,
                   qualifiersArray,
                   true,
                   (bm, cctx, qa) -> null);
  }

  private static final ServerBootstrap getServerBootstrap(final BeanManager beanManager,
                                                          final CreationalContext<?> cc,
                                                          final Annotation[] qualifiersArray) {
    return acquire(beanManager,
                   cc,
                   ServerBootstrap.class,
                   qualifiersArray,
                   true,
                   (bm, cctx, qa) -> {
                     final ServerBootstrap returnValue = new ServerBootstrap();
                     // See https://stackoverflow.com/a/28342821/208288
                     returnValue.group(getEventLoopGroup(bm, cctx, qa));
                     returnValue.channelFactory(getChannelFactory(bm, cctx, qa));

                     // Permit arbitrary customization
                     beanManager.getEvent().select(ServerBootstrap.class, qualifiersArray).fire(returnValue);
                     return returnValue;
                   });
  }

  private static final ChannelFactory<? extends ServerChannel> getChannelFactory(final BeanManager beanManager,
                                                                                 final CreationalContext<?> cc,
                                                                                 final Annotation[] qualifiersArray) {
    return acquire(beanManager,
                   cc,
                   new TypeLiteral<ChannelFactory<? extends ServerChannel>>() {
                     private static final long serialVersionUID = 1L;
                   }.getType(),
                   qualifiersArray,
                   true,
                   (bm, cctx, qa) -> {
                     final SelectorProvider selectorProvider = getSelectorProvider(bm, cctx, qa);
                     assert selectorProvider != null;
                     return () -> new NioServerSocketChannel(selectorProvider);
                   });
  }

  private static final EventLoopGroup getEventLoopGroup(final BeanManager beanManager,
                                                        final CreationalContext<?> cc,
                                                        final Annotation[] qualifiersArray) {
    return acquire(beanManager,
                   cc,
                   EventLoopGroup.class,
                   qualifiersArray,
                   true,
                   (bm, cctx, qa) -> {
                     final EventLoopGroup returnValue =
                       new NioEventLoopGroup(0 /* 0 == default number of threads */,
                                             getExecutor(bm, cctx, qa), // null is OK
                                             getEventExecutorChooserFactory(bm, cctx, qa),
                                             getSelectorProvider(bm, cctx, qa),
                                             getSelectStrategyFactory(bm, cctx, qa),
                                             getRejectedExecutionHandler(bm, cctx, qa));
                     // Permit arbitrary customization.  (Not much you can do here
                     // except call setIoRatio(int).)
                     beanManager.getEvent().select(EventLoopGroup.class, qa).fire(returnValue);
                     return returnValue;
                   });
  }

  private static final Executor getExecutor(final BeanManager beanManager,
                                            final CreationalContext<?> cc,
                                            final Annotation[] qualifiersArray) {
    return acquire(beanManager,
                   cc,
                   Executor.class,
                   qualifiersArray,
                   false, // do not fall back to @Default one
                   (bm, cctx, qa) -> null);
  }

  private static final RejectedExecutionHandler getRejectedExecutionHandler(final BeanManager beanManager,
                                                                            final CreationalContext<?> cc,
                                                                            final Annotation[] qualifiersArray) {
    return acquire(beanManager,
                   cc,
                   RejectedExecutionHandler.class,
                   qualifiersArray,
                   true,
                   (bm, cctx, qa) -> RejectedExecutionHandlers.reject());
  }

  private static final SelectorProvider getSelectorProvider(final BeanManager beanManager,
                                                            final CreationalContext<?> cc,
                                                            final Annotation[] qualifiersArray) {
    return acquire(beanManager,
                   cc,
                   SelectorProvider.class,
                   qualifiersArray,
                   true,
                   (bm, cctx, qa) -> SelectorProvider.provider());
  }

  private static final SelectStrategyFactory getSelectStrategyFactory(final BeanManager beanManager,
                                                                      final CreationalContext<?> cc,
                                                                      final Annotation[] qualifiersArray) {
    return acquire(beanManager,
                   cc,
                   SelectStrategyFactory.class,
                   qualifiersArray,
                   true,
                   (bm, cctx, qa) -> DefaultSelectStrategyFactory.INSTANCE);
  }

  private static final EventExecutorChooserFactory getEventExecutorChooserFactory(final BeanManager beanManager,
                                                                                  final CreationalContext<?> cc,
                                                                                  final Annotation[] qualifiersArray) {
    return acquire(beanManager,
                   cc,
                   EventExecutorChooserFactory.class,
                   qualifiersArray,
                   true,
                   (bm, cctx, qa) -> DefaultEventExecutorChooserFactory.INSTANCE);
  }


  /*
   * Static utility methods.
   */


  private static final <T> T acquire(final BeanManager beanManager,
                                     final CreationalContext<?> cc,
                                     final Type type) {
    return acquire(beanManager, cc, type, null, false, (bm, cctx, qa) -> null);
  }

  private static final <T> T acquire(final BeanManager beanManager,
                                     final CreationalContext<?> cc,
                                     final Type type,
                                     final Annotation[] qualifiersArray,
                                     final boolean fallbackWithDefaultQualifier,
                                     final DefaultValueFunction<? extends T> defaultValueFunction) {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(type);
    Objects.requireNonNull(defaultValueFunction);

    final T returnValue;

    Set<Bean<?>> beans = null;
    if (qualifiersArray == null || qualifiersArray.length <= 0 || (qualifiersArray.length == 1 && qualifiersArray[0] instanceof Default)) {
      beans = beanManager.getBeans(type);
    } else {
      beans = beanManager.getBeans(type, qualifiersArray);
      if (fallbackWithDefaultQualifier && (beans == null || beans.isEmpty())) {
        beans = beanManager.getBeans(type);
      }
    }
    if (beans == null || beans.isEmpty()) {
      returnValue = defaultValueFunction.getDefaultValue(beanManager, cc, qualifiersArray);
    } else {
      final Bean<?> bean = beanManager.resolve(beans);
      if (bean == null) {
        returnValue = defaultValueFunction.getDefaultValue(beanManager, cc, qualifiersArray);
      } else {
        @SuppressWarnings("unchecked")
        final T temp = (T)beanManager.getReference(bean, type, cc);
        returnValue = temp;
      }
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
                      final CreationalContext<?> cc,
                      final Annotation[] qualifiersArray);

  }

  private static final class NoOpHandler extends Handler {

    private final Thread thread;

    private NoOpHandler(final Thread t) {
      super();
      this.thread = Objects.requireNonNull(t);
    }

    @Override
    public final void close() {
      try {
        this.thread.join();
      } catch (final InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public final void flush() {

    }

    @Override
    public final void publish(final LogRecord logRecord) {

    }

  }

}
