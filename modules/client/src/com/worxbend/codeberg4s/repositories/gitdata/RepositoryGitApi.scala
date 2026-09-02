package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.core.CodebergRequest.{read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.gitdata.wire.{DiffPatchOptionsDto, GitDataQueries, NoteOptionsDto}
import com.worxbend.codeberg4s.repositories.{Commit, CommitSha}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** Raw Git data and commit-level reads for one repository.
  *
  * What CI said about a commit is on [[statuses]] and the reads that answer with bytes rather than with a modelled
  * record are on [[files]], each a group of its own.
  *
  * Reached as `client.repos.git`. Both error rails are here (ADR-0005): the methods on this class fail the `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryGitApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==The group failure contract==
  *
  * Every operation here can produce [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` — which covers both
  * "no such object" and "no such repository", because Forgejo deliberately does not distinguish a private repository
  * from a missing one — `403` when the token lacks the scope, and `401` when a token was required and none was sent.
  * [[com.worxbend.codeberg4s.CodebergError.Transport]] means nothing reached the instance,
  * [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] means a retryable failure outlived the policy, and
  * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] means a `2xx` payload did not fit the model, reported at
  * the JSON path that did not fit. Arguments are [[com.worxbend.codeberg4s.Owner]],
  * [[com.worxbend.codeberg4s.RepoName]], [[com.worxbend.codeberg4s.repositories.CommitSha]], [[RefName]],
  * [[CompareRange]] and [[com.worxbend.codeberg4s.repositories.ContentPath]] rather than `String`, so a value that
  * would forge a request path is rejected by its own smart constructor and no operation here produces
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] for its arguments. Anything an individual operation adds to
  * this is stated on that operation.
  *
  * ==Retries==
  *
  * Every read is a `GET` and is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. The
  * three writes are not retried at all, and each says why on its own method — including [[removeNote]], which is a
  * `DELETE` and therefore idempotent in effect but still not retried, for a reason worth reading before assuming
  * otherwise.
  *
  * ==Five of these endpoints do not answer JSON==
  *
  * `{sha}.diff` and `{sha}.patch` produce `text/plain`, and this library returns that text unchanged. `/raw`, `/media`
  * and `/archive` produce '''bytes''' — `application/octet-stream`, a zip, a gzipped tar — and the three methods that
  * serve them return a `String`, which is lossy for anything that is not text.
  *
  * That was once a limitation of core: a response body was a `String`, so the bytes were already gone before any code
  * here saw them. It no longer is. [[com.worxbend.codeberg4s.core.CodebergResponse]] carries a
  * [[com.worxbend.codeberg4s.core.ResponseBody]], so the bytes survive as far as the decoder, and the `String` these
  * three return is now this group's own choice rather than something forced on it. Changing their return type is a
  * change to the published API of this group, with its own tests and its own migration note, so it is deliberately not
  * folded into the change that removed the constraint.
  */
