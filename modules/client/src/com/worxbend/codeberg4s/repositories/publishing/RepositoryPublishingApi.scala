package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RequestBody, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.publishing.wire.{
  CreateForkOptionDto,
  CreateReleaseOptionDto,
  CreateTagOptionDto,
  EditAttachmentOptionsDto,
  EditReleaseOptionDto,
  GenerateRepoOptionDto,
  RepoTopicOptionsDto
}
import com.worxbend.codeberg4s.repositories.{
  Release,
  ReleaseAsset,
  ReleaseId,
  Repository,
  RepositoryDecoders,
  Tag,
  TagName
}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** The publishing surface of a repository: releases and their attachments, tags, topics, forking and templating.
  *
  * Reached as `client.repos.publishing`. Both error rails are here (ADR-0005): the methods on this class fail the
  * `Future` with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on
  * [[RepositoryPublishingApi.attempt]] never fail and return an `Either` instead. The typed rail is derived from this
  * one by [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * This is the group that '''writes'''. Reading a repository's releases, tags and topics is
  * [[com.worxbend.codeberg4s.repositories.RepositoryApi]]; everything that creates, edits or removes one is here,
  * together with the three reads that only make sense next to those writes — the latest release, a release by tag, and
  * a single tag.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository does not exist '''or''' is
  *     private to credentials the client does not have — Forgejo does not distinguish the two, on purpose — `401` when
  *     a token was required and none was sent, and `403` when the token lacks the scope. `422` '''and''' `400` both
  *     mean the request was rejected as invalid: `docs/HAZARDS.md` §4 captured Forgejo using `400` for a malformed
  *     identifier and `422` for a malformed timestamp, so a caller checking only for `422` will miss half of them. Two
  *     more statuses are common on this group and rare elsewhere: `409` when the thing already exists — a fork under a
  *     taken name, a tag that is already there — and `413` when the instance's storage quota would be exceeded, which
  *     is how a release-asset upload fails once a project gets large.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type — [[com.worxbend.codeberg4s.Owner]], [[com.worxbend.codeberg4s.repositories.TagName]],
  * [[Topic]], [[AssetId]] — so a value that would forge a path is rejected by its own smart constructor before a client
  * is ever involved.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
  *
  * For the writes the line this group draws is: '''a write is repeated only when repeating it re-states a value rather
  * than removing a uniquely identified resource.''' The three topic operations are set assignment — "these exact
  * topics", "this set contains `t`", "this set does not contain `t`" — and running any of them twice asserts the same
  * thing twice, so they use [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]] and survive a `503` that a
  * caller would otherwise have to handle by re-issuing the identical request by hand.
  *
  * Everything else uses [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], including the deletes. A `DELETE` of a
  * release, a tag or an attachment looks idempotent, and against a server at rest it is; but Forgejo offers no
  * conditional delete, so a repeat that follows a first attempt which actually succeeded either destroys a resource
  * recreated in between or comes back as a `404` the caller cannot tell apart from "it was never there". Neither is a
  * decision this library makes on a caller's behalf. A caller who knows their delete is safe to repeat can re-issue it,
  * which is one line and is visible in their own code.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class RepositoryPublishingApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryPublishingApi.Attempt = RepositoryPublishingApi.Attempt(this)

  // --- releases -------------------------------------------------------------

  /** Publishes a release — `POST /repos/{owner}/{repo}/releases`.
    *
    * '''This can create a tag as a side effect.''' When [[CreateRelease.tagName]] does not yet exist, Forgejo creates
    * it from [[CreateRelease.target]]; when it does exist, the release is attached to it and the target is ignored.
    *
    * '''Never retried.''' A repeat either publishes a second release or, more often, comes back `409` because the tag
    * now has one — and in the first case the tag has already moved. Forgejo has no idempotency key that would let the
    * instance recognise the repeat.
    *
    * '''Failures.''' The group contract above. `409` means the tag already carries a release. `422` means Forgejo
    * rejected the payload — most often a `target_commitish` that names nothing. Success is `201`, not `200`; both are
    * success as far as [[com.worxbend.codeberg4s.core.StatusMapping]] is concerned.
    *
    * @param command
    *   what to publish; built from [[CreateRelease.of]]
    */
  def createRelease(owner: Owner, name: RepoName, command: CreateRelease): Future[Release] =
    pipeline.call(RepositoryPublishingApi.createReleaseRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryDecoders.release)

  /** Reads the newest published release — `GET /repos/{owner}/{repo}/releases/latest`.
    *
    * '''"Latest" is Forgejo's answer, not this library's.''' It excludes drafts and prereleases, so a repository whose
    * only releases are prereleases answers `404` here while `client.repos.releases` returns them. That is the
    * endpoint's own contract and is not worked around.
    *
    * '''Failures.''' The group contract above; `404` additionally covers "the repository exists and has no release that
    * qualifies".
    */
  def latestRelease(owner: Owner, name: RepoName): Future[Release] =
    pipeline.call(RepositoryPublishingApi.latestReleaseRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryDecoders.release)

  /** Reads the release attached to a tag — `GET /repos/{owner}/{repo}/releases/tags/{tag}`.
    *
    * A different operation from `client.repos.getRelease`, which addresses a release by
    * [[com.worxbend.codeberg4s.repositories.ReleaseId]]. Use this one when the tag is what is known.
    *
    * '''The tag reaches the wire as several path segments when it contains `/`''' — `v16.0/forgejo` appears in
    * `golden/repository/tags-list.json` — because Forgejo routes this path with a wildcard and a name percent-encoded
    * whole into one segment answers `404`. That is what [[com.worxbend.codeberg4s.repositories.TagName.segments]] is
    * for, and why a raw `String` is not accepted here.
    *
    * '''Failures.''' The group contract above; `404` additionally covers "no release on that tag", including the case
    * where the tag itself exists.
    */
  def releaseByTag(owner: Owner, name: RepoName, tag: TagName): Future[Release] =
    pipeline.call(RepositoryPublishingApi.releaseByTagRequest(owner, name, tag), RetryEligibility.IdempotentOnly)(using
      RepositoryDecoders.release)

  /** Edits a release — `PATCH /repos/{owner}/{repo}/releases/{id}`.
    *
    * '''Only what the command sets is sent'''; everything else keeps its current value. Publishing a draft is
    * [[EditRelease.draft]] with `false`, which is a key with a `false` value and not an omission — see [[EditRelease]]
    * for why those differ.
    *
    * '''Never retried''', even though `PATCH` on a specific resource looks idempotent. It is not, here: the body is a
    * partial update applied to whatever the release has become, so a repeat after a transport failure can overwrite a
    * change someone else made in between.
    *
    * '''Failures.''' The group contract above. Retagging a release onto a tag that does not exist is a `404` naming the
    * tag rather than the release.
    */
  def editRelease(owner: Owner, name: RepoName, id: ReleaseId, command: EditRelease): Future[Release] =
    pipeline.call(RepositoryPublishingApi.editReleaseRequest(owner, name, id, command), RetryEligibility.Never)(using
      RepositoryDecoders.release)

  /** Deletes a release by its identifier — `DELETE /repos/{owner}/{repo}/releases/{id}`.
    *
    * '''The tag survives.''' A release is an annotation on a tag; removing the annotation leaves the tag in place, and
    * removing the tag as well is [[deleteTag]].
    *
    * '''Never retried''' — see the group's retry note for why an apparently idempotent delete is not repeated here.
    *
    * '''Failures.''' The group contract above. Success is `204` with no body, which is why this returns `Unit` through
    * [[com.worxbend.codeberg4s.core.ApiPipeline.callUnit]] and never looks at the payload.
    */
  def deleteRelease(owner: Owner, name: RepoName, id: ReleaseId): Future[Unit] =
    pipeline.callUnit(RepositoryPublishingApi.deleteReleaseRequest(owner, name, id), RetryEligibility.Never)

  /** Deletes the release attached to a tag — `DELETE /repos/{owner}/{repo}/releases/tags/{tag}`.
    *
    * The same deletion as [[deleteRelease]], addressed by tag instead of by identifier; the tag survives it too. The
    * tag reaches the wire as several path segments when it contains `/`, as in [[releaseByTag]].
    *
    * '''Never retried''' — see the group's retry note.
    *
    * '''Failures.''' The group contract above. Success is `204` with no body.
    */
  def deleteReleaseByTag(owner: Owner, name: RepoName, tag: TagName): Future[Unit] =
    pipeline.callUnit(RepositoryPublishingApi.deleteReleaseByTagRequest(owner, name, tag), RetryEligibility.Never)

  // --- release assets -------------------------------------------------------

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
  def listAssets(owner: Owner, name: RepoName, id: ReleaseId, params: PageParams): Future[Page[ReleaseAsset]] =
    pipeline.callPage(RepositoryPublishingApi.listAssetsRequest(owner, name, id, params), params)(using
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
    pipeline.call(RepositoryPublishingApi.uploadAssetRequest(owner, name, id, upload), RetryEligibility.Never)(using
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
      RepositoryPublishingApi.getAssetRequest(owner, name, id, asset),
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
      RepositoryPublishingApi.editAssetRequest(owner, name, id, asset, command),
      RetryEligibility.Never,
    )(using PublishingDecoders.asset)

  /** Removes an attachment from a release — `DELETE /repos/{owner}/{repo}/releases/{id}/assets/{attachment_id}`.
    *
    * '''Never retried''' — see the group's retry note.
    *
    * '''Failures.''' The group contract above. Success is `204` with no body.
    */
  def deleteAsset(owner: Owner, name: RepoName, id: ReleaseId, asset: AssetId): Future[Unit] =
    pipeline.callUnit(RepositoryPublishingApi.deleteAssetRequest(owner, name, id, asset), RetryEligibility.Never)

  // --- tags -----------------------------------------------------------------

  /** Creates a tag — `POST /repos/{owner}/{repo}/tags`.
    *
    * '''Annotated or lightweight is decided by [[CreateTag.message]] alone'''; there is no separate flag.
    *
    * '''Never retried.''' A repeat comes back `409` because the tag now exists, which is a failure the caller has to
    * interpret rather than a harmless no-op — and a retried create is never something this library does.
    *
    * '''Failures.''' The group contract above. `409` means the tag already exists, `423` means the repository is
    * archived, `405` means the repository is empty or a mirror, and `422` means the target names nothing. Success is
    * `201`.
    */
  def createTag(owner: Owner, name: RepoName, command: CreateTag): Future[Tag] =
    pipeline.call(RepositoryPublishingApi.createTagRequest(owner, name, command), RetryEligibility.Never)(using
      PublishingDecoders.tag)

  /** Reads one tag — `GET /repos/{owner}/{repo}/tags/{tag}`.
    *
    * The same `Tag` model `client.repos.tags` returns, one at a time. The tag reaches the wire as several path segments
    * when it contains `/`, as in [[releaseByTag]].
    *
    * '''Failures.''' The group contract above.
    */
  def getTag(owner: Owner, name: RepoName, tag: TagName): Future[Tag] =
    pipeline.call(RepositoryPublishingApi.getTagRequest(owner, name, tag), RetryEligibility.IdempotentOnly)(using
      PublishingDecoders.tag)

  /** Deletes a tag — `DELETE /repos/{owner}/{repo}/tags/{tag}`.
    *
    * '''A release attached to the tag is not deleted with it''', and is left pointing at a tag that no longer exists.
    * Deleting the release too is [[deleteRelease]] or [[deleteReleaseByTag]], and the order is the caller's choice.
    *
    * '''Never retried''' — see the group's retry note. The risk is concrete here: a tag is a name that CI recreates, so
    * a repeat could delete a tag that was pushed again between the two attempts.
    *
    * '''Failures.''' The group contract above, plus `409`, `405` and `423` as on [[createTag]]. Success is `204` with
    * no body.
    */
  def deleteTag(owner: Owner, name: RepoName, tag: TagName): Future[Unit] =
    pipeline.callUnit(RepositoryPublishingApi.deleteTagRequest(owner, name, tag), RetryEligibility.Never)

  // --- topics ---------------------------------------------------------------

  /** Replaces a repository's whole topic set — `PUT /repos/{owner}/{repo}/topics`.
    *
    * '''This is a replacement, not an addition.''' Topics not in `topics` are removed, and an empty vector removes
    * every topic — the body is `{"topics": []}`, which is a statement rather than an omission. Adding one topic without
    * knowing the rest is [[addTopic]].
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]]: the request states the exact
    * set the repository should have, so running it twice asserts the same thing twice and the second run cannot observe
    * a different outcome.
    *
    * '''Failures.''' The group contract above. `422` is what an instance answers for a topic name it will not accept —
    * see [[Topic]] for why this library does not try to predict that rule. Success is `204` with no body.
    */
  def replaceTopics(owner: Owner, name: RepoName, topics: Vector[Topic]): Future[Unit] =
    pipeline.callUnit(RepositoryPublishingApi.replaceTopicsRequest(owner, name, topics), RetryEligibility.AlwaysRetry)

  /** Adds one topic — `PUT /repos/{owner}/{repo}/topics/{topic}`.
    *
    * Leaves the repository's other topics alone, which is what makes this different from [[replaceTopics]].
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]]: the request asserts that the
    * topic set contains `topic`, and asserting it twice changes nothing.
    *
    * '''Failures.''' The group contract above, plus `422` when the instance's per-repository topic limit is reached.
    * Success is `204` with no body. The request carries a deliberately empty body — see
    * [[com.worxbend.codeberg4s.core.RequestBody.Empty]] — because a `PUT` with no body at all is a request some proxies
    * and some Forgejo builds handle differently.
    */
  def addTopic(owner: Owner, name: RepoName, topic: Topic): Future[Unit] =
    pipeline.callUnit(RepositoryPublishingApi.addTopicRequest(owner, name, topic), RetryEligibility.AlwaysRetry)

  /** Removes one topic — `DELETE /repos/{owner}/{repo}/topics/{topic}`.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], and it is the one delete in
    * this group that is: the request asserts that the topic set does '''not''' contain `topic`, which is a value being
    * stated and not a uniquely identified resource being destroyed. A repeat cannot remove anything a first attempt did
    * not, because a topic is a name in a set and not a row someone else can recreate under the same identity with
    * different content.
    *
    * '''Failures.''' The group contract above. Success is `204` with no body.
    */
  def removeTopic(owner: Owner, name: RepoName, topic: Topic): Future[Unit] =
    pipeline.callUnit(RepositoryPublishingApi.removeTopicRequest(owner, name, topic), RetryEligibility.AlwaysRetry)

  // --- forking and templating -----------------------------------------------

  /** Forks a repository — `POST /repos/{owner}/{repo}/forks`.
    *
    * `owner` and `name` are the '''upstream''' being forked; where the fork lands is [[CreateFork]].
    *
    * '''The answer is `202 Accepted`, not `201`.''' The repository comes back immediately and Forgejo copies the Git
    * data afterwards, so a fork that is returned here may still be empty for a while. Polling `client.repos.get` on the
    * returned slug is how a caller waits for it; this library does not wrap that in a loop, because how long to wait is
    * a decision it cannot make.
    *
    * '''Never retried.''' A repeat either creates a second fork under a generated name or comes back `409`.
    *
    * '''Failures.''' The group contract above. `409` means a repository of that name already exists under the target
    * owner, `403` means the account may not fork this repository at all, and `413` means the target owner is out of
    * quota.
    */
  def fork(owner: Owner, name: RepoName, command: CreateFork): Future[Repository] =
    pipeline.call(RepositoryPublishingApi.forkRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryDecoders.repository)

  /** Creates a repository from a template — `POST /repos/{template_owner}/{template_repo}/generate`.
    *
    * `templateOwner` and `templateName` name the '''template'''; everything about the repository being created is in
    * [[GenerateRepository]], including who will own it.
    *
    * '''A generate that does not ask for git content produces an empty repository''' — see
    * [[GenerateRepository.withGitContent]]. That is Forgejo's default, not this library's, and it is not quietly
    * overridden here.
    *
    * '''Never retried.''' A repeat creates a second repository or comes back `409`.
    *
    * '''Failures.''' The group contract above. `404` also means "that repository is not marked as a template", which is
    * indistinguishable from "no such repository" in the response. `409` means the target name is taken, and `403` means
    * the account may not create a repository under [[GenerateRepository.owner]]. Success is `201`.
    */
  def generate(templateOwner: Owner, templateName: RepoName, command: GenerateRepository): Future[Repository] =
    pipeline.call(
      RepositoryPublishingApi.generateRequest(templateOwner, templateName, command),
      RetryEligibility.Never,
    )(using RepositoryDecoders.repository)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryPublishingApi:

  /** The stable operation id of [[RepositoryPublishingApi.createRelease]]. Safe to alert on. */
  val CreateReleaseOperation: String = "repos.releases.create"

  /** The stable operation id of [[RepositoryPublishingApi.latestRelease]]. */
  val LatestReleaseOperation: String = "repos.releases.latest"

  /** The stable operation id of [[RepositoryPublishingApi.releaseByTag]]. */
  val ReleaseByTagOperation: String = "repos.releases.getByTag"

  /** The stable operation id of [[RepositoryPublishingApi.editRelease]]. */
  val EditReleaseOperation: String = "repos.releases.edit"

  /** The stable operation id of [[RepositoryPublishingApi.deleteRelease]]. */
  val DeleteReleaseOperation: String = "repos.releases.delete"

  /** The stable operation id of [[RepositoryPublishingApi.deleteReleaseByTag]]. */
  val DeleteReleaseByTagOperation: String = "repos.releases.deleteByTag"

  /** The stable operation id of [[RepositoryPublishingApi.listAssets]]. */
  val ListAssetsOperation: String = "repos.releases.assets.list"

  /** The stable operation id of [[RepositoryPublishingApi.uploadAsset]]. */
  val UploadAssetOperation: String = "repos.releases.assets.upload"

  /** The stable operation id of [[RepositoryPublishingApi.getAsset]]. */
  val GetAssetOperation: String = "repos.releases.assets.get"

  /** The stable operation id of [[RepositoryPublishingApi.editAsset]]. */
  val EditAssetOperation: String = "repos.releases.assets.edit"

  /** The stable operation id of [[RepositoryPublishingApi.deleteAsset]]. */
  val DeleteAssetOperation: String = "repos.releases.assets.delete"

  /** The stable operation id of [[RepositoryPublishingApi.createTag]]. */
  val CreateTagOperation: String = "repos.tags.create"

  /** The stable operation id of [[RepositoryPublishingApi.getTag]]. */
  val GetTagOperation: String = "repos.tags.get"

  /** The stable operation id of [[RepositoryPublishingApi.deleteTag]]. */
  val DeleteTagOperation: String = "repos.tags.delete"

  /** The stable operation id of [[RepositoryPublishingApi.replaceTopics]]. */
  val ReplaceTopicsOperation: String = "repos.topics.replace"

  /** The stable operation id of [[RepositoryPublishingApi.addTopic]]. */
  val AddTopicOperation: String = "repos.topics.add"

  /** The stable operation id of [[RepositoryPublishingApi.removeTopic]]. */
  val RemoveTopicOperation: String = "repos.topics.remove"

  /** The stable operation id of [[RepositoryPublishingApi.fork]]. */
  val ForkOperation: String = "repos.forks.create"

  /** The stable operation id of [[RepositoryPublishingApi.generate]]. */
  val GenerateOperation: String = "repos.generate"

  /** The form field name Forgejo expects an uploaded release asset in, per `spec/swagger.v1.json`. */
  val AssetFieldName: String = "attachment"

  /** The typed rail of [[RepositoryPublishingApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as
    * a value.
    *
    * Obtained as `client.repos.publishing.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryPublishingApi)(using exec: Exec[Future]):

    /** [[RepositoryPublishingApi.createRelease]] with its failure as a value. */
    def createRelease(owner: Owner, name: RepoName, command: CreateRelease): Future[Either[CodebergError, Release]] =
      exec.attempt(rail.createRelease(owner, name, command))

    /** [[RepositoryPublishingApi.latestRelease]] with its failure as a value. */
    def latestRelease(owner: Owner, name: RepoName): Future[Either[CodebergError, Release]] =
      exec.attempt(rail.latestRelease(owner, name))

    /** [[RepositoryPublishingApi.releaseByTag]] with its failure as a value. */
    def releaseByTag(owner: Owner, name: RepoName, tag: TagName): Future[Either[CodebergError, Release]] =
      exec.attempt(rail.releaseByTag(owner, name, tag))

    /** [[RepositoryPublishingApi.editRelease]] with its failure as a value. */
    def editRelease(
        owner: Owner,
        name: RepoName,
        id: ReleaseId,
        command: EditRelease,
    ): Future[Either[CodebergError, Release]] =
      exec.attempt(rail.editRelease(owner, name, id, command))

    /** [[RepositoryPublishingApi.deleteRelease]] with its failure as a value. */
    def deleteRelease(owner: Owner, name: RepoName, id: ReleaseId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteRelease(owner, name, id))

    /** [[RepositoryPublishingApi.deleteReleaseByTag]] with its failure as a value. */
    def deleteReleaseByTag(owner: Owner, name: RepoName, tag: TagName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteReleaseByTag(owner, name, tag))

    /** [[RepositoryPublishingApi.listAssets]] with its failure as a value. */
    def listAssets(
        owner: Owner,
        name: RepoName,
        id: ReleaseId,
        params: PageParams,
    ): Future[Either[CodebergError, Page[ReleaseAsset]]] =
      exec.attempt(rail.listAssets(owner, name, id, params))

    /** [[RepositoryPublishingApi.uploadAsset]] with its failure as a value. */
    def uploadAsset(
        owner: Owner,
        name: RepoName,
        id: ReleaseId,
        upload: UploadAsset,
    ): Future[Either[CodebergError, ReleaseAsset]] =
      exec.attempt(rail.uploadAsset(owner, name, id, upload))

    /** [[RepositoryPublishingApi.getAsset]] with its failure as a value. */
    def getAsset(
        owner: Owner,
        name: RepoName,
        id: ReleaseId,
        asset: AssetId,
    ): Future[Either[CodebergError, ReleaseAsset]] =
      exec.attempt(rail.getAsset(owner, name, id, asset))

    /** [[RepositoryPublishingApi.editAsset]] with its failure as a value. */
    def editAsset(
        owner: Owner,
        name: RepoName,
        id: ReleaseId,
        asset: AssetId,
        command: EditAsset,
    ): Future[Either[CodebergError, ReleaseAsset]] =
      exec.attempt(rail.editAsset(owner, name, id, asset, command))

    /** [[RepositoryPublishingApi.deleteAsset]] with its failure as a value. */
    def deleteAsset(
        owner: Owner,
        name: RepoName,
        id: ReleaseId,
        asset: AssetId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteAsset(owner, name, id, asset))

    /** [[RepositoryPublishingApi.createTag]] with its failure as a value. */
    def createTag(owner: Owner, name: RepoName, command: CreateTag): Future[Either[CodebergError, Tag]] =
      exec.attempt(rail.createTag(owner, name, command))

    /** [[RepositoryPublishingApi.getTag]] with its failure as a value. */
    def getTag(owner: Owner, name: RepoName, tag: TagName): Future[Either[CodebergError, Tag]] =
      exec.attempt(rail.getTag(owner, name, tag))

    /** [[RepositoryPublishingApi.deleteTag]] with its failure as a value. */
    def deleteTag(owner: Owner, name: RepoName, tag: TagName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteTag(owner, name, tag))

    /** [[RepositoryPublishingApi.replaceTopics]] with its failure as a value. */
    def replaceTopics(owner: Owner, name: RepoName, topics: Vector[Topic]): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.replaceTopics(owner, name, topics))

    /** [[RepositoryPublishingApi.addTopic]] with its failure as a value. */
    def addTopic(owner: Owner, name: RepoName, topic: Topic): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.addTopic(owner, name, topic))

    /** [[RepositoryPublishingApi.removeTopic]] with its failure as a value. */
    def removeTopic(owner: Owner, name: RepoName, topic: Topic): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.removeTopic(owner, name, topic))

    /** [[RepositoryPublishingApi.fork]] with its failure as a value. */
    def fork(owner: Owner, name: RepoName, command: CreateFork): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.fork(owner, name, command))

    /** [[RepositoryPublishingApi.generate]] with its failure as a value. */
    def generate(
        templateOwner: Owner,
        templateName: RepoName,
        command: GenerateRepository,
    ): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.generate(templateOwner, templateName, command))

  // --- request construction -------------------------------------------------

  private def createReleaseRequest(owner: Owner, name: RepoName, command: CreateRelease): CodebergRequest =
    write(CreateReleaseOperation, HttpMethod.Post, releasesPath(owner, name), CreateReleaseOptionDto.render(command))

  private def latestReleaseRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(LatestReleaseOperation, releasesPath(owner, name) :+ "latest", Nil)

  private def releaseByTagRequest(owner: Owner, name: RepoName, tag: TagName): CodebergRequest =
    read(ReleaseByTagOperation, releaseByTagPath(owner, name, tag), Nil)

  private def editReleaseRequest(
      owner: Owner,
      name: RepoName,
      id: ReleaseId,
      command: EditRelease,
  ): CodebergRequest =
    write(EditReleaseOperation, HttpMethod.Patch, releasePath(owner, name, id), EditReleaseOptionDto.render(command))

  private def deleteReleaseRequest(owner: Owner, name: RepoName, id: ReleaseId): CodebergRequest =
    remove(DeleteReleaseOperation, releasePath(owner, name, id))

  private def deleteReleaseByTagRequest(owner: Owner, name: RepoName, tag: TagName): CodebergRequest =
    remove(DeleteReleaseByTagOperation, releaseByTagPath(owner, name, tag))

  private def listAssetsRequest(
      owner: Owner,
      name: RepoName,
      id: ReleaseId,
      params: PageParams,
  ): CodebergRequest =
    read(ListAssetsOperation, assetsPath(owner, name, id), window(params))

  private def uploadAssetRequest(
      owner: Owner,
      name: RepoName,
      id: ReleaseId,
      upload: UploadAsset,
  ): CodebergRequest =
    CodebergRequest(
      operation = UploadAssetOperation,
      method    = HttpMethod.Post,
      path      = assetsPath(owner, name, id),
      query     = upload.name.map(stored => "name" -> stored).toList,
      headers   = Nil,
      body      = Some(RequestBody.Multipart(AssetFieldName, upload.fileName, upload.content, upload.mediaType)),
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

  private def createTagRequest(owner: Owner, name: RepoName, command: CreateTag): CodebergRequest =
    write(CreateTagOperation, HttpMethod.Post, tagsPath(owner, name), CreateTagOptionDto.render(command))

  private def getTagRequest(owner: Owner, name: RepoName, tag: TagName): CodebergRequest =
    read(GetTagOperation, tagsPath(owner, name) ++ tag.segments, Nil)

  private def deleteTagRequest(owner: Owner, name: RepoName, tag: TagName): CodebergRequest =
    remove(DeleteTagOperation, tagsPath(owner, name) ++ tag.segments)

  private def replaceTopicsRequest(owner: Owner, name: RepoName, topics: Vector[Topic]): CodebergRequest =
    write(ReplaceTopicsOperation, HttpMethod.Put, topicsPath(owner, name), RepoTopicOptionsDto.render(topics))

  private def addTopicRequest(owner: Owner, name: RepoName, topic: Topic): CodebergRequest =
    CodebergRequest(
      operation = AddTopicOperation,
      method    = HttpMethod.Put,
      path      = topicsPath(owner, name) :+ topic.value,
      query     = Nil,
      headers   = Nil,
      body      = Some(RequestBody.Empty),
    )

  private def removeTopicRequest(owner: Owner, name: RepoName, topic: Topic): CodebergRequest =
    remove(RemoveTopicOperation, topicsPath(owner, name) :+ topic.value)

  private def forkRequest(owner: Owner, name: RepoName, command: CreateFork): CodebergRequest =
    write(
      ForkOperation,
      HttpMethod.Post,
      List("repos", owner.value, name.value, "forks"),
      CreateForkOptionDto.render(command),
    )

  private def generateRequest(
      templateOwner: Owner,
      templateName: RepoName,
      command: GenerateRepository,
  ): CodebergRequest =
    write(
      GenerateOperation,
      HttpMethod.Post,
      List("repos", templateOwner.value, templateName.value, "generate"),
      GenerateRepoOptionDto.render(command),
    )

  /** A `DELETE` with no body at all, which is what every deletion in this group is. */
  /** The `page` and `limit` window, rendered by [[com.worxbend.codeberg4s.codec.PagingQuery.window]]. */
  private def window(params: PageParams): List[(String, String)] =
    PagingQuery.window(params)

  private def releasesPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "releases")

  private def releasePath(owner: Owner, name: RepoName, id: ReleaseId): List[String] =
    releasesPath(owner, name) :+ id.value.toString

  /** `…/releases/tags/{tag}`, with the tag decomposed so a `/` in it reaches the wire as a real separator. */
  private def releaseByTagPath(owner: Owner, name: RepoName, tag: TagName): List[String] =
    (releasesPath(owner, name) :+ "tags") ++ tag.segments

  private def assetsPath(owner: Owner, name: RepoName, id: ReleaseId): List[String] =
    releasePath(owner, name, id) :+ "assets"

  private def assetPath(owner: Owner, name: RepoName, id: ReleaseId, asset: AssetId): List[String] =
    assetsPath(owner, name, id) :+ asset.value.toString

  private def tagsPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "tags")

  private def topicsPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "topics")
