package com.pulptunes.relay.controllers

import javax.inject.{Inject, Named}

import scala.concurrent._
import scala.concurrent.duration._

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.{after, ask}
import akka.util.Timeout
import cats.data.{Xor, XorT}
import cats.std.future._
import cats.syntax.option._
import cats.syntax.xor._
import play.api.{Configuration, Logger}
import play.api.libs.concurrent._
import play.api.libs.json._
import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits._

import com.pulptunes.relay.actors._
import com.pulptunes.relay.models._

class WebServices @Inject() (config: Configuration, system: ActorSystem, @Named("pulp-events") eventsActor: ActorRef,
    serverProvider: ServerProvider, licenseProvider: LicenseProvider)
  extends Controller {

  implicit val timeout = Timeout(1.second)

  val maybeServerId = config.getString("pulp.server_id").toRightXor("server_id config not set")

  def sendfile = Action.async(parse.json) { implicit request =>
    val res = for {
      serverId <- XorT.fromXor[Future](maybeServerId.leftMap(InternalServerError(_)))

      futMaybeHost = serverProvider.getById(serverId).map(_.map(_.publicDns))
      host <- XorT(futMaybeHost.map(_.leftMap(t => BadRequest(t.getMessage))))

      futMaybeResult = sendFileEvent(request.body, host)
      result <- XorT(futMaybeResult)
    } yield result
    res.value.map(_.fold(identity, identity))
  }

  private def sendFileEvent(json: JsValue, enumHost: String): Future[Result Xor Result] = {
    val subdomain = (json \ "senderSubdomain").as[String]
    val streamId = (json \ "streamId").as[String]

    val jsonLog = if (json.toString.length > 200)
        json.toString.take(200) + "..."
      else
        json.toString

    Logger.debug(s"$subdomain - WebServices.sendfile json: $jsonLog")

    val res = eventsActor ? SendFile(
      subdomain,
      streamId,
      (json \ "host").as[String],
      enumHost,
      (json \ "uri").as[String],
      (json \ "ip").as[String],
      (json \ "headers").as[Map[String, List[String]]]
    )

    res.mapTo[Future[JsValue]].flatMap{ future =>
      val timeoutFuture = akka.pattern.after(6.seconds, system.scheduler)(Future("timeout"))
      Future.firstCompletedOf(Seq(future, timeoutFuture)).map {
        case jsValue: JsValue => Ok(jsValue).right
        case t: String =>
          Logger.debug(s"$subdomain - WebServices.sendfile TIMEOUT ($streamId)")
          BadRequest(t).left
      }
    }.recover {
      case e: Exception =>
        Logger.debug(s"$subdomain - WebServices.sendfile EXCEPTION ($streamId)")
        BadRequest(e.getMessage).left
    }
  }

  def processRequest = Action.async(parse.json) { implicit request =>
    val json = request.body
    val maybeSubdomain = (json \ "subdomain").asOpt[String].toRightXor("subdomain not found in request")

    val res = for {
      subdomain <- XorT.fromXor[Future](maybeSubdomain)

      futMaybeLicense = licenseProvider.getBySubdomain(subdomain)
      _ <- XorT(futMaybeLicense.map(_.leftMap(t => t.getMessage)))

      serverId <- XorT.fromXor[Future](maybeServerId)

      futMaybeHost = serverProvider.getById(serverId).map(_.map(_.publicDns))
      host <- XorT(futMaybeHost.map(_.leftMap(t => t.getMessage)))

      futMaybeResult = getRequestEvent(json, subdomain, host)
      result <- XorT(futMaybeResult)
    } yield result
    res.value.map(_.fold(err => Ok(Json.obj("error" -> err)), identity))
  }

  private def getRequestEvent(json: JsValue, subdomain: String, host: String): Future[String Xor Result] = {
    val jsonLog = if (json.toString.length > 200)
        json.toString.take(200) + "..."
      else
        json.toString
    Logger.debug(s"$subdomain - WebServices.processRequest json: $jsonLog")
    val res = eventsActor ? GetRequest(GetRequestData(json), host)
    res.mapTo[Future[JsValue]].flatMap { getRequestFuture => 
      val timeoutFuture = after(6.seconds, system.scheduler)(Future(subdomain))
      Future.firstCompletedOf(Seq(getRequestFuture, timeoutFuture)).map {
        case jsValue: JsValue =>
          val jsonLog = if (jsValue.toString.length > 200)
              jsValue.toString.take(200) + "..."
            else
              jsValue.toString

          Logger.debug(s"$subdomain - GetRequest response: $jsonLog")
          (jsValue \ "invalidUri").toOption.map(_ => Ok(Json.obj("invalidUri" -> true)).right)
            .orElse((jsValue \ "redirect").toOption.map(redirect => Ok(Json.obj("redirect" -> (redirect.as[String]))).right))
            .orElse(for {
              html <- (jsValue \ "html").toOption
              headers <- (jsValue \ "headers").toOption
            } yield Ok(Json.obj("html" -> (html.as[String]), "headers" -> headers)).right)
            .getOrElse(Ok(jsValue).right)
        case t: String =>
          // Happens when java app goes offline but Leave isn't called, so I do it here
          Logger.debug(s"$subdomain - GetRequest timed out")
          eventsActor ! Leave(subdomain)
          Dispatcher.SUBDOMAIN_OFFLINE.format(t).left
      }.recover {
        case e: Exception =>
          Logger.debug(s"$subdomain - WebServices.processRequest EXCEPTION")
          e.getMessage.left
      }
    }
  }
}
