package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.quota.{QuotaGroup, QuotaSubject, QuotaUsedSize}

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import scala.concurrent.Future

/** [[UserQuotaApi]] over a `BackendStub`; see [[AccountApiSuite]] for the harness and for the evidence note.
  *
  * Every operation in this group is a `GET`, so there is no retry decision to assert and the suite is about two things:
  * the paths and paging, and the one endpoint in the whole library whose response body is a bare JSON boolean.
  */
final class UserQuotaApiSuite extends AccountApiSuite:

  private val Subject: QuotaSubject = orFail(QuotaSubject.from("size:repos:public"))

  private val QuotaRoot: String = s"$Endpoint/quota"

  test("the report is read from the group's root path, with no query at all"):
    val backend = RecordingBackend(responding(200, UserQuotaApiSuite.ReportBody))

    onApi(backend): api =>
      api.get().map: report =>
        assertEquals(pathOf(backend), QuotaRoot)
        assertEquals(queryOf(backend), Nil)
        assertEquals(report.used.publicRepositories, Some(1024L))
        assertEquals(report.rules.flatMap(_.limit), Vector(1048576L))

  test("a report from an instance that does not enforce quota is a report with no groups, not a failure"):
    onApi(responding(200, "{}")): api =>
      api.get().map: report =>
        assertEquals(report.groups, Vector.empty[QuotaGroup])
        assertEquals(report.used, QuotaUsedSize.Empty)

  test("the check sends its subject and reads the bare boolean the endpoint answers"):
    val backend = RecordingBackend(responding(200, "true"))

    onApi(backend): api =>
      api.check(Subject).map: verdict =>
        assertEquals(pathOf(backend), s"$QuotaRoot/check")
        assertEquals(queryOf(backend), List("subject" -> "size:repos:public"))
        assertEquals(verdict, true)

  test("a refusal is a false verdict, not a failure — the call succeeded and the answer is no"):
    onApi(responding(200, "false")): api =>
      api.check(Subject).map(verdict => assertEquals(verdict, false))

  test("a check answering something other than a boolean is a decoding failure at the document root"):
    onApi(responding(200, """{"ok":true}""")): api =>
      api.attempt.check(Subject).map(outcome => assertEquals(decodingPathOf(outcome), "$"))

  test("the artifact listing targets its own path and pages"):
    val backend = RecordingBackend(responding(200, UserQuotaApiSuite.ArtifactListBody))

    onApi(backend): api =>
      api.artifacts(window(2, 15)).map: page =>
        assertEquals(pathOf(backend), s"$QuotaRoot/artifacts")
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "15"))
        assertEquals(page.items.flatMap(_.name), Vector("coverage"))

  test("the attachment listing targets its own path and lifts the containing object"):
    val backend = RecordingBackend(responding(200, UserQuotaApiSuite.AttachmentListBody))

    onApi(backend): api =>
      api.attachments(PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$QuotaRoot/attachments")
        assertEquals(page.items.flatMap(_.containedIn).flatMap(_.htmlUrl), Vector("https://forge.example/o/r/issues/3"))

  test("the package listing targets its own path and reports one entry per version"):
    val backend = RecordingBackend(responding(200, UserQuotaApiSuite.PackageListBody))

    onApi(backend): api =>
      api.packages(PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$QuotaRoot/packages")
        assertEquals(page.items.flatMap(_.version), Vector("0.1.0", "0.2.0"))

  test("a usage listing with no paging headers reports an unknown total, never zero"):
    onApi(responding(200, UserQuotaApiSuite.ArtifactListBody)): api =>
      api.artifacts(PageParams.First).map(page => assertEquals(page.totalCount, None))

  test("a sparse usage entry still decodes, because none of these models carries an identifier to require"):
    onApi(responding(200, "[{}]")): api =>
      api.artifacts(PageParams.First).map(page => assertEquals(page.items.flatMap(_.name), Vector.empty[String]))

  // --- failures -------------------------------------------------------------

  test("a 403 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(403, AccountApiSuite.ForbiddenBody)): api =>
      api.get().failed.map(failure => assertEquals(summary(unwrap(failure))._1, UserQuotaApi.GetOperation))

  test("a 403 reaches the typed rail as a Left reporting the very same failure"):
    onApi(responding(403, AccountApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.get().failed
        typed  <- api.attempt.get()
      yield assertRailsAgree(raised, typed)

  test("both rails agree on the check as well, whose 422 is the one status specific to it"):
    onApi(responding(422, AccountApiSuite.NotFoundBody)): api =>
      for
        raised <- api.check(Subject).failed
        typed  <- api.attempt.check(Subject)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a paged listing as well"):
    onApi(responding(401, AccountApiSuite.UnauthorizedBody)): api =>
      for
        raised <- api.packages(PageParams.First).failed
        typed  <- api.attempt.packages(PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("a failure carries the operation id of the endpoint it came from, so an alert can name it"):
    onApi(responding(403, AccountApiSuite.ForbiddenBody)): api =>
      api.attempt
        .attachments(PageParams.First)
        .map(outcome => assertEquals(operationOf(outcome), UserQuotaApi.AttachmentsOperation))

  private def unwrap(failure: Throwable): CodebergError =
    failure match
      case CodebergException(error) => error
      case other                    => fail(s"expected a CodebergException, got $other")

  private def onApi[A](backend: Backend[Future])(use: UserQuotaApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(UserQuotaApi(pipeline)))

/** The response bodies this suite stubs, all hand-written from `spec/swagger.v1.json`. */
object UserQuotaApiSuite:

  private val ReportBody: String =
    """{"groups": [{"name": "default", "rules": [{"name": "size-limit", "limit": 1048576,
      | "subjects": ["size:all"]}]}],
      | "used": {"size": {"repos": {"public": 1024, "private": 512}, "git": {"LFS": 4096},
      | "assets": {"artifacts": 64, "attachments": {"issues": 8, "releases": 16},
      | "packages": {"all": 2048}}}}}""".stripMargin

  private val ArtifactListBody: String =
    """[{"name": "coverage", "size": 2048, "html_url": "https://forge.example/o/r/actions/runs/4711"}]"""

  private val AttachmentListBody: String =
    """[{"name": "crash.log", "size": 4096, "api_url": "https://forge.example/api/v1/attachments/9",
      | "contained_in": {"api_url": "https://forge.example/api/v1/repos/o/r/issues/3",
      | "html_url": "https://forge.example/o/r/issues/3"}}]""".stripMargin

  private val PackageListBody: String =
    """[{"name": "codeberg4s", "version": "0.1.0", "type": "maven", "size": 65536},
      | {"name": "codeberg4s", "version": "0.2.0", "type": "maven", "size": 65540}]""".stripMargin
