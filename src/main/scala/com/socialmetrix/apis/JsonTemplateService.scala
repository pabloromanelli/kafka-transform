package com.socialmetrix.apis

import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.socialmetrix.utils.FutureOps.Retry
import com.socialmetrix.ws.OkResponseUtil._
import com.typesafe.config.Config
import play.api.libs.ws.DefaultBodyReadables._
import play.api.libs.ws.DefaultBodyWritables
import play.api.libs.ws.ahc._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class JsonTemplateService @Inject()(ws: StandaloneAhcWSClient, mapper: ObjectMapper)
                                   (implicit config: Config, ec: ExecutionContext, actorSystem: ActorSystem)
  extends Retry with DefaultBodyWritables {

  def render(data: JsonNode, template: JsonNode): Future[JsonNode] = retry {
    ws
      .url(config.getString("JsonTemplateService.url"))
      .postOnly2xx(mapper.writeValueAsString(data))
      .map(_.body[String])
      .map(mapper.readTree)
  }
}