// Copyright (c) 2014 Viktor Stanchev & Kirk Zathey.
// This file is licensed under the Affero General Public License version 3 or later,
// see the accompanying file COPYING or http://www.gnu.org/licenses/agpl.html.

import akka.actor.{ ActorRef, Props }
import anorm.MetaDataItem
import anorm.TypeDoesNotMatch
import anorm.{ TypeDoesNotMatch, MetaDataItem }
import com.googlecode.jsonrpc4j.JsonRpcHttpClient
import controllers.API.CryptoAddress
import java.net.{ PasswordAuthentication, Authenticator, URL }
import play.api.mvc.Result
import scala.concurrent.duration._
import models._
import org.joda.time.DateTime
import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.json.{ Json, JsObject }
import play.libs.Akka
import scala.collection.JavaConverters._
import scala.concurrent.Future
import service.txbitsUserService
import wallet.{ WalletModel, Wallet }

package object globals {

  // handles parsing bigDecimal columns
  // JDBC always gives us a java BigDecimal and we have to convert it into a scala one
  implicit val bigDecimalColumn = anorm.Column[BigDecimal] { (value, meta) =>
    val MetaDataItem(qualified, nullable, clazz) = meta
    value match {
      case v: java.math.BigDecimal => Right(BigDecimal(v))
      case _ => Left(TypeDoesNotMatch("Cannot convert " + value + ":" + value.asInstanceOf[AnyRef].getClass + " to BigDecimal for column " + qualified))
    }
  }

  // handles parsing timestamps
  implicit val timestampColumn = anorm.Column[DateTime] { (value, meta) =>
    val MetaDataItem(qualified, nullable, clazz) = meta
    value match {
      case v: java.sql.Timestamp => Right(new DateTime(v))
      case _ => Left(TypeDoesNotMatch("Cannot convert " + value + ":" + value.asInstanceOf[AnyRef].getClass + " to TimeStamp for column " + qualified))
    }
  }

  // handles parsing integers
  implicit val integerColumn = anorm.Column[Integer] { (value, meta) =>
    val MetaDataItem(qualified, nullable, clazz) = meta
    value match {
      case v: java.lang.Integer => Right(new Integer(v))
      case _ => Left(TypeDoesNotMatch("Cannot convert " + value + ":" + value.asInstanceOf[AnyRef].getClass + " to Integer for column " + qualified))
    }
  }

  // handles parsing symbols
  implicit val symbolColumn = anorm.Column[Symbol] { (value, meta) =>
    val MetaDataItem(qualified, nullable, clazz) = meta
    value match {
      case v: String => Right(Symbol(v))
      case _ => Left(TypeDoesNotMatch("Cannot convert " + value + ":" + value.asInstanceOf[AnyRef].getClass + " to Symbol for column " + qualified))
    }
  }

  val masterDB = "default"
  val userModel = new UserModel(masterDB)
  val metaModel = new MetaModel(masterDB)
  val engineModel = new EngineModel(masterDB)
  val logModel = new LogModel(masterDB)
  val walletModel = new WalletModel(masterDB)

  // set up rpc authenticator for wallets
  val rpcAuth = DefaultAuthenticator.getInstance()

  // create wallet actors from config
  //TODO: separate wallet from frontend
  val currencies = List(
    "bitcoin" -> Wallet.CryptoCurrency.BTC,
    "litecoin" -> Wallet.CryptoCurrency.LTC,
    "peercoin" -> Wallet.CryptoCurrency.PPC,
    "primecoin" -> Wallet.CryptoCurrency.XPM)

  val enabledCurrencies = currencies.filter(c =>
    Play.current.configuration.getBoolean("wallet.%s.enabled".format(c._1)).getOrElse(false))

  val wallets = for {
    (currencyName, currency) <- enabledCurrencies
    nodeId <- Play.current.configuration.getIntList("wallet.%s.node.ids".format(currencyName)).get.asScala
    rpcUrlString <- Play.current.configuration.getString("wallet.%s.node.%s.rpc.url".format(currencyName, nodeId))
    rpcUser <- Play.current.configuration.getString("wallet.%s.node.%s.rpc.user".format(currencyName, nodeId))
    rpcPassword <- Play.current.configuration.getString("wallet.%s.node.%s.rpc.password".format(currencyName, nodeId))
    checkDelay <- Play.current.configuration.getInt("wallet.%s.node.%s.checkDelay".format(currencyName, nodeId))
    checkInterval <- Play.current.configuration.getInt("wallet.%s.node.%s.checkInterval".format(currencyName, nodeId))
    addressDelay <- Play.current.configuration.getInt("wallet.%s.node.%s.addressDelay".format(currencyName, nodeId))
    addressInterval <- Play.current.configuration.getInt("wallet.%s.node.%s.addressInterval".format(currencyName, nodeId))
    addressPool <- Play.current.configuration.getInt("wallet.%s.node.%s.addressPool".format(currencyName, nodeId))
    backupPathString <- Play.current.configuration.getString("wallet.%s.node.%s.backupPath".format(currencyName, nodeId))
    backupPath = backupPathString if backupPathString.startsWith("/") && backupPathString.endsWith("/")
    coldAddressString <- Play.current.configuration.getString("wallet.%s.node.%s.coldAddress".format(currencyName, nodeId))
    coldAddress = coldAddressString if CryptoAddress.isValid(coldAddressString, currency.toString, Play.current.configuration.getBoolean("fakeexchange").get)
  } yield {
    val rpcUrl = new URL(rpcUrlString)
    rpcAuth.register(rpcUrl, new PasswordAuthentication(rpcUser, rpcPassword.toCharArray))
    val params = Wallet.WalletParams(checkDelay.seconds, checkInterval.seconds, addressDelay.seconds, addressInterval.seconds, addressPool, backupPath, coldAddress)
    Akka.system.actorOf(Wallet.props(new JsonRpcHttpClient(rpcUrl), currency, nodeId, params, walletModel))
  }

}

object Global extends GlobalSettings {

  override def onError(request: RequestHeader, ex: Throwable): Future[Result] = {
    implicit val r = request
    request.contentType.map {
      case "application/json" =>
        Future.successful(InternalServerError(Json.toJson(Map("error" -> ("Internal Error: " + ex.getMessage)))))
      case _ =>
        Future.successful(InternalServerError(views.html.meta.error(ex)))
    }.getOrElse(Future.successful(InternalServerError(views.html.meta.error(ex))))
  }

  override def onHandlerNotFound(request: RequestHeader) = {
    implicit val r = request
    request.contentType.map {
      case "application/json" =>
        Future.successful(NotFound(Json.toJson(Map("error" -> ("Not found: " + request.path)))))
      case _ =>
        Future.successful(NotFound(views.html.meta.notFound(request.path)))
    }.getOrElse(Future.successful(NotFound(views.html.meta.notFound(request.path))))
  }

  override def onBadRequest(request: RequestHeader, error: String) = {
    implicit val r = request
    request.contentType.map {
      case "application/json" =>
        Future.successful(BadRequest(Json.toJson(Map("error" -> ("Bad Request: " + error)))))
      case _ =>
        Future.successful(BadRequest(views.html.meta.badRequest(error)))
    }.getOrElse(Future.successful(BadRequest(views.html.meta.badRequest(error))))
  }

  override def onStart(app: Application) {
    Logger.info("Application has started")
    txbitsUserService.onStart()
    controllers.StatsAPI.APIv1.onStart()
  }

  override def onStop(app: Application) {
    Logger.info("Application shutdown...")
    txbitsUserService.onStop()
    controllers.StatsAPI.APIv1.onStop()
  }

}