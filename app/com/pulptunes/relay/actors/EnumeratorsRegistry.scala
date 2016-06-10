package com.pulptunes.relay.actors

import java.util.UUID
import javax.inject.Inject

import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.Actor
import akka.util.ByteString
import play.api.{Configuration, Logger}
import play.api.libs.iteratee.{Concurrent, Enumerator}
import play.api.libs.iteratee.Concurrent.Channel

import com.pulptunes.relay.models.TrackChannel

case class ChannelStream(key: String, completeEnumerator: Enumerator[ByteString])
case class GetFresh(subdomain: String, fileType: String, isRangeRequest: Boolean)
case class RetrieveChannel(key: String)
case class DiscardChannel(key: String)

class EnumeratorsRegistry @Inject() (config: Configuration) extends Actor {
  
  val serverId = config.getString("pulp.server_id").get

  var channels = Map[String, (String, String, TrackChannel)]()

  def receive = {
    case GetFresh(subdomain, fileType, isRangeRequest) =>
      val key = UUID.randomUUID.toString

      // Even if just one iteratee is going to bind to this enum, I can't use
      // Concurrent.unicast cuz its channel won't be returned until after the iteratee
      // has been bound to this enum, which might cause race conditions
      val (enumerator, channel) = Concurrent.broadcast[ByteString]
      val completeEnumerator = enumerator.onDoneEnumerating{
        // client closed connection, before or at streaming completion
        self ! DiscardChannel(key)
      }
      channels += key -> (subdomain, fileType, new TrackChannel(channel))

      // need to log the key here cuz of some timeouts I'm having on RetrieveChannel()
      val range = if (isRangeRequest) " (range)" else ""
      Logger.info(s"$subdomain - Stream ${fileType} STARTED$range. ${channels.size} active transfers ($key)");

      sender ! ChannelStream(key, completeEnumerator)

    case RetrieveChannel(key) => 
      val channelData = channels(key)
      sender ! channelData

    case DiscardChannel(key) =>
      channels.get(key) map { channelData =>
        channelData._3.eofAndEnd()
        channelData._3.finish()
        channels -= key
        Logger.info(s"${channelData._1} - Stream for ${channelData._2} ENDED. ${channels.size} active transfers ($key)")
      }
  }
}
