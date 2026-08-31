package com.worxbend.codeberg4s.it

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.CreateIssue
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.issues.LifecycleState
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize

import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

import java.util.concurrent.atomic.AtomicReference

/** The whole library against a real Forgejo, started for this suite and thrown away after it.
  *
  * ==What only a live instance can prove==
  *
  * Every other suite in this repository answers from a `BackendStub` or a captured fixture, which means every one of
  * them agrees with whatever the fixture author believed. Three beliefs are load-bearing enough to be worth paying a
  * container for:
  *
  *   - '''the `Link` header exists'''. `docs/HAZARDS.md` §5 measured it on codeberg.org and the pagination driver reads
  *     `rel="next"` and nothing else. If Forgejo stopped sending it, every stub-driven paging test would still pass and
  *     every real listing would stop after one page. [[walk]] pages a repository to its end and would hang or truncate
  *     if the header went away.
  *   - '''`Authorization: token …` is the scheme Forgejo accepts'''. The spec calls it `AuthorizationHeaderToken` and
  *     documents the literal word `token`; a stub accepts any header at all. The token this suite issues is only usable
  *     if the transport spells the scheme the way the server expects.
  *   - '''the DTOs decode what the server sends today''', rather than what it sent when the golden fixture was
  *     captured. Every assertion below reads a field off a domain model that a hand-written reader produced from a live
  *     payload.
  *
  * ==Not part of the default run==
  *
  * This suite needs a Docker daemon and a few hundred megabytes of image, so `verify.sh` never reaches it and every
  * test carries [[Integration.Tag]]. See [[IntegrationSuite]] for both mechanisms.
  *
  * ==Ordering==
  *
  * The container, the administrator, the token and the repository are created once for the suite. Tests that create
  * issues therefore accumulate them, so no assertion here may depend on an exact issue count for the repository as a
  * whole — [[walk]] is written against what the instance reports rather than against a number this file knows.
  */
