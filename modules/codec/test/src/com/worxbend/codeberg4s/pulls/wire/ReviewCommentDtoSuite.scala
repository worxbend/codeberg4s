package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.pulls.ReviewComment

import munit.FunSuite

import java.time.Instant

/** [[ReviewCommentDto]] against hand-written payloads.
  *
  * '''There is no golden fixture for this model.''' `golden/MANIFEST.md` holds no review-comment capture — every review
  * the anonymous harvest could reach carried `comments_count: 0` — so the bodies below are derived from
  * `spec/swagger.v1.json`'s `PullReviewComment` definition and are '''not''' evidence of what Codeberg sends. They pin
  * this module's own rules: every field absent-able, `null` and a missing key identical, and a failure reported at the
  * path that caused it.
  */
final class ReviewCommentDtoSuite extends FunSuite:

  test("a fully populated comment converts field for field"):
    val decoded = convert(ReviewCommentDtoSuite.FullBody)

    decoded match
      case Right(comment) =>
        assertEquals(comment.id.value, 918273L)
        assertEquals(comment.reviewId.map(_.value), Some(1654076L))
        assertEquals(comment.body, Some("this quoting is still wrong"))
        assertEquals(comment.path, Some("modules/git/hook_generate.go"))
        assertEquals(comment.position, 42L)
        assertEquals(comment.originalPosition, 0L)
        assertEquals(comment.extraLinesCount, 2L)
        assertEquals(comment.commit.map(_.value), Some("48079baa8d387f3ab770cc144c367409ddc2a879"))
        assertEquals(comment.author.map(_.login), Some("mfenniak"))
        assertEquals(comment.createdAt, Some(Instant.parse("2026-08-01T16:16:13Z")))
      case Left(failure)  => fail(s"could not convert the comment: $failure")

  test("an explicit null and an absent key decode identically, which is the module's whole contract"):
    val absent   = convert("""{"id":7}""")
    val explicit = convert("""{"id":7,"body":null,"path":null,"user":null,"resolver":null,"commit_id":null}""")

    assertEquals(absent, explicit)

  test("a comment nobody resolved reports itself as unresolved, derived from the resolver and not from a flag"):
    convert("""{"id":7}""") match
      case Right(comment) => assertEquals(comment.isResolved, false)
      case Left(failure)  => fail(s"could not convert the comment: $failure")

  test("a resolver makes the conversation resolved"):
    convert("""{"id":7,"resolver":{"id":1,"login":"gusted"}}""") match
      case Right(comment) => assertEquals(comment.isResolved, true)
      case Left(failure)  => fail(s"could not convert the comment: $failure")

  test("Forgejo's zero for an unattached review is absence, not a rejected identifier"):
    convert("""{"id":7,"pull_request_review_id":0}""") match
      case Right(comment) => assertEquals(comment.reviewId, None)
      case Left(failure)  => fail(s"a zero review id must cost the link, not the comment: $failure")

  test("the three positional counts fall back to zero, which is the sentinel Forgejo already uses"):
    convert("""{"id":7}""") match
      case Right(comment) =>
        assertEquals(comment.position, 0L)
        assertEquals(comment.originalPosition, 0L)
        assertEquals(comment.extraLinesCount, 0L)
      case Left(failure)  => fail(s"could not convert the comment: $failure")

  test("a diff hunk keeps its leading whitespace, because a hunk is whitespace-significant"):
    convert("""{"id":7,"diff_hunk":"@@ -1,2 +1,2 @@\n-old\n+new"}""") match
      case Right(comment) => assertEquals(comment.diffHunk, Some("@@ -1,2 +1,2 @@\n-old\n+new"))
      case Left(failure)  => fail(s"could not convert the comment: $failure")

  test("a comment with no id is a decoding failure at $.id"):
    assertEquals(failingPath("""{"body":"x"}"""), "$.id")

  test("a non-positive id is rejected at the same path as a missing one"):
    assertEquals(failingPath("""{"id":0}"""), "$.id")

  test("a commit_id that is not hexadecimal is reported at its own path, not dropped"):
    assertEquals(failingPath("""{"id":7,"commit_id":"nope!"}"""), "$.commit_id")

  test("the original commit is just as strict, and reports its own path"):
    assertEquals(failingPath("""{"id":7,"original_commit_id":"nope!"}"""), "$.original_commit_id")

  test("a bad author is reported at the nested path, and a bad resolver at its own"):
    assertEquals(failingPath("""{"id":7,"user":{"id":1}}"""), "$.user.login")
    assertEquals(failingPath("""{"id":7,"resolver":{"id":1}}"""), "$.resolver.login")

  test("a failing element of a list reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[ReviewCommentDto]]("""[{"id":1},{"id":0}]""")
      .flatMap(dtos => WireModel.all(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].id")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a field of the wrong JSON kind costs that field, not the comment"):
    convert("""{"id":7,"position":"forty-two","body":"still readable"}""") match
      case Right(comment) =>
        assertEquals(comment.position, 0L)
        assertEquals(comment.body, Some("still readable"))
      case Left(failure)  => fail(s"a mistyped field must not fail the comment: $failure")

  test("a garbage body is a DecodeFailure, not an escaping codec exception"):
    assert(Json.decode[ReviewCommentDto]("not json at all").isLeft)

  private def convert(body: String): Either[DecodeFailure, ReviewComment] =
    Json.decode[ReviewCommentDto](body).flatMap(_.toDomain)

  private def failingPath(body: String): String =
    convert(body) match
      case Left(failure) => failure.path.render
      case Right(value)  => fail(s"expected a failure, converted $value")

/** The payloads this suite decodes. Derived from the pinned spec, not captured; see the class note. */
object ReviewCommentDtoSuite:

  private val FullBody: String =
    """{
      |  "id": 918273,
      |  "pull_request_review_id": 1654076,
      |  "body": "this quoting is still wrong",
      |  "path": "modules/git/hook_generate.go",
      |  "position": 42,
      |  "original_position": 0,
      |  "extra_lines_count": 2,
      |  "diff_hunk": "@@ -40,3 +40,3 @@",
      |  "commit_id": "48079baa8d387f3ab770cc144c367409ddc2a879",
      |  "original_commit_id": "0fc17509190ed028a74eafbcf095917715aec3a1",
      |  "user": {"id": 222642, "login": "mfenniak"},
      |  "resolver": null,
      |  "html_url": "https://codeberg.org/forgejo/forgejo/pulls/13726#issuecomment-918273",
      |  "pull_request_url": "https://codeberg.org/forgejo/forgejo/pulls/13726",
      |  "created_at": "2026-08-01T16:16:13Z",
      |  "updated_at": "2026-08-01T16:20:01Z"
      |}""".stripMargin
