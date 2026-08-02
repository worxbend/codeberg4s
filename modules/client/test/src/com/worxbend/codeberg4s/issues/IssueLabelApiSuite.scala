package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future

import java.time.Instant

/** [[IssueLabelApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The sharpest thing under test is the add-versus-replace distinction: the same body, two methods, two different retry
  * decisions. Decoding is asserted against `golden/issue/labels-repo.json` in `modules/codec`, so the payloads here are
  * small hand-written bodies chosen to exercise a seam.
  */
final class IssueLabelApiSuite extends FunSuite with IssueLaneHarness:

  private val Bug: LabelId = orFail(LabelId.from(102L))

  private val Upstream: LabelId = orFail(LabelId.from(11356L))

  private val LabelBody: String =
    """{"id": 102, "name": "bug", "color": "ee0701", "exclusive": false, "is_archived": false}"""

  private val LabelListBody: String = s"[$LabelBody]"

  test("a single-label read addresses a repository label by id"):
    val backend = RecordingBackend(responding(200, LabelBody))

    onApi(backend): api =>
      api.get(Handle, Name, Bug).map: label =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/labels/102")
        assertEquals(label.name, "bug")

  test("an edit PATCHes only what the command sets, and sends the hashed colour Forgejo documents for input"):
    val backend = RecordingBackend(responding(200, LabelBody))
    val command = EditLabel.Empty.colouredAs(orFail(LabelColor.from("ee0701")))

    onApi(backend): api =>
      api.edit(Handle, Name, Bug, command).map: _ =>
        assertEquals(methodOf(backend), "PATCH")
        assertEquals(bodyOf(backend), """{"color":"#ee0701"}""")

  test("an edit that turns a flag off says so, which a plain Boolean could not"):
    val backend = RecordingBackend(responding(200, LabelBody))

    onApi(backend): api =>
      api
        .edit(Handle, Name, Bug, EditLabel.Empty.archived(false))
        .map(_ => assertEquals(bodyOf(backend), """{"is_archived":false}"""))

  test("an edit is never retried, because a repeat could overwrite somebody else's rename"):
    val backend = RecordingBackend(flakyThen(200, LabelBody))

    onApi(backend): api =>
      api.attempt
        .edit(Handle, Name, Bug, EditLabel.Empty.describedAs("broken"))
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the PATCH was retried"))

  test("deleting a repository label is retried, because it names one label row id"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.delete(Handle, Name, Bug).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(backend.allInteractions.size, 2, "the 503 on a delete was not retried")

  test("an issue's label listing targets the issue and sends no paging, because none is declared"):
    val backend = RecordingBackend(responding(200, LabelListBody))

    onApi(backend): api =>
      api.listOnIssue(Handle, Name, Number).map: labels =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/labels")
        assertEquals(queryOf(backend), Nil)
        assertEquals(labels.map(_.name), Vector("bug"))

  test("an unlabelled issue answers an empty array, which is a success and not a 404"):
    onApi(responding(200, "[]")): api =>
      api.listOnIssue(Handle, Name, Number).map(labels => assertEquals(labels, Vector.empty[Label]))

  test("adding labels POSTs their ids as JSON integers"):
    val backend = RecordingBackend(responding(200, LabelListBody))

    onApi(backend): api =>
      api.addToIssue(Handle, Name, Number, LabelUpdate.byId(Vector(Bug, Upstream))).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(bodyOf(backend), """{"labels":[102,11356]}""")

  test("labels may be named by name instead, and are then sent as JSON strings"):
    val backend = RecordingBackend(responding(200, LabelListBody))
    val command = LabelUpdate.byName(Vector(orFail(LabelName.from("bug"))))

    onApi(backend): api =>
      api
        .addToIssue(Handle, Name, Number, command)
        .map(_ => assertEquals(bodyOf(backend), """{"labels":["bug"]}"""))

  test("adding is never retried, because a POST adds to a set the request does not fully describe"):
    val backend = RecordingBackend(flakyThen(200, LabelListBody))

    onApi(backend): api =>
      api.attempt
        .addToIssue(Handle, Name, Number, LabelUpdate.byId(Vector(Bug)))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on an add must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("replacing is a PUT and IS retried, because the body states the complete set"):
    val backend = RecordingBackend(flakyThen(200, LabelListBody))

    onApi(backend): api =>
      api.replaceOnIssue(Handle, Name, Number, LabelUpdate.byId(Vector(Bug))).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(backend.allInteractions.size, 2, "the 503 on a replace was not retried")

  test("replacing with no labels clears the issue, and says so on the wire"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .replaceOnIssue(Handle, Name, Number, LabelUpdate.Empty)
        .map(_ => assertEquals(bodyOf(backend), """{"labels":[]}"""))

  test("an update that backdates itself sends updated_at alongside the labels"):
    val backend = RecordingBackend(responding(200, LabelListBody))
    val command = LabelUpdate.byId(Vector(Bug)).recordedAt(Instant.parse("2026-07-01T00:00:00Z"))

    onApi(backend): api =>
      api
        .replaceOnIssue(Handle, Name, Number, command)
        .map(_ => assertEquals(bodyOf(backend), """{"labels":[102],"updated_at":"2026-07-01T00:00:00Z"}"""))

  test("removing one label by id puts it in the path and sends an empty object as the body"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.removeFromIssue(Handle, Name, Number, LabelRef.ById(Bug), LabelRemoval.Empty).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/labels/102")
        assertEquals(bodyOf(backend), "{}")

  test("removing one label by name puts the name in the path"):
    val backend = RecordingBackend(responding(204, ""))
    val label   = LabelRef.ByName(orFail(LabelName.from("bug")))

    onApi(backend): api =>
      api
        .removeFromIssue(Handle, Name, Number, label, LabelRemoval.Empty)
        .map: _ =>
          assertEquals(
            pathOf(backend),
            "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/labels/bug",
          )

  test("clearing every label targets the issue's labels with no identifier, and is retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.clearOnIssue(Handle, Name, Number, LabelRemoval.Empty).map: _ =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/labels")
        assertEquals(backend.allInteractions.size, 2, "the 503 on a clear was not retried")

  test("a 404 reaches both rails as the very same failure"):
    onApi(responding(404, IssueLaneHarness.NotFoundBody)): api =>
      for
        raised <- api.get(Handle, Name, Bug).failed
        typed  <- api.attempt.get(Handle, Name, Bug)
      yield assertRailsAgree(raised, typed)

  test("a bad element of a label list reports its position, all the way through the pipeline"):
    onApi(responding(200, """[{"id":1,"name":"a"},{"name":"orphan"}]""")): api =>
      api.attempt.listOnIssue(Handle, Name, Number).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  private def onApi[A](backend: Backend[Future])(use: IssueLabelApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(IssueLabelApi(pipeline)))
