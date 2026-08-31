package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.ClientSuiteHarness
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.users.Username

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend
import sttp.model.Header

import munit.FunSuite

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import java.time.Instant
import java.time.LocalDate

/** [[UserSocialApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which query parameters and which body are sent, which calls may be
  * repeated, and what each rail does with a failure. Decoding itself is asserted in `modules/codec`, so the payloads
  * here are small hand-written bodies chosen to exercise a seam.
  *
  * '''No golden fixture backs this group.''' Every payload below was written from `spec/swagger.v1.json`; see the class
  * note on [[UserSocialApi]].
  */
final class UserSocialApiSuite extends FunSuite with ClientSuiteHarness:

  private val Handle: Username = orFail(Username.from("earl-warren"))

  private val Target: Username = orFail(Username.from("caesar"))

  private val Repo: Owner = orFail(Owner.from("forgejo"))

  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  // --- following ------------------------------------------------------------

  test("the signed-in follower listing dials /user/followers and pages it"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.followers(window(2, 25)).map: _ =>
        assertEquals(pathOf(backend), s"$Root/user/followers")
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))

  test("the signed-in following listing dials /user/following and decodes users"):
    val backend = RecordingBackend(responding(200, UserSocialApiSuite.UserListBody))

    onApi(backend): api =>
      api.following(PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$Root/user/following")
        assertEquals(page.items.map(_.login), Vector("earl-warren"))

  test("a listing ends where rel=next says it ends, not where a short page suggests"):
    onApi(responding(200, UserSocialApiSuite.UserListBody, UserSocialApiSuite.PagedHeaders)): api =>
      api.followers(window(1, 30)).map: page =>
        assertEquals(page.size, 1)
        assertEquals(page.totalCount, Some(97))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("a page past the end is an empty page, not a failure"):
    onApi(responding(200, "[]")): api =>
      api.following(PageParams.First).map(page => assertEquals(page.isLast, true))

  test("following an account is a PUT with no body"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.follow(Target).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(pathOf(backend), s"$Root/user/following/caesar")
        assertEquals(bodyOf(backend), NoBody)

  test("following is retried, because the end state after two attempts is the end state after one"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(204, "")))

    onApi(backend): api =>
      api.follow(Target).map(_ => assertEquals(attemptsOn(backend), 2, "the PUT was not retried"))

  test("unfollowing is a DELETE on the same path, and is retried for the mirror-image reason"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(204, "")))

    onApi(backend): api =>
      api.unfollow(Target).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/user/following/caesar")
        assertEquals(attemptsOn(backend), 2, "the DELETE was not retried")

  test("a 204 from the follow check means yes"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.isFollowing(Target).map: following =>
        assertEquals(methodOf(backend), "GET")
        assertEquals(pathOf(backend), s"$Root/user/following/caesar")
        assertEquals(following, true)

  test("a 404 from the follow check means no, and is not a failure on either rail"):
    onApi(responding(404, UserSocialApiSuite.NotFoundBody)): api =>
      for
        direct <- api.isFollowing(Target)
        typed  <- api.attempt.isFollowing(Target)
      yield
        assertEquals(direct, false)
        assertEquals(typed, Right(false))

  test("a 403 from the follow check is still a failure, so an unreadable account is not a negative answer"):
    onApi(responding(403, UserSocialApiSuite.ForbiddenBody)): api =>
      api.attempt.isFollowing(Target).map: outcome =>
        assert(outcome.isLeft, s"a 403 was reported as 'not following': $outcome")

  test("the third-party follow check names both accounts in the path"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.follows(Handle, Target).map: following =>
        assertEquals(pathOf(backend), s"$Root/users/earl-warren/following/caesar")
        assertEquals(following, true)

  test("a remote follow POSTs the target and is never retried"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(204, "")))
    val target  = orFail(RemoteFollowTarget.from("https://social.example/users/x"))

    onApi(backend): api =>
      api.attempt.followRemote(target).map: outcome =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/user/activitypub/follow")
        assertEquals(bodyOf(backend), """{"target":"https://social.example/users/x"}""")
        assert(outcome.isLeft, s"a 503 on a POST must not be retried into a success, got $outcome")
        assertEquals(attemptsOn(backend), 1, "the POST was retried")

  // --- stars and watches ----------------------------------------------------

  test("the signed-in star listing dials /user/starred and decodes repositories"):
    val backend = RecordingBackend(responding(200, UserSocialApiSuite.RepositoryListBody))

    onApi(backend): api =>
      api.starred(PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$Root/user/starred")
        assertEquals(page.items.map(_.slug.value), Vector("forgejo/forgejo"))

  test("another account's star listing names the account in the path"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.starredBy(Handle, window(3, 10)).map: _ =>
        assertEquals(pathOf(backend), s"$Root/users/earl-warren/starred")
        assertEquals(queryOf(backend), List("page" -> "3", "limit" -> "10"))

  test("starring is a PUT on the repository's star path, and is retried"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(204, "")))

    onApi(backend): api =>
      api.star(Repo, Name).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(pathOf(backend), s"$Root/user/starred/forgejo/forgejo")
        assertEquals(attemptsOn(backend), 2, "the PUT was not retried")

  test("unstarring is a DELETE on the same path"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.unstar(Repo, Name).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/user/starred/forgejo/forgejo")

  test("the star check answers yes on 204 and no on 404"):
    for
      yes <- onApi(responding(204, ""))(_.isStarred(Repo, Name))
      no  <- onApi(responding(404, UserSocialApiSuite.NotFoundBody))(_.isStarred(Repo, Name))
    yield
      assertEquals(yes, true)
      assertEquals(no, false)

  test("the watch listings dial subscriptions, which is a different set from the stars"):
    val mine   = RecordingBackend(responding(200, "[]"))
    val theirs = RecordingBackend(responding(200, "[]"))

    for
      _ <- onApi(mine)(_.subscriptions(PageParams.First))
      _ <- onApi(theirs)(_.subscriptionsOf(Handle, PageParams.First))
    yield
      assertEquals(pathOf(mine), s"$Root/user/subscriptions")
      assertEquals(pathOf(theirs), s"$Root/users/earl-warren/subscriptions")

  // --- blocks ---------------------------------------------------------------

  test("blocking and unblocking are both PUTs, on their own paths"):
    val blocking   = RecordingBackend(responding(204, ""))
    val unblocking = RecordingBackend(responding(204, ""))

    for
      _ <- onApi(blocking)(_.block(Target))
      _ <- onApi(unblocking)(_.unblock(Target))
    yield
      assertEquals(methodOf(blocking), "PUT")
      assertEquals(pathOf(blocking), s"$Root/user/block/caesar")
      assertEquals(methodOf(unblocking), "PUT")
      assertEquals(pathOf(unblocking), s"$Root/user/unblock/caesar")

  test("blocking is retried, because a block is a relationship and not a resource"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(204, "")))

    onApi(backend): api =>
      api.block(Target).map(_ => assertEquals(attemptsOn(backend), 2, "the PUT was not retried"))

  test("the blocked listing returns identifiers and timestamps, and has nowhere to put an account"):
    val backend = RecordingBackend(responding(200, UserSocialApiSuite.BlockedListBody))

    onApi(backend): api =>
      api.blocked(PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$Root/user/list_blocked")
        assertEquals(page.items.map(_.blockId.value), Vector(99L))

  // --- work -----------------------------------------------------------------

  test("the stopwatch listing decodes a timer and the issue it belongs to"):
    val backend = RecordingBackend(responding(200, UserSocialApiSuite.StopWatchListBody))

    onApi(backend): api =>
      api.stopWatches(PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$Root/user/stopwatches")
        assertEquals(page.items.map(_.issueIndex), Vector(42L))
        assertEquals(page.items.map(_.elapsed), Vector(3723.seconds))

  test("the tracked-time listing sends its window before its paging"):
    val backend = RecordingBackend(responding(200, "[]"))
    val bounds  = TrackedTimeWindow.Empty.updatedSince(Instant.parse("2026-08-01T10:11:12Z"))

    onApi(backend): api =>
      api.trackedTimes(bounds, window(2, 50)).map: _ =>
        assertEquals(pathOf(backend), s"$Root/user/times")
        assertEquals(
          queryOf(backend),
          List("since" -> "2026-08-01T10:11:12Z", "page" -> "2", "limit" -> "50"),
        )

  test("an unbounded tracked-time listing sends only its paging"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .trackedTimes(TrackedTimeWindow.Empty, PageParams.First)
        .map(_ => assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30")))

  test("the activity feed sends the hyphenated filter and the date before its paging"):
    val backend = RecordingBackend(responding(200, UserSocialApiSuite.ActivityListBody))
    val query   = ActivityFeedQuery.Empty.performedByTheAccount.on(LocalDate.of(2026, 8, 1))

    onApi(backend): api =>
      api.activityFeeds(Handle, query, PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$Root/users/earl-warren/activities/feeds")
        assertEquals(
          queryOf(backend),
          List("only-performed-by" -> "true", "date" -> "2026-08-01", "page" -> "1", "limit" -> "30"),
        )
        assertEquals(page.items.map(_.id.value), Vector(7L))

  test("the heatmap takes no paging parameters and answers a plain vector"):
    val backend = RecordingBackend(responding(200, UserSocialApiSuite.HeatmapBody))

    onApi(backend): api =>
      api.heatmap(Handle).map: buckets =>
        assertEquals(pathOf(backend), s"$Root/users/earl-warren/heatmap")
        assertEquals(queryOf(backend), Nil)
        assertEquals(buckets.map(_.contributions), Vector(7L, 2L))
        assertEquals(buckets.map(_.at), Vector(Instant.ofEpochSecond(1785000000L), Instant.ofEpochSecond(1785086400L)))

  // --- failures -------------------------------------------------------------

  test("a 403 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(403, UserSocialApiSuite.ForbiddenBody)): api =>
      api.followers(PageParams.First).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (UserSocialApi.FollowersOperation, 403, Some(UserSocialApiSuite.ForbiddenText)))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 403 reaches the typed rail as a Left reporting the very same failure"):
    onApi(responding(403, UserSocialApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.followers(PageParams.First).failed
        typed  <- api.attempt.followers(PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a unit-returning write as well, so the choice of rail is only a choice of style"):
    onApi(responding(403, UserSocialApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.block(Target).failed
        typed  <- api.attempt.block(Target)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on the status-only check too"):
    onApi(responding(401, UserSocialApiSuite.UnauthorizedBody)): api =>
      for
        raised <- api.isStarred(Repo, Name).failed
        typed  <- api.attempt.isStarred(Repo, Name)
      yield assertRailsAgree(raised, typed)

  test("a 400 is an Api failure too — Forgejo uses it for validation alongside 422"):
    onApi(responding(400, UserSocialApiSuite.ValidationBody)): api =>
      api.attempt.blocked(PageParams.First).map:
        case Left(CodebergError.Api(_, status, _)) => assertEquals(status, 400)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onApi(responding(200, """[{"created_at": null}]""")): api =>
      api.attempt.blocked(PageParams.First).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[0].block_id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a failure carries the operation id of the endpoint it came from, so an alert can name it"):
    onApi(responding(403, UserSocialApiSuite.ForbiddenBody)): api =>
      api.attempt.heatmap(Handle).map(outcome => assertEquals(operationOf(outcome), UserSocialApi.HeatmapOperation))

  // --- harness --------------------------------------------------------------

  /** Builds the API under test on a pipeline over `backend`, releasing the timer whatever happens. */
  private def onApi[A](backend: Backend[Future])(use: UserSocialApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(UserSocialApi(pipeline)))

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour.
  *
  * All of them are hand-written from `spec/swagger.v1.json`; no endpoint in this group has a golden capture.
  */
object UserSocialApiSuite:

  private val UserListBody: String =
    """[{"id": 3, "login": "earl-warren", "full_name": "", "email": "", "is_admin": false}]"""

  private val RepositoryListBody: String =
    """[{"id": 7, "name": "forgejo", "full_name": "forgejo/forgejo",
      | "owner": {"id": 3, "login": "forgejo"}, "private": false}]""".stripMargin

  private val BlockedListBody: String =
    """[{"block_id": 99, "created_at": "2026-07-30T21:14:15+02:00"}]"""

  private val StopWatchListBody: String =
    """[{"issue_index": 42, "issue_title": "the timer runs", "repo_name": "forgejo",
      | "repo_owner_name": "forgejo", "seconds": 3723, "duration": "1h2m3s"}]""".stripMargin

  private val ActivityListBody: String =
    """[{"id": 7, "op_type": "create_issue", "ref_name": "main", "is_private": false}]"""

  private val HeatmapBody: String =
    """[{"timestamp": 1785000000, "contributions": 7}, {"timestamp": 1785086400, "contributions": 2}]"""

  /** The paging headers `docs/HAZARDS.md` §5 captured verbatim, with the host and path swapped for this endpoint's. */
  private val PagedHeaders: List[Header] =
    List(
      Header("X-Total-Count", "97"),
      Header(
        "Link",
        "<https://forge.example/api/v1/user/followers?limit=30&page=2>; rel=\"next\"," +
          "<https://forge.example/api/v1/user/followers?limit=30&page=4>; rel=\"last\"",
      ),
    )

  private val ForbiddenText: String = "token does not have at least one of required scope(s): [read:user]"

  private val ForbiddenBody: String = s"""{"message":"$ForbiddenText"}"""

  private val UnauthorizedBody: String = """{"message":"token is required","url":"https://codeberg.org/api/swagger"}"""

  private val NotFoundBody: String =
    """{"message":"user redirect does not exist [name: caesar]","url":"https://codeberg.org/api/swagger"}"""

  private val ValidationBody: String =
    """{"message":"ListBlockedUsers","url":"https://codeberg.org/api/swagger","errors":["invalid page"]}"""
