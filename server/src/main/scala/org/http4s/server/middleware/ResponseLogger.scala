package org.http4s
package server
package middleware

import cats.data.Kleisli
import cats.effect._
import cats.implicits._
import fs2._
import org.http4s.util.CaseInsensitiveString
import org.log4s.getLogger
import scala.concurrent.ExecutionContext

/**
  * Simple middleware for logging responses as they are processed
  */
object ResponseLogger {
  private[this] val logger = getLogger

  def apply[F[_]](
      logHeaders: Boolean,
      logBody: Boolean,
      redactHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
  )(service: HttpService[F])(
      implicit F: Effect[F],
      ec: ExecutionContext = ExecutionContext.global): HttpService[F] =
    Kleisli { req =>
      service(req).semiflatMap { response =>
        if (!logBody)
          Logger.logMessage[F, Response[F]](response)(logHeaders, logBody, redactHeadersWhen)(
            logger) *> F.delay(response)
        else
          async.unboundedQueue[F, Byte].map { queue =>
            val newBody = Stream
              .eval(queue.size.get)
              .flatMap(size => queue.dequeue.take(size.toLong))

            response.copy(
              body = response.body
                .observe(queue.enqueue)
                .onFinalize {
                  Logger.logMessage[F, Response[F]](response.withBodyStream(newBody))(
                    logHeaders,
                    logBody,
                    redactHeadersWhen)(logger)
                }
            )
          }
      }
    }
}
