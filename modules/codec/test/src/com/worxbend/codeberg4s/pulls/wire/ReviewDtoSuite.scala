package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{GoldenFixtures, Json, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.pulls.Review
import com.worxbend.codeberg4s.pulls.ReviewState

import munit.FunSuite

import java.time.Instant

/** [[ReviewDto]] against `golden/pull/reviews-list.json`.
  *
  * Three rows of one pull request, two of which are review '''requests''' rather than reviews. That mix is the whole
  * reason [[com.worxbend.codeberg4s.pulls.Review]] exists in the shape it does.
  */
final class ReviewDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden review listing decodes all three rows"):
    assertEquals(reviews.map(_.id.value), Vector(1654064L, 1654067L, 1654076L))
    assertEquals(reviews.flatMap(_.author).map(_.login), Vector("Gusted", "patdyn", "mfenniak"))

  test("two of the three rows are review requests, not reviews"):
    assertEquals(
      reviews.map(_.state),
      Vector(Some(ReviewState.RequestReview), Some(ReviewState.RequestReview), Some(ReviewState.Approved)),
    )

  test("the approval converts field for field"):
    reviews.lift(2) match
      case Some(review) =>
        assertEquals(review.author.map(_.login), Some("mfenniak"))
        assertEquals(review.commit.map(_.value), Some("48079baa8d387f3ab770cc144c367409ddc2a879"))
        assertEquals(review.isOfficial, true)
        assertEquals(review.isStale, false)
        assertEquals(review.isDismissed, false)
        assertEquals(review.commentCount, 0L)
        assertEquals(review.submittedAt, Some(Instant.parse("2026-08-01T16:16:13Z")))
      case None         => fail("the fixture was expected to hold a third review")

  test("a review request pins no commit, because nothing has been reviewed yet"):
    assertEquals(reviews.headOption.flatMap(_.commit), None)

  test("the empty strings Forgejo sends for an unwritten review are absence, not empty text"):
    reviews.headOption match
      case Some(review) =>
        assertEquals(review.body, None)
        assertEquals(review.htmlUrl, None)
      case None         => fail("the fixture was expected to hold a first review")

  test("the pull request URL survives on every row, so a detached review is still traceable"):
    assertEquals(
      reviews.flatMap(_.pullRequestUrl).distinct,
      Vector("https://codeberg.org/forgejo/forgejo/pulls/13726"),
    )

  test("a state this library does not recognise costs the state, not the review"):
    val decoded = convert("""{"id":7,"state":"ABDICATED"}""")

    assertEquals(decoded.map(_.state), Right(None))

  test("a review with no id is a decoding failure at $.id"):
    assertEquals(failingPath("""{"state":"APPROVED"}"""), "$.id")

  test("a non-positive id is rejected at the same path as a missing one"):
    assertEquals(failingPath("""{"id":0,"state":"APPROVED"}"""), "$.id")

  test("a commit_id that is not hexadecimal is reported at its own path, not dropped"):
    assertEquals(failingPath("""{"id":7,"state":"APPROVED","commit_id":"nope!"}"""), "$.commit_id")

  test("a bad user inside a review is reported at the nested path"):
    assertEquals(failingPath("""{"id":7,"state":"APPROVED","user":{"id":1}}"""), "$.user.login")

  test("a failing element of a list reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[ReviewDto]]("""[{"id":1},{"id":0}]""")
      .flatMap(dtos => WireModel.all(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].id")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a garbage body is a DecodeFailure, not an escaping codec exception"):
    assert(Json.decode[ReviewDto]("not json at all").isLeft)

  private def reviews: Vector[Review] =
    Json
      .decode[Vector[ReviewDto]](golden("pull/reviews-list.json"))
      .flatMap(dtos => WireModel.all(JsonPath.Root, dtos)) match
      case Right(values) => values
      case Left(failure) => fail(s"could not decode the review listing: $failure")

  private def convert(body: String): Either[DecodeFailure, Review] =
    Json.decode[ReviewDto](body).flatMap(_.toDomain)

  private def failingPath(body: String): String =
    convert(body) match
      case Left(failure) => failure.path.render
      case Right(value)  => fail(s"expected a failure, converted $value")
