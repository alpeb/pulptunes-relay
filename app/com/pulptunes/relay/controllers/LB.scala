package com.pulptunes.relay.controllers

import javax.inject.{Inject, Named}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import cats.data.XorT
import cats.std.future._
import cats.syntax.option._
import play.api.Configuration
import play.api.libs.concurrent._
import play.api.mvc._

import com.pulptunes.relay.actors._
import com.pulptunes.relay.models._

class LB @Inject() (config: Configuration, @Named("pulp-events") eventsActor: ActorRef, serverProvider: ServerProvider)
    extends Controller {

  implicit val timeout = Timeout(1.second)

  val maybeServerId = config.getString("pulp.server_id").toRightXor(InternalServerError("server_id config not set"))

  def index = Action.async {
    val res = for {
      serverId <- XorT.fromXor[Future](maybeServerId)
      isUp <- XorT.right[Future, Result, Boolean](serverProvider.isUp(serverId, false))
    } yield isUp
    res.value.map(_.fold(
      identity,
      isUp => {
        if (isUp) Ok
        else {
          eventsActor ! LeaveAll
          Results.ServiceUnavailable
        }
      }))
    }
}
