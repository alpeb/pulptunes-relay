package com.pulptunes.relay.models

import java.util.UUID

import play.api.libs.json._

case class SendFileData(
  uri: String,
  ip: String,
  clientId: String = UUID.randomUUID.toString,
  host: String,
  enum_host: String,
  streamId: String,
  headers: Map[String, List[String]]
)

object SendFileData {

  def apply(json: JsValue): SendFileData = SendFileData(
    uri = (json \ "uri").as[String],
    ip = (json \ "ip").as[String],
    clientId = (json \ "clientId").as[String],
    host = (json \ "host").as[String],
    enum_host = (json \ "enum_host").as[String],
    streamId = (json \ "streamId").as[String],
    headers = (json \ "headers").as[Map[String, List[String]]]
  )
}

