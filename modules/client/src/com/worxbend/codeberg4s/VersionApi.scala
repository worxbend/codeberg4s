package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.wire.ServerVersionDto

import scala.concurrent.Future

/** `GET /version` — what software the instance is running.
  *
  * Reached as `client.version`. Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[CodebergException]], and the same operations on [[VersionApi.attempt]] never fail and return an `Either` instead.
  * The typed rail is derived from this one by [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree
  * about what an operation does.
  */
final class VersionApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: VersionApi.Attempt = VersionApi.Attempt(this)

  /** Reads the instance's version string.
    *
    * The endpoint is unauthenticated on Codeberg, so this is the cheapest way to check that a
    * [[CodebergConfig.baseUri]] really points at a Forgejo API root.
    *
    * '''Failures.''' The returned `Future` fails with [[CodebergException]] carrying [[CodebergError.Transport]] when
    * nothing reached the instance, [[CodebergError.Api]] for a non-2xx status, [[CodebergError.DecodingFailed]] when
    * the payload carries no usable `version` field, and [[CodebergError.RetriesExhausted]] when a retryable failure
    * outlived the policy. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def get(): Future[ServerVersion] =
    pipeline.call(VersionApi.Request, RetryEligibility.IdempotentOnly)(using VersionApi.Decoder)

/** The request this group issues, and its typed rail. */
object VersionApi:

  /** The stable operation id copied into every failure's [[CallContext]]. Safe to alert on. */
  val Operation: String = "version.get"

  /** The typed rail of [[VersionApi]]: every operation, with [[CodebergError]] as a value.
    *
    * Obtained as `client.version.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: VersionApi)(using exec: Exec[Future]):

    /** [[VersionApi.get]] with its failure as a value. The returned `Future` never fails with a [[CodebergException]]. */
    def get(): Future[Either[CodebergError, ServerVersion]] = exec.attempt(rail.get())

  private val Request: CodebergRequest = CodebergRequest(
    operation = Operation,
    method    = HttpMethod.Get,
    path      = List("version"),
    query     = Nil,
    headers   = Nil,
    body      = None,
  )

  private val Decoder: Decode[ServerVersion] =
    WireDecode.single(Json.decoder[ServerVersionDto])(_.toDomain)
