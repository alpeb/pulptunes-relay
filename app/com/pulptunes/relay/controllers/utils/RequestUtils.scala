package com.pulptunes.relay.controllers.utils

import javax.inject.{Inject, Singleton}

import play.api.Configuration
import play.api.http
import play.api.mvc._

@Singleton
class RequestUtils @Inject() (config: Configuration) {
  def getSubdomain(req: Request[AnyContent]): String = getSubdomain(req.headers)

  def getSubdomain(req: RequestHeader): String = getSubdomain(req.headers)

  def getIp(req: RequestHeader): String =
    req.headers.get(http.HeaderNames.X_FORWARDED_FOR) match {
        case Some(x) => x
        case None => req.remoteAddress
    }

  private def getSubdomain(headers: Headers) = {
    val host = headers(play.api.http.HeaderNames.HOST)
    host.substring(0, host.indexOf('.'))
  }
}
