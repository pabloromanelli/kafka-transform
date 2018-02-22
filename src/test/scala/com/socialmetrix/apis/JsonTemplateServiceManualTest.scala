package com.socialmetrix.apis

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.socialmetrix.json.Jackson.objectMapper
import com.typesafe.config.ConfigFactory
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._

object JsonTemplateServiceManualTest {

  def main(args: Array[String]): Unit = {
    // docker run -ti -p 8080:80 socialmetrix/json-template-service
    implicit val actorSystem = ActorSystem()
    implicit val materializer = ActorMaterializer()
    val ws = StandaloneAhcWSClient()
    val service = new JsonTemplateService(ws)(
      ConfigFactory.parseString(
        """
          JsonTemplateService.url="http://localhost:8080"
          retry.maxExecutions=1
          retry.baseWait=1
          retry.maxWait=1
        """),
      global,
      actorSystem
    )

    val result = Await.result(service.render(
      objectMapper.createObjectNode().put("value", 1234),
      objectMapper.createObjectNode().put("original", "{{this}}")
    ), 1.minute)

    println(objectMapper.writeValueAsString(result))
  }

}
