package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.issues.LifecycleState

import munit.FunSuite

import java.time.Instant

/** [[IssueDto]] against all five golden issue captures.
  *
  * These fixtures are the reason the DTO exists in the shape it does: `docs/HAZARDS.md` §1 measured `assignee`,
  * `assignees`, `closed_at`, `due_date` and `milestone` arriving as JSON `null` on the first issue of the first page,
  * and `assignees` is declared `type: array`, which a derived codec aborts on.
  */
final class IssueDtoSuite extends FunSuite with GoldenFixtures:

  test("the single golden issue converts field for field"):
    val issue = single

    assertEquals(issue.id, 6557096L)
    assertEquals(issue.number.value, 2966L)
    assertEquals(issue.title, "Git HTTPS smart HTTP endpoint returns 403 \"Bye\" for all repositories")
    assertEquals(issue.author.map(_.login), Some("personanon5"))
    assertEquals(issue.commentCount, 2L)
    assertEquals(issue.isLocked, false)
    assertEquals(issue.htmlUrl, Some("https://codeberg.org/Codeberg/Community/issues/2966"))
    assertEquals(issue.createdAt, Some(Instant.parse("2026-07-31T16:47:18Z")))
    assertEquals(issue.updatedAt, Some(Instant.parse("2026-07-31T18:21:21Z")))

  test("the nulls the spec swears cannot happen decode as absence, not as a crash"):
    val issue = single

    assertEquals(issue.assignees, Vector.empty)
    assertEquals(issue.milestone, None)
    assertEquals(issue.dueDate, None)
    assertEquals(issue.labels, Vector.empty)

  test("an empty ref is folded to absence rather than reaching the domain as an empty string"):
    assertEquals(single.ref, None)

  test("the reduced repository object becomes a slug, so a cross-repository listing stays addressable"):
    assertEquals(single.repository.map(_.value), Some("Codeberg/Community"))

  test("an open issue is Open and carries no closing instant"):
    assertEquals(single.state, LifecycleState.Open)

  test("a closed issue carries its closing instant inside the state"):
    assertEquals(
      first("issue/list-closed.json").state,
      LifecycleState.Closed(Some(Instant.parse("2026-08-01T07:46:45Z"))),
    )

  test("the closed listing decodes three issues, all of them closed"):
    assertEquals(list("issue/list-closed.json").count(_.state.isClosed), 3)

  test("labels embedded in an issue convert with the rest of it"):
    val issue = first("issue/list-closed.json")

    assertEquals(issue.labels.map(_.name), Vector("bug", "s/Forgejo", "upstream"))
    assertEquals(issue.labels.map(_.id.value), Vector(102L, 338L, 11356L))
    assertEquals(issue.labels.headOption.flatMap(_.color).map(_.value), Some("ee0701"))

  test("the issue listing really does contain pull requests, and they say so"):
    val pull = first("issue/list-labelled.json")

    assertEquals(pull.isPullRequest, true)
    assertEquals(pull.number.value, 13731L)
    assertEquals(pull.repository.map(_.value), Some("forgejo/forgejo"))

  test("a plain issue is not marked as a pull request"):
    assertEquals(single.isPullRequest, false)

  test("an exclusive scoped label on a pull request keeps its flag"):
    assertEquals(
      first("issue/list-labelled.json").labels.filter(_.isExclusive).map(_.name),
      Vector("bug/confirmed", "test/present"),
    )

  test("an embedded milestone converts, including its own lifecycle state"):
    first("issue/search.json").milestone match
      case Some(milestone) =>
        assertEquals(milestone.id.value, 96944L)
        assertEquals(milestone.title, "v1.x")
        assertEquals(milestone.state, LifecycleState.Open)
        assertEquals(milestone.openIssueCount, 10L)
        assertEquals(milestone.closedIssueCount, 1L)
      case None            => fail("the first search result was expected to carry a milestone")

  test("the cross-repository search listing keeps each issue's own repository"):
    assertEquals(first("issue/search.json").repository.map(_.value), Some("kiconnect/KiConnect"))

  test("the open listing decodes three open issues"):
    assertEquals(list("issue/list-open.json").count(_.state.isOpen), 3)

  test("an issue with no number is a decoding failure at $.number"):
    assertEquals(failingPath("""{"id":1,"title":"t","state":"open"}"""), "$.number")

  test("an issue with no title is a decoding failure at $.title"):
    assertEquals(failingPath("""{"id":1,"number":2,"state":"open"}"""), "$.title")

  test("an issue with no state is a decoding failure at $.state"):
    assertEquals(failingPath("""{"id":1,"number":2,"title":"t"}"""), "$.state")

  test("an issue with no id is a decoding failure at $.id"):
    assertEquals(failingPath("""{"number":2,"title":"t","state":"open"}"""), "$.id")

  test("a bad label inside an issue is reported at its own position"):
    assertEquals(
      failingPath("""{"id":1,"number":2,"title":"t","state":"open","labels":[{"id":1,"name":"a"},{"id":2}]}"""),
      "$.labels[1].name",
    )

  test("a bad assignee inside an issue is reported at its own position"):
    assertEquals(
      failingPath("""{"id":1,"number":2,"title":"t","state":"open","assignees":[{"id":1,"login":"a"},{"id":2}]}"""),
      "$.assignees[1].login",
    )

  test("a repository object that cannot be turned into a slug costs the slug, not the issue"):
    val decoded = convert("""{"id":1,"number":2,"title":"t","state":"open","repository":{"owner":"a/b","name":"c"}}""")

    assertEquals(decoded.map(_.repository), Right(None))

  test("a failing element of a list reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[IssueDto]]("""[{"id":1,"number":1,"title":"t","state":"open"},{"id":2,"number":2,"title":"t"}]""")
      .flatMap(dtos => IssueDto.toDomainAll(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].state")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a garbage body is a DecodeFailure, not an escaping codec exception"):
    assert(Json.decode[IssueDto]("not json at all").isLeft)
    assert(Json.decode[Vector[IssueDto]]("""{"id":1}""").isLeft)

  private def single: Issue =
    convert(golden("issue/single.json")) match
      case Right(issue)  => issue
      case Left(failure) => fail(s"could not decode the single issue: $failure")

  private def list(path: String): Vector[Issue] =
    Json.decode[Vector[IssueDto]](golden(path)).flatMap(dtos => IssueDto.toDomainAll(JsonPath.Root, dtos)) match
      case Right(values) => values
      case Left(failure) => fail(s"could not decode $path: $failure")

  private def first(path: String): Issue =
    list(path).headOption match
      case Some(issue) => issue
      case None        => fail(s"$path held no issues")

  private def convert(body: String): Either[DecodeFailure, Issue] =
    Json.decode[IssueDto](body).flatMap(_.toDomain)

  private def failingPath(body: String): String =
    convert(body) match
      case Left(failure) => failure.path.render
      case Right(value)  => fail(s"expected a failure, converted $value")
