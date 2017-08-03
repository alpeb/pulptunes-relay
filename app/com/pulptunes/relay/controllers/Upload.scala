package com.pulptunes.relay.controllers

import javax.inject.{Inject, Named}

import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.{ByteString, Timeout}
import play.api.{Configuration, Logger}
import play.api.libs.concurrent._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee._
import play.api.libs.iteratee.Concurrent.Channel
import play.api.mvc._

import com.pulptunes.relay.actors._
import com.pulptunes.relay.models._

class Upload @Inject() (config: Configuration, @Named("pulp-enums-registry") enumsRegistryActor: ActorRef)
  extends Controller {

  def index(streamId: String) = Action(fileParser(streamId)) (_ => Ok)

  private def fileParser(streamId: String) = BodyParser.iteratee { requestHeader =>
    implicit val timeout = Timeout(2.second)
    val futChannelDataFuture = enumsRegistryActor ? RetrieveChannel(streamId)
    val futIteratee = futChannelDataFuture.mapTo[(String, String, TrackChannel)].map { channelData =>
      val subdomain = channelData._1
      val channel = channelData._3

      Logger.debug(s"$subdomain - Upload headers: ${requestHeader.headers.toString}");
      
      fold(channel).map { channel =>
        // need to push an empty byte for the stream to end
        Logger.debug(s"$subdomain - Finished uploading file")
        channel.push(ByteString())
        enumsRegistryActor ! DiscardChannel(streamId)
        Right(())
      }
    }
    Iteratee.flatten(futIteratee)
  }

  /**
  * @see https://groups.google.com/forum/#!searchin/play-framework/disconnect/play-framework/VPwfJv3ZZck/vbSn3h4zAe4J
  *
  * I'm not using Iteratee.foreach because I need tighter control to be able to end the iteratee when the channel
  * is flagged as finished. Otherwise whenever a client interrupts a track download the pulptunes desktop app will
  * continue streaming it here.
  */
  private def fold(state: TrackChannel) : Iteratee[ByteString, Channel[ByteString]] = {

    val latency = config.getBoolean("pulp.production") match {
      case Some(true) => 0
      case _ => config.getInt("pulp.transfer_latency").get
    }

    def step(i: Input[ByteString]): Iteratee[ByteString, Channel[ByteString]] =
      i match {
        case Input.EOF => Done(state, Input.EOF)
        case Input.Empty => Cont[ByteString, Channel[ByteString]](i => step(i))
        case Input.El(e) =>
          if (latency > 0)
            Thread.sleep(latency)

          if (state.isFinished) {
            Done(state, Input.EOF)
          } else {
            state.push(e)
            Cont[ByteString, Channel[ByteString]](i => step(i))
          }
      }

    (Cont[ByteString, Channel[ByteString]](i => step(i)))
  }
}

