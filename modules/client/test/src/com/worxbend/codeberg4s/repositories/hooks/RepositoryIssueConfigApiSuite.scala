package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.CodebergError

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import scala.concurrent.Future

/** [[RepositoryIssueConfigApi]] over a `BackendStub`.
  *
  * The one behaviour worth guarding here is that an '''invalid''' issue config is a successful call carrying a verdict,
  * not a failure. A caller who treats a completed `Future` as a pass has misread the endpoint, and the second test is
  * what keeps that distinction visible.
  */
final class RepositoryIssueConfigApiSuite extends HookApiSuite:

  test("the issue config read targets issue_config and decodes the contact links"):
    val backend = RecordingBackend(responding(200, RepositoryIssueConfigApiSuite.ConfigBody))

    onApi(backend): api =>
      api.get(Handle, Name).map: config =>
        assertEquals(pathOf(backend), s"$Repository/issue_config")
        assertEquals(methodOf(backend), "GET")
        assertEquals(config.blankIssuesEnabled, Some(false))
        assertEquals(config.contactLinks.map(_.name), Vector("Security"))

  test("an invalid config is a successful call carrying the verdict, not a failure"):
    val backend = RecordingBackend(responding(200, """{"valid":false,"message":"yaml: line 3"}"""))

    onApi(backend): api =>
      api.validate(Handle, Name).map: verdict =>
        assertEquals(pathOf(backend), s"$Repository/issue_config/validate")
        assertEquals(verdict.isValid, false)
        assertEquals(verdict.message, Some("yaml: line 3"))

  test("a verdict with no verdict is a decoding failure rather than a silent false"):
    onApi(responding(200, """{"message":"something"}""")): api =>
      api.attempt.validate(Handle, Name).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.valid")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("the template listing targets issue_templates, unpaged, and tells a form from Markdown"):
    val backend = RecordingBackend(responding(200, RepositoryIssueConfigApiSuite.TemplatesBody))

    onApi(backend): api =>
      api.templates(Handle, Name).map: templates =>
        assertEquals(pathOf(backend), s"$Repository/issue_templates")
        assertEquals(queryOf(backend), Nil)
        assertEquals(templates.map(_.fileName), Vector("bug.md", "feature.yaml"))
        assertEquals(templates.map(_.isForm), Vector(false, true))

  test("a repository with no config file still answers, because nothing in the model is required"):
    onApi(responding(200, "{}")): api =>
      api.get(Handle, Name).map: config =>
        assertEquals(config.blankIssuesEnabled, None)
        assertEquals(config.contactLinks, Vector.empty[IssueContactLink])

  test("a 404 reaches both rails as the very same failure"):
    onApi(responding(404, HookApiSuite.NotFoundBody)): api =>
      for
        raised <- api.get(Handle, Name).failed
        typed  <- api.attempt.get(Handle, Name)
      yield
        assertRailsAgree(raised, typed)
        assertEquals(summary(materialise(typed))._1, RepositoryIssueConfigApi.GetOperation)

  test("a 404 on the template listing reaches both rails identically too"):
    onApi(responding(404, HookApiSuite.NotFoundBody)): api =>
      for
        raised <- api.templates(Handle, Name).failed
        typed  <- api.attempt.templates(Handle, Name)
      yield assertRailsAgree(raised, typed)

  test("a bad element of the template listing reports its position"):
    onApi(responding(200, """[{"file_name":"a.md"},{"name":"b"}]""")): api =>
      api.attempt.templates(Handle, Name).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].file_name")
        case other                                             => fail(s"expected a decoding failure, got $other")

  private def materialise[A](result: Either[CodebergError, A]): CodebergError =
    result match
      case Left(error) => error
      case Right(_)    => fail("expected a failure")

  private def onApi[A](backend: Backend[Future])(use: RepositoryIssueConfigApi => Future[A]): Future[A] =
    onPipeline(backend, (pipeline, exec) => RepositoryIssueConfigApi(pipeline)(using exec))(use)

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour. */
object RepositoryIssueConfigApiSuite:

  private val ConfigBody: String =
    """{
      |  "blank_issues_enabled": false,
      |  "contact_links": [{"name": "Security", "url": "https://example.org/security", "about": "Report privately"}]
      |}""".stripMargin

  private val TemplatesBody: String =
    """[
      |  {"file_name": "bug.md", "name": "Bug report", "content": "What happened?\n"},
      |  {"file_name": "feature.yaml", "name": "Feature", "body": [{"id": "summary", "type": "input"}]}
      |]""".stripMargin
