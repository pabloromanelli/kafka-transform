package com.socialmetrix.ws

import akka.actor.ActorSystem
import play.api.libs.ws.{BodyWritable, StandaloneWSRequest, StandaloneWSResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.{higherKinds, postfixOps}

object OkResponseUtil {

  implicit class OnlyOk(val request: StandaloneWSRequest) extends AnyVal {
    private def only2xx: StandaloneWSResponse => StandaloneWSResponse = (response) =>
      if ((200 until 300).contains(response.status))
        response
      else
        throw WsException(request.uri, response)

    def getOnly2xx()(implicit executor: ExecutionContext, actorSystem: ActorSystem): Future[StandaloneWSResponse] =
      request.get().map(only2xx)

    def postOnly2xx[T: BodyWritable](body: T)(implicit executor: ExecutionContext, actorSystem: ActorSystem): Future[StandaloneWSResponse] =
      request.post(body).map(only2xx)
  }

}