package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.core.CodebergRequest.read
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.repositories.ContentPath
import com.worxbend.codeberg4s.repositories.gitdata.wire.GitDataQueries
import com.worxbend.codeberg4s.{CodebergError, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** A repository's files as bytes: the raw file, the media file, the archive, and the editor config that describes how
  * they should be written.
  *
  * Reached as `client.repos.git.files`. It is a group of its own rather than more methods on [[RepositoryGitApi]]
  * because that class had grown past what a reader can hold in their head, and because these four are the ones that
  * answer with a '''document''' rather than with a modelled record. The endpoints and the retry decisions are unchanged
  * by the move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryFileApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==None of these paths starts at `/git`==
  *
  * `/raw`, `/media`, `/archive` and `/editorconfig` hang off the repository directly, which is why the operation ids
  * here read `repos.raw.get` and not `repos.git.raw.get`. Grouping them under `client.repos.git.files` is a statement
  * about what a caller is asking for — the bytes of something in the repository — not a claim about the path.
  *
  * ==The whole document arrives in memory==
  *
  * '''This library does not stream.''' Each of these reads holds the complete response before returning it, bounded by
  * [[com.worxbend.codeberg4s.CodebergConfig]]. For a large archive that is a fact to plan around rather than a setting
  * to change.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository, the ref or the object does
  *     not exist '''or''' is invisible to the credentials in use — Forgejo does not distinguish the two, on purpose —
  *     `401` when a token was required and none was sent, and `403` when the token lacks the scope. `422` '''and'''
  *     `400` both mean the request was rejected as invalid.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type, so a value that would forge a path or a query parameter is rejected by its own smart
  * constructor before a client is ever involved.
  *
  * ==Retries==
  *
  * Every operation here is a read and uses [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]: a repeated
  * read costs nothing but the round trip.
  */

final class RepositoryFileApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryFileApi.Attempt = RepositoryFileApi.Attempt(this)

  /** Reads the EditorConfig properties in force for a path — `GET /repos/{owner}/{repo}/editorconfig/{filepath}`.
    *
    * The instance resolves the repository's `.editorconfig` files itself and answers with the merged result, so nothing
    * here parses an EditorConfig file. The property names are not fixed, which is why the result is a map —
    * [[EditorConfigDefinitions]] says what that costs and what it buys.
    *
    * '''Failures.''' The group contract above; `404` also covers a path the repository does not have at the given ref.
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] is reachable only for a body that is not a JSON object at
    * all — the values inside one are rendered rather than required to be strings.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param path
    *   the repository-relative path of the file to resolve properties for; sent as several path segments
    * @param ref
    *   the branch, tag or commit to read at, absent for the repository's default branch
    */
  def getEditorConfig(
      owner: Owner,
      name: RepoName,
      path: ContentPath,
      ref: Option[RefName],
  ): Future[EditorConfigDefinitions] =
    pipeline.call(RepositoryFileApi.editorConfigRequest(owner, name, path, ref), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.editorConfig)

  /** Reads a file's raw bytes — `GET /repos/{owner}/{repo}/raw/{filepath}`.
    *
    * '''The result is the response body decoded as text, and that is a real limitation.''' The endpoint produces
    * `application/octet-stream`, and this method decodes it with the charset the response declared. For a text file
    * that is exactly what a caller wants. '''For a binary file it is lossy''' — bytes that are not valid in that
    * charset become replacement characters, and re-encoding the result does not give the file back. Use
    * [[com.worxbend.codeberg4s.repositories.RepositoryApi.getContents]] for a binary blob under the instance's inline
    * size limit, whose base64 payload does survive. The bytes now reach the decoder intact, so a lossless variant of
    * this method has become possible; see the group note above for why it is not part of this signature yet.
    *
    * Unlike the contents endpoint this returns the file itself with no envelope, and is therefore the cheap way to read
    * a large text file.
    *
    * '''Failures.''' The group contract above; `404` also covers a path that is a directory rather than a file.
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] is not reachable: nothing is parsed.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param path
    *   the repository-relative path of the file; sent as several path segments
    * @param ref
    *   the branch, tag or commit to read at, absent for the repository's default branch
    */
  def getRawFile(owner: Owner, name: RepoName, path: ContentPath, ref: Option[RefName]): Future[String] =
    pipeline.call(RepositoryFileApi.rawFileRequest(owner, name, path, ref), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.text)

  /** Reads a file, resolving Git-LFS pointers — `GET /repos/{owner}/{repo}/media/{filepath}`.
    *
    * The difference from [[getRawFile]] is one thing only: a path stored as an LFS pointer answers with the pointer
    * file there and with the '''object it points at''' here. For a path that is not LFS the two are the same response.
    *
    * '''The same text limitation as [[getRawFile]] applies, and applies harder''' — an LFS object is a large binary far
    * more often than not, which is the whole reason it was stored in LFS.
    *
    * '''Failures.''' As [[getRawFile]].
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param path
    *   the repository-relative path of the file; sent as several path segments
    * @param ref
    *   the branch, tag or commit to read at, absent for the repository's default branch
    */
  def getMediaFile(owner: Owner, name: RepoName, path: ContentPath, ref: Option[RefName]): Future[String] =
    pipeline.call(RepositoryFileApi.mediaFileRequest(owner, name, path, ref), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.text)

  /** Downloads a source archive of a ref — `GET /repos/{owner}/{repo}/archive/{archive}`.
    *
    * The ref and the format are '''one''' path parameter, `main.zip`, which is why [[ArchiveFormat]] exists and why a
    * misspelt suffix is a `404` rather than a content-type mismatch. A slashed ref is decomposed into segments and the
    * suffix goes on the last of them, so `release/2026` as a zip is `…/archive/release/2026.zip`.
    *
    * '''An archive is always binary, so the text limitation on [[getRawFile]] is not a caveat here but the whole
    * story.''' A zip or a gzipped tar decoded as text is not recoverable. This method builds and issues the request
    * correctly and returns what its own signature can express; it is not a way to obtain a usable archive file. Making
    * it one is now a change to this method's return type alone — the transport and core carry the bytes intact, and
    * `com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi.downloadArtifact` shows the shape such an
    * operation takes.
    *
    * '''Failures.''' The group contract above. [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] is not
    * reachable: nothing is parsed.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param ref
    *   the branch, tag or commit to archive
    * @param format
    *   which archive to generate
    */
  def getArchive(owner: Owner, name: RepoName, ref: RefName, format: ArchiveFormat): Future[String] =
    pipeline.call(RepositoryFileApi.archiveRequest(owner, name, ref, format), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.text)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryFileApi:

  /** The stable operation id of [[RepositoryFileApi.getEditorConfig]]. */
  val GetEditorConfigOperation: String = "repos.editorconfig.get"

  /** The stable operation id of [[RepositoryFileApi.getRawFile]]. */
  val GetRawFileOperation: String = "repos.raw.get"

  /** The stable operation id of [[RepositoryFileApi.getMediaFile]]. */
  val GetMediaFileOperation: String = "repos.media.get"

  /** The stable operation id of [[RepositoryFileApi.getArchive]]. */
  val GetArchiveOperation: String = "repos.archive.get"

  /** The typed rail of [[RepositoryFileApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.git.files.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryFileApi)(using exec: Exec[Future]):

    /** [[RepositoryFileApi.getEditorConfig]] with its failure as a value. */
    def getEditorConfig(
        owner: Owner,
        name: RepoName,
        path: ContentPath,
        ref: Option[RefName],
    ): Future[Either[CodebergError, EditorConfigDefinitions]] =
      exec.attempt(rail.getEditorConfig(owner, name, path, ref))

    /** [[RepositoryFileApi.getRawFile]] with its failure as a value. */
    def getRawFile(
        owner: Owner,
        name: RepoName,
        path: ContentPath,
        ref: Option[RefName],
    ): Future[Either[CodebergError, String]] =
      exec.attempt(rail.getRawFile(owner, name, path, ref))

    /** [[RepositoryFileApi.getMediaFile]] with its failure as a value. */
    def getMediaFile(
        owner: Owner,
        name: RepoName,
        path: ContentPath,
        ref: Option[RefName],
    ): Future[Either[CodebergError, String]] =
      exec.attempt(rail.getMediaFile(owner, name, path, ref))

    /** [[RepositoryFileApi.getArchive]] with its failure as a value. */
    def getArchive(
        owner: Owner,
        name: RepoName,
        ref: RefName,
        format: ArchiveFormat,
    ): Future[Either[CodebergError, String]] =
      exec.attempt(rail.getArchive(owner, name, ref, format))

  private def editorConfigRequest(
      owner: Owner,
      name: RepoName,
      path: ContentPath,
      ref: Option[RefName],
  ): CodebergRequest =
    read(
      GetEditorConfigOperation,
      RepositoryRequests.repositoryPath(owner, name) :+ "editorconfig" :++ path.segments,
      GitDataQueries.atRef(ref),
    )

  private def rawFileRequest(
      owner: Owner,
      name: RepoName,
      path: ContentPath,
      ref: Option[RefName],
  ): CodebergRequest =
    read(
      GetRawFileOperation,
      RepositoryRequests.repositoryPath(owner, name) :+ "raw" :++ path.segments,
      GitDataQueries.atRef(ref)
    )

  private def mediaFileRequest(
      owner: Owner,
      name: RepoName,
      path: ContentPath,
      ref: Option[RefName],
  ): CodebergRequest =
    read(
      GetMediaFileOperation,
      RepositoryRequests.repositoryPath(owner, name) :+ "media" :++ path.segments,
      GitDataQueries.atRef(ref)
    )

  private def archiveRequest(
      owner: Owner,
      name: RepoName,
      ref: RefName,
      format: ArchiveFormat,
  ): CodebergRequest =
    read(
      GetArchiveOperation,
      RepositoryRequests.repositoryPath(owner, name) :+ "archive" :++ archiveSegments(ref, format),
      Nil
    )

  /** The ref's segments with the format's suffix glued onto the last one, which is how Forgejo spells an archive name.
    *
    * Written with `lastOption` rather than `last` because a total function is cheaper to read than an argument about
    * why the list cannot be empty — even though [[RefName]] guarantees it is not.
    */
  private def archiveSegments(ref: RefName, format: ArchiveFormat): List[String] =
    val segments = ref.segments

    segments.dropRight(1) ++ segments.lastOption.map(last => s"$last.${format.suffix}")
