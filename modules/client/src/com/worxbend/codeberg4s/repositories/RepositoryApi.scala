package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto

import scala.concurrent.Future

/** Repository endpoints.
  *
  * Reached as `client.repos`. Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryApi.attempt]] never fail and
  * return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * Arguments are [[Owner]] and [[RepoName]] rather than `String`, so a value that would forge a request path is
  * rejected by [[Owner.from]] or [[RepoName.from]] before a client is ever involved — which is why no operation here
  * produces [[com.worxbend.codeberg4s.CodebergError.Validation]].
  */
final class RepositoryApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryApi.Attempt = RepositoryApi.Attempt(this)

  /** Reads one repository — `GET /repos/{owner}/{repo}`.
    *
    * '''Failures.''' The returned `Future` fails with [[com.worxbend.codeberg4s.CodebergException]] carrying
    * [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository does not exist '''or''' is
    * private to credentials the client does not have — Forgejo does not distinguish the two, on purpose — `403` when
    * the token lacks the scope, and `401` when a token was required and none was sent.
    * [[com.worxbend.codeberg4s.CodebergError.Transport]] means nothing reached the instance,
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] means the payload carried no `id`, `name` or `owner`, and
    * [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] means a retryable failure outlived the policy. `GET` is
    * safe, so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    */
  def get(owner: Owner, name: RepoName): Future[Repository] =
    pipeline.call(RepositoryApi.getRequest(owner, name), RetryEligibility.IdempotentOnly)(using RepositoryApi.Decoder)

/** The requests this group issues, and its typed rail. */
object RepositoryApi:

  /** The stable operation id [[RepositoryApi.get]] copies into every failure's [[com.worxbend.codeberg4s.CallContext]].
    * Safe to alert on.
    */
  val GetOperation: String = "repos.get"

  /** The typed rail of [[RepositoryApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.repos.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryApi)(using exec: Exec[Future]):

    /** [[RepositoryApi.get]] with its failure as a value. The returned `Future` never fails with a
      * [[com.worxbend.codeberg4s.CodebergException]].
      */
    def get(owner: Owner, name: RepoName): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.get(owner, name))

  private def getRequest(owner: Owner, name: RepoName): CodebergRequest =
    CodebergRequest(
      operation = GetOperation,
      method    = HttpMethod.Get,
      path      = List("repos", owner.value, name.value),
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  private val Decoder: Decode[Repository] =
    WireDecode.of(Json.decoder[RepositoryDto])(_.toDomain)