final class RepositoryGitApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryGitApi.Attempt = RepositoryGitApi.Attempt(this)

  /** What CI has said about a commit, and which pull request brought it. */
  val statuses: CommitStatusApi = CommitStatusApi(pipeline)

  /** A repository's files as bytes: the raw file, the media file, the archive, the editor config. */
  val files: RepositoryFileApi = RepositoryFileApi(pipeline)

  /** Reads one blob by its object id — `GET /repos/{owner}/{repo}/git/blobs/{sha}`.
    *
    * A blob has no path and no history; it is bytes and an id. [[GitBlob.content]] is absent when the blob exceeds the
    * instance's `default_max_blob_size`, which is a `200` with a size and no content rather than an error.
    *
    * '''Failures.''' The group contract above, plus a `400` when the id is well-formed hexadecimal but names no blob in
    * this repository, and [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$.sha` when the payload carried
    * no usable object id.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param sha
    *   the blob's object id
    */
  def getBlob(owner: Owner, name: RepoName, sha: CommitSha): Future[GitBlob] =
    pipeline.call(RepositoryGitApi.blobRequest(owner, name, sha), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.blob)

  /** Reads several blobs in one call — `GET /repos/{owner}/{repo}/git/blobs?shas=…`.
    *
    * '''Bounded by the URI length, not by a page.''' The ids go in the query string, comma-separated, and the spec
    * spells out the limit: roughly 2,083 characters overall, which is about fifty full SHA-1 ids. Nothing here splits
    * an over-long request for you — a caller who wants a hundred blobs makes two calls, and a caller who does not will
    * get whatever the instance's own URI limit produces, typically a `414`.
    *
    * An empty `shas` is not a request worth making, and this method sends `shas=` for it rather than inventing a
    * result; the instance answers `400`.
    *
    * '''Failures.''' The group contract above, plus a `400` for an unusable `shas`, and
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$[n].sha` when an element carried no usable id.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param shas
    *   the blob ids, in the order they should appear in the query
    */
  def getBlobs(owner: Owner, name: RepoName, shas: Vector[CommitSha]): Future[Vector[GitBlob]] =
    pipeline.call(RepositoryGitApi.blobsRequest(owner, name, shas), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.blobs)

  /** Lists the entries of one tree — `GET /repos/{owner}/{repo}/git/trees/{sha}`.
    *
    * '''This is the listing most likely to be enormous.''' A recursive listing of a large repository's root tree is
    * tens of thousands of entries, and the page is what stands between a caller and all of them.
    *
    * '''Paging.''' The window is the `Link` header's and nothing else — `docs/HAZARDS.md` §5. Two things on this
    * endpoint invite the opposite conclusion and both are refused here: the body's own `truncated` flag, and the size
    * of the returned array. Forgejo clamps a requested page size to its own maximum while echoing the requested value
    * back, so a short page is not the end of the collection. The body's `sha`, `total_count` and `truncated` are read
    * by the DTO and deliberately not surfaced, because a second end-of-collection test is how a caller convinces itself
    * an inhabited tree is exhausted.
    *
    * The size parameter is spelled `per_page` on this route and `limit` on every other one; that is Forgejo's
    * inconsistency, and it is handled here.
    *
    * '''Failures.''' The group contract above, plus a `400` when `sha` names no tree, and
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$.tree[n].path` or `$.tree[n].sha` when an entry
    * carried no usable path or id.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param sha
    *   the tree's object id, or the id of a commit whose tree should be listed
    * @param recursive
    *   whether to descend into subtrees. Explicit rather than optional, because the difference between a directory
    *   listing and a whole-repository walk is not something to leave to a default
    * @param params
    *   the page to fetch and how many entries it may hold
    */
  def tree(
      owner: Owner,
      name: RepoName,
      sha: CommitSha,
      recursive: Boolean,
      params: PageParams,
  ): Future[Page[GitTreeEntry]] =
    pipeline.callPage(RepositoryGitApi.treeRequest(owner, name, sha, recursive, params), params)(using
      GitDataDecoders.treeEntries)

  /** Reads one commit — `GET /repos/{owner}/{repo}/git/commits/{sha}`.
    *
    * The same [[com.worxbend.codeberg4s.repositories.Commit]] model the commit listing returns, so nothing new has to
    * be learned to read the result. What differs is that the three expensive parts — the line counts, the signature
    * verdict and the affected files — are optional here and can be declined; see [[CommitInclude]].
    *
    * Forgejo resolves the path segment as a ref as well as an id, so a branch name would work. This method takes a
    * [[com.worxbend.codeberg4s.repositories.CommitSha]] regardless: a commit read by branch name answers a different
    * commit tomorrow, and a caller who wants that should say so by resolving the ref first.
    *
    * '''Failures.''' The group contract above, plus a `422` when the instance rejects the id, and
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$.sha` or at a nested path such as
    * `$.commit.tree.sha`.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param sha
    *   the commit's object id
    * @param include
    *   which optional parts to ask the instance to compute; [[CommitInclude.Default]] asks for its defaults
    */
  def getCommit(owner: Owner, name: RepoName, sha: CommitSha, include: CommitInclude): Future[Commit] =
    pipeline.call(RepositoryGitApi.commitRequest(owner, name, sha, include), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.commit)

  /** Reads a commit's diff or patch — `GET /repos/{owner}/{repo}/git/commits/{sha}.{diffType}`.
    *
    * '''Not JSON.''' The endpoint produces `text/plain` and the body is returned unchanged: a unified diff for
    * [[DiffType.Diff]], a mailbox-format patch for [[DiffType.Patch]]. Nothing here parses it — a diff parser is a
    * library of its own, and a wrong one is worse than none.
    *
    * The format is part of the path rather than a parameter, which is why it is a [[DiffType]] and not a string: the
    * suffix is what the route matches on, and a misspelling is a `404` with no explanation.
    *
    * '''Failures.''' The group contract above. [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] is '''not'''
    * reachable: any sequence of characters is a valid diff as far as this library is concerned, including an empty one
    * — which is what an empty commit legitimately produces.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param sha
    *   the commit's object id
    * @param diffType
    *   which rendering to ask for
    */
  def getCommitDiff(owner: Owner, name: RepoName, sha: CommitSha, diffType: DiffType): Future[String] =
    pipeline.call(RepositoryGitApi.commitDiffRequest(owner, name, sha, diffType), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.text)

  /** Reads the Git note attached to a commit — `GET /repos/{owner}/{repo}/git/notes/{sha}`.
    *
    * A commit with no note answers `404`, which is the endpoint's contract rather than a defect: notes are optional
    * metadata, and their absence is the normal case.
    *
    * '''Failures.''' The group contract above, plus a `422` when the instance rejects the id, and
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$.commit.sha` when the echoed commit carried no
    * usable id.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param sha
    *   the commit the note is attached to
    * @param include
    *   which optional parts of the echoed commit to ask for. This route declares no `stat` parameter, so
    *   [[CommitInclude.stat]] is not sent
    */
  def getNote(owner: Owner, name: RepoName, sha: CommitSha, include: CommitInclude): Future[GitNote] =
    pipeline.call(RepositoryGitApi.noteRequest(owner, name, sha, include), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.note)

  /** Sets the Git note on a commit — `POST /repos/{owner}/{repo}/git/notes/{sha}`.
    *
    * Replaces whatever note the commit had; there is no append. The commit's own id does not change, which is the point
    * of notes — see [[GitNote]].
    *
    * '''Never retried.''' The effect is idempotent — the same message on the same commit twice leaves one note — but
    * the method is `POST`, and this library does not opt a caller into repeating a mutation on their behalf. A retry
    * would also be indistinguishable, from here, from a `POST` to a route that a future Forgejo made appending.
    *
    * '''Failures.''' The group contract above, plus a `422` when the instance rejects the id or the body. A token is
    * required, so an anonymous call is `401` rather than `404`.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param sha
    *   the commit to annotate
    * @param message
    *   the note's text. Sent even when empty, which is how a note is blanked without being removed
    */
  def setNote(owner: Owner, name: RepoName, sha: CommitSha, message: String): Future[GitNote] =
    pipeline.call(RepositoryGitApi.setNoteRequest(owner, name, sha, message), RetryEligibility.Never)(using
      GitDataDecoders.note)

  /** Removes the Git note from a commit — `DELETE /repos/{owner}/{repo}/git/notes/{sha}`.
    *
    * Answers `204` with no body, which [[com.worxbend.codeberg4s.core.ApiPipeline.callUnit]] reads without a decoder,
    * so an instance that decorates the `204` with an unexpected payload cannot fail the call.
    *
    * '''Never retried, despite being a `DELETE`.''' Repeating it converges on the same state, which is the usual
    * argument for retrying a delete — but Forgejo answers `404` for a commit with no note. A retry after a first
    * attempt that succeeded and whose response was lost would therefore turn a success into a reported failure, which
    * is a worse outcome than surfacing the original transport error.
    *
    * '''Failures.''' The group contract above, plus a `422` when the instance rejects the id.
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] is not reachable: no body is read.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param sha
    *   the commit to strip the note from
    */
  def removeNote(owner: Owner, name: RepoName, sha: CommitSha): Future[Unit] =
    pipeline.callUnit(RepositoryGitApi.removeNoteRequest(owner, name, sha), RetryEligibility.Never)

  /** Lists every ref in the repository — `GET /repos/{owner}/{repo}/git/refs`.
    *
    * Branches and tags together, each fully qualified — `refs/heads/main`, `refs/tags/v1.2`. The endpoint declares no
    * `page` or `limit`, so the whole set arrives at once; a repository with thousands of refs will send thousands.
    *
    * Remember that a ref's [[GitObjectRef.sha]] is not always a commit: an annotated tag's ref points at a tag object.
    * [[GitReference]] explains the dereference.
    *
    * '''Failures.''' The group contract above, plus [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at
    * `$[n].ref` when an element carried no usable ref name.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    */
  def refs(owner: Owner, name: RepoName): Future[Vector[GitReference]] =
    pipeline.call(RepositoryGitApi.refsRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.references)

  /** Lists the refs under a prefix — `GET /repos/{owner}/{repo}/git/refs/{ref}`.
    *
    * The path parameter is a '''prefix''', which is why the answer is a list and not one ref: `heads` lists every
    * branch, `heads/release` lists every branch under it, and `heads/main` lists exactly one. A ref name contains `/`
    * and is sent as several path segments for that reason — see [[RefName]].
    *
    * '''Failures.''' As [[refs]]. A prefix that matches nothing is a `404` rather than an empty array.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param ref
    *   the prefix or the whole ref name
    */
  def matchingRefs(owner: Owner, name: RepoName, ref: RefName): Future[Vector[GitReference]] =
    pipeline.call(RepositoryGitApi.matchingRefsRequest(owner, name, ref), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.references)

  /** Reads an annotated tag object — `GET /repos/{owner}/{repo}/git/tags/{sha}`.
    *
    * '''Annotated tags only.''' A lightweight tag is a ref with no object behind it, so this endpoint answers `404` for
    * one. [[AnnotatedTag]] contrasts the two models in full; the short version is that the argument here is the id of
    * the '''tag object''', which is what a ref listing reports as an object of kind [[GitObjectKind.Tag]], and not the
    * id of the commit.
    *
    * '''Failures.''' The group contract above, plus a `400` when the id names no tag object, and
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$.tag` or `$.sha`.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param sha
    *   the tag object's own id
    */
  def getAnnotatedTag(owner: Owner, name: RepoName, sha: CommitSha): Future[AnnotatedTag] =
    pipeline.call(RepositoryGitApi.annotatedTagRequest(owner, name, sha), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.annotatedTag)

  /** Compares two refs — `GET /repos/{owner}/{repo}/compare/{basehead}`.
    *
    * Git's symmetric-difference form: the commits reachable from the head and not from the base. Both halves of the
    * range may contain `/`, so the range is sent as several path segments — see [[CompareRange]].
    *
    * '''The answer can be truncated and does not say so in a header.''' The endpoint declares no paging at all;
    * [[CommitComparison.totalCommits]] against the size of the returned array is the only evidence, and
    * [[CommitComparison.isTruncated]] is that comparison.
    *
    * '''Failures.''' The group contract above; `404` also covers a base or head that does not resolve. Plus
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$.commits[n].sha`.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param range
    *   the `base...head` to compare
    */
  def compare(owner: Owner, name: RepoName, range: CompareRange): Future[CommitComparison] =
    pipeline.call(RepositoryGitApi.compareRequest(owner, name, range), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.comparison)

  /** Applies a patch to the repository — `POST /repos/{owner}/{repo}/diffpatch`.
    *
    * '''This writes.''' The patch becomes a commit on a branch. [[ApplyDiffPatch]] documents what goes in the patch
    * field and why this library encodes nothing on the caller's behalf.
    *
    * '''Never retried.''' A repeat that the instance already applied leaves a second commit, and Forgejo offers no
    * idempotency key with which to tell the two apart.
    *
    * '''Failures.''' The group contract above, plus `422` when the patch does not apply, `413` when the repository is
    * over its quota, and `423` when the repository is archived — all of them
    * [[com.worxbend.codeberg4s.CodebergError.Api]] carrying the instance's own message. A token is required, so an
    * anonymous call is `401`.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param command
    *   the patch and the commit to make of it
    */
  def applyDiffPatch(owner: Owner, name: RepoName, command: ApplyDiffPatch): Future[FileChange] =
    pipeline.call(RepositoryGitApi.diffPatchRequest(owner, name, command), RetryEligibility.Never)(using
      GitDataDecoders.fileChange)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryGitApi:

  /** The stable operation id [[RepositoryGitApi.getBlob]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]]. Safe to alert on.
    */
  val GetBlobOperation: String = "repos.git.blobs.get"

  /** The stable operation id of [[RepositoryGitApi.getBlobs]]. */
  val GetBlobsOperation: String = "repos.git.blobs.list"

  /** The stable operation id of [[RepositoryGitApi.tree]]. */
  val ListTreeOperation: String = "repos.git.trees.list"

  /** The stable operation id of [[RepositoryGitApi.getCommit]]. */
  val GetCommitOperation: String = "repos.git.commits.get"

  /** The stable operation id of [[RepositoryGitApi.getCommitDiff]]. */
  val GetCommitDiffOperation: String = "repos.git.commits.diff"

  /** The stable operation id of [[RepositoryGitApi.getNote]]. */
  val GetNoteOperation: String = "repos.git.notes.get"

  /** The stable operation id of [[RepositoryGitApi.setNote]]. */
  val SetNoteOperation: String = "repos.git.notes.set"

  /** The stable operation id of [[RepositoryGitApi.removeNote]]. */
  val RemoveNoteOperation: String = "repos.git.notes.remove"

  /** The stable operation id of [[RepositoryGitApi.refs]]. */
  val ListRefsOperation: String = "repos.git.refs.list"

  /** The stable operation id of [[RepositoryGitApi.matchingRefs]]. */
  val ListMatchingRefsOperation: String = "repos.git.refs.match"

  /** The stable operation id of [[RepositoryGitApi.getAnnotatedTag]]. */
  val GetAnnotatedTagOperation: String = "repos.git.tags.get"

  /** The stable operation id of [[RepositoryGitApi.compare]]. */
  val CompareOperation: String = "repos.compare.get"

  /** The stable operation id of [[RepositoryGitApi.applyDiffPatch]]. */
  val ApplyDiffPatchOperation: String = "repos.diffpatch.apply"

  /** The separator Forgejo's multi-blob read expects between object ids in its `shas` parameter. */
  private val ShaSeparator: String = ","

  /** The typed rail of [[RepositoryGitApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.git.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryGitApi)(using exec: Exec[Future]):

    /** [[RepositoryGitApi.getBlob]] with its failure as a value. The returned `Future` never fails with a
      * [[com.worxbend.codeberg4s.CodebergException]].
      */
    def getBlob(owner: Owner, name: RepoName, sha: CommitSha): Future[Either[CodebergError, GitBlob]] =
      exec.attempt(rail.getBlob(owner, name, sha))

    /** [[RepositoryGitApi.getBlobs]] with its failure as a value. */
    def getBlobs(
        owner: Owner,
        name: RepoName,
        shas: Vector[CommitSha],
    ): Future[Either[CodebergError, Vector[GitBlob]]] =
      exec.attempt(rail.getBlobs(owner, name, shas))

    /** [[RepositoryGitApi.tree]] with its failure as a value. */
    def tree(
        owner: Owner,
        name: RepoName,
        sha: CommitSha,
        recursive: Boolean,
        params: PageParams,
    ): Future[Either[CodebergError, Page[GitTreeEntry]]] =
      exec.attempt(rail.tree(owner, name, sha, recursive, params))

    /** [[RepositoryGitApi.getCommit]] with its failure as a value. */
    def getCommit(
        owner: Owner,
        name: RepoName,
        sha: CommitSha,
        include: CommitInclude,
    ): Future[Either[CodebergError, Commit]] =
      exec.attempt(rail.getCommit(owner, name, sha, include))

    /** [[RepositoryGitApi.getCommitDiff]] with its failure as a value. */
    def getCommitDiff(
        owner: Owner,
        name: RepoName,
        sha: CommitSha,
        diffType: DiffType,
    ): Future[Either[CodebergError, String]] =
      exec.attempt(rail.getCommitDiff(owner, name, sha, diffType))

    /** [[RepositoryGitApi.getNote]] with its failure as a value. */
    def getNote(
        owner: Owner,
        name: RepoName,
        sha: CommitSha,
        include: CommitInclude,
    ): Future[Either[CodebergError, GitNote]] =
      exec.attempt(rail.getNote(owner, name, sha, include))

    /** [[RepositoryGitApi.setNote]] with its failure as a value. */
    def setNote(
        owner: Owner,
        name: RepoName,
        sha: CommitSha,
        message: String,
    ): Future[Either[CodebergError, GitNote]] =
      exec.attempt(rail.setNote(owner, name, sha, message))

    /** [[RepositoryGitApi.removeNote]] with its failure as a value. */
    def removeNote(owner: Owner, name: RepoName, sha: CommitSha): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.removeNote(owner, name, sha))

    /** [[RepositoryGitApi.refs]] with its failure as a value. */
    def refs(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[GitReference]]] =
      exec.attempt(rail.refs(owner, name))

    /** [[RepositoryGitApi.matchingRefs]] with its failure as a value. */
    def matchingRefs(
        owner: Owner,
        name: RepoName,
        ref: RefName,
    ): Future[Either[CodebergError, Vector[GitReference]]] =
      exec.attempt(rail.matchingRefs(owner, name, ref))

    /** [[RepositoryGitApi.getAnnotatedTag]] with its failure as a value. */
    def getAnnotatedTag(owner: Owner, name: RepoName, sha: CommitSha): Future[Either[CodebergError, AnnotatedTag]] =
      exec.attempt(rail.getAnnotatedTag(owner, name, sha))

    /** [[RepositoryGitApi.compare]] with its failure as a value. */
    def compare(owner: Owner, name: RepoName, range: CompareRange): Future[Either[CodebergError, CommitComparison]] =
      exec.attempt(rail.compare(owner, name, range))

    /** [[RepositoryGitApi.applyDiffPatch]] with its failure as a value. */
    def applyDiffPatch(
        owner: Owner,
        name: RepoName,
        command: ApplyDiffPatch,
    ): Future[Either[CodebergError, FileChange]] =
      exec.attempt(rail.applyDiffPatch(owner, name, command))

  private def blobRequest(owner: Owner, name: RepoName, sha: CommitSha): CodebergRequest =
    read(GetBlobOperation, gitPath(owner, name, "blobs") :+ sha.value, Nil)

  private def blobsRequest(owner: Owner, name: RepoName, shas: Vector[CommitSha]): CodebergRequest =
    read(GetBlobsOperation, gitPath(owner, name, "blobs"), List("shas" -> shas.map(_.value).mkString(ShaSeparator)))

  private def treeRequest(
      owner: Owner,
      name: RepoName,
      sha: CommitSha,
      recursive: Boolean,
      params: PageParams,
  ): CodebergRequest =
    read(ListTreeOperation, gitPath(owner, name, "trees") :+ sha.value, GitDataQueries.treeWindow(params, recursive))

  private def commitRequest(
      owner: Owner,
      name: RepoName,
      sha: CommitSha,
      include: CommitInclude,
  ): CodebergRequest =
    read(GetCommitOperation, gitPath(owner, name, "commits") :+ sha.value, GitDataQueries.commitInclude(include))

  private def commitDiffRequest(
      owner: Owner,
      name: RepoName,
      sha: CommitSha,
      diffType: DiffType,
  ): CodebergRequest =
    read(GetCommitDiffOperation, gitPath(owner, name, "commits") :+ s"${sha.value}.${diffType.suffix}", Nil)

  private def noteRequest(owner: Owner, name: RepoName, sha: CommitSha, include: CommitInclude): CodebergRequest =
    read(GetNoteOperation, notePath(owner, name, sha), GitDataQueries.noteInclude(include))

  private def setNoteRequest(owner: Owner, name: RepoName, sha: CommitSha, message: String): CodebergRequest =
    write(SetNoteOperation, HttpMethod.Post, notePath(owner, name, sha), NoteOptionsDto.render(message))

  private def removeNoteRequest(owner: Owner, name: RepoName, sha: CommitSha): CodebergRequest =
    remove(RemoveNoteOperation, notePath(owner, name, sha))

  private def refsRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListRefsOperation, gitPath(owner, name, "refs"), Nil)

  private def matchingRefsRequest(owner: Owner, name: RepoName, ref: RefName): CodebergRequest =
    read(ListMatchingRefsOperation, gitPath(owner, name, "refs") ++ ref.segments, Nil)

  private def annotatedTagRequest(owner: Owner, name: RepoName, sha: CommitSha): CodebergRequest =
    read(GetAnnotatedTagOperation, gitPath(owner, name, "tags") :+ sha.value, Nil)

  private def compareRequest(owner: Owner, name: RepoName, range: CompareRange): CodebergRequest =
    read(CompareOperation, RepositoryRequests.repositoryPath(owner, name) :+ "compare" :++ range.segments, Nil)

  private def diffPatchRequest(owner: Owner, name: RepoName, command: ApplyDiffPatch): CodebergRequest =
    write(
      ApplyDiffPatchOperation,
      HttpMethod.Post,
      RepositoryRequests.repositoryPath(owner, name) :+ "diffpatch",
      DiffPatchOptionsDto.render(command),
    )

  private def gitPath(owner: Owner, name: RepoName, resource: String): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "git" :+ resource

  private def notePath(owner: Owner, name: RepoName, sha: CommitSha): List[String] =
    gitPath(owner, name, "notes") :+ sha.value
