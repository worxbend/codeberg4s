package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.issues.CreateLabel
import com.worxbend.codeberg4s.issues.EditLabel
import com.worxbend.codeberg4s.issues.LabelColor
import com.worxbend.codeberg4s.issues.LabelId
import com.worxbend.codeberg4s.issues.LabelName
import com.worxbend.codeberg4s.paging.PageParams

import sttp.client4.testing.RecordingBackend

import munit.FunSuite

/** [[OrganizationLabelApi]] over a `BackendStub`.
  *
  * The subject is that an organisation label is the '''repository''' label model served from a second path — which
  * `golden/organization/org-labels-list.json` proves, and which `LabelDtoSuite` asserts field for field. What differs
  * is the URI, the `sort` parameter, and the operation ids, so that is what is asserted here.
  */
final class OrganizationLabelApiSuite extends FunSuite with OrganizationStubs:

  private val Bug: LabelId = orFail(LabelId.from(107L))

  // --- request shape --------------------------------------------------------

  test("orgs.labels.list sends page and limit, and no sort when the caller named no ordering"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.labels
        .list(Org, None, window(2, 5))
        .map: _ =>
          assertEquals(methodOf(backend), "GET")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/labels")
          assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "5"))

  test("orgs.labels.list sends the ordering in the spelling the spec's enum uses"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.labels
        .list(Org, Some(OrganizationLabelSort.MostIssues), PageParams.First)
        .map(_ => assertEquals(queryOf(backend), List("sort" -> "mostissues", "page" -> "1", "limit" -> "30")))

  test("the `orgs.labels.get` route addresses the label by id, because a label name is not unique"):
    val backend = RecordingBackend(responding(200, OrganizationLabelApiSuite.LabelBody))

    onApi(backend): api =>
      api.labels
        .get(Org, Bug)
        .map: label =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/labels/107")
          assertEquals(label.id.value, 107L)
          assertEquals(label.name, "bug")

  test("orgs.labels.create posts CreateLabelOption with the colour in the hashed form the spec documents"):
    val backend = RecordingBackend(responding(201, OrganizationLabelApiSuite.LabelBody))

    onApi(backend): api =>
      val command = CreateLabel
        .of(orFail(LabelName.from("needs-triage")), orFail(LabelColor.from("eb6420")))
        .describedAs("nobody has looked at this yet")

      api.labels
        .create(Org, command)
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/labels")
          assertEquals(
            bodyOf(backend),
            """{"name":"needs-triage","color":"#eb6420","description":"nobody has looked at this yet"}""",
          )

  test("orgs.labels.edit patches the label and sends only what the command set"):
    val backend = RecordingBackend(responding(200, OrganizationLabelApiSuite.LabelBody))

    onApi(backend): api =>
      api.labels
        .edit(Org, Bug, EditLabel.Empty.archived(true))
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/labels/107")
          assertEquals(bodyOf(backend), """{"is_archived":true}""")

  test("orgs.labels.delete sends a bodiless DELETE at the label's own path"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.labels
        .delete(Org, Bug)
        .map: _ =>
          assertEquals(methodOf(backend), "DELETE")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/labels/107")
          assertEquals(bodyOf(backend), NoBody)

  // --- retries --------------------------------------------------------------

  test("orgs.labels.create is never retried, because a repeat would create a second label with the same name"):
    val backend = RecordingBackend(flakyThen(201, OrganizationLabelApiSuite.LabelBody))

    onApi(backend): api =>
      api.labels.attempt
        .create(Org, CreateLabel.of(orFail(LabelName.from("bug")), orFail(LabelColor.from("eb6420"))))
        .map(_ => assertEquals(attemptsOn(backend), 1, "the label create was repeated"))

  test("orgs.labels.edit is never retried, because a repeat would clobber somebody else's rename"):
    val backend = RecordingBackend(flakyThen(200, OrganizationLabelApiSuite.LabelBody))

    onApi(backend): api =>
      api.labels.attempt
        .edit(Org, Bug, EditLabel.Empty.archived(true))
        .map(_ => assertEquals(attemptsOn(backend), 1, "the label edit was repeated"))

  test("orgs.labels.delete is retried, because a label id is a row id the instance never reuses"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.labels.delete(Org, Bug).map(_ => assertEquals(attemptsOn(backend), 2, "the 503 was not retried"))

  // --- payloads and failures ------------------------------------------------

  test("a label listing yields the Label model the issue group owns, colour normalised"):
    onApi(responding(200, s"[${OrganizationLabelApiSuite.LabelBody}]")): api =>
      api.labels
        .list(Org, None, PageParams.First)
        .map: page =>
          assertEquals(page.items.map(_.name), Vector("bug"))
          assertEquals(page.items.flatMap(_.color).map(_.value), Vector("eb6420"))

  test("a colour the instance sends in a shape LabelColor rejects costs the colour, not the label"):
    onApi(responding(200, """[{"id":107,"name":"bug","color":"not-a-colour"}]""")): api =>
      api.labels
        .list(Org, None, PageParams.First)
        .map: page =>
          assertEquals(page.items.map(_.name), Vector("bug"))
          assertEquals(page.items.map(_.color), Vector(None))

  test("a page past the end is an empty page, not a failure"):
    onApi(responding(200, "[]")): api =>
      api.labels
        .list(Org, None, PageParams.First)
        .map: page =>
          assertEquals(page.items.size, 0)
          assertEquals(page.isLast, true)

  test("a 404 on the single-label read reaches both rails identically"):
    onApi(responding(404, OrganizationStubs.NotFoundBody)): api =>
      for
        raised <- api.labels.get(Org, Bug).failed
        typed  <- api.labels.attempt.get(Org, Bug)
      yield
        assertEquals(operationOf(typed), OrganizationLabelApi.GetOperation)
        assertRailsAgree(raised, typed)

  test("a 422 on the label create reaches both rails identically"):
    onApi(responding(422, OrganizationStubs.NotFoundBody)): api =>
      val command = CreateLabel.of(orFail(LabelName.from("bug")), orFail(LabelColor.from("eb6420")))

      for
        raised <- api.labels.create(Org, command).failed
        typed  <- api.labels.attempt.create(Org, command)
      yield
        assertEquals(operationOf(typed), OrganizationLabelApi.CreateOperation)
        assertRailsAgree(raised, typed)

  test("a bad element of a label listing reports its position all the way through the pipeline"):
    onApi(responding(200, """[{"id":1,"name":"a"},{"id":2}]""")): api =>
      api.labels.attempt.list(Org, None, PageParams.First).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].name")
        case other                                             => fail(s"expected a decoding failure, got $other")

/** The response bodies this suite stubs, shaped like the elements of `golden/organization/org-labels-list.json`. */
object OrganizationLabelApiSuite:

  /** One label, with the colour in the bare form every capture shows Forgejo returning. */
  val LabelBody: String =
    """{
      |  "id": 107,
      |  "name": "bug",
      |  "color": "eb6420",
      |  "description": "something is broken",
      |  "exclusive": false,
      |  "is_archived": false,
      |  "url": "https://codeberg.org/api/v1/orgs/forgejo/labels/107"
      |}""".stripMargin
