package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{GoldenFixtures, Json, WireModel}
import com.worxbend.codeberg4s.issues.Comment

import munit.FunSuite

import java.time.Instant

/** [[CommentDto]] against `golden/issue/comments-list.json`. */
final class CommentDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden comment listing decodes into two comments"):
    assertEquals(comments.size, 2)

  test("the first golden comment converts field for field"):
    val comment = comments.head

    assertEquals(comment.id.value, 20366420L)
    assertEquals(comment.author.map(_.login), Some("jkassel"))
    assertEquals(comment.issueUrl, Some("https://codeberg.org/Codeberg/Community/issues/2966"))
    assertEquals(comment.createdAt, Some(Instant.parse("2026-07-31T17:20:04Z")))
    assert(comment.body.exists(_.startsWith("Happening to me too")), comment.body.toString)

  test("Forgejo's empty-string-for-absent is folded away, so a plain issue comment has no pull-request URL"):
    assertEquals(comments.head.pullRequestUrl, None)
    assertEquals(comments.head.originalAuthor, None)

  test("a comment with no author is not a failure, because imported comments have none"):
    CommentDto(
      Some(1L),
      Some("body"),
      None,
      Some("someone@elsewhere"),
      None,
      None,
      None,
      None,
      None,
      None,
    ).toDomain match
      case Right(comment) =>
        assertEquals(comment.author, None)
        assertEquals(comment.originalAuthor, Some("someone@elsewhere"))
      case Left(failure)  => fail(s"expected a comment, got $failure")

  test("a comment with no id is a decoding failure at $.id"):
    CommentDto(None, Some("body"), None, None, None, None, None, None, None, None).toDomain match
      case Left(failure) => assertEquals(failure.path.render, "$.id")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a failure inside the author is reported at $.user, not at the comment"):
    val decoded = Json.decode[CommentDto]("""{"id":1,"user":{"login":"nobody"}}""").flatMap(_.toDomain)

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$.user.id")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a failing element reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[CommentDto]]("""[{"id":1},{"body":"orphan"}]""")
      .flatMap(dtos => WireModel.all(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].id")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("an assets array the DTO does not model does not break the decode"):
    val decoded = Json.decode[CommentDto]("""{"id":1,"assets":[{"id":9,"name":"x.png"}]}""").flatMap(_.toDomain)

    assertEquals(decoded.map(_.id.value), Right(1L))

  private def comments: Vector[Comment] =
    Json
      .decode[Vector[CommentDto]](golden("issue/comments-list.json"))
      .flatMap(dtos => WireModel.all(JsonPath.Root, dtos)) match
      case Right(values) => values
      case Left(failure) => fail(s"could not decode the comment listing: $failure")
