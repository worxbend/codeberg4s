package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.core.CodebergRequest.{read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.hooks.wire.{HookQueries, WikiPageOptionsDto}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** A repository's wiki: its pages, their content and their history.
  *
  * Reached as `client.repos.wiki`. Both error rails are here (ADR-0005), on the same terms as [[RepositoryHookApi]]:
  * the typed rail is derived from this one by [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot
  * disagree.
  *
  * ==A wiki is a second Git repository==
  *
  * Forgejo stores a repository's wiki in a companion Git repository, which is why every write here produces a
  * '''commit''' and why [[revisions]] exists at all. It is also why a wiki page's content is base64: it is a file, and
  * this library models it with the same [[com.worxbend.codeberg4s.repositories.FileContent]] that
  * `GET /repos/{owner}/{repo}/contents/{filepath}` produces. Build outgoing content with [[WikiContent]].
  *
  * A repository whose wiki is disabled answers `404` to every route here, indistinguishably from one that does not
  * exist.
  *
  * ==Page names are not one path segment==
  *
  * A [[WikiPageName]] may contain `/`, because a wiki has sub-pages, and it reaches the wire as several segments for
  * the reason [[com.worxbend.codeberg4s.repositories.BranchName]] does — a slash percent-encoded into one segment
  * addresses a page that does not exist. Spaces are ordinary here too. See [[WikiPageName]].
  *
  * ==Evidence==
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response'''; see [[RepositoryHookApi]] for why no
  * fixture exists.
  *
  * ==Failures==
  *
  * The four remote failures are exactly those [[RepositoryHookApi]] lists, and are not repeated here. Three statuses
  * are specific to the wiki writes and are worth naming, because all three arrive as an ordinary
  * [[com.worxbend.codeberg4s.CodebergError.Api]] and are easy to misread as bugs:
  *
  *   - `413` — the instance's storage quota for this repository is exhausted;
  *   - `423` — the repository is archived, and archived repositories accept no writes at all;
  *   - `403` — the credentials may read the wiki but not write it.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. '''No write here is retried''', which is
  * unusual for this library and is argued at each of the three; the short version is that every wiki write makes a Git
  * commit, and a commit is a thing that is created.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class RepositoryWikiApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryWikiApi.Attempt = RepositoryWikiApi.Attempt(this)

  /** Lists a wiki's pages — `GET /repos/{owner}/{repo}/wiki/pages`.
    *
    * '''Metadata only.''' The elements are [[WikiPageMeta]] and carry no content, which is what stops listing a wiki
    * from transferring every page's text; [[page]] reads one.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past
    * the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above.
    */
  def listPages(owner: Owner, name: RepoName, params: PageParams): Future[Page[WikiPageMeta]] =
    pipeline.callPage(RepositoryWikiApi.listPagesRequest(owner, name, params), params)(using
      RepositoryHookDecoders.wikiPages)

  /** Reads one wiki page with its content — `GET /repos/{owner}/{repo}/wiki/page/{pageName}`.
    *
    * '''The content is base64''' and is handed back as a [[com.worxbend.codeberg4s.repositories.FileContent]], decoded
    * only when the caller asks. `sidebar` and `footer` are '''not''' decoded, because the spec does not say they are
    * encoded; see [[WikiPage]].
    *
    * '''Failures.''' The group contract above. A page name that does not exist is a `404`, as is a wiki that is
    * disabled.
    */
  def page(owner: Owner, name: RepoName, pageName: WikiPageName): Future[WikiPage] =
    pipeline.call(RepositoryWikiApi.pageRequest(owner, name, pageName), RetryEligibility.IdempotentOnly)(using
      RepositoryHookDecoders.wikiPage)

  /** Creates a wiki page — `POST /repos/{owner}/{repo}/wiki/new`.
    *
    * '''Never retried.''' It is a `POST`, Forgejo offers no idempotency key, and a repeat writes a second commit. What
    * a repeat produces depends on timing rather than on anything the caller can see: if the first attempt succeeded and
    * its response was lost, the second gets a `400` because the page already exists; if the first attempt failed after
    * committing, the second may succeed and leave two revisions where the caller intended one. Neither outcome is worth
    * gambling for. [[page]] resolves the uncertainty.
    *
    * '''Answers `201` with the created page''', content included.
    *
    * '''Failures.''' The group contract above, including the `413` and `423` named there. A `400` is what an existing
    * title produces.
    */
  def createPage(owner: Owner, name: RepoName, command: CreateWikiPage): Future[WikiPage] =
    pipeline.call(RepositoryWikiApi.createPageRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryHookDecoders.wikiPage)

  /** Edits a wiki page — `PATCH /repos/{owner}/{repo}/wiki/page/{pageName}`.
    *
    * '''Never retried, and this one deserves its reasons stated in full''', because a `PATCH` that assigns a stated
    * content to a stated page looks exactly like the idempotent writes [[RepositoryHookApi.edit]] does retry. Two
    * things break the resemblance:
    *
    *   - '''it commits'''. Every edit writes a revision into the wiki's Git repository, and a revision is created
    *     rather than assigned. A retry after a lost success can therefore leave a second commit in a history a human
    *     reads, and [[WikiPage.commitCount]] counting one more than the caller expects;
    *   - '''it can rename'''. A command carrying [[EditWikiPage.renamedTo]] moves the page, so a repeat addresses a
    *     name that no longer exists and answers `404`.
    *
    * The second reason applies only to some commands, and this library could have chosen per call as
    * [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi.updateVariable]] does. The first applies to all
    * of them, so there is nothing to choose between.
    *
    * '''Answers `200` with the edited page.'''
    *
    * '''Failures.''' The group contract above, including the `413` and `423` named there.
    *
    * @param pageName
    *   the page to edit. To move it, put the new title in the command; see [[EditWikiPage]]
    */
  def editPage(
      owner: Owner,
      name: RepoName,
      pageName: WikiPageName,
      command: EditWikiPage,
  ): Future[WikiPage] =
    pipeline.call(RepositoryWikiApi.editPageRequest(owner, name, pageName, command), RetryEligibility.Never)(using
      RepositoryHookDecoders.wikiPage)

  /** Deletes a wiki page — `DELETE /repos/{owner}/{repo}/wiki/page/{pageName}`.
    *
    * '''Never retried''', unlike every other delete in this package, and for the first reason [[editPage]] gives:
    * removing a wiki page writes a commit. The end state after two attempts is the same, but the history is not, and a
    * wiki's history is something a human reads. The bar earlier waves set for
    * [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]] is that nothing is created; a commit is created.
    *
    * A caller who does not care about the extra revision can simply call this again — the second call answers `404`
    * once the page is gone, which is information rather than damage.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above, including the `423` for an archived repository.
    */
  def deletePage(owner: Owner, name: RepoName, pageName: WikiPageName): Future[Unit] =
    pipeline.callUnit(RepositoryWikiApi.deletePageRequest(owner, name, pageName), RetryEligibility.Never)

  /** Lists one page's revisions — `GET /repos/{owner}/{repo}/wiki/revisions/{pageName}`.
    *
    * '''The body is an envelope, not an array.''' This endpoint answers `{"commits", "count"}`, unlike the page listing
    * beside it; see [[com.worxbend.codeberg4s.repositories.hooks.wire.WikiCommitListDto]]. The body's own `count` is
    * '''not''' what [[com.worxbend.codeberg4s.paging.Page.totalCount]] reports — that comes from the `X-Total-Count`
    * header, as it does for every other paged call.
    *
    * '''Only `page` is sent, never `limit`.''' The spec declares no `limit` for this operation, so the instance chooses
    * the page size and the size half of the requested window does not reach the wire; see
    * [[com.worxbend.codeberg4s.repositories.hooks.wire.HookQueries.revisionPaging]]. Where the collection ends is still
    * decided by the `Link` header, as everywhere else.
    *
    * '''Failures.''' The group contract above.
    */
  def revisions(
      owner: Owner,
      name: RepoName,
      pageName: WikiPageName,
      params: PageParams,
  ): Future[Page[WikiCommit]] =
    pipeline.callPage(RepositoryWikiApi.revisionsRequest(owner, name, pageName, params), params)(using
      RepositoryHookDecoders.wikiRevisions)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryWikiApi:

  /** The stable operation id of [[RepositoryWikiApi.listPages]]. Safe to alert on. */
  val ListPagesOperation: String = "repos.wiki.pages.list"

  /** The stable operation id of the single-page read on [[RepositoryWikiApi]]. */
  val GetPageOperation: String = "repos.wiki.pages.get"

  /** The stable operation id of [[RepositoryWikiApi.createPage]]. */
  val CreatePageOperation: String = "repos.wiki.pages.create"

  /** The stable operation id of [[RepositoryWikiApi.editPage]]. */
  val EditPageOperation: String = "repos.wiki.pages.edit"

  /** The stable operation id of [[RepositoryWikiApi.deletePage]]. */
  val DeletePageOperation: String = "repos.wiki.pages.delete"

  /** The stable operation id of [[RepositoryWikiApi.revisions]]. */
  val ListRevisionsOperation: String = "repos.wiki.revisions.list"

  /** The typed rail of [[RepositoryWikiApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.wiki.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryWikiApi)(using exec: Exec[Future]):

    /** [[RepositoryWikiApi.listPages]] with its failure as a value. */
    def listPages(
        owner: Owner,
        name: RepoName,
        params: PageParams,
    ): Future[Either[CodebergError, Page[WikiPageMeta]]] =
      exec.attempt(rail.listPages(owner, name, params))

    /** The single-page read on [[RepositoryWikiApi]], with its failure as a value. */
    def page(owner: Owner, name: RepoName, pageName: WikiPageName): Future[Either[CodebergError, WikiPage]] =
      exec.attempt(rail.page(owner, name, pageName))

    /** [[RepositoryWikiApi.createPage]] with its failure as a value. */
    def createPage(
        owner: Owner,
        name: RepoName,
        command: CreateWikiPage,
    ): Future[Either[CodebergError, WikiPage]] =
      exec.attempt(rail.createPage(owner, name, command))

    /** [[RepositoryWikiApi.editPage]] with its failure as a value. */
    def editPage(
        owner: Owner,
        name: RepoName,
        pageName: WikiPageName,
        command: EditWikiPage,
    ): Future[Either[CodebergError, WikiPage]] =
      exec.attempt(rail.editPage(owner, name, pageName, command))

    /** [[RepositoryWikiApi.deletePage]] with its failure as a value. */
    def deletePage(owner: Owner, name: RepoName, pageName: WikiPageName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deletePage(owner, name, pageName))

    /** [[RepositoryWikiApi.revisions]] with its failure as a value. */
    def revisions(
        owner: Owner,
        name: RepoName,
        pageName: WikiPageName,
        params: PageParams,
    ): Future[Either[CodebergError, Page[WikiCommit]]] =
      exec.attempt(rail.revisions(owner, name, pageName, params))

  private def listPagesRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListPagesOperation, wikiPath(owner, name) :+ "pages", HookQueries.paging(params))

  private def pageRequest(owner: Owner, name: RepoName, pageName: WikiPageName): CodebergRequest =
    read(GetPageOperation, pagePath(owner, name, pageName), Nil)

  private def createPageRequest(owner: Owner, name: RepoName, command: CreateWikiPage): CodebergRequest =
    write(
      CreatePageOperation,
      HttpMethod.Post,
      wikiPath(owner, name) :+ "new",
      WikiPageOptionsDto.renderCreate(command),
    )

  private def editPageRequest(
      owner: Owner,
      name: RepoName,
      pageName: WikiPageName,
      command: EditWikiPage,
  ): CodebergRequest =
    write(
      EditPageOperation,
      HttpMethod.Patch,
      pagePath(owner, name, pageName),
      WikiPageOptionsDto.renderEdit(command),
    )

  private def deletePageRequest(owner: Owner, name: RepoName, pageName: WikiPageName): CodebergRequest =
    remove(DeletePageOperation, pagePath(owner, name, pageName))

  private def revisionsRequest(
      owner: Owner,
      name: RepoName,
      pageName: WikiPageName,
      params: PageParams,
  ): CodebergRequest =
    read(
      ListRevisionsOperation,
      wikiPath(owner, name) ++ ("revisions" :: pageName.segments),
      HookQueries.revisionPaging(params),
    )

  private def wikiPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "wiki"

  private def pagePath(owner: Owner, name: RepoName, pageName: WikiPageName): List[String] =
    wikiPath(owner, name) ++ ("page" :: pageName.segments)
