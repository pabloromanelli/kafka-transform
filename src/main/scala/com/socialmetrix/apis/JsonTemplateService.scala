package com.socialmetrix.apis

import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.socialmetrix.json.Jackson.objectMapper
import com.socialmetrix.utils.FutureOps.Retry
import com.socialmetrix.ws.OkResponseUtil._
import com.typesafe.config.Config
import play.api.libs.ws.DefaultBodyReadables._
import play.api.libs.ws.DefaultBodyWritables
import play.api.libs.ws.ahc._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class JsonTemplateService @Inject()(ws: StandaloneAhcWSClient)
                                   (implicit config: Config, ec: ExecutionContext, actorSystem: ActorSystem)
  extends Retry with DefaultBodyWritables {

  def render(data: JsonNode, template: JsonNode): Future[Option[JsonNode]] = retry {
    ws
      .url(config.getString("JsonTemplateService.url"))
      .withHttpHeaders("Content-Type" -> "application/json")
      .postOnly2xx(objectMapper.writeValueAsString(
        objectMapper.createObjectNode()
          .set("data", data).asInstanceOf[ObjectNode]
          .set("template", template)
      ))
      .map(_.body[String])
      .map(body => Option(objectMapper.readTree(body)))
  }
}