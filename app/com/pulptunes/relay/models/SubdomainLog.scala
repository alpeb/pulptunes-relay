package com.pulptunes.relay.models

import java.sql.Timestamp
import java.util.Calendar
import javax.inject.Inject

import scala.concurrent.Future

import play.api._
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.mvc._
import slick.driver.JdbcProfile

import com.pulptunes.relay.controllers.utils.RequestUtils

case class SubdomainLog(id: Int = 0, subdomain: String, ip: String, timestamp: Timestamp)

class SubdomainLogProvider @Inject() (protected val dbConfigProvider: DatabaseConfigProvider, requestUtils: RequestUtils)
    extends HasDatabaseConfigProvider[JdbcProfile] {

  import driver.api._

  def add(model: SubdomainLog): Future[SubdomainLog] = db.run {
    (subdomainLogs returning subdomainLogs.map(_.id) into ((row, id) => row.copy(id = id))) += model
  }

  def add(subdomain: String, req: RequestHeader): Future[SubdomainLog] = db.run {
    val cal = Calendar.getInstance;
    val model = SubdomainLog(subdomain = subdomain, ip = requestUtils.getIp(req), timestamp = new Timestamp(cal.getTime.getTime))
    (subdomainLogs returning subdomainLogs.map(_.id) into ((row, id) => row.copy(id = id))) += model
  }

  private val subdomainLogs = TableQuery[SubdomainLogTable]

  private class SubdomainLogTable(_tableTag: Tag) extends Table[SubdomainLog](_tableTag, "subdomain_log") {
    def * = (id, subdomain, ip, timestamp) <> (SubdomainLog.tupled, SubdomainLog.unapply)
    /** Maps whole row to an option. Useful for outer joins. */
    def ? = (Rep.Some(id), Rep.Some(subdomain), Rep.Some(ip), Rep.Some(timestamp)).shaped.<>({r=>import r._; _1.map(_=> SubdomainLog.tupled((_1.get, _2.get, _3.get, _4.get)))}, (_:Any) =>  throw new Exception("Inserting into ? projection not supported."))

    /** Database column id SqlType(INT), AutoInc, PrimaryKey */
    val id: Rep[Int] = column[Int]("id", O.AutoInc, O.PrimaryKey)
    /** Database column subdomain SqlType(VARCHAR), Length(20,true) */
    val subdomain: Rep[String] = column[String]("subdomain", O.Length(20,varying=true))
    /** Database column ip SqlType(VARCHAR), Length(20,true) */
    val ip: Rep[String] = column[String]("ip", O.Length(20,varying=true))
    /** Database column timestamp SqlType(DATETIME) */
    val timestamp: Rep[Timestamp] = column[Timestamp]("timestamp")
  }
}
