package com.pulptunes.relay.actors

import java.util.UUID
import javax.inject.{Inject, Singleton}

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.collection.immutable.Queue

import akka.actor.{Actor, ActorSystem, Props}
import cats.data.Xor.{Left, Right}
import play.api.{Configuration, Logger}
import play.api.libs.concurrent._
import play.api.libs.iteratee.{Concurrent, Iteratee}
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.json._
import play.api.mvc._

import com.pulptunes.relay.controllers.utils._
import com.pulptunes.relay.models._

case object GetWebSocketChannels
case object GetLongPollingChannels
case object GetLongPollingStarts
case object GetLongPollingQueuedGetRequests
case object GetLongPollingQueuedSendFiles
case class Join(subdomain: String, serverId: String)
case class StartLongPoll(subdomain: String, serverId: String, enumHost: String)
case class Leave(subdomain: String)
case object LeaveAll
case object GC
case class GetRequest(getRequestData: GetRequestData, enumHost: String)
case class SendResponse(clientId: String, json: JsValue)
case class SendFile(subdomain: String, streamId: String, host: String, enumHost: String, uri: String,
  ip: String, headers: Map[String, List[String]])

@Singleton
class Events @Inject() (config: Configuration, system: ActorSystem, serverProvider: ServerProvider,
    licenseProvider: LicenseProvider, servingListenerProvider: ServingListenerProvider)
  extends Actor {

  // must be greater than the HAProxy timeout (240s). And also the jfx app's (300s), I think.
  private val LONG_POLLING_GC_SECS = 360

  var webSocketChannels = Map[String, Channel[JsValue]]()
  var longPollingChannels = Map[String, Channel[JsValue]]()
  var longPollingStarts = Map[String, Long]()
  var longPollingQueuedGetRequests = Map[String, List[GetRequestData]]()
  var longPollingQueuedSendFiles = Map[String, List[SendFileData]]()
  var jfxAppResponses = Map[String, Promise[JsValue]]()

  system.scheduler.schedule(0.seconds, LONG_POLLING_GC_SECS seconds, self, GC)

  def receive = {
    case GetWebSocketChannels => sender ! webSocketChannels
    case GetLongPollingChannels => sender ! longPollingChannels
    case GetLongPollingStarts => sender ! longPollingStarts
    case GetLongPollingQueuedGetRequests => sender ! longPollingQueuedGetRequests
    case GetLongPollingQueuedSendFiles => sender ! longPollingQueuedSendFiles

    case Join(subdomain: String, serverId: String) =>
      val iteratee = Iteratee.foreach[JsValue] { json => 
        val received = if (json.toString.length > 200) json.toString.substring(0, 200) + "..." else json.toString
        Logger.debug(s"$subdomain - RECEIVED: $received")
        (json \ "ping").asOpt[Boolean] match {
          case Some(_) =>
            Logger.debug(s"$subdomain - SENDING: pong")
            webSocketChannels.get(subdomain).foreach(_.push(Json.obj("pong" -> true)))
          case None =>
            val clientId = (json \ "clientId").as[String]
            val status = (json \ "replyCode").as[Int]
            if (status == play.api.http.Status.OK ||
                status == play.api.http.Status.UNAUTHORIZED ||
                status == play.api.http.Status.SEE_OTHER) {
              jfxAppResponses(clientId).success(json)
            } else
              jfxAppResponses(clientId).failure(
                new Exception((json \ "replyMessage").as[String])
              )
            jfxAppResponses -= clientId
        }
      }.map { _ =>
        Logger.debug(s"$subdomain - iteratee done")
        leaveWebSocket(subdomain)
      }

      val enumerator = Concurrent.unicast[JsValue] { channel =>
        webSocketChannels += subdomain -> channel
        Logger.info(s"$subdomain - websocket established. ${webSocketChannels.size.toString} websocket connections")

        licenseProvider.getBySubdomain(subdomain).map{
          case Left(t) => channel.push(Json.obj("error" -> t.getMessage))
          case Right(license) if (license.hasExpired) => channel.push(Json.obj("error" -> "Your license has expired"))
          case _ =>
        }
      }

      servingListenerProvider.save(ServingListener(senderSubdomain = subdomain, serverId = serverId, polling = 0))

      sender ! (iteratee, enumerator)

    case StartLongPoll(subdomain: String, serverId: String, enumHost: String) =>
      val enumerator = Concurrent.unicast[JsValue] { channel =>
        longPollingChannels += subdomain -> channel
        longPollingStarts += subdomain -> System.currentTimeMillis
        Logger.info(s"$subdomain - longpolling established. ${longPollingChannels.size.toString} longpolling connections")

        licenseProvider.getBySubdomain(subdomain) map {
          case Left(t) =>
            channel.push(Json.obj("requests" -> Json.arr(Json.obj("error" -> t.getMessage))))
            leaveLongPolling(subdomain, false)
          case Right(license) if (license.hasExpired) =>
            channel.push(Json.obj("requests" -> Json.arr(Json.obj("error" -> "Your license has expired"))))
            leaveLongPolling(subdomain, false)
          case _ =>
            longPollingQueuedSendFiles.get(subdomain) match {
              case None =>
              case Some(pendingSendFiles) =>
                Logger.info(s"$subdomain - flushing pending SendFiles: " + pendingSendFiles.map(_.streamId).mkString(", "))
                channel.push(Json.obj("requests" -> Json.toJson(pendingSendFiles.map(getJsonRequest(_, enumHost)))))
                longPollingQueuedSendFiles -= subdomain
                leaveLongPolling(subdomain, false)
            }
            longPollingQueuedGetRequests.get(subdomain) match {
              case None =>
              case Some(pendingGetRequests) =>
                Logger.info(s"$subdomain - flushing ${pendingGetRequests.length} pending GetRequests")
                channel.push(Json.obj("requests" -> Json.toJson(pendingGetRequests.map(getJsonRequest(_, enumHost)))))
                longPollingQueuedGetRequests -= subdomain
                leaveLongPolling(subdomain, false)
            }
        }
      }

      servingListenerProvider.save(ServingListener(senderSubdomain = subdomain, serverId = serverId, polling = 2))

      sender ! enumerator

    case Leave(subdomain) =>
      webSocketChannels.get(subdomain) match {
        case Some(channel) => leaveWebSocket(subdomain)
        case None => longPollingChannels.get(subdomain) match {
          case Some(channel) => leaveLongPolling(subdomain, false)
          case None =>
        }
      }

    case LeaveAll =>
      if (webSocketChannels.nonEmpty || longPollingChannels.nonEmpty) {
        Logger.info(s"Sending changeRelayServer signal to all instances (${webSocketChannels.size.toString} websockets, ${longPollingChannels.size.toString} longpolling)")
        webSocketChannels.foreach(_._2.push(Json.obj("changeRelayServer" -> true)))
        longPollingChannels.foreach { pair =>
          pair._2.push(Json.obj("requests" -> Json.arr(Json.obj("changeRelayServer" -> true))))
          leaveLongPolling(pair._1, false)
        }
      }

    case GC =>
      longPollingStarts.filter(_._2 < (System.currentTimeMillis - LONG_POLLING_GC_SECS * 1000))
      .foreach(x => leaveLongPolling(x._1, true))

    case GetRequest(getRequestData, enumHost) =>
      val responsePromise: Promise[JsValue] = scala.concurrent.Promise[JsValue]()
      jfxAppResponses += getRequestData.clientId -> responsePromise
      webSocketChannels.get(getRequestData.subdomain) match {
        case Some(channel) =>
          channel.push(getJsonRequest(getRequestData, enumHost))

        case None => 
          longPollingChannels.get(getRequestData.subdomain) match {
            case Some(channel) =>
              val jsonRequests = Json.toJson(
                (for (queuedRequest <- longPollingQueuedGetRequests.get(getRequestData.subdomain).getOrElse(Nil))
                  yield getJsonRequest(queuedRequest, enumHost)) :+ getJsonRequest(getRequestData, enumHost)
              )
              channel.push(Json.obj("requests" -> jsonRequests))
              longPollingQueuedGetRequests -= getRequestData.subdomain
              leaveLongPolling(getRequestData.subdomain, false)

            case None =>
              // might happen if longpoll is being restarted
              Logger.debug(s"${getRequestData.subdomain} - GetRequest queued - clientId: ${getRequestData.clientId}")
              val newQueue = longPollingQueuedGetRequests.get(getRequestData.subdomain) match {
                case Some(oldQueue) => oldQueue :+ getRequestData
                case None => List(getRequestData)
              }
              longPollingQueuedGetRequests += getRequestData.subdomain -> newQueue
          }
      }

      sender ! responsePromise.future

    case SendResponse(clientId, json) =>
      Logger.debug(s"SendResponse clientId: $clientId")
      jfxAppResponses.get(clientId).map { promise =>
        promise.success(json)
        jfxAppResponses -= clientId
      }

    case SendFile(subdomain, streamId, host, enumHost, uri, ip, headers) =>
      val responsePromise: Promise[JsValue] = scala.concurrent.Promise[JsValue]
      val clientId = UUID.randomUUID.toString
      jfxAppResponses += clientId -> responsePromise
      webSocketChannels.get(subdomain) match {
        case Some(channel) =>
          channel.push(Json.obj("uri" -> uri, "ip" -> ip, "clientId" -> clientId, "host" -> host,
            "enum_host" -> enumHost, "streamId" -> streamId, "headers" -> Json.toJson(headers)))

        case None =>
          val sendFileData = SendFileData(uri, ip, clientId, host, enumHost, streamId, headers)

          longPollingChannels.get(subdomain) match {
            case Some(channel) =>
              Logger.debug(s"$subdomain - SendFile signal to longpoll ($streamId) - clientId: $clientId")

              val jsonRequests = Json.toJson(
                (for (queuedRequest <- longPollingQueuedSendFiles.get(subdomain).getOrElse(Nil))
                  yield getJsonRequest(queuedRequest, enumHost)) :+ getJsonRequest(sendFileData, enumHost)
              )
              channel.push(Json.obj("requests" -> jsonRequests))
              longPollingQueuedGetRequests -= subdomain
              leaveLongPolling(subdomain, false)

            case None =>
              // might happen if longpoll is being restarted
              Logger.debug(s"$subdomain - SendFile queued ($streamId) - clientId: $clientId")
              val newQueue = longPollingQueuedSendFiles.get(subdomain) match {
                case Some(oldQueue) => oldQueue :+ sendFileData
                case None => List(sendFileData)
              }
              longPollingQueuedSendFiles += subdomain -> newQueue
          }
      }

      sender ! responsePromise.future
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    Logger.warn("Actor Events preRestart: " + message.getOrElse("").toString, reason)
    super.preRestart(reason, message);
  }

  private def leaveWebSocket(subdomain: String) {
    webSocketChannels.get(subdomain).foreach { channel =>
      channel.eofAndEnd()
      webSocketChannels -= subdomain
      Logger.info(s"$subdomain - websocket disconnected. ${webSocketChannels.size.toString} websocket connections")
    }
  }

  /**
  * When calling channel.push() when doing polling, *ALWAYS* call this immediately so the request is flushed!
  */
  private def leaveLongPolling(subdomain: String, gc: Boolean) {
    longPollingChannels.get(subdomain).foreach { channel =>
      channel.eofAndEnd()
      longPollingChannels -= subdomain
      longPollingStarts -= subdomain
      val gcStr = if (gc) "GC - " else ""
      Logger.info(s"$subdomain - ${gcStr}longpolling disconnected. ${longPollingChannels.size.toString} longpolling connections")
    }
  }

  private def getJsonRequest(getRequestData: GetRequestData, enumHost: String) =
    Json.obj("uri" -> getRequestData.uri, "ip" -> getRequestData.ip, "enum_host" -> enumHost,
      "clientId" -> getRequestData.clientId, "headers" -> Json.toJson(getRequestData.headers),
      "params" -> Json.toJson(getRequestData.params))

  private def getJsonRequest(sendFileData: SendFileData, enumHost: String) =
    Json.obj("uri" -> sendFileData.uri, "ip" -> sendFileData.ip, "clientId" -> sendFileData.clientId,
      "host" -> sendFileData.host, "enum_host" -> enumHost, "streamId" -> sendFileData.streamId,
      "headers" -> Json.toJson(sendFileData.headers))
}
