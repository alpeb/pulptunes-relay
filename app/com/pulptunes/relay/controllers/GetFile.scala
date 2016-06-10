package com.pulptunes.relay.controllers

import javax.inject.{Inject, Named}

import scala.concurrent._
import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import cats.data.{Xor, XorT}
import cats.std.future._
import cats.syntax.option._
import cats.syntax.xor._
import play.api.Configuration
import play.api.http
import play.api.libs.concurrent._
import play.api.Logger
import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee._
import play.api.libs.json._
import play.api.libs.streams.Streams
import play.api.libs.ws.{WSClient, WSResponse}

import com.pulptunes.relay.controllers.utils._
import com.pulptunes.relay.actors._
import com.pulptunes.relay.models._

class GetFile @Inject() (config: Configuration, ws: WSClient, serverProvider: ServerProvider,
    servingListenerProvider: ServingListenerProvider, requestUtils: RequestUtils,
    @Named("pulp-enums-registry") enumsRegistryActor: ActorRef)
  extends Controller {

  implicit val timeout = Timeout(1.seconds)

  val fileRegex = "^(.+)\\.(\\w+)$".r

  val maybeServerId = config.getString("pulp.server_id").toRightXor(InternalServerError("server_id config not set"))

  def song(songRequest: String) = {
    getFile("song")
  }

  def cover(coverRequest: String) = {
    getFile("cover")
  }

  private def getFile(fileType: String) = Action.async { implicit request =>
    val subdomain = requestUtils.getSubdomain(request)

    val res = for {
      serverId <- XorT.fromXor[Future](maybeServerId)

      futMaybeHost = serverProvider.getById(serverId)
      host <- XorT(futMaybeHost.map(_.leftMap(t => BadRequest(t.getMessage))))

      futMaybeServingListener = servingListenerProvider.getBySenderSubdomain(subdomain)
      servingListener <- XorT(futMaybeServingListener.map(_.leftMap{ t =>
        Logger.debug(s"$subdomain - GetFile.getFile: subdomain is offline")
        BadRequest(t.getMessage)
      }))

      futMaybeServer = servingListenerProvider.getServer(servingListener)
      server <- XorT(futMaybeServer.map(_.leftMap(t => InternalServerError(t.getMessage))))

      futMaybeChannelStream = getEnumsRegistryChannels(subdomain, fileType)
      channelStream <- XorT(futMaybeChannelStream.map(_.leftMap(t => InternalServerError(t.getMessage))))

      futMaybeWsResp = sendWsReq(server, subdomain, host.publicDns, channelStream.key)
      wsResp <- XorT(futMaybeWsResp.map(_.leftMap(t => InternalServerError(t.getMessage))))
    } yield {
      if (wsResp.status == http.Status.OK) {
        // simple stream instead of chunks (Ok.stream) to be able to get a download progress bar
        // on the downloading side

        val fileName = (wsResp.json \ "fileName").asOpt[String]

        // stupid GetAlbumCoverAction sends fileSize
        var length = (wsResp.json \ "length").asOpt[Long].orElse((wsResp.json \ "fileSize").asOpt[Long])

        if (fileName.isEmpty || length.isEmpty) {
          Logger.warn(s"$subdomain - GetFile.getFile - error parsing response. Response body: ${wsResp.body.toString}")
          enumsRegistryActor ! DiscardChannel(channelStream.key)
          BadRequest
        } else {
          val fileRegex(prefix, extension) = fileName.get
          val mimeType = extension match {
            case "m4a" => "aac"
            case "wav" => "wav"
            case _ => "mpeg"
          }

          val responseStatus = if (isRangeRequest) http.Status.PARTIAL_CONTENT else http.Status.OK

          var responseHeaders = Map(
            CONTENT_DISPOSITION -> s"""attachment; filename="${fileName.get}"""",
            CONTENT_TYPE -> s"audio/$mimeType",
            CONTENT_LENGTH -> length.get.toString
          )

          if (isRangeRequest) responseHeaders += (
            ACCEPT_RANGES -> "bytes",
            ETAG -> (wsResp.json \ "etag").asOpt[String].get,
            CONTENT_RANGE -> (wsResp.json \ "range").asOpt[String].get
          )

          val bodyPublisher = Streams.enumeratorToPublisher(channelStream.completeEnumerator)
          val bodySource = Source.fromPublisher(bodyPublisher)
          val entity = http.HttpEntity.Streamed(bodySource, None, None)

          Result(header = ResponseHeader(responseStatus, responseHeaders), body = entity)
        }
      } else {
        enumsRegistryActor ! DiscardChannel(channelStream.key)
        BadRequest
      }
    }

    res.value.map(_.fold(identity, identity))
  }

  private def getEnumsRegistryChannels(subdomain: String, fileType: String)(implicit request: RequestHeader)
      : Future[Throwable Xor ChannelStream] = {
    val res = enumsRegistryActor ? GetFresh(subdomain, fileType, isRangeRequest)
    res.mapTo[ChannelStream].map(_.right).recover{ case t => t.left }
  }

  private def sendWsReq(server: Server, subdomain: String, host: String, key: String)(implicit request: RequestHeader)
    : Future[Throwable Xor WSResponse] = {

    val res = ws.url("http://" + server.publicDns + routes.WebServices.sendfile)
      .withHeaders(CONTENT_TYPE -> http.ContentTypes.JSON)
      .post(Json.obj(
        "senderSubdomain" -> subdomain,
        "streamId" -> key,
        "host" -> host,
        "uri" -> request.uri.substring(request.uri.indexOf('/', 1)),
        "ip" -> requestUtils.getIp(request),
        "headers" -> Json.toJson(request.headers.toMap)))
    res.map(_.right).recover{ case t => t.left }
  }

  private def isRangeRequest(implicit request: RequestHeader) =  request.headers.toMap contains RANGE
}