final class ForgejoContainerSuite extends IntegrationSuite with TestContainerForAll:

  override val containerDef: GenericContainer.Def[GenericContainer] = ForgejoContainer.definition

  /** Long enough to pull the image on a cold machine; a container suite is not a unit test. */
  override def munitTimeout: Duration = 10.minutes

  private given ExecutionContext = munitExecutionContext

  /** How many pages [[walk]] may fetch before it gives up. Bounded so a missing `rel="next"` truncates rather than
    * hangs, which reports as a count mismatch and names the defect.
    */
  private val PageBudget: Int = 32

  /** The bootstrapped instance, or the reason it could not be bootstrapped.
    *
    * An `AtomicReference` rather than a `var` because `SCALA_CODE_STYLE.md` bans `var` outright, and because munit's
    * lifecycle hooks and its test bodies are not guaranteed to run on the same thread.
    */
  private val bootstrapped: AtomicReference[Option[Either[String, ForgejoInstance]]] = AtomicReference(None)

  override def afterContainersStart(containers: Containers): Unit =
    bootstrapped.set(Some(ForgejoInstance.bootstrap(containers)))

  override def beforeContainersStop(containers: Containers): Unit =
    bootstrapped.getAndSet(None).foreach(outcome => outcome.foreach(instance => instance.close()))

  // --- the instance answers at all -------------------------------------------

  test("GET /version answers, so the base URI really is a Forgejo API root"):
    forgejo.client.version.get().map: version =>
      assert(version.raw.nonEmpty, "the instance reported an empty version string")

  test("the issued token authenticates, which is only true if the scheme is `Authorization: token`"):
    forgejo.client.users.current().map: user =>
      assertEquals(user.login, ForgejoContainer.Admin.username)
      assertEquals(user.isAdmin, true)

  test("the repository created for this run decodes from a live payload"):
    forgejo.client.repos.get(forgejo.owner, forgejo.repository).map: repository =>
      assertEquals(repository.fullName, s"${ForgejoContainer.Admin.username}/${ForgejoContainer.RepositoryName}")
      assertEquals(repository.slug.name.value, ForgejoContainer.RepositoryName)
      assertEquals(repository.defaultBranch, Some(ForgejoContainer.DefaultBranch))
      assertEquals(repository.isPrivate, false)
      assertEquals(repository.isEmpty, false)
      assertEquals(repository.owner.login, ForgejoContainer.Admin.username)

  // --- issues ----------------------------------------------------------------

  test("an issue created through the client reads back by its number"):
    val command = orFail(CreateIssue.of("a live issue")).withBody("filed by the integration suite")

    for
      created <- forgejo.client.issues.create(forgejo.owner, forgejo.repository, command)
      read    <- forgejo.client.issues.get(forgejo.owner, forgejo.repository, created.number)
    yield
      assertEquals(read.number.value, created.number.value)
      assertEquals(read.title, "a live issue")
      assertEquals(read.body, Some("filed by the integration suite"))
      assertEquals(read.state, LifecycleState.Open)
      assertEquals(read.isPullRequest, false)
      assertEquals(read.author.map(user => user.login), Some(ForgejoContainer.Admin.username))

  test("a full page still reports a next one, because the Link header decides and not the item count"):
    val window = PageParams(PageNumber.First, orFail(PageSize.from(2)))

    for
      _     <- fileIssues(Vector("paging one", "paging two", "paging three"))
      first <- forgejo.client.issues.list(forgejo.owner, forgejo.repository, IssueQuery.Empty, window)
    yield
      assertEquals(first.size, 2, "the instance did not honour limit=2")
      assertEquals(first.isLast, false, "a full page reported itself as the last one — the Link header was not read")
      assertEquals(first.nextPage.map(page => page.value), Some(2))
      assert(first.totalCount.exists(total => total >= 3), s"x-total-count was ${first.totalCount}")

  test("paging terminates on rel=next disappearing, and sees every issue exactly once"):
    val window = PageParams(PageNumber.First, orFail(PageSize.from(2)))

    for
      first     <- forgejo.client.issues.list(forgejo.owner, forgejo.repository, IssueQuery.Empty, window)
      collected <- walk(window, PageBudget, Vector.empty)
    yield
      val numbers = collected.map(issue => issue.number.value)
      assertEquals(numbers.distinct.size, numbers.size, "an issue was returned on two different pages")
      assertEquals(Some(numbers.size), first.totalCount, "the walk did not collect x-total-count issues")

  // --- failure ---------------------------------------------------------------

  test("a repository that does not exist is a 404 both rails agree about"):
    val missing = orFail(RepoName.from("no-such-repository"))

    for
      raised <- forgejo.client.repos.get(forgejo.owner, missing).failed
      typed  <- forgejo.client.repos.attempt.get(forgejo.owner, missing)
    yield
      assertEquals(statusOf(typed), 404)
      assertRailsAgree(raised, typed)

  // --- helpers ---------------------------------------------------------------

  /** The bootstrapped instance, or a failure that names the setup step that broke. */
  private def forgejo: ForgejoInstance =
    bootstrapped.get() match
      case Some(Right(instance)) => instance
      case Some(Left(reason))    => fail(s"the Forgejo instance could not be prepared: $reason")
      case None                  => fail("the Forgejo container was never started")

  /** Files issues one after another, because a repository's issue numbers are allocated in order. */
  private def fileIssues(titles: Vector[String]): Future[Vector[Issue]] =
    titles.foldLeft(Future.successful(Vector.empty[Issue])): (sofar, title) =>
      sofar.flatMap: created =>
        forgejo.client
          .issues
          .create(forgejo.owner, forgejo.repository, orFail(CreateIssue.of(title)))
          .map(issue => created :+ issue)

  /** Follows `rel="next"` to the end of the collection, or until `budget` pages have been read. */
  private def walk(params: PageParams, budget: Int, seen: Vector[Issue]): Future[Vector[Issue]] =
    if budget <= 0 then Future.successful(seen)
    else
      forgejo.client.issues.list(forgejo.owner, forgejo.repository, IssueQuery.Empty, params).flatMap: page =>
        page.nextPage match
          case None       => Future.successful(seen ++ page.items)
          case Some(next) => walk(params.at(next), budget - 1, seen ++ page.items)

  private def assertRailsAgree[A](raised: Throwable, typed: Either[CodebergError, A]): Unit =
    (raised, typed) match
      case (CodebergException(convenience), Left(materialised)) =>
        assertEquals(statusOf(Left(materialised)), statusOf(Left(convenience)))
      case (convenience, materialised)                          =>
        fail(s"the rails disagreed: $convenience versus $materialised")

  private def statusOf[A](result: Either[CodebergError, A]): Int =
    result match
      case Left(CodebergError.Api(_, status, _)) => status
      case other                                 => fail(s"expected an Api failure, got $other")

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid test input: ${Reasons.invalid(error)}")
