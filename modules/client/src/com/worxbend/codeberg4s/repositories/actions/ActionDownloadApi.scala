package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.core.{ApiPipeline, BinaryHttpPort, BinaryResponse, CodebergRequest, Exec}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** The two Actions endpoints whose success body is a ZIP archive rather than text.
  *
  * Reached as `client.downloads`. They are separate from [[RepositoryActionApi]] because they are the only operations
  * in the library that need a byte-carrying transport ([[com.worxbend.codeberg4s.core.BinaryHttpPort]]), and folding
  * that requirement into the class that serves the other twenty-six would have made every one of them depend on a
  * capability none of them use.
  *
  * '''Memory.''' Both operations hold the whole archive in memory as an `Array[Byte]`. A CI artifact can be large, and
  * this library does not stream. Check [[com.worxbend.codeberg4s.repositories.actions.ActionArtifact.sizeInBytes]]
  * before downloading if that matters, or fetch
  * [[com.worxbend.codeberg4s.repositories.actions.ActionArtifact.archiveDownloadUrl]] with your own HTTP client.
  *
  * The heap is not the only thing standing in the way: these two are the operations
  * [[com.worxbend.codeberg4s.CodebergConfig.maxDownloadBodyBytes]] bounds — 50 MiB by default, rather than the 16 MiB
  * every other operation gets, because an artifact is whatever a workflow uploaded and is legitimately far larger than
  * a JSON document. An archive past the bound fails as [[com.worxbend.codeberg4s.TransportCause.ResponseTooLarge]] and
  * is not retried, since a second attempt would download it again. Raise the setting if you need bigger archives and
  * have the memory for them.
  *
  * '''Failures.''' As everywhere else: [[com.worxbend.codeberg4s.CodebergError.Api]] for a non-2xx — `404` when the
  * artifact or run does not exist, has expired, or belongs to a repository the token cannot see, and `410` when Forgejo
  * has garbage-collected it — [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing arrived, and
  * [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy. No
  * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] is possible: nothing is decoded. An error body is still
  * JSON, and is parsed the usual way.
  *
  * Both are `GET`s and are retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
  */
final class ActionDownloadApi private[codeberg4s] (
    pipeline: ApiPipeline[Future],
    binary: BinaryHttpPort[Future],
)(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: ActionDownloadApi.Attempt = ActionDownloadApi.Attempt(this)

  /** Downloads an artifact's ZIP — `GET /repos/{owner}/{repo}/actions/artifacts/{id}/zip`.
    *
    * The returned [[com.worxbend.codeberg4s.core.BinaryResponse]] carries the bytes verbatim along with the response
    * headers, so a caller can read `content-disposition` for the server's own file name.
    */
  def artifact(owner: Owner, name: RepoName, artifact: ArtifactId): Future[BinaryResponse] =
    pipeline.callBinary(ActionDownloadApi.artifactRequest(owner, name, artifact), binary)

  /** Downloads a run's logs as a ZIP — `GET /repos/{owner}/{repo}/actions/runs/{id}/logs`.
    *
    * For one job's logs as plain text, use [[RepositoryActionApi.jobLogs]] instead; it needs no archive handling.
    */
  def runLogs(owner: Owner, name: RepoName, run: RunId): Future[BinaryResponse] =
    pipeline.callBinary(ActionDownloadApi.runLogsRequest(owner, name, run), binary)

/** The requests this group issues, and its typed rail. */
object ActionDownloadApi:

  /** The stable operation id [[ActionDownloadApi.artifact]] copies into every failure's `CallContext`. */
  val DownloadArtifactOperation: String = "repos.actions.artifacts.download"

  /** The stable operation id [[ActionDownloadApi.runLogs]] copies into every failure's `CallContext`. */
  val DownloadRunLogsOperation: String = "repos.actions.runs.logs.download"

  /** The typed rail: both operations with [[com.worxbend.codeberg4s.CodebergError]] as a value. */
  final class Attempt private[codeberg4s] (rail: ActionDownloadApi)(using exec: Exec[Future]):

    /** [[ActionDownloadApi.artifact]] with its failure as a value. */
    def artifact(
        owner: Owner,
        name: RepoName,
        artifact: ArtifactId,
    ): Future[Either[CodebergError, BinaryResponse]] =
      exec.attempt(rail.artifact(owner, name, artifact))

    /** [[ActionDownloadApi.runLogs]] with its failure as a value. */
    def runLogs(owner: Owner, name: RepoName, run: RunId): Future[Either[CodebergError, BinaryResponse]] =
      exec.attempt(rail.runLogs(owner, name, run))

  private def artifactRequest(owner: Owner, name: RepoName, artifact: ArtifactId): CodebergRequest =
    CodebergRequest(
      operation = DownloadArtifactOperation,
      method    = HttpMethod.Get,
      path      = List("repos", owner.value, name.value, "actions", "artifacts", artifact.value.toString, "zip"),
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  private def runLogsRequest(owner: Owner, name: RepoName, run: RunId): CodebergRequest =
    CodebergRequest(
      operation = DownloadRunLogsOperation,
      method    = HttpMethod.Get,
      path      = List("repos", owner.value, name.value, "actions", "runs", run.value.toString, "logs"),
      query     = Nil,
      headers   = Nil,
      body      = None,
    )
