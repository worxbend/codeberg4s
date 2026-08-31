package com.worxbend.codeberg4s.repositories.gitdata

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
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.ContentPath
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.transport.SttpHttpPort

import sttp.client4.Backend
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/** [[RepositoryGitApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which query parameters and which body are sent, which calls may be
  * repeated, and what the paging headers are allowed to decide. Decoding itself is asserted in `modules/codec`, so the
  * payloads here are the smallest bodies that get past the decoder.
  *
  * Three things in this group are easy to get wrong in the request builder and each has its own test: a ref name
  * reaches the wire as several segments rather than percent-encoded whole, the archive format is glued onto the last of
  * those segments, and the tree listing spells its page size `per_page`.
  */
final class RepositoryGitApiSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val Handle: Owner = orFail(Owner.from("worxbend"))

  private val Name: RepoName = orFail(RepoName.from("codeberg4s"))

  private val Sha: CommitSha = orFail(CommitSha.from("1111111111111111111111111111111111111111"))

  private val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  private def ref(value: String): RefName = orFail(RefName.from(value))

  private def path(value: String): ContentPath = orFail(ContentPath.from(value))

  // --- blobs, trees and commits ---------------------------------------------

  test("a blob read addresses the blob by its object id"):
    val backend = RecordingBackend(responding(200, RepositoryGitApiSuite.BlobBody))

    onBackend(backend): api =>
      api.getBlob(Handle, Name, Sha).map: blob =>
        assertEquals(pathOf(backend), s"https://forge.example/api/v1/repos/worxbend/codeberg4s/git/blobs/${Sha.value}")
        assertEquals(blob.content.flatMap(_.text), Some("hello"))

  test("a multi-blob read puts the ids in one comma-separated shas parameter"):
    val backend = RecordingBackend(responding(200, s"[${RepositoryGitApiSuite.BlobBody}]"))
    val second  = orFail(CommitSha.from("2222222222222222222222222222222222222222"))

    onBackend(backend): api =>
      api.getBlobs(Handle, Name, Vector(Sha, second)).map: blobs =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/worxbend/codeberg4s/git/blobs")
        assertEquals(queryOf(backend), List("shas" -> s"${Sha.value},${second.value}"))
        assertEquals(blobs.size, 1)

  test("the tree listing sends per_page, not limit — this route is the odd one out"):
    val backend = RecordingBackend(responding(200, """{"tree":[]}"""))

    onBackend(backend): api =>
      api
        .listTree(Handle, Name, Sha, false, window(2, 50))
        .map: _ =>
          assertEquals(pathOf(backend), s"https://forge.example/api/v1/repos/worxbend/codeberg4s/git/trees/${Sha.value}")
          assertEquals(queryOf(backend), List("page" -> "2", "per_page" -> "50"))

  test("a recursive tree listing says so, and only when asked"):
    val backend = RecordingBackend(responding(200, """{"tree":[]}"""))

    onBackend(backend): api =>
      api
        .listTree(Handle, Name, Sha, true, PageParams.First)
        .map(_ => assertEquals(queryOf(backend).headOption, Some("recursive" -> "true")))

  test("a tree page ends where rel=next says it ends, and not where truncated or a short page suggest"):
    val backend = responding(200, RepositoryGitApiSuite.TruncatedTreeBody, RepositoryGitApiSuite.PagedHeaders)

    onStub(backend): api =>
      api.listTree(Handle, Name, Sha, true, window(1, 50)).map: page =>
        assertEquals(page.size, 1)
        assertEquals(page.totalCount, Some(4210))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("a tree page whose response carries no Link header reports itself as the last one"):
    onStub(responding(200, RepositoryGitApiSuite.TruncatedTreeBody)): api =>
      api.listTree(Handle, Name, Sha, true, PageParams.First).map: page =>
        assertEquals(page.isLast, true)
        assertEquals(page.nextPage, None)

  test("a single-commit read sends only the parts the caller took a position on"):
    val backend = RecordingBackend(responding(200, RepositoryGitApiSuite.CommitBody))

    onBackend(backend): api =>
      api
        .getCommit(Handle, Name, Sha, CommitInclude.Default.withFiles(false))
        .map: commit =>
          assertEquals(
            pathOf(backend),
            s"https://forge.example/api/v1/repos/worxbend/codeberg4s/git/commits/${Sha.value}",
          )
          assertEquals(queryOf(backend), List("files" -> "false"))
          assertEquals(commit.sha.value, Sha.value)

  test("a commit read that asserts nothing sends no query at all"):
    val backend = RecordingBackend(responding(200, RepositoryGitApiSuite.CommitBody))

    onBackend(backend): api =>
      api.getCommit(Handle, Name, Sha, CommitInclude.Default).map(_ => assertEquals(queryOf(backend), Nil))

  test("a diff is a path suffix and comes back as text, unparsed"):
    val backend = RecordingBackend(responding(200, RepositoryGitApiSuite.DiffBody))

    onBackend(backend): api =>
      api.getCommitDiff(Handle, Name, Sha, DiffType.Diff).map: diff =>
        assertEquals(
          pathOf(backend),
          s"https://forge.example/api/v1/repos/worxbend/codeberg4s/git/commits/${Sha.value}.diff",
        )
        assertEquals(diff, RepositoryGitApiSuite.DiffBody)

  test("a patch differs from a diff only in the suffix"):
    val backend = RecordingBackend(responding(200, ""))

    onBackend(backend): api =>
      api
        .getCommitDiff(Handle, Name, Sha, DiffType.Patch)
        .map(body => assertEquals((pathOf(backend).endsWith(".patch"), body), (true, "")))

  // --- notes ----------------------------------------------------------------

  test("a note read drops the stat parameter, which this route does not declare"):
    val backend = RecordingBackend(responding(200, """{"message":"seen"}"""))

    onBackend(backend): api =>
      api
        .getNote(Handle, Name, Sha, CommitInclude.Minimal)
        .map: note =>
          assertEquals(pathOf(backend), s"https://forge.example/api/v1/repos/worxbend/codeberg4s/git/notes/${Sha.value}")
          assertEquals(queryOf(backend), List("verification" -> "false", "files" -> "false"))
          assertEquals(note.message, Some("seen"))

  test("setting a note POSTs the one key the model has"):
    val backend = RecordingBackend(responding(200, """{"message":"seen"}"""))

    onBackend(backend): api =>
      api.setNote(Handle, Name, Sha, "seen").map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(bodyOf(backend), """{"message":"seen"}""")

  test("setting a note is never retried, POST being a POST whatever its effect"):
    val backend = RecordingBackend(afterOneOutage("""{"message":"seen"}""", 200))

    onBackend(backend): api =>
      api.attempt
        .setNote(Handle, Name, Sha, "seen")
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a note write must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("removing a note is a DELETE that reads no body"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.removeNote(Handle, Name, Sha).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"https://forge.example/api/v1/repos/worxbend/codeberg4s/git/notes/${Sha.value}")

  test("a 204 decorated with an unexpected payload still succeeds, because nothing decodes it"):
    onStub(responding(204, """{"unexpected":true}""")): api =>
      api.attempt.removeNote(Handle, Name, Sha).map(outcome => assertEquals(outcome.isRight, true))

  test("removing a note is not retried either — a repeat would answer 404 for work that succeeded"):
    val backend = RecordingBackend(afterOneOutage("", 204))

    onBackend(backend): api =>
      api.attempt
        .removeNote(Handle, Name, Sha)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a note delete must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the DELETE was retried")

  // --- refs and tags --------------------------------------------------------

  test("the whole-repository ref listing takes no window, because the route declares none"):
    val backend = RecordingBackend(responding(200, RepositoryGitApiSuite.RefsBody))

    onBackend(backend): api =>
      api.listRefs(Handle, Name).map: refs =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/worxbend/codeberg4s/git/refs")
        assertEquals(queryOf(backend), Nil)
        assertEquals(refs.map(_.name.value), Vector("refs/heads/main"))

  test("a slashed ref reaches the wire as several segments, because encoding it whole is a 404"):
    val backend = RecordingBackend(responding(200, RepositoryGitApiSuite.RefsBody))

    onBackend(backend): api =>
      api
        .listMatchingRefs(Handle, Name, ref("refs/heads/main"))
        .map: _ =>
          assertEquals(
            pathOf(backend),
            "https://forge.example/api/v1/repos/worxbend/codeberg4s/git/refs/refs/heads/main",
          )

  test("an annotated tag is addressed by the id of the tag object"):
    val backend = RecordingBackend(responding(200, RepositoryGitApiSuite.AnnotatedTagBody))

    onBackend(backend): api =>
      api.getAnnotatedTag(Handle, Name, Sha).map: tag =>
        assertEquals(pathOf(backend), s"https://forge.example/api/v1/repos/worxbend/codeberg4s/git/tags/${Sha.value}")
        assertEquals(tag.name.value, "v1.0")

  // --- commit-level reads ---------------------------------------------------

  test("a combined status windows the nested statuses and keeps the envelope"):
    val backend = RecordingBackend(responding(200, RepositoryGitApiSuite.CombinedStatusBody))

    onBackend(backend): api =>
      api
        .getCombinedStatus(Handle, Name, ref("main"), window(1, 30))
        .map: combined =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/worxbend/codeberg4s/commits/main/status")
          assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))
          assertEquals(combined.state, Some(CommitStatusState.Pending))
          assertEquals(combined.totalCount, 2L)

  test("a status listing sends its filters before its window"):
    val backend = RecordingBackend(responding(200, "[]"))
    val query   = CommitStatusQuery.Empty.sortedBy(CommitStatusSort.Oldest).inState(CommitStatusState.Failure)

    onBackend(backend): api =>
      api
        .listStatuses(Handle, Name, ref("v1.0"), query, window(1, 30))
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/worxbend/codeberg4s/commits/v1.0/statuses")
          assertEquals(
            queryOf(backend),
            List("sort" -> "oldest", "state" -> "failure", "page" -> "1", "limit" -> "30"),
          )

  test("a status page past the end is an empty page, not a failure"):
    onStub(responding(200, "[]")): api =>
      api
        .listStatuses(Handle, Name, ref("main"), CommitStatusQuery.Empty, PageParams.First)
        .map: page =>
          assertEquals(page.items, Vector.empty[CommitStatus])
          assertEquals(page.isLast, true)

  test("a commit's pull request is read through the commits path, not the git path"):
    val backend = RecordingBackend(responding(200, RepositoryGitApiSuite.PullRequestBody))

    onBackend(backend): api =>
      api.getCommitPullRequest(Handle, Name, Sha).map: pull =>
        assertEquals(
          pathOf(backend),
          s"https://forge.example/api/v1/repos/worxbend/codeberg4s/commits/${Sha.value}/pull",
        )
        assertEquals(pull.number.value, 42L)

  test("a comparison sends base...head, splitting only at the slashes"):
    val backend = RecordingBackend(responding(200, RepositoryGitApiSuite.CompareBody))
    val range   = CompareRange.between(ref("v1.0"), ref("renovate/deps"))

    onBackend(backend): api =>
      api.compare(Handle, Name, range).map: comparison =>
        assertEquals(
          pathOf(backend),
          "https://forge.example/api/v1/repos/worxbend/codeberg4s/compare/v1.0...renovate/deps",
        )
        assertEquals(comparison.totalCommits, 3L)
        assertEquals(comparison.isTruncated, true)

  // --- writes and non-JSON reads --------------------------------------------

  test("applying a patch POSTs the rendered UpdateFileOptions to diffpatch"):
    val backend = RecordingBackend(responding(200, RepositoryGitApiSuite.FileResponseBody))
    val command = ApplyDiffPatch.of("--- a").withMessage("apply")

    onBackend(backend): api =>
      api.applyDiffPatch(Handle, Name, command).map: change =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/worxbend/codeberg4s/diffpatch")
        assertEquals(bodyOf(backend), """{"content":"--- a","message":"apply"}""")
        assertEquals(change.commit.map(_.sha.value), Some(Sha.value))

  test("applying a patch is never retried, because a repeat leaves a second commit"):
    val backend = RecordingBackend(afterOneOutage(RepositoryGitApiSuite.FileResponseBody, 200))

    onBackend(backend): api =>
      api.attempt
        .applyDiffPatch(Handle, Name, ApplyDiffPatch.of("--- a"))
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the POST was retried"))

  test("a read is retried, so the eligibility difference is real and not a comment"):
    val backend = RecordingBackend(afterOneOutage(RepositoryGitApiSuite.BlobBody, 200))

    onBackend(backend): api =>
      api.getBlob(Handle, Name, Sha).map: blob =>
        assertEquals(blob.size, 5L)
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("editorconfig sends the path as segments and the ref as a query parameter"):
    val backend = RecordingBackend(responding(200, """{"indent_style":"space","indent_size":4}"""))

    onBackend(backend): api =>
      api
        .getEditorConfig(Handle, Name, path("modules/core/Foo.scala"), Some(ref("refs/heads/main")))
        .map: definitions =>
          assertEquals(
            pathOf(backend),
            "https://forge.example/api/v1/repos/worxbend/codeberg4s/editorconfig/modules/core/Foo.scala",
          )
          assertEquals(queryOf(backend), List("ref" -> "refs/heads/main"))
          assertEquals(definitions.valueOf("indent_size"), Some("4"))

  test("a raw file read sends no ref when none was given, and returns the body unchanged"):
    val backend = RecordingBackend(responding(200, "# codeberg4s\n"))

    onBackend(backend): api =>
      api.getRawFile(Handle, Name, path("README.md"), None).map: body =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/worxbend/codeberg4s/raw/README.md")
        assertEquals(queryOf(backend), Nil)
        assertEquals(body, "# codeberg4s\n")

  test("the media read differs from the raw read only in the path segment"):
    val backend = RecordingBackend(responding(200, "pointer"))

    onBackend(backend): api =>
      api
        .getMediaFile(Handle, Name, path("assets/logo.png"), Some(ref("main")))
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/worxbend/codeberg4s/media/assets/logo.png")
          assertEquals(queryOf(backend), List("ref" -> "main"))

  test("an archive glues the format onto the last segment of the ref"):
    val backend = RecordingBackend(responding(200, "PK"))

    onBackend(backend): api =>
      api
        .getArchive(Handle, Name, ref("release/2026"), ArchiveFormat.TarGz)
        .map: _ =>
          assertEquals(
            pathOf(backend),
            "https://forge.example/api/v1/repos/worxbend/codeberg4s/archive/release/2026.tar.gz",
          )

  test("an unslashed ref archives as one segment, suffix included"):
    val backend = RecordingBackend(responding(200, "PK"))

    onBackend(backend): api =>
      api
        .getArchive(Handle, Name, ref("main"), ArchiveFormat.Zip)
        .map(_ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/worxbend/codeberg4s/archive/main.zip")
        )

  // --- failures -------------------------------------------------------------

  test("a 404 fails the convenience rail with a CodebergException carrying the Api failure"):
    onStub(responding(404, RepositoryGitApiSuite.NotFoundBody)): api =>
      api.getBlob(Handle, Name, Sha).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (RepositoryGitApi.GetBlobOperation, 404, Some("GetBlob")))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 404 reaches the typed rail as a Left reporting the very same failure"):
    onStub(responding(404, RepositoryGitApiSuite.NotFoundBody)): api =>
      for
        raised <- api.getBlob(Handle, Name, Sha).failed
        typed  <- api.attempt.getBlob(Handle, Name, Sha)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a tree listing failure too, so the choice of rail is only a choice of style"):
    onStub(responding(404, RepositoryGitApiSuite.NotFoundBody)): api =>
      for
        raised <- api.listTree(Handle, Name, Sha, false, PageParams.First).failed
        typed  <- api.attempt.listTree(Handle, Name, Sha, false, PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on the archive read as well, which has no decoder to disagree about"):
    onStub(responding(404, RepositoryGitApiSuite.NotFoundBody)): api =>
      for
        raised <- api.getArchive(Handle, Name, ref("main"), ArchiveFormat.Zip).failed
        typed  <- api.attempt.getArchive(Handle, Name, ref("main"), ArchiveFormat.Zip)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a note deletion failure, where the success carries no value at all"):
    onStub(responding(404, RepositoryGitApiSuite.NotFoundBody)): api =>
      for
        raised <- api.removeNote(Handle, Name, Sha).failed
        typed  <- api.attempt.removeNote(Handle, Name, Sha)
      yield assertRailsAgree(raised, typed)

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onStub(responding(200, """{"size":3}""")): api =>
      api.attempt.getBlob(Handle, Name, Sha).map:
        case Left(CodebergError.DecodingFailed(_, _, failed, _)) => assertEquals(failed.render, "$.sha")
        case other                                               => fail(s"expected a decoding failure, got $other")

  test("a bad entry of a tree page reports its position, all the way through the pipeline"):
    onStub(responding(200, """{"tree":[{"path":"a","sha":"cafebabe"},{"path":"b"}]}""")): api =>
      api.attempt.listTree(Handle, Name, Sha, false, PageParams.First).map:
        case Left(CodebergError.DecodingFailed(_, _, failed, _)) => assertEquals(failed.render, "$.tree[1].sha")
        case other                                               => fail(s"expected a decoding failure, got $other")

  test("a 422 on a patch reaches both rails identically, carrying Forgejo's errors array"):
    onStub(responding(422, RepositoryGitApiSuite.ValidationBody)): api =>
      for
        raised <- api.applyDiffPatch(Handle, Name, ApplyDiffPatch.of("--- a")).failed
        typed  <- api.attempt.applyDiffPatch(Handle, Name, ApplyDiffPatch.of("--- a"))
      yield
        assertEquals(details(typed), List("patch does not apply"))
        assertRailsAgree(raised, typed)

  test("a 423 on an archived repository is an Api failure carrying the operation id"):
    onStub(responding(423, RepositoryGitApiSuite.ValidationBody)): api =>
      api.attempt.applyDiffPatch(Handle, Name, ApplyDiffPatch.of("--- a")).map:
        case Left(CodebergError.Api(ctx, status, _)) =>
          assertEquals((ctx.operation, status), (RepositoryGitApi.ApplyDiffPatchOperation, 423))
        case other                                   => fail(s"expected an Api failure, got $other")

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

  private def details[A](result: Either[CodebergError, A]): List[String] =
    result match
      case Left(CodebergError.Api(_, _, body)) => body.errors
      case other                               => fail(s"expected an Api failure, got $other")

  // --- harness --------------------------------------------------------------

  private def responding(status: Int, body: String): BackendStub[Future] =
    responding(status, body, Nil)

  private def responding(status: Int, body: String, headers: List[Header]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  /** A backend that answers `503` once and then succeeds, which is what tells a retried call apart from a bare one. */
  private def afterOneOutage(body: String, status: Int): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
      ResponseStub.adjust("", StatusCode(503)),
      ResponseStub.adjust(body, StatusCode(status)),
    )

  private def dialled(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.toString
      case None               => fail("no request reached the backend")

  /** The dialled URI without its query string. Written with `indexOf` rather than a character comparison because
    * `.scalafix.conf` bans universal equality outright.
    */
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

  private def bodyOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.body.show.stripPrefix("string: ")
      case None               => fail("no request reached the backend")

  private def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  private def onStub[A](backend: Backend[Future])(use: RepositoryGitApi => Future[A]): Future[A] =
    onBackend(backend)(use)

  /** Builds the pipeline this group's API sits on, and releases the timer whatever the outcome. */
  private def onBackend[A](backend: Backend[Future])(use: RepositoryGitApi => Future[A]): Future[A] =
    given Exec[Future] = FutureExec()

    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = RepositoryGitApiSuite.PromptRetry)
    val timer  = FutureTimer()

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, config),
      config,
      timer,
      Telemetry.noOp[Future],
      ApiErrorBodyCodec.parse,
    )

    use(RepositoryGitApi(pipeline)).transform: outcome =>
      timer.close()
      outcome

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The response bodies this suite stubs, derived from `spec/swagger.v1.json` and kept out of the test bodies so each
  * test reads as one behaviour. No golden capture exists for any endpoint in this group.
  */
