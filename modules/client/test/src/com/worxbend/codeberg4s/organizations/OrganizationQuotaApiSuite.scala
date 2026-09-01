package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.quota.{QuotaSubject, QuotaUsedSize}

import sttp.client4.testing.RecordingBackend

import munit.FunSuite

/** [[OrganizationQuotaApi]] over a `BackendStub`.
  *
  * Every route here is a `GET`, so there is no retry judgement to assert. What is worth asserting is the shape: five
  * paths under one segment, one required query parameter, and one response that is a bare JSON boolean rather than an
  * object — the only such response in the whole organisation surface.
  *
  * '''No quota fixture exists'''; see [[com.worxbend.codeberg4s.quota.QuotaInfo]]. The payloads below are the spec's
  * definitions written by hand.
  */
final class OrganizationQuotaApiSuite extends FunSuite with OrganizationStubs:

  private val Subject: QuotaSubject = orFail(QuotaSubject.from("size:repos:public"))

  // --- request shape --------------------------------------------------------

  test("the `orgs.quota.get` route reads the organisation's quota with no query at all"):
    val backend = RecordingBackend(responding(200, OrganizationQuotaApiSuite.QuotaBody))

    onApi(backend): api =>
      api.quota
        .get(Org)
        .map: _ =>
          assertEquals(methodOf(backend), "GET")
          assertEquals(dialled(backend), "https://forge.example/api/v1/orgs/forgejo/quota")

  test("orgs.quota.check always sends its subject, because the spec marks it required"):
    val backend = RecordingBackend(responding(200, "true"))

    onApi(backend): api =>
      api.quota
        .check(Org, Subject)
        .map: verdict =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/quota/check")
          assertEquals(queryOf(backend), List("subject" -> "size:repos:public"))
          assertEquals(verdict, true)

  test("the three usage listings are three paths under the quota segment, each paged"):
    val artifacts   = RecordingBackend(responding(200, "[]"))
    val attachments = RecordingBackend(responding(200, "[]"))
    val packages    = RecordingBackend(responding(200, "[]"))

    for
      _ <- onApi(artifacts)(api => api.quota.artifacts(Org, window(2, 5)))
      _ <- onApi(attachments)(api => api.quota.attachments(Org, PageParams.First))
      _ <- onApi(packages)(api => api.quota.packages(Org, PageParams.First))
    yield
      assertEquals(pathOf(artifacts), "https://forge.example/api/v1/orgs/forgejo/quota/artifacts")
      assertEquals(pathOf(attachments), "https://forge.example/api/v1/orgs/forgejo/quota/attachments")
      assertEquals(pathOf(packages), "https://forge.example/api/v1/orgs/forgejo/quota/packages")
      assertEquals(queryOf(artifacts), List("page" -> "2", "limit" -> "5"))

  // --- payloads -------------------------------------------------------------

  test("a quota read decodes the whole size tree, LFS included"):
    onApi(responding(200, OrganizationQuotaApiSuite.QuotaBody)): api =>
      api.quota
        .get(Org)
        .map: info =>
          assertEquals(info.used.publicRepositories, Some(1024L))
          assertEquals(info.used.gitLfs, Some(65536L))
          assertEquals(info.groups.flatMap(_.rules).flatMap(_.subjects), Vector("size:repos:all"))

  test("an instance that tracks nothing yields an empty tree rather than a decoding failure"):
    onApi(responding(200, "{}")): api =>
      api.quota
        .get(Org)
        .map: info =>
          assertEquals(info.groups, Vector.empty)
          assertEquals(info.used, QuotaUsedSize.Empty)

  test("a false verdict is an answer on the success channel, not a failure"):
    onApi(responding(200, "false")): api =>
      api.quota.check(Org, Subject).map(verdict => assertEquals(verdict, false))

  test("a false verdict is a Right on the typed rail too"):
    onApi(responding(200, "false")): api =>
      api.quota.attempt.check(Org, Subject).map(result => assertEquals(result, Right(false)))

  test("a verdict body that is not a boolean is a decoding failure at the root"):
    onApi(responding(200, """{"ok":true}""")): api =>
      api.quota.attempt.check(Org, Subject).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a package usage listing keeps a registry name this library does not recognise"):
    onApi(responding(200, """[{"name":"codeberg4s","type":"some-future-registry","size":128}]""")): api =>
      api.quota
        .packages(Org, PageParams.First)
        .map: page =>
          assertEquals(page.items.map(_.packageType), Vector(Some("some-future-registry")))
          assertEquals(page.items.map(_.sizeBytes), Vector(Some(128L)))

  test("an attachment usage listing carries the context an attachment hangs off"):
    val body = """[{"name":"crash.log","size":64,""" +
      """"contained_in":{"api_url":"https://forge.example/api/i/2","html_url":"https://forge.example/i/2"}}]"""

    onApi(responding(200, body)): api =>
      api.quota
        .attachments(Org, PageParams.First)
        .map: page =>
          assertEquals(page.items.flatMap(_.containedIn).flatMap(_.htmlUrl), Vector("https://forge.example/i/2"))

  // --- failures -------------------------------------------------------------

  test("a 404 — which is also what an instance without quota answers — reaches both rails identically"):
    onApi(responding(404, OrganizationStubs.NotFoundBody)): api =>
      for
        raised <- api.quota.get(Org).failed
        typed  <- api.quota.attempt.get(Org)
      yield
        assertEquals(operationOf(typed), OrganizationQuotaApi.GetOperation)
        assertRailsAgree(raised, typed)

  test("a 422 on an unknown subject reaches both rails identically"):
    onApi(responding(422, OrganizationStubs.NotFoundBody)): api =>
      for
        raised <- api.quota.check(Org, Subject).failed
        typed  <- api.quota.attempt.check(Org, Subject)
      yield
        assertEquals(operationOf(typed), OrganizationQuotaApi.CheckOperation)
        assertRailsAgree(raised, typed)

  test("a 403 on the artifact listing reaches both rails identically"):
    onApi(responding(403, OrganizationStubs.UnauthorizedBody)): api =>
      for
        raised <- api.quota.artifacts(Org, PageParams.First).failed
        typed  <- api.quota.attempt.artifacts(Org, PageParams.First)
      yield
        assertEquals(operationOf(typed), OrganizationQuotaApi.ArtifactsOperation)
        assertRailsAgree(raised, typed)

/** The response bodies this suite stubs. Derived from the spec's quota definitions; no capture exists. */
object OrganizationQuotaApiSuite:

  /** A quota tree with one group, one rule, and a size in every branch the spec declares. */
  val QuotaBody: String =
    """{
      |  "groups": [{"name": "default", "rules": [{"name": "repo-size", "limit": 1073741824,
      |    "subjects": ["size:repos:all"]}]}],
      |  "used": {"size": {"repos": {"public": 1024, "private": 2048},
      |    "assets": {"artifacts": 4096, "attachments": {"issues": 8192, "releases": 16384},
      |      "packages": {"all": 32768}},
      |    "git": {"LFS": 65536}}}
      |}""".stripMargin
