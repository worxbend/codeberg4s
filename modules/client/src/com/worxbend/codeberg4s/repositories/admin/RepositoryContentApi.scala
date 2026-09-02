package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.core.CodebergRequest.{read, removeWithBody, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.repositories.admin.wire.{AdminQueries, FileOptionsDto}
import com.worxbend.codeberg4s.repositories.gitdata.{FileChange, RefName}
import com.worxbend.codeberg4s.repositories.{ContentEntry, ContentPath}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** Reading a repository's files and committing changes to them through the API rather than through Git.
  *
  * Reached as `client.repos.admin.contents`. It is a group of its own rather than more methods on
  * [[RepositoryAdminApi]] because that class had grown past what a reader can hold in their head; the endpoints, the
  * models and the retry decisions are unchanged by the move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryContentApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Committing without a clone==
  *
  * Every write here produces a real commit on a branch, which is why each takes the message and the optional author
  * that commit carries. A caller that already has a working copy should clone and push instead: these endpoints exist
  * for the caller that has neither the repository on disk nor anywhere to put it.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository does not exist '''or''' is
  *     private to credentials the client does not have — Forgejo does not distinguish the two, on purpose — `401` when
  *     a token was required and none was sent, and `403` when the token lacks the scope or the account lacks the
  *     permission. `422` '''and''' `400` both mean the request was rejected as invalid; `docs/HAZARDS.md` §4 records
  *     Forgejo using `400` where a reader would expect `422`.
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
  * [[contents]] uses [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. No write here is retried.
  * [[createFile]] is a `POST`, so a repeat would add a second commit. [[updateFile]] and [[deleteFile]] name the blob
  * id they expect to replace, so after a lost success that blob no longer exists and the retry answers `409` — the
  * guard makes the retry harmless and useless at the same time, and reporting a `409` for a write that landed is not an
  * improvement. [[changeFiles]] is both at once.
  *
  * ==Evidence==
  *
  * '''Every model this group declares is derived from `spec/swagger.v1.json`, not from a captured response.''' The
  * harvest behind `modules/codec/test/resources/golden` was anonymous and every endpoint here requires a token, so no
  * fixture exists for any of them. Where a shape is asserted in a test, the payload was written by hand to match the
  * spec's definition — it is not evidence that Forgejo sends exactly this.
  */

final class RepositoryContentApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryContentApi.Attempt = RepositoryContentApi.Attempt(this)

  /** Lists the entries of the repository's root directory — `GET /repos/{owner}/{repo}/contents`.
    *
    * The root-only sibling of `RepositoryApi.contents`, which takes a path. '''Not paged''': the endpoint declares no
    * `page` or `limit` and answers the whole directory.
    *
    * '''A file listed here carries no content.''' `docs/HAZARDS.md` §3 measured it: a directory listing sends
    * `content: null` for its files, and only a request for a file's own path carries the bytes. See
    * [[com.worxbend.codeberg4s.repositories.ContentEntry.File]].
    *
    * '''Failures.''' The group contract above. `404` is also what a repository with no commits answers, since there is
    * no tree to list.
    *
    * @param ref
    *   the branch, tag or commit to read at; absent means the repository's default branch
    */
  def contents(owner: Owner, name: RepoName, ref: Option[RefName]): Future[Vector[ContentEntry]] =
    pipeline.call(RepositoryContentApi.contentsRequest(owner, name, ref), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.contents)

  /** Adds a file — `POST /repos/{owner}/{repo}/contents/{filepath}`.
    *
    * '''Never retried''', as every `POST` here is. A repeat after a lost success answers `422`, because the file now
    * exists — which would report a failure for a commit that landed.
    *
    * '''Answers `201`''' and the file as committed, together with the commit that wrote it.
    *
    * '''Failures.''' The group contract above. `422` covers a path that already exists; `404` a branch that does not;
    * `423` an archived repository; `409` a conflicting concurrent write.
    *
    * @param path
    *   where the file will live, relative to the repository root
    */
  def createFile(owner: Owner, name: RepoName, path: ContentPath, command: CreateFile): Future[FileChange] =
    pipeline.call(RepositoryContentApi.createFileRequest(owner, name, path, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.fileChange)

  /** Replaces a file — `PUT /repos/{owner}/{repo}/contents/{filepath}`.
    *
    * '''The command names the blob it expects to replace''', and Forgejo refuses the write with `409` when the file has
    * moved on. That is not optional and it is the reason this call cannot silently discard somebody else's commit; see
    * [[UpdateFile]].
    *
    * '''Never retried''', despite `PUT` being the method a retry is usually safe on. After a lost success the file's
    * blob id has already changed, so the retry cannot succeed — it answers `409`, reporting a conflict for a write that
    * in fact landed. The guard makes the repeat harmless and useless at once, which is the case the retry rules
    * exclude.
    *
    * '''Answers `200`''' and the file as committed.
    *
    * '''Failures.''' The group contract above. `409` is the concurrent-edit case above; `404` a path or branch that
    * does not exist; `423` an archived repository.
    */
  def updateFile(owner: Owner, name: RepoName, path: ContentPath, command: UpdateFile): Future[FileChange] =
    pipeline.call(RepositoryContentApi.updateFileRequest(owner, name, path, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.fileChange)

  /** Removes a file — `DELETE /repos/{owner}/{repo}/contents/{filepath}`.
    *
    * '''This `DELETE` carries a body''', which is unusual and is what the spec declares: `DeleteFileOptions` is
    * required, and it holds the same sha guard [[updateFile]] carries plus the commit settings.
    *
    * '''Never retried''', for the reason [[updateFile]] gives — the sha guard means a repeat after a lost success
    * answers `400`, reporting a failure for a commit that landed. Note that this is the one delete in the group that
    * '''creates''' something: a commit. That alone rules out
    * [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]].
    *
    * '''Answers `200`''' and the commit that removed the file. The response's `content` is always `null`, which
    * [[com.worxbend.codeberg4s.repositories.gitdata.FileChange.content]] reports as absent.
    *
    * '''Failures.''' The group contract above. `400` is what a stale sha produces here, where the update answers `409`.
    */
  def deleteFile(owner: Owner, name: RepoName, path: ContentPath, command: DeleteFile): Future[FileChange] =
    pipeline.call(RepositoryContentApi.deleteFileRequest(owner, name, path, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.fileChange)

  /** Writes several files in one commit — `POST /repos/{owner}/{repo}/contents`.
    *
    * '''The only atomic multi-file write.''' Four separate single-file calls produce four commits and four chances to
    * leave the repository half-changed; this produces one commit that either lands whole or does not land. See
    * [[ChangeFiles]].
    *
    * '''Never retried''', as every `POST` here is, and with an extra reason: the batch's update and delete operations
    * carry sha guards, so a repeat after a lost success fails on the first guard it reaches — reporting a conflict for
    * a commit that landed.
    *
    * '''Answers `201`''' and every entry the commit touched. A batch of deletes legitimately answers with an empty
    * [[FileChangeSet.files]].
    *
    * '''Failures.''' The group contract above. `409` is the concurrent-edit case; `422` a batch Forgejo rejected, for
    * instance a create for a path that exists.
    */
  def changeFiles(owner: Owner, name: RepoName, command: ChangeFiles): Future[FileChangeSet] =
    pipeline.call(RepositoryContentApi.changeFilesRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.fileChangeSet)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryContentApi:

  /** The stable operation id of [[RepositoryContentApi.contents]]. */
  val ListContentsOperation: String = "repos.admin.contents.list"

  /** The stable operation id of [[RepositoryContentApi.createFile]]. */
  val CreateFileOperation: String = "repos.admin.contents.create"

  /** The stable operation id of [[RepositoryContentApi.updateFile]]. */
  val UpdateFileOperation: String = "repos.admin.contents.update"

  /** The stable operation id of [[RepositoryContentApi.deleteFile]]. */
  val DeleteFileOperation: String = "repos.admin.contents.delete"

  /** The stable operation id of [[RepositoryContentApi.changeFiles]]. */
  val ChangeFilesOperation: String = "repos.admin.contents.change"

  /** The typed rail of [[RepositoryContentApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.admin.contents.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryContentApi)(using exec: Exec[Future]):

    /** [[RepositoryContentApi.contents]] with its failure as a value. */
    def contents(
        owner: Owner,
        name: RepoName,
        ref: Option[RefName],
    ): Future[Either[CodebergError, Vector[ContentEntry]]] =
      exec.attempt(rail.contents(owner, name, ref))

    /** [[RepositoryContentApi.createFile]] with its failure as a value. */
    def createFile(
        owner: Owner,
        name: RepoName,
        path: ContentPath,
        command: CreateFile,
    ): Future[Either[CodebergError, FileChange]] =
      exec.attempt(rail.createFile(owner, name, path, command))

    /** [[RepositoryContentApi.updateFile]] with its failure as a value. */
    def updateFile(
        owner: Owner,
        name: RepoName,
        path: ContentPath,
        command: UpdateFile,
    ): Future[Either[CodebergError, FileChange]] =
      exec.attempt(rail.updateFile(owner, name, path, command))

    /** [[RepositoryContentApi.deleteFile]] with its failure as a value. */
    def deleteFile(
        owner: Owner,
        name: RepoName,
        path: ContentPath,
        command: DeleteFile,
    ): Future[Either[CodebergError, FileChange]] =
      exec.attempt(rail.deleteFile(owner, name, path, command))

    /** [[RepositoryContentApi.changeFiles]] with its failure as a value. */
    def changeFiles(
        owner: Owner,
        name: RepoName,
        command: ChangeFiles,
    ): Future[Either[CodebergError, FileChangeSet]] =
      exec.attempt(rail.changeFiles(owner, name, command))

  private def contentsRequest(owner: Owner, name: RepoName, ref: Option[RefName]): CodebergRequest =
    read(ListContentsOperation, contentsPath(owner, name), AdminQueries.contents(ref))

  private def createFileRequest(
      owner: Owner,
      name: RepoName,
      path: ContentPath,
      command: CreateFile,
  ): CodebergRequest =
    write(
      CreateFileOperation,
      HttpMethod.Post,
      contentsPath(owner, name) ++ path.segments,
      FileOptionsDto.renderCreate(command),
    )

  private def updateFileRequest(
      owner: Owner,
      name: RepoName,
      path: ContentPath,
      command: UpdateFile,
  ): CodebergRequest =
    write(
      UpdateFileOperation,
      HttpMethod.Put,
      contentsPath(owner, name) ++ path.segments,
      FileOptionsDto.renderUpdate(command),
    )

  /** The one `DELETE` in the library that carries a body; the spec declares `DeleteFileOptions` required. */
  private def deleteFileRequest(
      owner: Owner,
      name: RepoName,
      path: ContentPath,
      command: DeleteFile,
  ): CodebergRequest =
    removeWithBody(
      DeleteFileOperation,
      contentsPath(owner, name) ++ path.segments,
      FileOptionsDto.renderDelete(command),
    )

  private def changeFilesRequest(owner: Owner, name: RepoName, command: ChangeFiles): CodebergRequest =
    write(ChangeFilesOperation, HttpMethod.Post, contentsPath(owner, name), FileOptionsDto.renderChange(command))

  private def contentsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "contents"
