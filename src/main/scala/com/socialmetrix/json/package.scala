package com.socialmetrix

import com.fasterxml.jackson.databind.JsonNode
import com.socialmetrix.json.Jackson.objectMapper
import play.api.libs.ws.BodyReadable
import play.api.libs.ws.ahc.StandaloneAhcWSResponse
import play.shaded.ahc.org.asynchttpclient.{Response => AHCResponse}

package object json {

  implicit val jsonBodyReadable = BodyReadable[JsonNode] { response =>
    val ahcResponse = response.asInstanceOf[StandaloneAhcWSResponse].underlying[AHCResponse]
    objectMapper.readTree(ahcResponse.getResponseBodyAsStream)
  }

}