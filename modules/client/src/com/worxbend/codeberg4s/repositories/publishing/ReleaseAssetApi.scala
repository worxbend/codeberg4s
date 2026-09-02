package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RequestBody, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.publishing.PublishingRequests.releasePath
import com.worxbend.codeberg4s.repositories.publishing.wire.EditAttachmentOptionsDto
import com.worxbend.codeberg4s.repositories.{ReleaseAsset, ReleaseId}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** The files attached to a release: listing them, uploading one, renaming one, and deleting one.
  *
  * Reached as `client.repos.publishing.assets`. It is a group of its own rather than more methods on
  * [[RepositoryPublishingApi]] because that class had grown past what a reader can hold in their head, and because an
  * asset is the one thing on the publishing surface that is a '''file''' rather than a record: it is the only place in
  * the library that sends a multipart body. The endpoints, the models and the retry decisions are unchanged by the
  * move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[ReleaseAssetApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==An asset hangs off a release, and the release must exist first==
  *
  * Every path here is `/repos/{owner}/{repo}/releases/{id}/assets…`, so a [[com.worxbend.codeberg4s.repositories.ReleaseId]]
  * is needed before anything can be uploaded. [[RepositoryPublishingApi.createRelease]] is where that id comes from,
  * and a draft release is a legitimate place to put assets before announcing them.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository, the release or the asset
  *     does not exist '''or''' is invisible to the credentials in use, `401` when a token was required and none was
  *     sent, and `403` when the token lacks the scope. `422` '''and''' `400` both mean the request was rejected as
  *     invalid.
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
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. [[uploadAsset]] is never retried: a
  * repeat uploads the file a second time and Forgejo keeps both. The edit and the delete say what they do on their own
  * methods.
  */

final class ReleaseAssetApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: ReleaseAssetApi.Attempt = ReleaseAssetApi.Attempt(this)

  /** Lists a release's attachments — `GET /repos/{owner}/{repo}/releases/{id}/assets`.
    *
    * '''Paging on this endpoint is weaker than on a repository listing, and a caller has to know it.''' The pinned spec
    * declares no `page` or `limit` parameter for this operation. Both are still sent, because they cost nothing and
    * Forgejo honours them wherever it supports them — but two things follow:
    *
    *   - the returned page always reports itself as the last one, since `nextPage` is read from `rel="next"` and there
    *     is no `Link` header to read it from;
    *   - an instance that ignores the parameters answers every request with the '''complete''' attachment list, so
    *     asking for page two may return the same items as page one rather than nothing.
    *
    * A release carries tens of attachments, not thousands — `golden/repository/release-latest.json` has twenty-one — so
    * the whole list arriving at once is the expected case rather than a hazard.
    *
    * '''Failures.''' The group contract above.
    */
  def assets(owner: Owner, name: RepoName, id: ReleaseId, params: PageParams): Future[Page[ReleaseAsset]] =
    pipeline.callPage(ReleaseAssetApi.assetsRequest(owner, name, id, params), params)(using
      PublishingDecoders.assets)

  /** Uploads a file and attaches it to a release — `POST /repos/{owner}/{repo}/releases/{id}/assets`.
    *
    * '''The only `multipart/form-data` request this library sends.''' The bytes go in a part named `attachment`, which
    * is the name the spec declares, and [[UploadAsset.name]] — when set — goes in the `name` query parameter, which is
    * what Forgejo stores the attachment as. The boundary is chosen by the transport, never here.
    *
    * '''The bytes are held in memory, whole.''' A caller uploading a multi-gigabyte artefact should not use this
    * method; streaming an upload is a different shape of API that this library does not offer yet, and pretending
    * otherwise by accepting an `InputStream` it would immediately drain would be worse than saying so.
    *
    * '''Never retried.''' A repeat attaches the file twice: Forgejo does not reject a duplicate attachment name, so a
    * retried upload leaves two identical assets with different ids — and it re-sends every byte, which for a release
    * artefact is exactly the request a caller least wants repeated blindly.
    *
    * '''Failures.''' The group contract above. `413` means the repository's or the instance's quota is exhausted, and
    * `400` is what Forgejo answers for a part it could not read. Success is `201`.
    *
    * @param upload
    *   the file to attach; built from [[UploadAsset.of]], which has already rejected a file name that could inject a
    *   header
    */
  def uploadAsset(owner: Owner, name: RepoName, id: ReleaseId, upload: UploadAsset): Future[ReleaseAsset] =
    pipeline.call(ReleaseAssetApi.uploadAssetRequest(owner, name, id, upload), RetryEligibility.Never)(using
      PublishingDecoders.asset)

  /** Reads one attachment's metadata — `GET /repos/{owner}/{repo}/releases/{id}/assets/{attachment_id}`.
    *
    * '''Metadata, not bytes.''' The payload is the same `Attachment` object that appears inside a release's `assets`;
    * fetching the file itself means following [[com.worxbend.codeberg4s.repositories.ReleaseAsset.browserDownloadUrl]]
    * with an HTTP client of the caller's choosing, because an arbitrarily large body belongs in a stream and not in a
    * `String`.
    *
    * '''Failures.''' The group contract above; `404` additionally covers "that attachment belongs to another release".
    */
  def getAsset(owner: Owner, name: RepoName, id: ReleaseId, asset: AssetId): Future[ReleaseAsset] =
    pipeline.call(
      ReleaseAssetApi.getAssetRequest(owner, name, id, asset),
      RetryEligibility.IdempotentOnly,
    )(using PublishingDecoders.asset)

  /** Renames an attachment — `PATCH /repos/{owner}/{repo}/releases/{id}/assets/{attachment_id}`.
    *
    * '''This never replaces the bytes''', only the metadata; see [[EditAsset]].
    *
    * '''Never retried''', for the reason [[editRelease]] gives: a partial update is applied to whatever the resource
    * has become.
    *
    * '''Failures.''' The group contract above. Setting [[EditAsset.browserDownloadUrl]] on an ordinary uploaded
    * attachment is rejected, since the spec allows it only for an external one. Success is `201` here rather than `200`
    * — Forgejo's own choice, and success either way.
    */
  def editAsset(owner: Owner, name: RepoName, id: ReleaseId, asset: AssetId, command: EditAsset): Future[ReleaseAsset] =
    pipeline.call(
      ReleaseAssetApi.editAssetRequest(owner, name, id, asset, command),
      RetryEligibility.Never,
    )(using PublishingDecoders.asset)

  /** Removes an attachment from a release — `DELETE /repos/{owner}/{repo}/releases/{id}/assets/{attachment_id}`.
    *
    * '''Never retried''' — see the group's retry note.
    *
    * '''Failures.''' The group contract above. Success is `204` with no body.
    */
  def deleteAsset(owner: Owner, name: RepoName, id: ReleaseId, asset: AssetId): Future[Unit] =
    pipeline.callUnit(ReleaseAssetApi.deleteAssetRequest(owner, name, id, asset), RetryEligibility.Never)

  // --- tags -----------------------------------------------------------------

/** The requests this group issues, its operation ids, and its typed rail. */
object ReleaseAssetApi:

  /** The stable operation id of [[ReleaseAssetApi.assets]]. */
  val ListAssetsOperation: String = "repos.releases.assets.list"

  /** The stable operation id of [[ReleaseAssetApi.uploadAsset]]. */
  val UploadAssetOperation: String = "repos.releases.assets.upload"

  /** The stable operation id of [[ReleaseAssetApi.getAsset]]. */
  val GetAssetOperation: String = "repos.releases.assets.get"

  /** The stable operation id of [[ReleaseAssetApi.editAsset]]. */
  val EditAssetOperation: String = "repos.releases.assets.edit"

  /** The stable operation id of [[ReleaseAssetApi.deleteAsset]]. */
  val DeleteAssetOperation: String = "repos.releases.assets.delete"

  /** The form field name Forgejo expects an uploaded release asset in, per `spec/swagger.v1.json`. */
  val AssetFieldName: String = "attachment"

  /** The typed rail of [[ReleaseAssetApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.publishing.assets.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: ReleaseAssetApi)(using exec: Exec[Future]):

    /** [[ReleaseAssetApi.assets]] with its failure as a value. */
    def assets(
        owner: Owner,
        name: RepoName,
        id: ReleaseId,
        params: PageParams,
    ): Future[Either[CodebergError, Page[ReleaseAsset]]] =
      exec.attempt(rail.assets(owner, name, id, params))

    /** [[ReleaseAssetApi.uploadAsset]] with its failure as a value. */
    def uploadAsset(
        owner: Owner,
        name: RepoName,
        id: ReleaseId,
        upload: UploadAsset,
    ): Future[Either[CodebergError, ReleaseAsset]] =
      exec.attempt(rail.uploadAsset(owner, name, id, upload))

    /** [[ReleaseAssetApi.getAsset]] with its failure as a value. */
    def getAsset(
        owner: Owner,
        name: RepoName,
        id: ReleaseId,
        asset: AssetId,
    ): Future[Either[CodebergError, ReleaseAsset]] =
      exec.attempt(rail.getAsset(owner, name, id, asset))

    /** [[ReleaseAssetApi.editAsset]] with its failure as a value. */
    def editAsset(
        owner: Owner,
        name: RepoName,
        id: ReleaseId,
        asset: AssetId,
        command: EditAsset,
    ): Future[Either[CodebergError, ReleaseAsset]] =
      exec.attempt(rail.editAsset(owner, name, id, asset, command))

    /** [[ReleaseAssetApi.deleteAsset]] with its failure as a value. */
    def deleteAsset(
        owner: Owner,
        name: RepoName,
        id: ReleaseId,
        asset: AssetId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteAsset(owner, name, id, asset))

  private def assetsRequest(
      owner: Owner,
      name: RepoName,
      id: ReleaseId,
      params: PageParams,
  ): CodebergRequest =
    read(ListAssetsOperation, assetsPath(owner, name, id), PagingQuery.window(params))

  private def uploadAssetRequest(
      owner: Owner,
      name: RepoName,
      id: ReleaseId,
      upload: UploadAsset,
  ): CodebergRequest =
    CodebergRequest.upload(
      UploadAssetOperation,
      assetsPath(owner, name, id),
      upload.name.map(stored => "name" -> stored).toList,
      RequestBody.Multipart(AssetFieldName, upload.fileName, upload.content, upload.mediaType),
    )

  private def getAssetRequest(owner: Owner, name: RepoName, id: ReleaseId, asset: AssetId): CodebergRequest =
    read(GetAssetOperation, assetPath(owner, name, id, asset), Nil)

  private def editAssetRequest(
      owner: Owner,
      name: RepoName,
      id: ReleaseId,
      asset: AssetId,
      command: EditAsset,
  ): CodebergRequest =
    write(
      EditAssetOperation,
      HttpMethod.Patch,
      assetPath(owner, name, id, asset),
      EditAttachmentOptionsDto.render(command),
    )

  private def deleteAssetRequest(owner: Owner, name: RepoName, id: ReleaseId, asset: AssetId): CodebergRequest =
    remove(DeleteAssetOperation, assetPath(owner, name, id, asset))

  private def assetsPath(owner: Owner, name: RepoName, id: ReleaseId): List[String] =
    releasePath(owner, name, id) :+ "assets"

  private def assetPath(owner: Owner, name: RepoName, id: ReleaseId, asset: AssetId): List[String] =
    assetsPath(owner, name, id) :+ asset.value.toString
