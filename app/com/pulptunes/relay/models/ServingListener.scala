package com.pulptunes.relay.models

import javax.inject.Inject

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import cats.data.Xor
import cats.syntax.option._
import cats.syntax.xor._
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.driver.JdbcProfile

case class ServingListener(id: Int = 0, senderSubdomain: String, serverId: String, polling: Int = 0)

class ServingListenerProvider @Inject() (protected val dbConfigProvider: DatabaseConfigProvider, serverProvider: ServerProvider)
    extends HasDatabaseConfigProvider[JdbcProfile] {

  import driver.api._

  def getServer(model: ServingListener): Future[Throwable Xor Server] = serverProvider.getById(model.serverId)

  def add(model: ServingListener): Future[ServingListener] = db.run {
    (servingListeners returning servingListeners.map(_.id) into ((servingListener, id) => servingListener.copy(id = id))) += model
  }

  def save(model: ServingListener) = db.run {
    servingListeners.insertOrUpdate(model)
  }

  def getBySenderSubdomain(senderSubdomain: String): Future[Throwable Xor ServingListener] = {
    val res = db.run {
      servingListeners.filter(_.senderSubdomain === senderSubdomain).result.headOption
    }
    res.map(_ toRightXor(new Exception(s"Subdomain $senderSubdomain is offline"))).recover {
      case t => t.left
    }
  }

  private val servingListeners = TableQuery[ServingListeners]

  private class ServingListeners(_tableTag: Tag) extends Table[ServingListener](_tableTag, "serving_listeners") {
    def * = (id, senderSubdomain, serverId, polling) <> (ServingListener.tupled, ServingListener.unapply)
    /** Maps whole row to an option. Useful for outer joins. */
    def ? = (Rep.Some(id), Rep.Some(senderSubdomain), Rep.Some(serverId), Rep.Some(polling)).shaped.<>({r=>import r._; _1.map(_=> ServingListener.tupled((_1.get, _2.get, _3.get, _4.get)))}, (_:Any) =>  throw new Exception("Inserting into ? projection not supported."))

    /** Database column id SqlType(INT), AutoInc, PrimaryKey */
    val id: Rep[Int] = column[Int]("id", O.AutoInc, O.PrimaryKey)
    /** Database column sender_subdomain SqlType(VARCHAR), Length(50,true) */
    val senderSubdomain: Rep[String] = column[String]("sender_subdomain", O.Length(50,varying=true))
    /** Database column server_id SqlType(VARCHAR), Length(100,true) */
    val serverId: Rep[String] = column[String]("server_id", O.Length(100,varying=true))
    /** Database column polling SqlType(INT), Default(0) */
    val polling: Rep[Int] = column[Int]("polling", O.Default(0))

    /** Foreign key referencing Servers (database name serving_listeners_ibfk_1) */
    lazy val serversFk = foreignKey("serving_listeners_ibfk_1", serverId, serverProvider.servers)(r => r.id, onUpdate=ForeignKeyAction.NoAction, onDelete=ForeignKeyAction.NoAction)

    /** Uniqueness Index over (senderSubdomain) (database name sender_subdomain) */
    val index1 = index("sender_subdomain", senderSubdomain, unique=true)
  }
}
