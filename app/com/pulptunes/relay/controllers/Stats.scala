package com.pulptunes.relay.controllers

import javax.inject.{Named, Inject}

import scala.concurrent.Await
import scala.collection.immutable.Queue
import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import play.api.Configuration
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.json._
import play.api.mvc._

import com.pulptunes.relay.actors._
import com.pulptunes.relay.models._
import com.pulptunes.relay.views

class Stats @Inject() (@Named("pulp-events") eventsActor: ActorRef)(implicit config: Configuration)
  extends Controller {

  def index = Action {
    implicit val timeout = Timeout(2.second)
    
    Ok(views.html.stats(
      webSocketChannels =
        Await.result(eventsActor ? GetWebSocketChannels, timeout.duration).asInstanceOf[Map[String, Channel[JsValue]]],
      longPollingChannels = 
        Await.result(eventsActor ? GetLongPollingChannels, timeout.duration).asInstanceOf[Map[String, Channel[JsValue]]],
      longPollingStarts = 
        Await.result(eventsActor ? GetLongPollingStarts, timeout.duration).asInstanceOf[Map[String, Long]],
      longPollingQueuedGetRequests = 
        Await.result(eventsActor ? GetLongPollingQueuedGetRequests, timeout.duration).asInstanceOf[Map[String, List[GetRequestData]]],
      longPollingQueuedSendFiles =
        Await.result(eventsActor ? GetLongPollingQueuedSendFiles, timeout.duration).asInstanceOf[Map[String, List[SendFileData]]]
    ))
  }
}
