package com.socialmetrix.apis

import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import com.fasterxml.jackson.databind.JsonNode
import com.google.inject.AbstractModule
import com.socialmetrix.json.Jackson.objectMapper
import com.socialmetrix.utils.FutureOps.Retry
import com.socialmetrix.ws.OkResponseUtil._
import com.typesafe.config.Config
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.{ExecutionContext, Future}

case class Rule(query: String, template: JsonNode)

trait RulesService {
  def getRules: Future[List[Rule]]

  def close(): Unit
}

@Singleton
class HttpRulesService @Inject()(ws: StandaloneAhcWSClient)
                                (implicit config: Config,
                                 executor: ExecutionContext,
                                 actorSystem: ActorSystem) extends Retry with RulesService {

  def getRules: Future[List[Rule]] = retry {
    /*
     * TODO can't log when actually executes requests using
     *  filters because it logs even when getting responses from cache
     */
    ws
      .url(config.getString("rules.url"))
      .getOnly2xx()
      .map(response => objectMapper.readValue[List[Rule]](response.body))
  }

  override def close(): Unit = {
    ws.close()
    actorSystem.terminate()
  }
}

@Singleton
class LocalRulesService @Inject()(config: Config) extends RulesService {
  def getRules: Future[List[Rule]] = Future.successful(
    objectMapper.readValue[List[Rule]](config.getString("rules.local"))
  )

  override def close(): Unit = ()
}

class RulesModule(config: Config) extends AbstractModule {
  override def configure(): Unit = {
    config.getString("rules.type") match {
      case "remote" => bind(classOf[RulesService]).to(classOf[HttpRulesService])
      case "local" => bind(classOf[RulesService]).to(classOf[LocalRulesService])
      case unknown => throw new RuntimeException(s"Unknown rule type: $unknown. Rule type can be 'remote' or 'local'")
    }
  }
}