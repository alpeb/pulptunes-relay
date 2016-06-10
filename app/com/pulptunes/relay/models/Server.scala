package com.pulptunes.relay.models

import javax.inject.Inject

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import cats.data.Xor
import cats.syntax.option._
import cats.syntax.xor._
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.driver.JdbcProfile

case class Server(id: String, status: String, publicDns: String, online: String, onlineTest: String)

class ServerProvider @Inject() (protected val dbConfigProvider: DatabaseConfigProvider)
    extends HasDatabaseConfigProvider[JdbcProfile] {

  import driver.api._

  def getAll: Future[List[Server]] = db.run {
    servers.to[List].result
  }

  def getById(id: String): Future[Throwable Xor Server] = {
    val res = db.run {
      servers.filter(_.id === id).result.headOption
    }
    res.map(_ toRightXor(new Exception(s"Server $id not found in the database"))).recover {
      case t => t.left
    }
  }

  def isUp(id: String, testStatus: Boolean): Future[Boolean] = {
    getById(id).map(_.fold(
      _ => false,
      server => {
        if (testStatus) server.status == "test" && server.onlineTest == "true"
        else server.status == "production" && server.online == "true"
      }
    ))
  }

  val servers = TableQuery[Servers]

  class Servers(_tableTag: Tag) extends Table[Server](_tableTag, "servers") {
    def * = (id, status, publicDns, online, onlineTest) <> (Server.tupled, Server.unapply)
    /** Maps whole row to an option. Useful for outer joins. */
    def ? = (Rep.Some(id), Rep.Some(status), Rep.Some(publicDns), Rep.Some(online), Rep.Some(onlineTest)).shaped.<>({r=>import r._; _1.map(_=> Server.tupled((_1.get, _2.get, _3.get, _4.get, _5.get)))}, (_:Any) =>  throw new Exception("Inserting into ? projection not supported."))

    /** Database column id SqlType(VARCHAR), PrimaryKey, Length(100,true) */
    val id: Rep[String] = column[String]("id", O.PrimaryKey, O.Length(100,varying=true))
    /** Database column status SqlType(VARCHAR), Length(20,true) */
    val status: Rep[String] = column[String]("status", O.Length(20,varying=true))
    /** Database column public_dns SqlType(VARCHAR), Length(100,true) */
    val publicDns: Rep[String] = column[String]("public_dns", O.Length(100,varying=true))
    /** Database column online SqlType(VARCHAR), Length(10,true) */
    val online: Rep[String] = column[String]("online", O.Length(10,varying=true))
    /** Database column online_test SqlType(VARCHAR), Length(10,true) */
    val onlineTest: Rep[String] = column[String]("online_test", O.Length(10,varying=true))
  }
}

