package com.pulptunes.relay.models

import java.util.UUID

import play.api.libs.json._

case class GetRequestData (
  subdomain: String,
  uri: String,
  ip: String,
  headers: Map[String, List[String]],
  params: Map[String, String],
  clientId: String = UUID.randomUUID.toString
)

object GetRequestData {

  def apply(json: JsValue): GetRequestData = GetRequestData(
    subdomain = (json \ "subdomain").as[String],
    uri = (json \ "uri").as[String],
    ip = (json \ "ip").as[String],
    headers = (json \ "headers").as[Map[String, List[String]]],
    params = (json \ "params").as[Map[String, String]]
  )
}
