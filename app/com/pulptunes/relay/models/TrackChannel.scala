package com.pulptunes.relay.models

import akka.util.ByteString
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.iteratee.Input

class TrackChannel(val subdomain: String, val fileType: String, val channel: Channel[ByteString]) extends Channel[ByteString] {

  private var finished = false

  def finish() = {
    finished = true
  }

  def isFinished = finished

  def push(chunk: Input[ByteString]) = channel.push(chunk)
  def end(e: Throwable) = channel.end(e)
  def end() = channel.end()
}
