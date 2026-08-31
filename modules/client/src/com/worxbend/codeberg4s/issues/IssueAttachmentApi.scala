package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.issues.wire.EditAttachmentOptionDto
import com.worxbend.codeberg4s.issues.wire.IssueQueries

import scala.concurrent.Future

/** The files attached to an issue, and the files attached to a comment.
  *
  * Reached as `client.issues.attachments`. Ten operations, in two identical halves: Forgejo offers list, upload, read,
  * edit and delete under `…/issues/{index}/assets` and again under `…/issues/comments/{id}/assets`, with the same
  * request and response models on both. They are one class because the model is one model — separating them would
  * duplicate every decision here for no gain — and the method names say which half they are.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[IssueAttachmentApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Evidence==
  *
  * '''Every model here is derived from `spec/swagger.v1.json`, not from a captured response.''' The golden harvest was
  * anonymous, `assets` is `[]` on all thirteen captured issues and both captured comments, and uploading needs a token.
  * The field set, the `type` vocabulary and the nullability treatment are the spec read literally under the
  * conservative rule `docs/HAZARDS.md` §1 states for the whole API. Where a shape is asserted in a test, the payload
  * was written by hand to match that definition — it is not evidence that Forgejo sends exactly this.
  *
  * ==Nothing here downloads==
  *
  * Every method returns metadata. [[IssueAttachment.browserDownloadUrl]] is the supported route to the content: hand it
  * to an HTTP client that can stream bytes.
  *
  * That used to be forced by the library: a response body was a `String`, and an arbitrary file that has been through a
  * UTF-8 decoder is no longer that file. It is no longer forced — [[com.worxbend.codeberg4s.core.CodebergResponse]]
  * carries a [[com.worxbend.codeberg4s.core.ResponseBody]], which is bytes — so an attachment-download operation is now
  * something this group '''could''' offer. It does not yet, because adding one is a new endpoint with its own tests
  * rather than a rider on the change that made it possible, and because an attachment can be arbitrarily large and
  * nothing here streams.
  *
  * ==Not paged, and that is the endpoints' decision==
  *
  * Neither listing declares `page` or `limit` in the spec, so both return a `Vector` rather than a
  * [[com.worxbend.codeberg4s.paging.Page]]: a page reporting a window nobody chose would be a lie about what was
  * requested. An issue's attachment count is bounded by what somebody uploaded by hand, so this is not the unbounded
  * read that would make paging necessary.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository, the issue, the comment or
  *     the attachment does not exist '''or''' is private to credentials the client does not have — Forgejo does not
  *     distinguish the two, on purpose — `401` when a token was required and none was sent, and `403` when the token
  *     lacks the scope. `422` '''and''' `400` both mean the request was rejected as invalid, per `docs/HAZARDS.md` §4.
  *     Two statuses are specific to this group and worth naming: `413` when the file exceeds the instance's attachment
  *     size limit, and `423` when the issue is locked or the repository archived.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type — [[AttachmentId]], [[UploadAttachment]] — so a value that would forge a path or inject a
  * multipart header is rejected by its own smart constructor before a client is ever involved.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class IssueAttachmentApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: IssueAttachmentApi.Attempt = IssueAttachmentApi.Attempt(this)

  // --- an issue's attachments -----------------------------------------------

  /** Lists an issue's attachments — `GET /repos/{owner}/{repo}/issues/{index}/assets`.
    *
    * '''Not paged'''; see the class note.
    *
    * '''Failures.''' The group contract above.
    */
  def listForIssue(owner: Owner, name: RepoName, number: IssueNumber): Future[Vector[IssueAttachment]] =
    pipeline.call(IssueAttachmentApi.listForIssueRequest(owner, name, number), RetryEligibility.IdempotentOnly)(using
      IssueDecoders.attachments)

  /** Attaches a file to an issue — `POST /repos/{owner}/{repo}/issues/{index}/assets`.
    *
    * '''Never retried.''' A repeat uploads the file a second time and Forgejo has no idempotency key that would let the
    * instance recognise it, so the issue ends up with two identical attachments under two ids. A transport failure
    * therefore leaves the caller genuinely unsure whether the file arrived, which [[listForIssue]] resolves and which
    * is better than two copies of a large file.
    *
    * '''`multipart/form-data`, with the two optional settings in the query string''' — that is how
    * `spec/swagger.v1.json` declares the operation: one `formData` part named `attachment`, plus `name` and
    * `updated_at` as query parameters. [[UploadAttachment]] carries all three.
    *
    * '''Failures.''' The group contract above; `413` and `423` are the two worth handling here.
    */
  def uploadToIssue(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      upload: UploadAttachment,
  ): Future[IssueAttachment] =
    pipeline.call(IssueAttachmentApi.uploadToIssueRequest(owner, name, number, upload), RetryEligibility.Never)(using
      IssueDecoders.attachment)

  /** Reads one of an issue's attachments — `GET …/issues/{index}/assets/{attachment_id}`.
    *
    * '''Metadata only'''; see the class note on why nothing here downloads.
    *
    * '''Failures.''' The group contract above.
    */
  def getOnIssue(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      id: AttachmentId,
  ): Future[IssueAttachment] =
    pipeline.call(IssueAttachmentApi.getOnIssueRequest(owner, name, number, id), RetryEligibility.IdempotentOnly)(using
      IssueDecoders.attachment)

  /** Renames or repoints one of an issue's attachments — `PATCH …/issues/{index}/assets/{attachment_id}`.
    *
    * '''Never retried.''' It is tempting to call this idempotent — it names one attachment by an id the instance never
    * reuses and sets stated values — but the two are not the same thing. A repeat after a lost success overwrites
    * whatever the attachment has become in the meantime, including somebody else's rename, and Forgejo offers no
    * conditional-update header that would let the instance refuse a stale write.
    * [[com.worxbend.codeberg4s.issues.IssueApi]] makes the same call for its `PATCH`; the one write in this group that
    * genuinely may be repeated is a `DELETE`.
    *
    * '''Answers `201`''', not `200`, which is success all the same as far as
    * [[com.worxbend.codeberg4s.core.StatusMapping]] is concerned.
    *
    * '''Failures.''' The group contract above. A `423` here means the issue is locked.
    */
  def editOnIssue(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      id: AttachmentId,
      command: EditAttachment,
  ): Future[IssueAttachment] =
    pipeline.call(
      IssueAttachmentApi.editOnIssueRequest(owner, name, number, id, command),
      RetryEligibility.Never,
    )(using IssueDecoders.attachment)

  /** Deletes one of an issue's attachments — `DELETE …/issues/{index}/assets/{attachment_id}`.
    *
    * '''Retried''', because the request names exactly one object by an identifier the instance never reuses: an
    * attachment id is a database row id, so the state after N attempts is the state after one and nothing is created.
    * The cost is one a caller has to know — if the first attempt succeeded and its response was lost, the retry
    * addresses something that no longer exists and answers `404`. A `404` from a delete therefore means "it is gone",
    * not necessarily "it was never there".
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteOnIssue(owner: Owner, name: RepoName, number: IssueNumber, id: AttachmentId): Future[Unit] =
    pipeline.callUnit(
      IssueAttachmentApi.deleteOnIssueRequest(owner, name, number, id),
      RetryEligibility.AlwaysRetry,
    )

  // --- a comment's attachments ----------------------------------------------

  /** Lists a comment's attachments — `GET /repos/{owner}/{repo}/issues/comments/{id}/assets`.
    *
    * '''Not paged'''; see the class note. The path carries no issue number, because [[CommentId]] is instance-wide.
    *
    * '''Failures.''' The group contract above.
    */
  def listForComment(owner: Owner, name: RepoName, comment: CommentId): Future[Vector[IssueAttachment]] =
    pipeline.call(IssueAttachmentApi.listForCommentRequest(owner, name, comment), RetryEligibility.IdempotentOnly)(using
      IssueDecoders.attachments)

  /** Attaches a file to a comment — `POST /repos/{owner}/{repo}/issues/comments/{id}/assets`.
    *
    * '''Never retried''', for the reason [[uploadToIssue]] gives, and with the same multipart shape.
    *
    * '''Failures.''' The group contract above; `413` and `423` are the two worth handling here.
    */
  def uploadToComment(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      upload: UploadAttachment,
  ): Future[IssueAttachment] =
    pipeline.call(
      IssueAttachmentApi.uploadToCommentRequest(owner, name, comment, upload),
      RetryEligibility.Never,
    )(using IssueDecoders.attachment)

  /** Reads one of a comment's attachments — `GET …/issues/comments/{id}/assets/{attachment_id}`.
    *
    * '''Metadata only'''; see the class note.
    *
    * '''Failures.''' The group contract above.
    */
  def getOnComment(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      id: AttachmentId,
  ): Future[IssueAttachment] =
    pipeline.call(
      IssueAttachmentApi.getOnCommentRequest(owner, name, comment, id),
      RetryEligibility.IdempotentOnly,
    )(using IssueDecoders.attachment)

  /** Renames or repoints one of a comment's attachments — `PATCH …/issues/comments/{id}/assets/{attachment_id}`.
    *
    * '''Never retried''', for the reason [[editOnIssue]] gives. '''Answers `201`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def editOnComment(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      id: AttachmentId,
      command: EditAttachment,
  ): Future[IssueAttachment] =
    pipeline.call(
      IssueAttachmentApi.editOnCommentRequest(owner, name, comment, id, command),
      RetryEligibility.Never,
    )(using IssueDecoders.attachment)

  /** Deletes one of a comment's attachments — `DELETE …/issues/comments/{id}/assets/{attachment_id}`.
    *
    * '''Retried''', with the same reasoning and the same `404`-after-a-lost-success consequence [[deleteOnIssue]]
    * describes. '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def deleteOnComment(owner: Owner, name: RepoName, comment: CommentId, id: AttachmentId): Future[Unit] =
    pipeline.callUnit(
      IssueAttachmentApi.deleteOnCommentRequest(owner, name, comment, id),
      RetryEligibility.AlwaysRetry,
    )

/** The requests this group issues, its operation ids, and its typed rail. */
object IssueAttachmentApi:

  /** The form field name both upload operations declare for the file part. */
  private val AttachmentFieldName: String = "attachment"

  /** The stable operation id of [[IssueAttachmentApi.listForIssue]]. Safe to alert on. */
  val ListForIssueOperation: String = "issues.assets.list"

  /** The stable operation id of [[IssueAttachmentApi.uploadToIssue]]. */
  val UploadToIssueOperation: String = "issues.assets.create"

  /** The stable operation id of the single-attachment read on an issue. */
  val GetOnIssueOperation: String = "issues.assets.get"

  /** The stable operation id of [[IssueAttachmentApi.editOnIssue]]. */
  val EditOnIssueOperation: String = "issues.assets.edit"

  /** The stable operation id of [[IssueAttachmentApi.deleteOnIssue]]. */
  val DeleteOnIssueOperation: String = "issues.assets.delete"

  /** The stable operation id of [[IssueAttachmentApi.listForComment]]. */
  val ListForCommentOperation: String = "issues.comments.assets.list"

  /** The stable operation id of [[IssueAttachmentApi.uploadToComment]]. */
  val UploadToCommentOperation: String = "issues.comments.assets.create"

  /** The stable operation id of the single-attachment read on a comment. */
  val GetOnCommentOperation: String = "issues.comments.assets.get"

  /** The stable operation id of [[IssueAttachmentApi.editOnComment]]. */
  val EditOnCommentOperation: String = "issues.comments.assets.edit"

  /** The stable operation id of [[IssueAttachmentApi.deleteOnComment]]. */
  val DeleteOnCommentOperation: String = "issues.comments.assets.delete"

  /** The typed rail of [[IssueAttachmentApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.issues.attachments.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: IssueAttachmentApi)(using exec: Exec[Future]):

    /** [[IssueAttachmentApi.listForIssue]] with its failure as a value. */
    def listForIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
    ): Future[Either[CodebergError, Vector[IssueAttachment]]] =
      exec.attempt(rail.listForIssue(owner, name, number))

    /** [[IssueAttachmentApi.uploadToIssue]] with its failure as a value. */
    def uploadToIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        upload: UploadAttachment,
    ): Future[Either[CodebergError, IssueAttachment]] =
      exec.attempt(rail.uploadToIssue(owner, name, number, upload))

    /** The single-attachment read on an issue, with its failure as a value. */
    def getOnIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        id: AttachmentId,
    ): Future[Either[CodebergError, IssueAttachment]] =
      exec.attempt(rail.getOnIssue(owner, name, number, id))

    /** [[IssueAttachmentApi.editOnIssue]] with its failure as a value. */
    def editOnIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        id: AttachmentId,
        command: EditAttachment,
    ): Future[Either[CodebergError, IssueAttachment]] =
      exec.attempt(rail.editOnIssue(owner, name, number, id, command))

    /** [[IssueAttachmentApi.deleteOnIssue]] with its failure as a value. */
    def deleteOnIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        id: AttachmentId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteOnIssue(owner, name, number, id))

    /** [[IssueAttachmentApi.listForComment]] with its failure as a value. */
    def listForComment(
        owner: Owner,
        name: RepoName,
        comment: CommentId,
    ): Future[Either[CodebergError, Vector[IssueAttachment]]] =
      exec.attempt(rail.listForComment(owner, name, comment))

    /** [[IssueAttachmentApi.uploadToComment]] with its failure as a value. */
    def uploadToComment(
        owner: Owner,
        name: RepoName,
        comment: CommentId,
        upload: UploadAttachment,
    ): Future[Either[CodebergError, IssueAttachment]] =
      exec.attempt(rail.uploadToComment(owner, name, comment, upload))

    /** The single-attachment read on a comment, with its failure as a value. */
    def getOnComment(
        owner: Owner,
        name: RepoName,
        comment: CommentId,
        id: AttachmentId,
    ): Future[Either[CodebergError, IssueAttachment]] =
      exec.attempt(rail.getOnComment(owner, name, comment, id))

    /** [[IssueAttachmentApi.editOnComment]] with its failure as a value. */
    def editOnComment(
        owner: Owner,
        name: RepoName,
        comment: CommentId,
        id: AttachmentId,
        command: EditAttachment,
    ): Future[Either[CodebergError, IssueAttachment]] =
      exec.attempt(rail.editOnComment(owner, name, comment, id, command))

    /** [[IssueAttachmentApi.deleteOnComment]] with its failure as a value. */
    def deleteOnComment(
        owner: Owner,
        name: RepoName,
        comment: CommentId,
        id: AttachmentId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteOnComment(owner, name, comment, id))

  private def listForIssueRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    IssueRequests.read(ListForIssueOperation, issueAssetsPath(owner, name, number), Nil)

  private def uploadToIssueRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      upload: UploadAttachment,
  ): CodebergRequest =
    IssueRequests.upload(
      UploadToIssueOperation,
      issueAssetsPath(owner, name, number),
      IssueQueries.attachmentUpload(upload),
      multipart(upload),
    )

  private def getOnIssueRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      id: AttachmentId,
  ): CodebergRequest =
    IssueRequests.read(GetOnIssueOperation, issueAssetPath(owner, name, number, id), Nil)

  private def editOnIssueRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      id: AttachmentId,
      command: EditAttachment,
  ): CodebergRequest =
    IssueRequests.write(
      EditOnIssueOperation,
      HttpMethod.Patch,
      issueAssetPath(owner, name, number, id),
      EditAttachmentOptionDto.render(command),
    )

  private def deleteOnIssueRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      id: AttachmentId,
  ): CodebergRequest =
    IssueRequests.remove(DeleteOnIssueOperation, issueAssetPath(owner, name, number, id))

  private def listForCommentRequest(owner: Owner, name: RepoName, comment: CommentId): CodebergRequest =
    IssueRequests.read(ListForCommentOperation, commentAssetsPath(owner, name, comment), Nil)

  private def uploadToCommentRequest(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      upload: UploadAttachment,
  ): CodebergRequest =
    IssueRequests.upload(
      UploadToCommentOperation,
      commentAssetsPath(owner, name, comment),
      IssueQueries.attachmentUpload(upload),
      multipart(upload),
    )

  private def getOnCommentRequest(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      id: AttachmentId,
  ): CodebergRequest =
    IssueRequests.read(GetOnCommentOperation, commentAssetPath(owner, name, comment, id), Nil)

  private def editOnCommentRequest(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      id: AttachmentId,
      command: EditAttachment,
  ): CodebergRequest =
    IssueRequests.write(
      EditOnCommentOperation,
      HttpMethod.Patch,
      commentAssetPath(owner, name, comment, id),
      EditAttachmentOptionDto.render(command),
    )

  private def deleteOnCommentRequest(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      id: AttachmentId,
  ): CodebergRequest =
    IssueRequests.remove(DeleteOnCommentOperation, commentAssetPath(owner, name, comment, id))

  private def multipart(upload: UploadAttachment): RequestBody =
    RequestBody.Multipart(AttachmentFieldName, upload.fileName, upload.content, upload.mediaType)

  private def issueAssetsPath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    IssueRequests.issuePath(owner, name, number) :+ "assets"

  private def issueAssetPath(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      id: AttachmentId,
  ): List[String] =
    issueAssetsPath(owner, name, number) :+ id.value.toString

  private def commentAssetsPath(owner: Owner, name: RepoName, comment: CommentId): List[String] =
    IssueRequests.commentPath(owner, name, comment) :+ "assets"

  private def commentAssetPath(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      id: AttachmentId,
  ): List[String] =
    commentAssetsPath(owner, name, comment) :+ id.value.toString
