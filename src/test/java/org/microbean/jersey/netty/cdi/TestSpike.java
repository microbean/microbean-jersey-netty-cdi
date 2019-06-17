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

import javax.enterprise.inject.se.SeContainerInitializer;

import javax.enterprise.inject.spi.Extension;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glassfish.jersey.ext.cdi1x.internal.CdiComponentProvider;

import org.microbean.configuration.cdi.ConfigurationsExtension;

import org.microbean.jaxrs.cdi.JaxRsExtension;

import static org.junit.Assume.assumeTrue;

public class TestSpike {

  private AutoCloseable container;
  
  public TestSpike() {
    super();
  }

  @Before
  public void startContainer() throws Exception {
    this.stopContainer();
    assumeTrue(Boolean.getBoolean("runBlockingTests"));
    final SeContainerInitializer initializer = SeContainerInitializer.newInstance();
    initializer.disableDiscovery();
    initializer.addExtensions(ConfigurationsExtension.class,
                              JaxRsExtension.class,
                              JerseyNettyExtension.class,
                              CdiComponentProvider.class);
    initializer.addBeanClasses(MyResource.class);
    this.container = initializer.initialize();
  }

  @After
  public void stopContainer() throws Exception {
    if (this.container != null) {
      this.container.close();
      this.container = null;
    }
  }

  @Test
  public void testSpike() {
    assumeTrue(Boolean.getBoolean("runBlockingTests"));
  }

  @Path("")
  public static final class MyResource {

    public MyResource() {
      super();
    }

    @GET
    @Path("gorp")
    @Produces("text/plain")
    public String gorp() {
      return "Gorp";
    }
    
  }
  
}
