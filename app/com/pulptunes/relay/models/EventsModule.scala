package com.pulptunes.relay.actors

import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport

class EventsModule extends AbstractModule with AkkaGuiceSupport {
  def configure = {
    bindActor[Events]("pulp-events")
    bindActor[EnumeratorsRegistry]("pulp-enums-registry")
  }
}
