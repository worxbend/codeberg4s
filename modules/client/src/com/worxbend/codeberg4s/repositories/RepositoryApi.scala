package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams

import scala.concurrent.Future

/** Repository endpoints.
  *
  * Reached as `client.repos`. Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryApi.attempt]] never fail and
  * return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * Arguments are [[Owner]], [[RepoName]], [[BranchName]], [[ContentPath]] and [[ReleaseId]] rather than `String` and
  * `Long`, so a value that would forge a request path is rejected by its own smart constructor before a client is ever
  * involved — which is why no operation here produces [[com.worxbend.codeberg4s.CodebergError.Validation]] for its
  * arguments.
  *
  * '''Listing operations return one [[com.worxbend.codeberg4s.paging.Page]], never a whole collection.''' The page
  * knows whether another exists, and it knows that from the response's `Link` header rather than from how many items
  * arrived: Forgejo silently clamps `limit` to its own maximum while echoing the requested value, so a short page is
  * not evidence of the end of the collection (`docs/HAZARDS.md` §5). Walking the pages is the caller's decision, and
  * `page.nextPage` is what it is made with.
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
    pipeline.call(RepositoryApi.getRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryDecoders.repository)

  /** Searches repositories across the instance — `GET /repos/search`.
    *
    * Results are what the credentials can see: anonymously, public repositories only. The response is the `{"ok",
    * "data"}` envelope rather than a bare array (`docs/HAZARDS.md` §3), which is handled here and invisible to callers.
    *
    * '''Failures.''' [[com.worxbend.codeberg4s.CodebergError.Api]] with `422` when the instance rejects the query,
    * `429` when the search rate limit is hit, and `401`/`403` on an instance that requires a token to search.
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] means an element of `data` carried no `id`, `name` or
    * `owner`, reported at `$.data[n]`. [[com.worxbend.codeberg4s.CodebergError.Transport]] and
    * [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] as for [[get]]. Retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param term
    *   the `q` parameter — a substring matched against repository names. An empty string is accepted by Forgejo and
    *   returns everything visible, page by page. Percent-encoding happens at the transport boundary
    * @param params
    *   the page to fetch and how many results it may hold
    */
  def search(term: String, params: PageParams): Future[Page[Repository]] =
    pipeline.callPage(RepositoryApi.searchRequest(term, params), params)(using RepositoryDecoders.searchResults)

  /** Lists a repository's branches — `GET /repos/{owner}/{repo}/branches`.
    *
    * '''Failures.''' As [[get]], plus [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a branch carries no
    * `name` or no `commit`, reported at `$[n]`. Retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param params
    *   the page to fetch and how many branches it may hold
    */
  def listBranches(owner: Owner, name: RepoName, params: PageParams): Future[Page[Branch]] =
    pipeline.callPage(RepositoryApi.branchesRequest(owner, name, params), params)(using RepositoryDecoders.branches)

  /** Reads one branch — `GET /repos/{owner}/{repo}/branches/{branch}`.
    *
    * A branch name containing `/` is sent as several path segments rather than percent-encoded whole, because that is
    * what Forgejo's route expects; see [[BranchName]].
    *
    * '''Failures.''' As [[get]]: `404` covers both "no such branch" and "no such repository", and the two are not
    * distinguishable from the response. Retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param branch
    *   the branch name
    */
  def getBranch(owner: Owner, name: RepoName, branch: BranchName): Future[Branch] =
    pipeline.call(RepositoryApi.branchRequest(owner, name, branch), RetryEligibility.IdempotentOnly)(using
      RepositoryDecoders.branch)

  /** Lists a repository's tags — `GET /repos/{owner}/{repo}/tags`.
    *
    * Lightweight and annotated tags are listed together; [[Tag.message]] is the only thing distinguishing them.
    *
    * '''Failures.''' As [[get]], plus [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a tag carries no
    * `name` or no `id`, reported at `$[n]`. Retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param params
    *   the page to fetch and how many tags it may hold
    */
  def listTags(owner: Owner, name: RepoName, params: PageParams): Future[Page[Tag]] =
    pipeline.callPage(RepositoryApi.tagsRequest(owner, name, params), params)(using RepositoryDecoders.tags)

  /** Lists commits on the repository's default branch — `GET /repos/{owner}/{repo}/commits`.
    *
    * The commit history of an active repository is long: `forgejo/forgejo` is past forty thousand commits, so this is
    * the operation where treating a page as the whole collection hurts most.
    *
    * '''Failures.''' As [[get]], plus [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a commit carries no
    * `sha` or a nested object is unusable, reported at the failing path such as `$[3].commit.tree.sha`. An empty
    * repository answers `409`, which arrives as [[com.worxbend.codeberg4s.CodebergError.Api]] — it has no commits to
    * list rather than not existing. Retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param params
    *   the page to fetch and how many commits it may hold
    */
  def listCommits(owner: Owner, name: RepoName, params: PageParams): Future[Page[Commit]] =
    pipeline.callPage(RepositoryApi.commitsRequest(owner, name, params), params)(using RepositoryDecoders.commits)

  /** Lists a repository's releases — `GET /repos/{owner}/{repo}/releases`.
    *
    * Drafts are included only for credentials that may see them; an anonymous call lists published releases.
    *
    * '''Failures.''' As [[get]], plus [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a release carries
    * no `id` or no `tag_name`, reported at `$[n]`. Retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param params
    *   the page to fetch and how many releases it may hold
    */
  def listReleases(owner: Owner, name: RepoName, params: PageParams): Future[Page[Release]] =
    pipeline.callPage(RepositoryApi.releasesRequest(owner, name, params), params)(using RepositoryDecoders.releases)

  /** Reads one release by its identifier — `GET /repos/{owner}/{repo}/releases/{id}`.
    *
    * Addressed by [[ReleaseId]] and not by tag: `GET /releases/tags/{tag}` is a different operation and is not
    * implemented here.
    *
    * '''Failures.''' As [[get]]; `404` covers a release that does not exist, one that is a draft the caller may not
    * see, and a repository that does not exist. Retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param id
    *   the release's instance-local identifier
    */
  def getRelease(owner: Owner, name: RepoName, id: ReleaseId): Future[Release] =
    pipeline.call(RepositoryApi.releaseRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      RepositoryDecoders.release)

  /** Lists a repository's topics — `GET /repos/{owner}/{repo}/topics`.
    *
    * The body is `{"topics": [...]}` rather than an array, which is handled here. The endpoint declares `page` and
    * `limit`, so the result is a page like every other listing.
    *
    * '''Failures.''' As [[get]]. [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] is reachable only for a body
    * that is not a JSON object at all — a topic is free text, so nothing inside the envelope can fail to convert.
    * Retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param params
    *   the page to fetch and how many topics it may hold
    */
  def listTopics(owner: Owner, name: RepoName, params: PageParams): Future[Page[String]] =
    pipeline.callPage(RepositoryApi.topicsRequest(owner, name, params), params)(using RepositoryDecoders.topics)

  /** Reads a file or a directory — `GET /repos/{owner}/{repo}/contents/{filepath}`.
    *
    * '''This endpoint answers with two different JSON shapes and the specification only admits one of them.''' A file
    * comes back as an object and a directory as an array; the result is a [[RepositoryContent]] so the caller branches
    * on an ADT rather than on a guess. See that type and `docs/HAZARDS.md` §3 for the measurement.
    *
    * A file's bytes arrive base64-encoded in [[ContentEntry.File.content]] and are decoded on demand. Forgejo refuses
    * to inline a blob past its configured `default_max_blob_size`, and answers `403` when asked to.
    *
    * '''Failures.''' As [[get]], plus [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when the body is neither
    * shape, when an entry names a `type` this library does not know, or when an entry carries no `name`, `path` or
    * `sha`. Retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param path
    *   the repository-relative path of the file or directory; it is sent as several path segments, see [[ContentPath]]
    */
  def getContents(owner: Owner, name: RepoName, path: ContentPath): Future[RepositoryContent] =
    pipeline.call(RepositoryApi.contentsRequest(owner, name, path), RetryEligibility.IdempotentOnly)(using
      RepositoryDecoders.contents)

  /** Lists the repositories forked from this one — `GET /repos/{owner}/{repo}/forks`.
    *
    * Each element is a full repository whose [[Repository.parent]] is the repository asked about, which is what
    * `golden/repository/forks-list.json` shows.
    *
    * '''Failures.''' As [[get]], plus [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a fork carries no
    * `id`, `name` or `owner`, reported at `$[n]`. Retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param params
    *   the page to fetch and how many forks it may hold
    */
  def listForks(owner: Owner, name: RepoName, params: PageParams): Future[Page[Repository]] =
    pipeline.callPage(RepositoryApi.forksRequest(owner, name, params), params)(using RepositoryDecoders.repositories)

