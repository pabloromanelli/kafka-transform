package com.socialmetrix.apis

import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import com.fasterxml.jackson.databind.JsonNode
import com.socialmetrix.json.Jackson
import com.socialmetrix.utils.FutureOps.Retry
import com.socialmetrix.ws.OkResponseUtil._
import com.typesafe.config.Config
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.{ExecutionContext, Future}

case class Rule(query: String, template: JsonNode)

@Singleton
class RulesService @Inject()(ws: StandaloneAhcWSClient)
                            (implicit config: Config,
                             executor: ExecutionContext,
                             actorSystem: ActorSystem) extends Retry {

  def getRules: Future[List[Rule]] = retry {
    /*
     * TODO can't log when actually executes requests using
     *  filters because it logs even when getting responses from cache
     */
    ws
      .url(config.getString("RulesService.url"))
      .getOnly2xx()
      .map(response => Jackson.objectMapper.readValue[List[Rule]](response.body))
  }

}