package com.worxbend.codeberg4s.notifications.wire

import com.worxbend.codeberg4s.codec.Json

import munit.FunSuite

/** [[NotificationCountDto]] against hand-written bodies.
  *
  * There is no fixture at all for `GET /notifications/new` — not even a synthetic one — because the endpoint needs a
  * token and the golden harvest was anonymous. The bodies below are written from `definitions.NotificationCount` in
  * `spec/swagger.v1.json` and prove only that the reader agrees with it.
  */
final class NotificationCountDtoSuite extends FunSuite:

  private def count(body: String): Either[String, Long] =
    Json.decode[NotificationCountDto](body).flatMap(_.toDomain) match
      case Right(unread) => Right(unread.value)
      case Left(failure) => Left(failure.path.render)

  test("the one key the endpoint declares decodes into the count"):
    assertEquals(count("""{"new":17}"""), Right(17L))

  test("zero unread is a count, not an absent one"):
    assertEquals(count("""{"new":0}"""), Right(0L))

  test("hasUnread reads the count the way the endpoint's own summary phrases the question"):
    Json.decode[NotificationCountDto]("""{"new":0}""").flatMap(_.toDomain) match
      case Right(unread) => assertEquals(unread.hasUnread, false)
      case Left(failure) => fail(s"did not convert: ${failure.path.render}")

  test("keys the DTO does not name are ignored, so a richer payload still decodes"):
    assertEquals(count("""{"new":3,"unseen":9}"""), Right(3L))

  test("an absent count fails at its own path — the endpoint did not answer the question"):
    assertEquals(count("""{}"""), Left("$.new"))

  test("an explicit null is absence, and fails the same way"):
    assertEquals(count("""{"new":null}"""), Left("$.new"))

  test("a count of the wrong JSON kind is absence rather than a decoded zero"):
    assertEquals(count("""{"new":"17"}"""), Left("$.new"))

  test("a negative count is rejected instead of travelling into a caller's dashboard"):
    assertEquals(count("""{"new":-1}"""), Left("$.new"))

  test("a body that is not an object fails without an exception escaping"):
    assert(count("""[]""").isLeft, "an array body should not decode as a count")