/** The requests this group issues, and its typed rail. */
object RepositoryApi:

  /** The stable operation id [[RepositoryApi.get]] copies into every failure's [[com.worxbend.codeberg4s.CallContext]].
    * Safe to alert on.
    */
  val GetOperation: String = "repos.get"

  /** The stable operation id of [[RepositoryApi.search]]. */
  val SearchOperation: String = "repos.search"

  /** The stable operation id of [[RepositoryApi.listBranches]]. */
  val ListBranchesOperation: String = "repos.branches.list"

  /** The stable operation id of [[RepositoryApi.getBranch]]. */
  val GetBranchOperation: String = "repos.branches.get"

  /** The stable operation id of [[RepositoryApi.listTags]]. */
  val ListTagsOperation: String = "repos.tags.list"

  /** The stable operation id of [[RepositoryApi.listCommits]]. */
  val ListCommitsOperation: String = "repos.commits.list"

  /** The stable operation id of [[RepositoryApi.listReleases]]. */
  val ListReleasesOperation: String = "repos.releases.list"

  /** The stable operation id of [[RepositoryApi.getRelease]]. */
  val GetReleaseOperation: String = "repos.releases.get"

  /** The stable operation id of [[RepositoryApi.listTopics]]. */
  val ListTopicsOperation: String = "repos.topics.list"

  /** The stable operation id of [[RepositoryApi.getContents]]. */
  val GetContentsOperation: String = "repos.contents.get"

  /** The stable operation id of [[RepositoryApi.listForks]]. */
  val ListForksOperation: String = "repos.forks.list"

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

    /** [[RepositoryApi.search]] with its failure as a value. */
    def search(term: String, params: PageParams): Future[Either[CodebergError, Page[Repository]]] =
      exec.attempt(rail.search(term, params))

    /** [[RepositoryApi.listBranches]] with its failure as a value. */
    def listBranches(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[Branch]]] =
      exec.attempt(rail.listBranches(owner, name, params))

    /** [[RepositoryApi.getBranch]] with its failure as a value. */
    def getBranch(owner: Owner, name: RepoName, branch: BranchName): Future[Either[CodebergError, Branch]] =
      exec.attempt(rail.getBranch(owner, name, branch))

    /** [[RepositoryApi.listTags]] with its failure as a value. */
    def listTags(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[Tag]]] =
      exec.attempt(rail.listTags(owner, name, params))

    /** [[RepositoryApi.listCommits]] with its failure as a value. */
    def listCommits(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[Commit]]] =
      exec.attempt(rail.listCommits(owner, name, params))

    /** [[RepositoryApi.listReleases]] with its failure as a value. */
    def listReleases(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[Release]]] =
      exec.attempt(rail.listReleases(owner, name, params))

    /** [[RepositoryApi.getRelease]] with its failure as a value. */
    def getRelease(owner: Owner, name: RepoName, id: ReleaseId): Future[Either[CodebergError, Release]] =
      exec.attempt(rail.getRelease(owner, name, id))

    /** [[RepositoryApi.listTopics]] with its failure as a value. */
    def listTopics(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[String]]] =
      exec.attempt(rail.listTopics(owner, name, params))

    /** [[RepositoryApi.getContents]] with its failure as a value. */
    def getContents(
        owner: Owner,
        name: RepoName,
        path: ContentPath,
    ): Future[Either[CodebergError, RepositoryContent]] =
      exec.attempt(rail.getContents(owner, name, path))

    /** [[RepositoryApi.listForks]] with its failure as a value. */
    def listForks(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[Repository]]] =
      exec.attempt(rail.listForks(owner, name, params))

  private def getRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(GetOperation, List("repos", owner.value, name.value), Nil)

  private def searchRequest(term: String, params: PageParams): CodebergRequest =
    read(SearchOperation, List("repos", "search"), ("q" -> term) :: window(params))

  private def branchesRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListBranchesOperation, List("repos", owner.value, name.value, "branches"), window(params))

  private def branchRequest(owner: Owner, name: RepoName, branch: BranchName): CodebergRequest =
    read(GetBranchOperation, List("repos", owner.value, name.value, "branches") ++ branch.segments, Nil)

  private def tagsRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListTagsOperation, List("repos", owner.value, name.value, "tags"), window(params))

  private def commitsRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListCommitsOperation, List("repos", owner.value, name.value, "commits"), window(params))

  private def releasesRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListReleasesOperation, List("repos", owner.value, name.value, "releases"), window(params))

  private def releaseRequest(owner: Owner, name: RepoName, id: ReleaseId): CodebergRequest =
    read(GetReleaseOperation, List("repos", owner.value, name.value, "releases", id.value.toString), Nil)

  private def topicsRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListTopicsOperation, List("repos", owner.value, name.value, "topics"), window(params))

  private def contentsRequest(owner: Owner, name: RepoName, path: ContentPath): CodebergRequest =
    read(GetContentsOperation, List("repos", owner.value, name.value, "contents") ++ path.segments, Nil)

  private def forksRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListForksOperation, List("repos", owner.value, name.value, "forks"), window(params))

  /** Every operation in this group is a `GET` that carries no body and adds no header of its own. */
  private def read(operation: String, path: List[String], query: List[(String, String)]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Get,
      path      = path,
      query     = query,
      headers   = Nil,
      body      = None,
    )

  /** The `page` and `limit` parameters, in the order Forgejo's own `Link` header writes them. */
  private def window(params: PageParams): List[(String, String)] =
    List("page" -> params.page.value.toString, "limit" -> params.size.value.toString)
