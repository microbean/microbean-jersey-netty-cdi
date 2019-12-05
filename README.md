# microBean™ Jersey Netty CDI Integration

[![Build Status](https://travis-ci.com/microbean/microbean-jersey-netty-cdi.svg?branch=master)](https://travis-ci.com/microbean/microbean-jersey-netty-cdi)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-jersey-netty-cdi/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-jersey-netty-cdi)

The microBean™ Jersey Netty CDI Integration project integrates
[Jersey](https://jersey.github.io/) and [Netty](https://netty.io) into
[CDI 2.0](http://cdi-spec.org/) environments in an idiomatic way.

## Installation and Usage

Add this project as a `runtime`-scoped dependency in your CDI 2.0-based Maven project:
```
<dependency>
  <groupId>org.microbean</groupId>
  <artifactId>microbean-jersey-netty-cdi</artifactId>
  <version>0.3.0</version>
  <scope>runtime</scope>
</dependency>
```
Then [start a CDI SE
container](https://docs.jboss.org/cdi/api/2.0/javax/enterprise/inject/se/SeContainerInitializer.html).
Any [JAX-RS
applications](https://jax-rs.github.io/apidocs/2.1/javax/ws/rs/core/Application.html)
or resource classes found on the classpath will be served up by [Eclipse Jersey](https://projects.eclipse.org/projects/ee4j.jersey) and [Netty](https://netty.io/) on
`0.0.0.0` port `8080` by default, or port `443` by default if there is
an
[`SslContext`](https://netty.io/4.1/api/io/netty/handler/ssl/SslContext.html)
available to your CDI container.  You can pass `host` and `port`
System properties (or source them from any other [microBean™
Configuration](https://microbean.github.io/microbean-configuration/)
[`Configuration`](https://microbean.github.io/microbean-configuration/apidocs/org/microbean/configuration/spi/Configuration.html)
instances) to change your application's endpoint.

HTTP 1.1 and HTTP/2 requests, including upgrades via [HTTP's upgrade
header](https://svn.tools.ietf.org/svn/wg/httpbis/specs/rfc7230.html#header.upgrade),
[ALPN](https://www.rfc-editor.org/rfc/rfc7301#page-2) or [prior
knowledge](https://http2.github.io/http2-spec/#known-http), are fully
supported.

## Related Projects

* [microBean™ Jersey Netty Integration](https://microbean.github.io/microbean-jersey-netty/)
* [microBean™ Jakarta RESTful Web Services CDI Integration](https://microbean.github.io/microbean-jaxrs-cdi/)
* [microBean™ Configuration API](https://microbean.github.io/microbean-configuration-api/)
* [microBean™ Configuration](https://microbean.github.io/microbean-configuration/)
* [microBean™ Configuration CDI](https://microbean.github.io/microbean-configuration-cdi/)
