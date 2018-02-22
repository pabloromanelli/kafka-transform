package com.socialmetrix.lucene

import javax.inject.Singleton

import com.fasterxml.jackson.databind.JsonNode

@Singleton
class Matcher {

  def matches(data: JsonNode, rule: String): Boolean = {
    // TODO
    true
  }

}
