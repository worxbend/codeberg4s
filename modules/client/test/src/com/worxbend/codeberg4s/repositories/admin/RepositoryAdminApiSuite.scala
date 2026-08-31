package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.client.FutureExec
import com.worxbend.codeberg4s.client.FutureTimer
import com.worxbend.codeberg4s.codec.ApiErrorBodyCodec
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.Telemetry
import com.worxbend.codeberg4s.issues.TrackedTimeQuery
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.ContentPath
import com.worxbend.codeberg4s.repositories.gitdata.RefName
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.transport.SttpHttpPort
import com.worxbend.codeberg4s.users.Username

import sttp.client4.Backend
import sttp.client4.NoBody
import sttp.client4.Response
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.client4.testing.StubBody
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import java.time.LocalDate

/** [[RepositoryAdminApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which query parameters and which body are sent, which calls may be
  * repeated, and what each rail does with a failure. Decoding itself is asserted in `modules/codec`, so the payloads
  * here are small hand-written bodies chosen to exercise a seam.
  *
  * '''No golden fixture backs this group.''' Every payload below was written from `spec/swagger.v1.json`; see the class
  * note on [[RepositoryAdminApi]].
  */
final class RepositoryAdminApiSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val Handle: Owner = orFail(Owner.from("forgejo"))

  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  private val Mirror: MirrorName = orFail(MirrorName.from("remote_a1b2c3"))

  private val Branch: BranchName = orFail(BranchName.from("release/v1"))

  private val Path: ContentPath = orFail(ContentPath.from("docs/README.md"))

  private val Sha: CommitSha = orFail(CommitSha.from("abcd1234"))

  private val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  private val Root: String = "https://forge.example/api/v1/repos/forgejo/forgejo"

  // --- the repository itself ------------------------------------------------

  test("repos.admin.create posts to the current user's repositories, not to a slug"):
    val backend = RecordingBackend(responding(201, RepositoryAdminApiSuite.RepositoryBody))

    onBackend(backend): api =>
      api.create(CreateRepository.named(Name).initialised).map: repository =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/user/repos")
        assertEquals(bodyOf(backend), """{"name":"forgejo","private":false,"auto_init":true,"template":false}""")
        assertEquals(repository.slug.name.value, "forgejo")

  test("repos.admin.getById addresses the instance-wide id, which survives a rename"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.RepositoryBody))

    onBackend(backend): api =>
      api.byId(orFail(RepositoryId.from(12L))).map: _ =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repositories/12")

  test("repos.admin.edit is a PATCH carrying only the fields the command set"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.RepositoryBody))

    onBackend(backend): api =>
      api.edit(Handle, Name, EditRepository.Empty.archivedRepository).map: _ =>
        assertEquals(methodOf(backend), "PATCH")
        assertEquals(pathOf(backend), Root)
        assertEquals(bodyOf(backend), """{"archived":true}""")

  test("an edit is never retried, because a rename would make the retry address something else"):
    val backend = retryProbe(200, RepositoryAdminApiSuite.RepositoryBody)

    onBackend(backend): api =>
      api.attempt
        .edit(Handle, Name, EditRepository.Empty.renamedTo(Name))
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the PATCH was retried"))

  test("repos.admin.delete is a DELETE that reads no body"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.delete(Handle, Name).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), Root)

  test("deleting a repository is never retried, because the name is free the instant it succeeds"):
    val backend = retryProbe(204, "")

    onBackend(backend): api =>
      api.attempt
        .delete(Handle, Name)
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the destructive DELETE was retried"))

  test("repos.admin.migrate posts to the instance-wide migrate endpoint"):
    val backend = RecordingBackend(responding(201, RepositoryAdminApiSuite.RepositoryBody))
    val command = MigrateRepository
      .from("https://github.com/a/b.git", Name)
      .usingService(MigrationService.GitHub)
      .authenticatedWith(orFail(RemoteCredential.from("ghp_SECRET")))

    onBackend(backend): api =>
      api.migrate(command).map: _ =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/migrate")
        assert(bodyOf(backend).contains(""""auth_token":"ghp_SECRET""""), "the credential must reach the body")

  test("a migration credential reaches the request bytes and nothing else"):
    val backend = RecordingBackend(responding(403, RepositoryAdminApiSuite.ForbiddenBody))
    val command = MigrateRepository
      .from("https://github.com/a/b.git", Name)
      .authenticatedWith(orFail(RemoteCredential.from("ghp_SECRET")))

    onBackend(backend): api =>
      api.attempt.migrate(command).map: outcome =>
        val rendered = outcome.fold(_.describe, _.toString)

        assert(!rendered.contains("ghp_SECRET"), s"the failure leaked the credential: $rendered")
        assert(!dialled(backend).contains("ghp_SECRET"), "the credential must not reach the URI")

  test("repos.admin.transfer.start posts the new owner to the transfer endpoint"):
    val backend = RecordingBackend(responding(202, RepositoryAdminApiSuite.RepositoryBody))

    onBackend(backend): api =>
      api.transfer(Handle, Name, TransferRepository.to(Handle)).map: _ =>
        assertEquals(pathOf(backend), s"$Root/transfer")
        assertEquals(bodyOf(backend), """{"new_owner":"forgejo"}""")

  test("accepting and rejecting a transfer are bodiless POSTs to their own sub-paths"):
    val accepting = RecordingBackend(responding(202, RepositoryAdminApiSuite.RepositoryBody))
    val rejecting = RecordingBackend(responding(200, RepositoryAdminApiSuite.RepositoryBody))

    for
      _ <- onBackend(accepting)(api =>
             api.acceptTransfer(Handle, Name).map(_ => assertEquals(pathOf(accepting), s"$Root/transfer/accept"))
           )
      _ <- onBackend(rejecting)(api =>
             api.rejectTransfer(Handle, Name).map(_ => assertEquals(pathOf(rejecting), s"$Root/transfer/reject"))
           )
    yield ()

  test("repos.admin.convert is a bodiless POST"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.RepositoryBody))

    onBackend(backend): api =>
      api.convert(Handle, Name).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/convert")

  // --- mirrors --------------------------------------------------------------

  test("repos.admin.mirror.sync posts to mirror-sync and ignores the empty 200 body"):
    val backend = RecordingBackend(responding(200, ""))

    onBackend(backend): api =>
      api.syncMirror(Handle, Name).map: _ =>
        assertEquals(pathOf(backend), s"$Root/mirror-sync")

  test("repos.admin.pushMirrors.list pages the mirrors"):
    val backend = RecordingBackend(responding(200, "[]"))

    onBackend(backend): api =>
      api.pushMirrors(Handle, Name, window(2, 25)).map: _ =>
        assertEquals(pathOf(backend), s"$Root/push_mirrors")
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))

  test("a mirror listing ends where rel=next says it ends, not where a short page suggests"):
    onStub(responding(200, RepositoryAdminApiSuite.PushMirrorListBody, RepositoryAdminApiSuite.PagedHeaders)): api =>
      api.pushMirrors(Handle, Name, window(1, 30)).map: page =>
        assertEquals(page.size, 1)
        assertEquals(page.totalCount, Some(97))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("a page past the end is an empty page, not a failure"):
    onStub(responding(200, "[]")): api =>
      api.pushMirrors(Handle, Name, PageParams.First).map: page =>
        assertEquals(page.items, Vector.empty[PushMirror])
        assertEquals(page.isLast, true)

  test("a single mirror read addresses it by its generated remote name"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.PushMirrorBody))

    onBackend(backend): api =>
      api.pushMirror(Handle, Name, Mirror).map: mirror =>
        assertEquals(pathOf(backend), s"$Root/push_mirrors/remote_a1b2c3")
        assertEquals(mirror.remoteName.value, "remote_a1b2c3")

  test("adding a push mirror posts the remote address and answers the stored mirror"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.PushMirrorBody))

    onBackend(backend): api =>
      api.addPushMirror(Handle, Name, CreatePushMirror.to("https://example.test/a.git").overSsh).map: _ =>
        assertEquals(pathOf(backend), s"$Root/push_mirrors")
        assert(bodyOf(backend).contains(""""use_ssh":true"""), bodyOf(backend))

  test("deleting a push mirror is retried, because the generated remote name is never handed out again"):
    val backend = retryProbe(204, "")

    onBackend(backend): api =>
      api.deletePushMirror(Handle, Name, Mirror).map(_ => assertEquals(backend.allInteractions.size, 2))

  test("repos.admin.pushMirrors.sync posts to the hyphenated sync path"):
    val backend = RecordingBackend(responding(200, ""))

    onBackend(backend): api =>
      api.syncPushMirrors(Handle, Name).map(_ => assertEquals(pathOf(backend), s"$Root/push_mirrors-sync"))

  // --- fork syncing ---------------------------------------------------------

  test("the default fork-sync read and the branch one differ only by the branch segments"):
    val plain    = RecordingBackend(responding(200, RepositoryAdminApiSuite.SyncForkBody))
    val branched = RecordingBackend(responding(200, RepositoryAdminApiSuite.SyncForkBody))

    for
      _ <- onBackend(plain)(api =>
             api.forkSyncInfo(Handle, Name).map(_ => assertEquals(pathOf(plain), s"$Root/sync_fork"))
           )
      _ <- onBackend(branched)(api =>
             api
               .branchForkSyncInfo(Handle, Name, Branch)
               .map(_ => assertEquals(pathOf(branched), s"$Root/sync_fork/release/v1"))
           )
    yield ()

  test("a slashed branch name reaches the wire as real separators, not as one escaped segment"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.syncForkBranch(Handle, Name, Branch).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/sync_fork/release/v1")

  test("a fork-sync read reports how far behind the fork is"):
    onStub(responding(200, RepositoryAdminApiSuite.SyncForkBody)): api =>
      api.forkSyncInfo(Handle, Name).map: info =>
        assertEquals(info.commitsBehind, 12L)
        assertEquals(info.isBehind, true)

  // --- watching -------------------------------------------------------------

  test("reading a subscription is a GET on the subscription path"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.WatchBody))

    onBackend(backend): api =>
      api.subscription(Handle, Name).map: status =>
        assertEquals(pathOf(backend), s"$Root/subscription")
        assertEquals(status.isNotifying, true)

  test("watching is a PUT that sends no body, because Forgejo declares none"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.WatchBody))

    onBackend(backend): api =>
      api.watch(Handle, Name).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(sendsNoBody(backend), true)

  test("watching is retried, because it sets one named subscription and creates nothing"):
    val backend = retryProbe(200, RepositoryAdminApiSuite.WatchBody)

    onBackend(backend): api =>
      api.watch(Handle, Name).map(_ => assertEquals(backend.allInteractions.size, 2))

  test("unwatching is retried for the same reason, and answers 204"):
    val backend = retryProbe(204, "")

    onBackend(backend): api =>
      api.unwatch(Handle, Name).map: _ =>
        assertEquals(backend.allInteractions.size, 2)
        assertEquals(methodOf(backend), "DELETE")

  // --- people ---------------------------------------------------------------

  test("assignees and reviewers are separate unpaged listings on separate paths"):
    val assigning = RecordingBackend(responding(200, RepositoryAdminApiSuite.UserListBody))
    val reviewing = RecordingBackend(responding(200, RepositoryAdminApiSuite.UserListBody))

    for
      _ <- onBackend(assigning): api =>
             api.assignees(Handle, Name).map: people =>
               assertEquals(pathOf(assigning), s"$Root/assignees")
               assertEquals(queryOf(assigning), Nil)
               assertEquals(people.map(_.login), Vector("octocat"))
      _ <- onBackend(reviewing)(api =>
             api.reviewers(Handle, Name).map(_ => assertEquals(pathOf(reviewing), s"$Root/reviewers"))
           )
    yield ()

  test("stargazers and subscribers are paged listings on separate paths"):
    val starring = RecordingBackend(responding(200, RepositoryAdminApiSuite.UserListBody))
    val watching = RecordingBackend(responding(200, RepositoryAdminApiSuite.UserListBody))

    for
      _ <- onBackend(starring): api =>
             api.stargazers(Handle, Name, window(3, 10)).map: _ =>
               assertEquals(pathOf(starring), s"$Root/stargazers")
               assertEquals(queryOf(starring), List("page" -> "3", "limit" -> "10"))
      _ <-
        onBackend(watching)(api =>
          api.subscribers(Handle, Name, PageParams.First).map(_ => assertEquals(pathOf(watching), s"$Root/subscribers"))
        )
    yield ()

  // --- branches -------------------------------------------------------------

  test("creating a branch posts the new name to the branches collection"):
    val backend = RecordingBackend(responding(201, RepositoryAdminApiSuite.BranchBody))

    onBackend(backend): api =>
      api.createBranch(Handle, Name, CreateBranch.named(Branch).startingAt("main")).map: created =>
        assertEquals(pathOf(backend), s"$Root/branches")
        assertEquals(bodyOf(backend), """{"new_branch_name":"release/v1","old_ref_name":"main"}""")
        assertEquals(created.name.value, "release/v1")

  test("deleting a branch is never retried, because a branch name is reused"):
    val backend = retryProbe(204, "")

    onBackend(backend): api =>
      api.attempt
        .deleteBranch(Handle, Name, Branch)
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the branch DELETE was retried"))

  test("renaming a branch is a PATCH on the branch's own path, answering nothing"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.renameBranch(Handle, Name, Branch, RenameBranch(orFail(BranchName.from("v1")))).map: _ =>
        assertEquals(methodOf(backend), "PATCH")
        assertEquals(pathOf(backend), s"$Root/branches/release/v1")
        assertEquals(bodyOf(backend), """{"name":"v1"}""")

  // --- contents -------------------------------------------------------------

  test("the root contents listing sends ref only when the caller named one"):
    val defaulted = RecordingBackend(responding(200, "[]"))
    val pinned    = RecordingBackend(responding(200, "[]"))

    for
      _ <- onBackend(defaulted): api =>
             api.contents(Handle, Name, None).map: _ =>
               assertEquals(pathOf(defaulted), s"$Root/contents")
               assertEquals(queryOf(defaulted), Nil)
      _ <- onBackend(pinned)(api =>
             api
               .contents(Handle, Name, Some(orFail(RefName.from("main"))))
               .map(_ => assertEquals(queryOf(pinned), List("ref" -> "main")))
           )
    yield ()

  test("creating a file posts base64 to the file's own path"):
    val backend = RecordingBackend(responding(201, RepositoryAdminApiSuite.FileResponseBody))

    onBackend(backend): api =>
      api.createFile(Handle, Name, Path, CreateFile.of(FileBytes.ofText("hello"))).map: change =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/contents/docs/README.md")
        assert(bodyOf(backend).contains(""""content":"aGVsbG8=""""), bodyOf(backend))
        assertEquals(change.commit.map(_.sha.short), Some("aaaaaaa"))

  test("updating a file is a PUT carrying the sha guard"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.FileResponseBody))

    onBackend(backend): api =>
      api.updateFile(Handle, Name, Path, UpdateFile.of(FileBytes.ofText("hi"), Sha)).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assert(bodyOf(backend).contains(""""sha":"abcd1234""""), bodyOf(backend))

  test("an update is never retried, because after a lost success the sha guard can only report a conflict"):
    val backend = retryProbe(200, RepositoryAdminApiSuite.FileResponseBody)

    onBackend(backend): api =>
      api.attempt
        .updateFile(Handle, Name, Path, UpdateFile.of(FileBytes.ofText("hi"), Sha))
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the guarded PUT was retried"))

  test("deleting a file is a DELETE that carries a body, which the spec requires"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.FileDeleteBody))

    onBackend(backend): api =>
      api.deleteFile(Handle, Name, Path, DeleteFile.of(Sha)).map: change =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/contents/docs/README.md")
        assert(bodyOf(backend).contains(""""sha":"abcd1234""""), bodyOf(backend))
        assertEquals(change.content, None)

  test("a file delete is never retried, because it creates a commit"):
    val backend = retryProbe(200, RepositoryAdminApiSuite.FileDeleteBody)

    onBackend(backend): api =>
      api.attempt
        .deleteFile(Handle, Name, Path, DeleteFile.of(Sha))
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the file DELETE was retried"))

  test("a batch write posts to the contents collection and answers every entry it touched"):
    val backend = RecordingBackend(responding(201, RepositoryAdminApiSuite.FilesResponseBody))
    val batch   = ChangeFiles
      .of(FileOperation.Create(orFail(ContentPath.from("a.txt")), FileBytes.ofText("a")))
      .and(FileOperation.Delete(orFail(ContentPath.from("b.txt")), Sha))

    onBackend(backend): api =>
      api.changeFiles(Handle, Name, batch).map: changed =>
        assertEquals(pathOf(backend), s"$Root/contents")
        assert(bodyOf(backend).contains(""""operation":"create""""), bodyOf(backend))
        assertEquals(changed.files.map(_.meta.name), Vector("a.txt"))

  // --- avatar ---------------------------------------------------------------

  test("updating an avatar posts base64 in a JSON body, never a multipart part"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.updateAvatar(Handle, Name, orFail(AvatarImage.ofBase64("aGk="))).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/avatar")
        assertEquals(bodyOf(backend), """{"image":"aGk="}""")

  test("deleting an avatar is retried, because it names one repository and creates nothing"):
    val backend = retryProbe(204, "")

    onBackend(backend): api =>
      api.deleteAvatar(Handle, Name).map(_ => assertEquals(backend.allInteractions.size, 2))

  // --- reporting ------------------------------------------------------------

  test("the activity feed sends its calendar day before the paging parameters"):
    val backend = RecordingBackend(responding(200, "[]"))

    onBackend(backend): api =>
      api.activityFeed(Handle, Name, Some(LocalDate.of(2026, 8, 1)), window(1, 20)).map: _ =>
        assertEquals(pathOf(backend), s"$Root/activities/feeds")
        assertEquals(queryOf(backend), List("date" -> "2026-08-01", "page" -> "1", "limit" -> "20"))

  test("the activity feed omits the day when the caller named none"):
    val backend = RecordingBackend(responding(200, "[]"))

    onBackend(backend): api =>
      api.activityFeed(Handle, Name, None, PageParams.First).map: _ =>
        assertEquals(queryOf(backend).map((key, _) => key), List("page", "limit"))

  test("the language statistics decode a bare object into a breakdown"):
    val backend = RecordingBackend(responding(200, """{"Go": 100, "Scala": 20}"""))

    onBackend(backend): api =>
      api.languages(Handle, Name).map: breakdown =>
        assertEquals(pathOf(backend), s"$Root/languages")
        assertEquals(breakdown.dominant, Some("Go"))
        assertEquals(breakdown.total, 120L)

  test("the pin-allowance read answers both flags"):
    val backend = RecordingBackend(responding(200, """{"issues": true, "pull_requests": false}"""))

    onBackend(backend): api =>
      api.newPinAllowed(Handle, Name).map: allowed =>
        assertEquals(pathOf(backend), s"$Root/new_pin_allowed")
        assertEquals(allowed, IssuePinsAllowed(issues = true, pullRequests = false))

  test("the pinned-issue listing is unpaged and sits under the issues path"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.IssueListBody))

    onBackend(backend): api =>
      api.pinnedIssues(Handle, Name).map: pinned =>
        assertEquals(pathOf(backend), s"$Root/issues/pinned")
        assertEquals(queryOf(backend), Nil)
        assertEquals(pinned.map(_.number.value), Vector(42L))

  test("the signing key is returned verbatim, because it is not JSON"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.ArmoredKey))

    onBackend(backend): api =>
      api.signingKey(Handle, Name).map: key =>
        assertEquals(pathOf(backend), s"$Root/signing-key.gpg")
        assertEquals(key.map(_.armored), Some(RepositoryAdminApiSuite.ArmoredKey))

  test("a repository that signs nothing answers an empty body, which is a success and not a failure"):
    onStub(responding(200, "")): api =>
      api.signingKey(Handle, Name).map(key => assertEquals(key, None))

  test("the tracked-time listing sends the shared filters before the paging parameters"):
    val backend = RecordingBackend(responding(200, "[]"))
    val query   = TrackedTimeQuery.Empty.forUser("octocat")

    onBackend(backend): api =>
      api.trackedTimes(Handle, Name, query, window(1, 50)).map: _ =>
        assertEquals(pathOf(backend), s"$Root/times")
        assertEquals(queryOf(backend), List("user" -> "octocat", "page" -> "1", "limit" -> "50"))

  test("the per-user tracked-time listing is unpaged, as the spec's own response name says"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.TrackedTimeListBody))

    onBackend(backend): api =>
      api.trackedTimesFor(Handle, Name, orFail(Username.from("octocat"))).map: entries =>
        assertEquals(pathOf(backend), s"$Root/times/octocat")
        assertEquals(queryOf(backend), Nil)
        assertEquals(entries.map(_.id.value), Vector(5L))

  test("the topic search is instance-wide and unwraps the topics envelope"):
    val backend = RecordingBackend(responding(200, RepositoryAdminApiSuite.TopicSearchBody))

    onBackend(backend): api =>
      api.searchTopics("scala", window(1, 10)).map: page =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/topics/search")
        assertEquals(queryOf(backend), List("q" -> "scala", "page" -> "1", "limit" -> "10"))
        assertEquals(page.items.map(_.name), Vector("scala"))

  // --- failures -------------------------------------------------------------

  test("a 404 fails the convenience rail with a CodebergException carrying the Api failure"):
    onStub(responding(404, RepositoryAdminApiSuite.NotFoundBody)): api =>
      api.byId(orFail(RepositoryId.from(12L))).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (RepositoryAdminApi.GetByIdOperation, 404, Some("GetRepositoryByID")))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 404 reaches the typed rail as a Left reporting the very same failure"):
    onStub(responding(404, RepositoryAdminApiSuite.NotFoundBody)): api =>
      for
        raised <- api.byId(orFail(RepositoryId.from(12L))).failed
        typed  <- api.attempt.byId(orFail(RepositoryId.from(12L)))
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a paged listing failure as well, so the choice of rail is only a choice of style"):
    onStub(responding(403, RepositoryAdminApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.pushMirrors(Handle, Name, PageParams.First).failed
        typed  <- api.attempt.pushMirrors(Handle, Name, PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a unit-returning write as well"):
    onStub(responding(403, RepositoryAdminApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.delete(Handle, Name).failed
        typed  <- api.attempt.delete(Handle, Name)
      yield assertRailsAgree(raised, typed)

  test("a 423 is an Api failure — it is what every write against an archived repository answers"):
    onStub(responding(423, RepositoryAdminApiSuite.ArchivedBody)): api =>
      api.attempt.createFile(Handle, Name, Path, CreateFile.of(FileBytes.ofText("x"))).map:
        case Left(CodebergError.Api(_, status, _)) => assertEquals(status, 423)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("a 409 from a guarded update is an Api failure, which is how a concurrent edit surfaces"):
    onStub(responding(409, RepositoryAdminApiSuite.ConflictBody)): api =>
      api.attempt.updateFile(Handle, Name, Path, UpdateFile.of(FileBytes.ofText("x"), Sha)).map:
        case Left(CodebergError.Api(_, status, _)) => assertEquals(status, 409)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onStub(responding(200, """{"remote_address":"https://example.test"}""")): api =>
      api.attempt.pushMirror(Handle, Name, Mirror).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.remote_name")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a failure carries the operation id of the endpoint it came from, so an alert can name it"):
    onStub(responding(403, RepositoryAdminApiSuite.ForbiddenBody)): api =>
      api.attempt.deleteAvatar(Handle, Name).map: outcome =>
        assertEquals(operation(outcome), RepositoryAdminApi.DeleteAvatarOperation)

  // --- assertions -----------------------------------------------------------

  private def assertRailsAgree[A](raised: Throwable, typed: Either[CodebergError, A]): Unit =
    (raised, typed) match
      case (CodebergException(convenience), Left(materialised)) =>
        assertEquals(summary(materialised), summary(convenience))
      case (convenience, materialised)                          =>
        fail(s"the rails disagreed: $convenience versus $materialised")

  private def summary(error: CodebergError): (String, Int, Option[String]) =
    error match
      case CodebergError.Api(ctx, status, body) => (ctx.operation, status, body.message)
      case other                                => fail(s"expected an Api failure, got ${other.describe}")

  private def operation[A](result: Either[CodebergError, A]): String =
    result match
      case Left(CodebergError.Api(ctx, _, _)) => ctx.operation
      case other                              => fail(s"expected an Api failure, got $other")

  // --- harness --------------------------------------------------------------

  private def responding(status: Int, body: String): BackendStub[Future] =
    responding(status, body, Nil)

  private def responding(status: Int, body: String, headers: List[Header]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  /** A backend that answers `first` once and `rest` from then on — how a retry is made observable. */
  private def cycling(first: Response[StubBody], rest: Response[StubBody]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(first, rest)

  /** A recording backend that fails once with a retryable status and then succeeds.
    *
    * One interaction means the call was not repeated; two mean it was.
    */
  private def retryProbe(status: Int, body: String): Backend[Future] & RecordingBackend =
    RecordingBackend(
      cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust(body, StatusCode(status)))
    )

  private def dialled(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.toString
      case None               => fail("no request reached the backend")

  /** The dialled URI without its query string, written with `indexOf` because universal equality is banned. */
  private def pathOf(backend: RecordingBackend): String =
    val uri   = dialled(backend)
    val query = uri.indexOf('?')

    if query < 0 then uri else uri.take(query)

  private def queryOf(backend: RecordingBackend): List[(String, String)] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.params.toSeq.toList
      case None               => fail("no request reached the backend")

  private def methodOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.method.method
      case None               => fail("no request reached the backend")

  /** Whether the recorded request carried no body at all — sttp models that as `NoBody`, not as an empty string. */
  private def sendsNoBody(backend: RecordingBackend): Boolean =
    backend.allInteractions.headOption match
      case Some((request, _)) =>
        request.body match
          case NoBody => true
          case _      => false
      case None               => fail("no request reached the backend")

  private def bodyOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.body.show.stripPrefix("string: ")
      case None               => fail("no request reached the backend")

  private def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  private def onStub[A](backend: Backend[Future])(use: RepositoryAdminApi => Future[A]): Future[A] =
    onBackend(backend)(use)

  /** Builds the pipeline this group's API sits on, and releases the timer whatever the outcome. */
  private def onBackend[A](backend: Backend[Future])(use: RepositoryAdminApi => Future[A]): Future[A] =
    given Exec[Future] = FutureExec()

    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = RepositoryAdminApiSuite.PromptRetry)
    val timer  = FutureTimer()

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, config),
      config,
      timer,
      Telemetry.noOp[Future],
      ApiErrorBodyCodec.parse,
    )

    use(RepositoryAdminApi(pipeline)).transform: outcome =>
      timer.close()
      outcome

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour.
  *
  * All of them are hand-written from `spec/swagger.v1.json`; no endpoint in this group has a golden capture.
  */
object RepositoryAdminApiSuite:

  /** A retry policy with no real waiting, so a retry assertion does not cost a second. */
  private val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 2,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )

  private val PagedHeaders: List[Header] =
    List(
      Header("x-total-count", "97"),
      Header("link", """<https://forge.example/api/v1/x?page=2>; rel="next""""),
    )

  private val RepositoryBody: String =
    """{"id": 12, "name": "forgejo", "full_name": "forgejo/forgejo", "owner": {"id": 3, "login": "forgejo"}}"""

  private val BranchBody: String =
    """{"name": "release/v1", "commit": {"id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}"""

  private val PushMirrorBody: String =
    """{"remote_name": "remote_a1b2c3", "remote_address": "https://example.test/a.git", "sync_on_commit": true}"""

  private val PushMirrorListBody: String = s"[$PushMirrorBody]"

  private val SyncForkBody: String =
    """{"allowed": true, "commits_behind": 12, "base_commit": "aaaa", "fork_commit": "bbbb"}"""

  private val WatchBody: String = """{"subscribed": true, "ignored": false}"""

  private val UserListBody: String = """[{"id": 3, "login": "octocat"}]"""

  private val IssueListBody: String = """[{"id": 900, "number": 42, "title": "pinned", "state": "open"}]"""

  private val TrackedTimeListBody: String = """[{"id": 5, "time": 3600, "user_name": "octocat"}]"""

  private val TopicSearchBody: String =
    """{"topics": [{"id": 41, "topic_name": "scala", "repo_count": 137}]}"""

  private val FileResponseBody: String =
    """{"commit": {"sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
      | "content": {"name": "README.md", "path": "docs/README.md",
      |             "sha": "1111111111111111111111111111111111111111", "type": "file"}}""".stripMargin

  private val FileDeleteBody: String =
    """{"commit": {"sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}, "content": null}"""

  private val FilesResponseBody: String =
    """{"commit": {"sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
      | "files": [{"name": "a.txt", "path": "a.txt",
      |            "sha": "1111111111111111111111111111111111111111", "type": "file"}]}""".stripMargin

  private val ArmoredKey: String =
    "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nmQINBGAA\n-----END PGP PUBLIC KEY BLOCK-----"

  private val NotFoundBody: String = """{"message": "GetRepositoryByID", "url": "https://forge.example/api/swagger"}"""

  private val ForbiddenBody: String = """{"message": "token does not have scope", "url": "https://forge.example"}"""

  private val ArchivedBody: String = """{"message": "repository is archived", "url": "https://forge.example"}"""

  private val ConflictBody: String = """{"message": "sha does not match", "url": "https://forge.example"}"""
