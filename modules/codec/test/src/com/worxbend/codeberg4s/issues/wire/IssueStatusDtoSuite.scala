package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.issues.IssueDeadline
import com.worxbend.codeberg4s.issues.IssueSubscription

import munit.FunSuite

import java.time.Instant

/** The two small single-object responses in the issue group: [[IssueDeadlineDto]] and [[IssueSubscriptionDto]].
  *
  * '''Both are derived from `spec/swagger.v1.json`, not from a captured response''': setting a deadline needs a token,
  * and the subscription check answers about the authenticated account, so the anonymous harvest could reach neither.
  *
  * Neither DTO can fail, which is the property under test alongside the `docs/HAZARDS.md` §1 rule that present, JSON
  * `null` and absent decode alike.
  */
final class IssueStatusDtoSuite extends FunSuite:

  test("a deadline decodes when present"):
    assertEquals(
      deadline("""{"due_date":"2026-09-01T00:00:00Z"}""").dueDate,
      Some(Instant.parse("2026-09-01T00:00:00Z")),
    )

  test("a null due_date and an absent one decode identically, and neither is a failure"):
    assertEquals(deadline("""{"due_date":null}"""), deadline("{}"))
    assertEquals(deadline("{}").dueDate, None)

  test("Forgejo's Go zero-time sentinel for a cleared deadline is folded into absence"):
    assertEquals(deadline("""{"due_date":"0001-01-01T00:00:00Z"}""").dueDate, None)

  test("a due_date that is not a timestamp is absence, not a failure — the call still succeeded"):
    assertEquals(deadline("""{"due_date":"whenever"}""").dueDate, None)

  test("a subscription decodes every declared field when present"):
    val status = subscription(
      """{
        |  "subscribed": true,
        |  "ignored": false,
        |  "url": "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966",
        |  "repository_url": "https://forge.example/api/v1/repos/Codeberg/Community",
        |  "created_at": "2026-07-31T17:20:04+02:00"
        |}""".stripMargin
    )

    assertEquals(status.isSubscribed, true)
    assertEquals(status.isIgnored, false)
    assertEquals(status.url, Some("https://forge.example/api/v1/repos/Codeberg/Community/issues/2966"))
    assertEquals(status.repositoryUrl, Some("https://forge.example/api/v1/repos/Codeberg/Community"))
    assertEquals(status.createdAt, Some(Instant.parse("2026-07-31T15:20:04Z")))

  test("JSON null and an absent key decode identically, for every field of a subscription"):
    val nulled =
      """{"subscribed":null,"ignored":null,"url":null,"repository_url":null,"created_at":null}"""

    assertEquals(subscription(nulled), subscription("{}"))

  test("an unstated flag is false, which is the honest reading of the instance not saying"):
    val status = subscription("{}")

    assertEquals(status.isSubscribed, false)
    assertEquals(status.isIgnored, false)

  test("subscribed and ignored are kept apart, because the wire does not promise they are opposites"):
    val both = subscription("""{"subscribed":true,"ignored":true}""")

    assertEquals(both.isSubscribed, true)
    assertEquals(both.isIgnored, true)

  test("the untyped reason key the spec declares without a type does not break the decode"):
    assertEquals(subscription("""{"subscribed":true,"reason":{"name":"mention"}}""").isSubscribed, true)

  private def deadline(body: String): IssueDeadline =
    Json.decode[IssueDeadlineDto](body).flatMap(_.toDomain) match
      case Right(value)  => value
      case Left(failure) => fail(s"could not decode the deadline: $failure")

  private def subscription(body: String): IssueSubscription =
    Json.decode[IssueSubscriptionDto](body).flatMap(_.toDomain) match
      case Right(value)  => value
      case Left(failure) => fail(s"could not decode the subscription: $failure")
