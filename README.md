PulpTunes-Relay
===============

PulpTunes is an app coupled with a service that allows to stream music files from your iTunes library in your desktop to a browser (desktop or mobile) elsewhere in the net.

This repository contains the backend service part, that relays the data between the desktop app and the browser. It must be coupled with some other service for creating the accounts (see table `licenses` explained below), that is not provided here.

This is the same service that powers the pulptunes.com site, which offers the app and this service as a paid service.

The desktop app might also be released as open source in the future, if enough interest is manifested (it's a JavaFX app, that I'd like to rewrite to ScalaFX before doing so...)

**Table of Contents:**

- [Scalability](#scalability)
- [Tech Stack](#tech-stack)
- [Subdomains/Accounts](#subdomainsaccounts)
- [Architecture Summary](#architecture-summary)
- [Clustering](#clustering)
- [Database](#database)
- [Deploying and Running](#deploying-and-running)
- [Monitoring](#monitoring)
- [Contributing](#contributing)
- [Future Developments](#future-developments)
- [License](#license)

Scalability
-----------

File streaming can be very resource-intensive. Pulptunes-relay was designed from the ground up to be very easily scalable horizontally.

Whenever the need arises, you just need to start new instances and refer them in the `servers` table (details explained below). Instances expose a set of webservices through which they can communicate between themselves in a point-to-point fashion.

There are only two single-point-of-failure elements: a load balancer distributing load among all instances, and a single database. The industry already provides many solutions for scaling these services. In pulptunes.com we have spinning multiple pulptunes-relay instances, and a single [HAproxy](http://www.haproxy.org/) load balancer and a single [MySQL](https://www.mysql.com/) database, which have proved enough.

Tech Stack
-----------

- This is a [Scala](http://www.scala-lang.org/) application written on top of the [Play Framework](https://playframework.com/) (v.2.5).
- It uses the [Slick](http://slick.lightbend.com/) (v.3.1) library for interacting with a relational database ([MySQL](https://www.mysql.com/) by default).
- It uses [Akka](http://akka.io/) actors to handle concurrency.
- It relies on the [Cats](http://github.com/non/cats) library for handling interactions with the database and the actors asynchronously, and saves us from callback hell.
- It uses [Iteratees](https://www.lightbend.com/activator/template/play-iteratees), a functional concept for dealing with the streams of data.

Subdomains/Accounts
----------------------

The PulpTunes desktop app first asks you to install a license obtained from pulptunes.com. This will determine the subdomain under which your music will be accessed, like `john.pulptunes.com`. The pulptunes-relay server lets you handle as many subdomains as you like. They must be referred in the `licenses` table as explained below. There is no limit to the number of concurrent streams a subdomain can handle; usually the practical limit is set by the bandwidth available to the desktop app network.

Architecture Summary
--------------------

![Sequence diagram](https://github.com/alpeb/pulptunes-relay/blob/master/sequence_diagram.png)

This sequence diagram explains how the desktop app joins the pulptunes-relay cluster, and the lifecycle of a file request.
(diagram generated with [Mermaid](https://github.com/knsv/mermaid), which is awesome)

#### Desktop App Connection Establishment

- When the desktop app (depicted as Desktop Server) starts it requests some specific subdomain and establishes a websocket connection (fallbacks to long-polling if needed) with a pulptunes-relay instance randomly assigned by the load balancer. In this example that instance is identified by `backend_id` and depicted as Backend A in the diagram.

- That backend adds an entry into the table `serving_listeners` mapping the subdomain with `backend_id`.

#### Music File Request Lifecycle

- When a browser points to the subdomain and asks for a file, the load balancer connects it to a random backend instance, depicted as Backend B in the diagram.

- That instance spawns an [Enumerator](https://playframework.com/documentation/2.5.x/api/scala/index.html#play.api.libs.iteratee.Enumerator) identified by some `stream_id`. Whenever this Enumerator gets fed bytes in the following steps, it will forward those bytes back to the browser.

- Given the subomdain the browser used to connect, the backend instance asks the database which `backend_id` that subdomain corresponds to, i.e. what backend the desktop app linked to this subdomain is connected to (Backend A in this case).

- Then the Backend B directly calls a webservice in that other backend (A), informing what music file was requested and the `stream_id` waiting for it.

- The Backend A sends a message through the websocket connection to the Desktop app, asking it to send the music file directly to the Backend B and `stream_id`.

- The desktop app connects directly to the Backend B and sends the requested file, sending alongside the ID `stream_id`.

- The Backend B starts feeding the Enumerator as it receives the bytes from the desktop app, and they get forwarded to the browser that initiated the request.

Clustering
-----------

As explained above in [Scalability](#scalability), your pulptunes-relay cluster needs a load balancer in front of it. In pulptunes.com we use [HAProxy](http://www.haproxy.org/). Here's a sample config you could use: [haproxy.conf](https://github.com/alpeb/pulptunes-relay/blob/master/conf/haproxy.conf).

In that config file we've declared two instances of pulp-relay (called `pulp1` (listening on port 9001) and `pulp2` listening on port 9002), amongst which the load balancer distributes the load in a round-robin fashion.

As explained in the previous section, when the desktop app sends a music file it will connect directly to the instance the browser has connected to. In this example this could be either `pulp1.example.org` or `pulp2.example.org`. You should adapt this config file using your own domain name.

If you're testing locally, make sure you have entries for your subdomains in your `/etc/hosts` files (that's for Mac and Linux; for Windows I believe that's under some `System32` directory, at least in older Windows). In our example it would be something like this:
```
::1    localhost localhost.localdomain pulp.localhost.localdomain pulp1.localhost.localdomain pulp2.localhost.localdomain
```

#### Staggered Upgrades

You can upgrade your pulptunes-relay instances without downtime by switching off instances one at a time.

To do so, first set the field `online` to `false` in the entry corresponding to the instance you want to upgrade in the table `servers` (read below for a more detailed explanation of the database tables). Wait for current streams for that instance to finish (for this you'll need to rely on your load balancer monitoring interface). Then kill the instance, upgrade it, launch it again and set `online` back to `true`. Repeat for all your instances!

Database
---------

By default, the database engine we use is MySQL, and the database name is `pulptunes`. You can change this by changing the `slick.dbs.default.db` config directives located in `conf/application.conf`. To use a different db engine make sure you also properly set up its JDBC driver dependency in `build.sbt`. The database username and password need to be provided as explained in [Deploying and Running](#deploying-and-running).

### Database Tables

Upon running for the first time, the following tables will be created (using the evolutions scripts under [conf/evolutions](https://github.com/alpeb/pulptunes-relay/blob/master/conf/evolutions)). So make sure that after starting the first instance for the first time, or after starting an updated instance containing database changes, hit it directly with the browser for the evolution scripts to be applied.

#### Table licenses

Most importantly it contains the list of subdomains that the server will serve. For each subdomain there is associated user info such as first name and last name, email and password. This user info is used in the pulptunes.com site to manage accouts and you may ignore it, or integrate it into your own solution for user registration handling. For pulptunes-relay the only thing you need here is to have entries for the subdomains you wish to handle. By default, the system gives your a "pulp" subdomain, attached to a user "John" with an email "pulp@mailnator.com" and a password "whateva".

#### Table servers

This table contains the list of pulptunes-relay instances you have running, amongst which the load will be spread.

- `id`: can be any string you like, it must match the `pulp.server_id` config directive.
- `status`: can be either `production` or `test`.
- `public_dns`: network-reachable host name for the instance.
- `online`: `true` or `false`. Set it to false before bringing down an instance, for example before upgrading it. See above, [Staggered Upgrades](#staggered-upgrades).

By default this table contains entries for two instances listening to `pulp1.localhost.localdomain:9000` and `pulp2.localhost.localdomain:9001`, the first only being flagged as online.

#### Table serving_listeners

This table maps each subdomain with a pulptunes-server instance.

#### Table subdomain_log

This logs every connection made into the cluster.

Deploying and Running
---------------------

This is a standard Play application. Please read [Play's docs](https://playframework.com/documentation/2.5.x/Deploying) to decide the best deployment/running strategy for you.

In pulptunes.com we run directly from the source by issuing `sbt start` one time for each instance and passing-on the appropriate config overrides.

When running from the source, each instance can be stopped with `kill $(cat target/universal/stage/RUNNING_PID)`.

These are the config directives you should override in production. Each instance launched should have a different `pulp.server_id` directive, and be referred in the table `servers`:

```
pulp.server_id="pulp1"
pulp.production=true
slick.dbs.default.driver="slick.driver.MySQLDriver$"
slick.dbs.default.db.driver="com.mysql.jdbc.Driver"
slick.dbs.default.db.url="jdbc:mysql://localhost/pulptunes"
slick.dbs.default.db.user="root"
slick.dbs.default.db.password=""
```

Monitoring
----------

By pointing your browser to `/stats` you'll see a table listing the desktop apps connected to a given instance, as well as the current streams. Note that the [HAProxy config](https://github.com/alpeb/pulptunes-relay/blob/master/conf/haproxy.conf) we provide blocks all access except for the ip whitelist in there.

Also, the `subdomain_log` table contains the history of all these connections.


Contributing
-------------

- Development process: [Collective Code Construction Contract (C4)](http://rfc.zeromq.org/spec:42). Among other things this means _aggressive_ (as in fast and without any red tape) PR merging policy :)
- Coding standards: we follow the [Databricks Scala Guide](https://github.com/databricks/scala-style-guide)

Future Developments
-------------------

Even though I've tried using modern techniques in this code base, this is an old project and the concept itself is pretty dated. If I had to rewrite this today, I'd build it over WebRTC. Nowadays the majority of desktop browsers support that protocol, and so does Chrome for Android. Mobile Safari doesn't yet, but it seems it will soon.

With WebRTC this would be a truly peer-to-peer streamer, more performant and requiring lesser backend support. In those scenarios were p2p connections fail, one has to provide a relay fallback (TURN) which would replace this pulptunes-relay solution. Since it'd only handle edge-cases that would imply less backend resources consumed. There's a standard TURN server [Coturn](https://github.com/coturn/coturn) that we could use. WebRTC also requires other minor backend support, like a STUN server and a signaling mechanism, which are standard enough to be solved through other 3rd party servers.

License
---------
[Mozilla Public License Version 2.0](https://github.com/alpeb/pulptunes-relay/blob/master/LICENSE.txt)
