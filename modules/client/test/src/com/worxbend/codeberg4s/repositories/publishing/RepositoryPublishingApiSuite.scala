package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
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
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.ReleaseId
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.TagName
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.transport.SttpHttpPort

import sttp.client4.Backend
import sttp.client4.MultipartBody
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import java.nio.charset.StandardCharsets

/** [[RepositoryPublishingApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which method, which query parameters and which body are sent, what
  * each rail does with a failure, and which writes the retry engine is allowed to repeat. Decoding itself is asserted
  * against the golden captures in `modules/codec`, so the payloads here are small bodies chosen to exercise a seam.
  *
  * The tag used throughout is `v16.0/forgejo`, taken from `golden/repository/tags-list.json`, because a tag name with a
  * slash in it is the one that breaks a request builder that percent-encodes the whole name into a single segment.
  */
final class RepositoryPublishingApiSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val Handle: Owner = orFail(Owner.from("forgejo"))

  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  private val Version: TagName = orFail(TagName.from("v16.0.2"))

  private val Slashed: TagName = orFail(TagName.from("v16.0/forgejo"))

  private val Id: ReleaseId = orFail(ReleaseId.from(11189746L))

  private val Attachment: AssetId = orFail(AssetId.from(1730449L))

  private val Forge: Topic = orFail(Topic.from("forge"))

  private val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  private val Base: String = "https://forge.example/api/v1/repos/forgejo/forgejo"

  // --- releases -------------------------------------------------------------

  test("repos.releases.create POSTs the rendered CreateReleaseOption to the repository's releases"):
    val backend = RecordingBackend(responding(201, RepositoryPublishingApiSuite.ReleaseBody))
    val command = CreateRelease.of(Version).titled("v16.0.2").asPrerelease

    onBackend(backend): api =>
      api.createRelease(Handle, Name, command).map: release =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Base/releases")
        assertEquals(bodyOf(backend), """{"tag_name":"v16.0.2","name":"v16.0.2","prerelease":true}""")
        assertEquals(release.id.value, 11189746L)

  test("repos.releases.create is never retried, because a repeat can move a tag as well as publish twice"):
    val backend = RecordingBackend(flaky(201, RepositoryPublishingApiSuite.ReleaseBody))

    onBackend(backend): api =>
      api.attempt
        .createRelease(Handle, Name, CreateRelease.of(Version))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on create must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("repos.releases.latest reads the endpoint that excludes drafts and prereleases"):
    val backend = RecordingBackend(responding(200, RepositoryPublishingApiSuite.ReleaseBody))

    onBackend(backend): api =>
      api.latestRelease(Handle, Name).map: release =>
        assertEquals(methodOf(backend), "GET")
        assertEquals(pathOf(backend), s"$Base/releases/latest")
        assertEquals(release.tagName.value, "v16.0.2")

  test("a read is retried, so the eligibility difference is real and not a comment"):
    val backend = RecordingBackend(flaky(200, RepositoryPublishingApiSuite.ReleaseBody))

    onBackend(backend): api =>
      api.latestRelease(Handle, Name).map: release =>
        assertEquals(release.id.value, 11189746L)
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("repos.releases.getByTag sends a slashed tag as real path segments, not as one encoded segment"):
    val backend = RecordingBackend(responding(200, RepositoryPublishingApiSuite.ReleaseBody))

    onBackend(backend): api =>
      api
        .releaseByTag(Handle, Name, Slashed)
        .map(_ => assertEquals(pathOf(backend), s"$Base/releases/tags/v16.0/forgejo"))

  test("repos.releases.edit PATCHes only what the command sets"):
    val backend = RecordingBackend(responding(200, RepositoryPublishingApiSuite.ReleaseBody))

    onBackend(backend): api =>
      api
        .editRelease(Handle, Name, Id, EditRelease.Empty.draft(false))
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(pathOf(backend), s"$Base/releases/11189746")
          assertEquals(bodyOf(backend), """{"draft":false}""")

  test("repos.releases.edit is never retried, because a partial update is not idempotent here"):
    val backend = RecordingBackend(flaky(200, RepositoryPublishingApiSuite.ReleaseBody))

    onBackend(backend): api =>
      api.attempt
        .editRelease(Handle, Name, Id, EditRelease.Empty.draft(false))
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the PATCH was retried"))

  test("repos.releases.delete addresses the release by id and reads no body"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.deleteRelease(Handle, Name, Id).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Base/releases/11189746")

  test("repos.releases.delete is never retried, so a 503 is reported rather than repeated"):
    val backend = RecordingBackend(flaky(204, ""))

    onBackend(backend): api =>
      api.attempt
        .deleteRelease(Handle, Name, Id)
        .map: outcome =>
          assert(outcome.isLeft, s"the delete must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the DELETE was retried")

  test("repos.releases.deleteByTag addresses the release by its tag, slashes intact"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.deleteReleaseByTag(Handle, Name, Slashed).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Base/releases/tags/v16.0/forgejo")

  test("a 204 with a body Forgejo should not have sent is still a success, because no decoder runs"):
    onStub(responding(204, """{"unexpected":true}""")): api =>
      api.attempt.deleteRelease(Handle, Name, Id).map(outcome => assertEquals(outcome, Right(())))

  // --- release assets -------------------------------------------------------

  test("repos.releases.assets.list pages the release's attachments"):
    val backend = RecordingBackend(responding(200, RepositoryPublishingApiSuite.AssetListBody))

    onBackend(backend): api =>
      api.listAssets(Handle, Name, Id, window(2, 25)).map: page =>
        assertEquals(pathOf(backend), s"$Base/releases/11189746/assets")
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))
        assertEquals(page.items.map(_.name), Vector("forgejo-16.0.2-linux-amd64"))

  test("an attachment listing with no Link header reports itself as the last page, whatever the total says"):
    onStub(responding(200, RepositoryPublishingApiSuite.AssetListBody)): api =>
      api.listAssets(Handle, Name, Id, PageParams.First).map: page =>
        assertEquals(page.isLast, true)
        assertEquals(page.nextPage, None)

  test("a bad element of an attachment listing reports its position, all the way through the pipeline"):
    onStub(responding(200, """[{"id":1,"name":"a"},{"id":2}]""")): api =>
      api.attempt.listAssets(Handle, Name, Id, PageParams.First).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].name")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("repos.releases.assets.upload POSTs a multipart body in the field the spec names"):
    val backend = RecordingBackend(responding(201, RepositoryPublishingApiSuite.AssetBody))
    val upload  = orFail(UploadAsset.of("forgejo-16.0.2-linux-amd64", RepositoryPublishingApiSuite.Bytes))

    onBackend(backend): api =>
      api.uploadAsset(Handle, Name, Id, upload).map: asset =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Base/releases/11189746/assets")
        assertEquals(partsOf(backend), List("attachment" -> Some("forgejo-16.0.2-linux-amd64")))
        assertEquals(asset.id, 1730449L)

  test("an upload sends no name query parameter when the stored name is the file name"):
    val backend = RecordingBackend(responding(201, RepositoryPublishingApiSuite.AssetBody))
    val upload  = orFail(UploadAsset.of("out.tar.gz", RepositoryPublishingApiSuite.Bytes))

    onBackend(backend): api =>
      api.uploadAsset(Handle, Name, Id, upload).map(_ => assertEquals(queryOf(backend), Nil))

  test("an upload sends the name query parameter when the stored name differs from the file name"):
    val backend = RecordingBackend(responding(201, RepositoryPublishingApiSuite.AssetBody))
    val upload  = orFail(UploadAsset.of("out.tar.gz", RepositoryPublishingApiSuite.Bytes))

    onBackend(backend): api =>
      api
        .uploadAsset(Handle, Name, Id, upload.named("forgejo-16.0.2-linux-amd64"))
        .map(_ => assertEquals(queryOf(backend), List("name" -> "forgejo-16.0.2-linux-amd64")))

  test("repos.releases.assets.upload is never retried, because a repeat attaches the file twice"):
    val backend = RecordingBackend(flaky(201, RepositoryPublishingApiSuite.AssetBody))
    val upload  = orFail(UploadAsset.of("checksums.txt", RepositoryPublishingApiSuite.Bytes))

    onBackend(backend): api =>
      api.attempt
        .uploadAsset(Handle, Name, Id, upload)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on upload must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the upload was retried")

  test("repos.releases.assets.get addresses the attachment under its release"):
    val backend = RecordingBackend(responding(200, RepositoryPublishingApiSuite.AssetBody))

    onBackend(backend): api =>
      api.getAsset(Handle, Name, Id, Attachment).map: asset =>
        assertEquals(methodOf(backend), "GET")
        assertEquals(pathOf(backend), s"$Base/releases/11189746/assets/1730449")
        assertEquals(asset.browserDownloadUrl, Some("https://forge.example/a"))

  test("repos.releases.assets.edit PATCHes only what the command sets"):
    val backend = RecordingBackend(responding(201, RepositoryPublishingApiSuite.AssetBody))

    onBackend(backend): api =>
      api
        .editAsset(Handle, Name, Id, Attachment, EditAsset.Empty.renamedTo("checksums.txt"))
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(pathOf(backend), s"$Base/releases/11189746/assets/1730449")
          assertEquals(bodyOf(backend), """{"name":"checksums.txt"}""")

  test("repos.releases.assets.delete removes the attachment and reads no body"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.deleteAsset(Handle, Name, Id, Attachment).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Base/releases/11189746/assets/1730449")

  // --- tags -----------------------------------------------------------------

  test("repos.tags.create POSTs the rendered CreateTagOption"):
    val backend = RecordingBackend(responding(201, RepositoryPublishingApiSuite.TagBody))

    onBackend(backend): api =>
      api
        .createTag(Handle, Name, CreateTag.of(Version).annotated("security patches"))
        .map: created =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), s"$Base/tags")
          assertEquals(bodyOf(backend), """{"tag_name":"v16.0.2","message":"security patches"}""")
          assertEquals(created.name.value, "v16.0.2")

  test("repos.tags.get sends a slashed tag as real path segments"):
    val backend = RecordingBackend(responding(200, RepositoryPublishingApiSuite.TagBody))

    onBackend(backend): api =>
      api.getTag(Handle, Name, Slashed).map: found =>
        assertEquals(pathOf(backend), s"$Base/tags/v16.0/forgejo")
        assertEquals(found.commitSha.value, "5f7e2e5c003c066a865ea483e42809fa87d85eae")

  test("repos.tags.delete is never retried, because CI recreates tag names"):
    val backend = RecordingBackend(flaky(204, ""))

    onBackend(backend): api =>
      api.attempt
        .deleteTag(Handle, Name, Slashed)
        .map: outcome =>
          assert(outcome.isLeft, s"the delete must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the DELETE was retried")
          assertEquals(methodOf(backend), "DELETE")

  // --- topics ---------------------------------------------------------------

  test("repos.topics.replace PUTs the whole set, and an empty set is an empty array"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.replaceTopics(Handle, Name, Vector.empty).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(pathOf(backend), s"$Base/topics")
        assertEquals(bodyOf(backend), """{"topics":[]}""")

  test("repos.topics.replace sends the names it was given, in order"):
    val backend = RecordingBackend(responding(204, ""))
    val topics  = Vector("forge", "forgejo").map(name => orFail(Topic.from(name)))

    onBackend(backend): api =>
      api.replaceTopics(Handle, Name, topics).map(_ =>
        assertEquals(bodyOf(backend), """{"topics":["forge","forgejo"]}""")
      )

  test("repos.topics.replace is retried, because it re-states a value rather than destroying a resource"):
    val backend = RecordingBackend(flaky(204, ""))

    onBackend(backend): api =>
      api.replaceTopics(Handle, Name, Vector(Forge)).map: _ =>
        assertEquals(backend.allInteractions.size, 2, "the 503 on a topic replacement was not retried")

  test("repos.topics.add PUTs the topic as a path segment with a deliberately empty body"):
    val backend = RecordingBackend(responding(204, ""))

    onBackend(backend): api =>
      api.addTopic(Handle, Name, Forge).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(pathOf(backend), s"$Base/topics/forge")
        assertEquals(bodyOf(backend), "")

  test("repos.topics.add is retried, because asserting set membership twice asserts the same thing"):
    val backend = RecordingBackend(flaky(204, ""))

    onBackend(backend): api =>
      api
        .addTopic(Handle, Name, Forge)
        .map(_ => assertEquals(backend.allInteractions.size, 2, "the add was not retried"))

  test("repos.topics.remove DELETEs the topic and is retried, unlike every other delete in this group"):
    val backend = RecordingBackend(flaky(204, ""))

    onBackend(backend): api =>
      api.removeTopic(Handle, Name, Forge).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Base/topics/forge")
        assertEquals(backend.allInteractions.size, 2, "the topic removal was not retried")

  // --- forking and templating -----------------------------------------------

  test("repos.forks.create POSTs to the upstream repository's forks"):
    val backend = RecordingBackend(responding(202, RepositoryPublishingApiSuite.RepositoryBody))

    onBackend(backend): api =>
      api.fork(Handle, Name, CreateFork.Empty.into(Handle)).map: repository =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Base/forks")
        assertEquals(bodyOf(backend), """{"organization":"forgejo"}""")
        assertEquals(repository.slug.value, "forgejo/forgejo")

  test("a 202 is a success — forking is accepted now and finished later"):
    onStub(responding(202, RepositoryPublishingApiSuite.RepositoryBody)): api =>
      api.attempt.fork(Handle, Name, CreateFork.Empty).map(outcome => assert(outcome.isRight, s"got $outcome"))

  test("repos.generate names the template in the path and the new repository in the body"):
    val backend = RecordingBackend(responding(201, RepositoryPublishingApiSuite.RepositoryBody))
    val command = GenerateRepository.of(Handle, Name).withGitContent

    onBackend(backend): api =>
      api.generate(Handle, Name, command).map: repository =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Base/generate")
        assertEquals(bodyOf(backend), """{"owner":"forgejo","name":"forgejo","git_content":true}""")
        assertEquals(repository.owner.login, "forgejo")

  test("repos.generate is never retried, because a repeat creates a second repository"):
    val backend = RecordingBackend(flaky(201, RepositoryPublishingApiSuite.RepositoryBody))

    onBackend(backend): api =>
      api.attempt
        .generate(Handle, Name, GenerateRepository.of(Handle, Name))
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the generate was retried"))

  // --- failures -------------------------------------------------------------

  test("a 404 fails the convenience rail with a CodebergException carrying the Api failure"):
    onStub(responding(404, RepositoryPublishingApiSuite.NotFoundBody)): api =>
      api.getTag(Handle, Name, Version).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (RepositoryPublishingApi.GetTagOperation, 404, Some("GetTag")))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 404 reaches the typed rail as a Left reporting the very same failure"):
    onStub(responding(404, RepositoryPublishingApiSuite.NotFoundBody)): api =>
      for
        raised <- api.getTag(Handle, Name, Version).failed
        typed  <- api.attempt.getTag(Handle, Name, Version)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a 409 from a create, carrying Forgejo's errors array"):
    onStub(responding(409, RepositoryPublishingApiSuite.ConflictBody)): api =>
      for
        raised <- api.createTag(Handle, Name, CreateTag.of(Version)).failed
        typed  <- api.attempt.createTag(Handle, Name, CreateTag.of(Version))
      yield
        assertEquals(details(typed), List("tag already exists"))
        assertRailsAgree(raised, typed)

  test("both rails agree on a unit-returning failure too, so the choice of rail is only a choice of style"):
    onStub(responding(422, RepositoryPublishingApiSuite.ValidationBody)): api =>
      for
        raised <- api.addTopic(Handle, Name, Forge).failed
        typed  <- api.attempt.addTopic(Handle, Name, Forge)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on an upload failure, bytes and all"):
    val upload = orFail(UploadAsset.of("checksums.txt", RepositoryPublishingApiSuite.Bytes))

    onStub(responding(413, RepositoryPublishingApiSuite.QuotaBody)): api =>
      for
        raised <- api.uploadAsset(Handle, Name, Id, upload).failed
        typed  <- api.attempt.uploadAsset(Handle, Name, Id, upload)
      yield assertRailsAgree(raised, typed)

  test("a 413 carries the upload operation id, so an alert can name the endpoint"):
    val upload = orFail(UploadAsset.of("checksums.txt", RepositoryPublishingApiSuite.Bytes))

    onStub(responding(413, RepositoryPublishingApiSuite.QuotaBody)): api =>
      api.attempt
        .uploadAsset(Handle, Name, Id, upload)
        .map(outcome => assertEquals(operation(outcome), RepositoryPublishingApi.UploadAssetOperation))

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onStub(responding(200, """{"name":"v1"}""")): api =>
      api.attempt.getTag(Handle, Name, Version).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.id")
        case other                                             => fail(s"expected a decoding failure, got $other")

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

  private def operation[A](result: Either[CodebergError, A]): String =
    result match
      case Left(CodebergError.Api(ctx, _, _)) => ctx.operation
      case other                              => fail(s"expected an Api failure, got $other")

  // --- harness --------------------------------------------------------------

  private def responding(status: Int, body: String): BackendStub[Future] =
    responding(status, body, Nil)

  private def responding(status: Int, body: String, headers: List[Header]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  /** A backend that fails once with a retryable status and then succeeds, so a retry is visible as a second interaction
    * and its absence as a `Left`.
    */
  private def flaky(status: Int, body: String): BackendStub[Future] =
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

  /** The multipart parts of the recorded request, as `(field name, file name)`. */
  private def partsOf(backend: RecordingBackend): List[(String, Option[String])] =
    backend.allInteractions.headOption match
      case Some((request, _)) =>
        request.body match
          case multipart: MultipartBody[?] => multipart.parts.map(part => (part.name, part.fileName)).toList
          case other                       => fail(s"expected a multipart body, got ${other.show}")
      case None               => fail("no request reached the backend")

  private def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  private def onStub[A](backend: Backend[Future])(use: RepositoryPublishingApi => Future[A]): Future[A] =
    onBackend(backend)(use)

  /** Builds the pipeline this group's API sits on, and releases the timer whatever the outcome. */
  private def onBackend[A](backend: Backend[Future])(use: RepositoryPublishingApi => Future[A]): Future[A] =
    given Exec[Future] = FutureExec()

    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = RepositoryPublishingApiSuite.Prompt)
    val timer  = FutureTimer()

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, config),
      config,
      timer,
      Telemetry.noOp[Future],
      ApiErrorBodyCodec.parse,
    )

    use(RepositoryPublishingApi(pipeline)).transform: outcome =>
      timer.close()
      outcome

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour. */
object RepositoryPublishingApiSuite:

  /** `golden/repository/release-latest.json`, reduced to the keys these tests assert on. */
  private val ReleaseBody: String =
    """{"id":11189746,"tag_name":"v16.0.2","name":"v16.0.2","draft":false,"prerelease":false,"assets":[]}"""

  /** One element of that capture's `assets`, reduced the same way. */
  private val AssetBody: String =
    """{"id":1730449,"name":"forgejo-16.0.2-linux-amd64","size":119142664,"download_count":705,""" +
      """"browser_download_url":"https://forge.example/a"}"""

  private val AssetListBody: String = s"[$AssetBody]"

  /** One element of `golden/repository/tags-list.json`, reduced to the keys these tests assert on. */
  private val TagBody: String =
    """{"name":"v16.0.2","message":"security patches","id":"5f7e2e5c003c066a865ea483e42809fa87d85eae"}"""

  /** `golden/repository/repo-single.json`, reduced the same way. */
  private val RepositoryBody: String =
    """{"id":1,"name":"forgejo","full_name":"forgejo/forgejo","owner":{"id":2,"login":"forgejo"}}"""

  private val Bytes: Array[Byte] = "checksums".getBytes(StandardCharsets.UTF_8)

  /** A 404 shaped like `golden/error/404-repo-not-found.json`: a Go symbol for a message, and the useful text in
    * `errors`.
    */
  private val NotFoundBody: String =
    """{"message":"GetTag","url":"https://codeberg.org/api/swagger","errors":["tag does not exist"]}"""

  private val ConflictBody: String =
    """{"message":"CreateTag","url":"https://codeberg.org/api/swagger","errors":["tag already exists"]}"""

  private val ValidationBody: String =
    """{"message":"AddTopic","url":"https://codeberg.org/api/swagger","errors":["topic is invalid"]}"""

  private val QuotaBody: String =
    """{"message":"quota exceeded","url":"https://codeberg.org/api/swagger","errors":["storage quota exceeded"]}"""

  /** Retries promptly and predictably: the default policy would make the retry tests take a quarter of a second. */
  private val Prompt: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )
