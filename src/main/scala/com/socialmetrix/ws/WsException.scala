package com.socialmetrix.ws

import java.net.URI

import play.api.libs.ws.StandaloneWSResponse

case class WsException(uri: URI, response: StandaloneWSResponse) extends RuntimeException(
  s"${response.status} ${response.statusText} uri: '$uri', body: '${response.body}'")
