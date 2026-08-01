package com.worxbend.codeberg4s.it

import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

import java.util.concurrent.atomic.AtomicReference

/** A handful of read-only `GET`s against the real `https://codeberg.org`, run only when asked for.
  *
  * ==Why this exists next to the container suite==
  *
  * The container proves the library works against '''a''' Forgejo. This proves it works against '''the''' instance the
  * library is named after, which is a different claim: codeberg.org sits behind an edge proxy that adds its own
  * headers, serves a development build ahead of any release, and is the instance whose behaviour `docs/HAZARDS.md` was
  * measured on. When those measurements go stale, this is the suite that says so.
  *
  * ==Opt-in, and read-only==
  *
  * Set `CODEBERG_IT=1` to run it. Without the variable every test is '''skipped''' rather than failed, through munit's
  * `assume`, which reports the test as ignored and prints [[LiveSmoke.SkipMessage]] — so a run that skipped everything
  * is visibly different from a run that had nothing to do. `CODEBERG_IT_TOKEN` optionally supplies a personal access
  * token, which only widens the rate limit; the suite asserts nothing that needs authentication.
  *
  * Every call below is a `GET` against a repository the suite does not own. Nothing here creates, edits or deletes
  * anything, and nothing here may ever be changed to. The whole suite costs six requests out of the 2000-per-ten-minute
  * anonymous budget measured in `docs/HAZARDS.md` §6.
  */
final class CodebergLiveSmokeSuite extends IntegrationSuite:

  /** A public instance over the public internet; the default munit timeout is far too tight for that. */
  override def munitTimeout: Duration = 2.minutes

  private given ExecutionContext = munitExecutionContext

  /** The owner half of the probe target. Public, busy and unlikely to be renamed. */
  private val Handle: Owner = orFail(Owner.from("forgejo"))

  /** The repository half of the probe target; `forgejo/forgejo` is its own repository's owner and name. */
  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  /** The client, built only when the suite is enabled so that a skipped run opens no connection pool at all. */
  private val client: AtomicReference[Option[CodebergClient]] = AtomicReference(None)

  override def beforeAll(): Unit =
    if LiveSmoke.enabled then client.set(Some(CodebergClient(LiveSmoke.config)))

  override def afterAll(): Unit =
    client.getAndSet(None).foreach(open => open.close())

  test("GET /version answers, so the published base URI still points at the API root"):
    live: codeberg =>
      codeberg.version.get().map: version =>
        assert(version.raw.nonEmpty, "codeberg.org reported an empty version string")

  test("GET /settings/api still reports the limit clamp the pagination driver was designed around"):
    live: codeberg =>
      codeberg.misc.apiSettings().map: settings =>
        assert(settings.maxResponseItems > 0, s"max_response_items was ${settings.maxResponseItems}")
        assert(settings.defaultPagingNum > 0, s"default_paging_num was ${settings.defaultPagingNum}")

  test("a public repository decodes from whatever codeberg.org is serving today"):
    live: codeberg =>
      codeberg.repos.get(Handle, Name).map: repository =>
        assertEquals(repository.fullName, "forgejo/forgejo")
        assertEquals(repository.slug.value, "forgejo/forgejo")
        assert(repository.htmlUrl.isDefined, "the repository carried no html_url")

  test("a busy listing still sends the Link header the pagination driver reads"):
    live: codeberg =>
      val window = PageParams(PageNumber.First, orFail(PageSize.from(2)))

      codeberg.issues.list(Handle, Name, IssueQuery.Empty, window).map: page =>
        assertEquals(page.size, 2, "limit=2 was not honoured")
        assertEquals(page.isLast, false, "a full page of a busy repository reported itself as the last one")
        assertEquals(page.nextPage.map(next => next.value), Some(2))
        assert(page.totalCount.isDefined, "x-total-count was absent from a paged listing")

  test("a page past the end is an empty page rather than a 404, exactly as HAZARDS.md measured"):
    live: codeberg =>
      val window = PageParams(orFail(PageNumber.from(99999)), orFail(PageSize.from(1)))

      codeberg.issues.list(Handle, Name, IssueQuery.Empty, window).map: page =>
        assertEquals(page.size, 0)
        assertEquals(page.isLast, true)

  test("a repository that does not exist is a 404 carrying Forgejo's errors array"):
    live: codeberg =>
      codeberg.repos.attempt.get(orFail(Owner.from("definitely")), orFail(RepoName.from("nonexistent-xyz"))).map:
        case Left(CodebergError.Api(_, status, body)) =>
          assertEquals(status, 404)
          assert(body.errors.nonEmpty, s"the 404 carried no errors array, only ${body.message}")
        case other                                    =>
          fail(s"expected an Api failure, got $other")

  // --- helpers ---------------------------------------------------------------

  /** Runs `use` against the live client, or skips the test visibly when `CODEBERG_IT` is not set to `1`. */
  private def live[A](use: CodebergClient => Future[A]): Future[A] =
    assume(LiveSmoke.enabled, LiveSmoke.SkipMessage)
    client.get() match
      case Some(open) => use(open)
      case None       => fail("the live client was not built even though the suite is enabled")

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid test input: ${Reasons.invalid(error)}")
