package com.socialmetrix.ws

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.{AbstractModule, Provides}
import com.typesafe.config.Config
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.ahc.cache.{AhcHttpCache, Cache, EffectiveURIKey, ResponseEntry}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CaffeineHttpCache @Inject()(config: Config)(implicit ec: ExecutionContext) extends Cache {
  val underlying = Caffeine.newBuilder()
    .expireAfterWrite(config.getDuration("play.ws.cache.expire").toMillis, TimeUnit.MILLISECONDS)
    .build[EffectiveURIKey, ResponseEntry]()

  override def remove(key: EffectiveURIKey) = Future(underlying.invalidate(key))

  override def put(key: EffectiveURIKey, entry: ResponseEntry) = Future(underlying.put(key, entry))

  override def get(key: EffectiveURIKey) = Future(Option(underlying.getIfPresent(key)))

  override def close(): Unit = underlying.cleanUp()
}

object WsModule extends AbstractModule {
  override def configure(): Unit = ()

  @Provides
  @Singleton
  def actorSystemProvider(): ActorSystem = ActorSystem()

  @Provides
  @Singleton
  def clientProvider(cache: CaffeineHttpCache)
                    (implicit actorSystem: ActorSystem, ec: ExecutionContext): StandaloneAhcWSClient = {
    implicit val materializer = ActorMaterializer()
    StandaloneAhcWSClient(httpCache = Some(new AhcHttpCache(cache)))
  }
}
