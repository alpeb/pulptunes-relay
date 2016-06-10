package com.pulptunes.relay.models

import java.sql.Timestamp
import java.util.{Calendar, Date}
import javax.inject.Inject

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import cats.data.Xor
import cats.syntax.option._
import cats.syntax.xor._
import play.api.Configuration
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.driver.JdbcProfile

case class License(id: Int = 0, subdomain: String, name: String, email: String, signup: Timestamp,
    trial: Int, trialEnds: Timestamp, password: String, passwordToken: Option[String] = None,
    passwordTokenExpiration: Option[Timestamp] = None) {
  def hasExpired: Boolean = trial == 1 && trialEnds.compareTo(new Date) < 0
}

class LicenseProvider @Inject() (config: Configuration, protected val dbConfigProvider: DatabaseConfigProvider)
    extends HasDatabaseConfigProvider[JdbcProfile] {

  import driver.api._

  private val TRIAL_DAYS = 7
  
  def add(model: License): Future[License] = db.run {
    val cal = Calendar.getInstance;
    cal.add(Calendar.DAY_OF_MONTH, TRIAL_DAYS);
    val modelWithTrial = model.copy(trialEnds = new Timestamp(cal.getTime.getTime))
    (licenses returning licenses.map(_.id) into ((license, id) => license.copy(id = id))) += modelWithTrial
  }

  def save(model: License) = db.run {
    licenses.filter(_.id === model.id).update(model)
  }

  def getBySubdomain(subdomain: String): Future[Throwable Xor License] = {
    val res = db.run {
      licenses.filter(_.subdomain === subdomain).result.headOption
    }
    res.map(_ toRightXor(new Exception("Invalid subdomain"))).recover {
      case t => t.left
    }
  }

  def getByEmail(email: String): Future[Option[License]] = db.run {
    licenses.filter(_.email === email).result.headOption
  }

  def getByEmailOrSubdomain(emailOrSubdomain: String): Future[Option[License]] = db.run {
    licenses.filter(r => r.email === emailOrSubdomain || r.subdomain === emailOrSubdomain).result.headOption
  }

  def getByToken(token: String): Future[Option[License]] = db.run {
    licenses.filter(_.passwordToken === token).result.headOption
  }

  private val licenses = TableQuery[Licenses]

  private class Licenses(_tableTag: Tag) extends Table[License](_tableTag, "licenses") {
    def * = (id, subdomain, name, email, signup, trial, trialEnds, password, passwordToken, passwordTokenExpiration) <> (License.tupled, License.unapply)
    /** Maps whole row to an option. Useful for outer joins. */
    def ? = (Rep.Some(id), Rep.Some(subdomain), Rep.Some(name), Rep.Some(email), Rep.Some(signup), Rep.Some(trial), Rep.Some(trialEnds), Rep.Some(password), passwordToken, passwordTokenExpiration).shaped.<>({r=>import r._; _1.map(_=> License.tupled((_1.get, _2.get, _3.get, _4.get, _5.get, _6.get, _7.get, _8.get, _9, _10)))}, (_:Any) =>  throw new Exception("Inserting into ? projection not supported."))

    /** Database column id SqlType(INT), AutoInc, PrimaryKey */
    val id: Rep[Int] = column[Int]("id", O.AutoInc, O.PrimaryKey)
    /** Database column subdomain SqlType(VARCHAR), Length(20,true) */
    val subdomain: Rep[String] = column[String]("subdomain", O.Length(20,varying=true))
    /** Database column name SqlType(VARCHAR), Length(100,true) */
    val name: Rep[String] = column[String]("name", O.Length(100,varying=true))
    /** Database column email SqlType(VARCHAR), Length(100,true) */
    val email: Rep[String] = column[String]("email", O.Length(100,varying=true))
    /** Database column signup SqlType(DATETIME) */
    val signup: Rep[Timestamp] = column[Timestamp]("signup")
    /** Database column trial SqlType(INT) */
    val trial: Rep[Int] = column[Int]("trial")
    /** Database column trial_ends SqlType(DATETIME) */
    val trialEnds: Rep[Timestamp] = column[Timestamp]("trial_ends")
    /** Database column password SqlType(CHAR), Length(102,false) */
    val password: Rep[String] = column[String]("password", O.Length(102,varying=false))
    /** Database column password_token SqlType(VARCHAR), Length(50,true), Default(None) */
    val passwordToken: Rep[Option[String]] = column[Option[String]]("password_token", O.Length(50,varying=true), O.Default(None))
    /** Database column password_token_expiration SqlType(TIMESTAMP), Default(None) */
    val passwordTokenExpiration: Rep[Option[Timestamp]] = column[Option[Timestamp]]("password_token_expiration", O.Default(None))

    /** Uniqueness Index over (email) (database name email) */
    val index1 = index("email", email, unique=true)
    /** Uniqueness Index over (subdomain) (database name subdomain) */
    val index2 = index("subdomain", subdomain, unique=true)
  }
}
