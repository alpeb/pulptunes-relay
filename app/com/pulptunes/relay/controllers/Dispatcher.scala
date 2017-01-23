package com.pulptunes.relay.controllers

import javax.inject.{Named, Inject}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.collection.immutable.Queue
import scala.concurrent.duration._

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.{ask, AskTimeoutException}
import akka.util.Timeout
import cats.data.{Xor, XorT}
import cats.std.future._
import cats.syntax.option._
import cats.syntax.xor._
import com.fasterxml.jackson.core.JsonParseException
import play.api._
import play.api.http.ContentTypes
import play.api.{Configuration, Logger}
import play.api.libs.concurrent._
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.mvc._

import com.pulptunes.relay.controllers.utils._
import com.pulptunes.relay.actors._
import com.pulptunes.relay.models._
import com.pulptunes.relay.views

object Dispatcher {
  val PULP_USER_AGENT = "PulpTunes Agent"
  val SUBDOMAIN_OFFLINE = "Sorry, the subdomain %s is currently offline"
}

class Dispatcher @Inject() (ws: WSClient, system: ActorSystem, @Named("pulp-events") eventsActor: ActorRef,
    subdomainLogProvider: SubdomainLogProvider, requestUtils: RequestUtils, serverProvider: ServerProvider,
    licenseProvider: LicenseProvider, servingListenerProvider: ServingListenerProvider)
    (implicit config: Configuration)
  extends Controller {

  import Dispatcher._

  implicit val timeout = Timeout(2.second)

  val maybeServerId = config.getString("pulp.server_id").toRightXor(InternalServerError("server_id config not set"))

  def main = index("")

  def index(ignore: String) = Action.async { implicit request =>

    val subdomain = requestUtils.getSubdomain(request)
    val futMaybeLicense = licenseProvider.getBySubdomain(subdomain)

    val res = for {
      _ <- XorT(futMaybeLicense.map(_.leftMap{ t =>
        Logger.warn(s"Invalid subdomain requested: $subdomain")
        Ok(errorView(t.getMessage))
      }))

      futMaybeServingListener = servingListenerProvider.getBySenderSubdomain(subdomain)
      servingListener <- XorT(futMaybeServingListener.map(_.leftMap{ _ =>
        Ok(errorView(SUBDOMAIN_OFFLINE.format(subdomain)))
      }))

      uri <- XorT.fromXor[Future](uri)

      futMaybeServer = servingListenerProvider.getServer(servingListener)
      server <- XorT(futMaybeServer.map(_.leftMap(t => InternalServerError(t.getMessage))))

      futMaybeWsResp = sendWsReq(server, subdomain, uri)
      wsResp <- XorT(futMaybeWsResp.map(_.leftMap(t => InternalServerError(t.getMessage))))
    } yield {
      try {
        val received = if (wsResp.json.toString.length > 200)
            wsResp.json.toString.substring(0, 200) + "..."
          else
            wsResp.json.toString

        Logger.debug(s"$subdomain - Dispatcher.index received: $received")
        val res = for {
          // these will get prettier with play 2.6 (with scala 2.12 (right-biased Either))
          _ <- Xor.fromEither((wsResp.json \ "invalidUri").toEither).map(_ => NotFound).swap
          _ <- Xor.fromEither((wsResp.json \ "redirect").toEither).map(url => Redirect(url.as[String])).swap
          _ <- Xor.fromEither((wsResp.json \ "error").toEither).map{ error =>
            Logger.warn(s"$subdomain - Error: ${error.as[String]}")
            Logger.debug(s"$subdomain - ref: " + request.headers.get(REFERER).getOrElse("unknown"))
            Ok(errorView(error.as[String]))
          }.swap
          _ <- Xor.fromEither((wsResp.json \ "html").toEither).map{ html =>
            var headers = List[(String, String)]()
            (wsResp.json \ "headers").asOpt[Map[String, String]]
              .foreach(_.foreach(header => headers = headers :+ header))
            Ok(html.as[String]).as("text/html").withHeaders(headers: _*)
          }.swap
        } yield wsResp.json
        res.fold(identity, Ok(_))
      } catch {
        // happens if the target server is down (wsResp.json above throws an exception)
        case jpe: JsonParseException =>
          Logger.error(s"$subdomain - Server ${server.publicDns} is down")
          Ok(errorView(SUBDOMAIN_OFFLINE.format(subdomain)))
      }
    }

    res.value.map(_.fold(identity, identity))
  }

  private def uri(implicit request: RequestHeader): Result Xor String = {
    // remove the version part
    if (request.uri == "/") "/".right
    else if (request.uri.indexOf("/PlayNextQueue") == 0) request.uri.right
    else if (request.uri.indexOf("/login") == 0) request.uri.right
    else if (request.uri.indexOf('/', 1) == -1) {
      NotFound.left
    } else request.uri.substring(request.uri.indexOf('/', 1)).right
  }

  private def sendWsReq(server: Server, subdomain: String, uri: String)(implicit request: Request[AnyContent])
      : Future[Throwable Xor WSResponse] = {
    val params = request.body match {
      case AnyContentAsFormUrlEncoded(x) => x
      case _ => Map()
    }
    val res = ws.url("http://" + server.publicDns + routes.WebServices.processRequest)

      .withHeaders(CONTENT_TYPE -> ContentTypes.JSON)
      .post(Json.obj(
        "subdomain" -> subdomain,
        "uri" -> uri,
        "ip" -> requestUtils.getIp(request),
        "headers" -> Json.toJson(request.headers.toMap),
        "params" -> JsObject(
          for (pair <- params.toList) yield (pair._1, JsString(pair._2(0)))
        )))
    res.map(_.right).recover{ case t => t.left }
  }

  def test = WebSocket.using[String] { request =>
    val in = Iteratee.consume[String]() // dummy iteratee
    val out = Enumerator.generateM {
      // gotta wait a bit or else message will be ignored
      akka.pattern.after(4.seconds, system.scheduler)(Future(Some("HELLO")))

      // I CANNOT CLOSE HERE OR ELSE THE TEST WON'T WORK FOR TREND MICRO TITANIUM INTERNET SECURITY (WIN) WHEN USING UNSECURE WEBSOCKETS
      //out.close()
    }

    (in, out)
  }

  def longPollGet(subdomain: String) = Action.async { implicit requestHeader =>
    requestHeader.headers.get(USER_AGENT) match {
      case None => Future(BadRequest("Invalid user agent"))
      case Some(ua) if (ua != PULP_USER_AGENT) => Future(BadRequest("Invalid user agent"))
      case Some(_) =>
        val res = for {
          serverId <- XorT.fromXor[Future](maybeServerId)

          futMaybeHost = serverProvider.getById(serverId).map(_.map(_.publicDns))
          host <- XorT(futMaybeHost.map(_.leftMap(t => BadRequest(t.getMessage))))

          futMaybeResult = startLongPoll(subdomain, serverId, host)
          result <- XorT(futMaybeResult)
        } yield result
        res.value.map(_.fold(identity, identity))
    }
  }

  private def startLongPoll(subdomain: String, serverId: String, host: String)(implicit request: RequestHeader)
      : Future[Result Xor Result] = {
    val res = eventsActor ? StartLongPoll(subdomain, serverId, host)
    res.mapTo[Enumerator[JsValue]].map { enumerator =>
      subdomainLogProvider.add(subdomain, request)
      Ok.chunked(enumerator)
    }.map(_.right).recover{ case t: Throwable => InternalServerError(t.getMessage).left }
  }

  def longPollPost(subdomain: String) = Action(parse.json) { implicit request =>
    val json = request.body
    val received = if (json.toString.length > 200)
        json.toString.substring(0, 200) + "..."
      else
        json.toString
    Logger.debug(s"Dispatcher.longPollPost: $received")
    val clientId = (json \ "clientId").as[String]
    val responseFuture = eventsActor ! SendResponse(clientId, json)
    Ok
  }

  def join = WebSocket.tryAccept { implicit request =>
    val subdomain = requestUtils.getSubdomain(request)
    subdomainLogProvider.add(subdomain, request)
    val res = for {
      serverId <- XorT.fromXor[Future](maybeServerId)
      futMaybeJoinChannel = joinChannel(subdomain, serverId)
      joinChannelRes <- XorT(futMaybeJoinChannel)
    } yield joinChannelRes
    res.value.map(_.fold(Left(_), Right(_)))
  }

  private def joinChannel(subdomain: String, serverId: String)(implicit request: RequestHeader)
      : Future[Result Xor (Iteratee[JsValue, _], Enumerator[JsValue])] = {
    val res = eventsActor ? Join(subdomain, serverId)
    res.mapTo[(Iteratee[JsValue, _], Enumerator[JsValue])].map(_.right).recover{ case t: Throwable => BadRequest(t.getMessage).left }
  }

  private def errorView(error: String)(implicit request: RequestHeader) = {
    if (isMobile) views.html.error_mobile(error) else views.html.error(error)
  }

  private def isMobile(implicit request: RequestHeader): Boolean = 
    request.headers.get(USER_AGENT) match {
      case Some(ua) =>
        List("IPHONE", "IPOD", "IPAD", "ANDROID").find(ua.toUpperCase contains _).isDefined
      case None => false
    }
}