object RepositoryGitApiSuite:

  private val BlobBody: String =
    """{"sha":"1111111111111111111111111111111111111111","size":5,"encoding":"base64","content":"aGVsbG8="}"""

  private val CommitBody: String =
    """{"sha":"1111111111111111111111111111111111111111","commit":{"message":"one"}}"""

  private val DiffBody: String =
    "diff --git a/README.md b/README.md\n--- a/README.md\n+++ b/README.md\n"

  private val RefsBody: String =
    """[{"ref":"refs/heads/main","object":{"type":"commit","sha":"1111111111111111111111111111111111111111"}}]"""

  private val AnnotatedTagBody: String =
    """{"tag":"v1.0","sha":"1111111111111111111111111111111111111111","message":"release 1.0"}"""

  private val CombinedStatusBody: String =
    """{"sha":"1111111111111111111111111111111111111111","state":"pending","total_count":2,"statuses":[]}"""

  private val PullRequestBody: String =
    """{"id":9,"number":42,"title":"Add the git data group","state":"open","user":{"id":1,"login":"ada"}}"""

  private val CompareBody: String =
    """{"total_commits":3,"commits":[{"sha":"1111111111111111111111111111111111111111"}],"files":[]}"""

  private val FileResponseBody: String =
    """{"commit":{"sha":"1111111111111111111111111111111111111111","message":"apply"}}"""

  /** One entry out of thousands, with `truncated` saying the collection is exhausted. The `Link` header says otherwise
    * and the `Link` header is what this library believes — `docs/HAZARDS.md` §5.
    */
  private val TruncatedTreeBody: String =
    """{
      |  "sha": "1111111111111111111111111111111111111111",
      |  "tree": [{"path": "README.md", "mode": "100644", "type": "blob", "size": 42, "sha": "1a2b3c4d"}],
      |  "truncated": false,
      |  "total_count": 1
      |}""".stripMargin

  private val PagedHeaders: List[Header] =
    List(
      Header("X-Total-Count", "4210"),
      Header(
        "Link",
        "<https://forge.example/api/v1/repos/worxbend/codeberg4s/git/trees/x?per_page=50&page=2>; rel=\"next\"," +
          "<https://forge.example/api/v1/repos/worxbend/codeberg4s/git/trees/x?per_page=50&page=85>; rel=\"last\"",
      ),
    )

  /** A 404 shaped like `golden/error/404-repo-not-found.json`: a Go symbol for a message, and the useful text in
    * `errors`.
    */
  private val NotFoundBody: String =
    """{"message":"GetBlob","url":"https://codeberg.org/api/swagger","errors":["object does not exist"]}"""

  private val ValidationBody: String =
    """{"message":"ApplyDiffPatch","url":"https://codeberg.org/api/swagger","errors":["patch does not apply"]}"""

  /** Retries promptly and predictably: the default policy would make the retry tests take a quarter of a second. */
  private val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )
