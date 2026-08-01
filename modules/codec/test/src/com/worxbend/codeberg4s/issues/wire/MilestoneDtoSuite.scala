package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.issues.LifecycleState
import com.worxbend.codeberg4s.issues.Milestone

import munit.FunSuite

import java.time.Instant

/** [[MilestoneDto]] against `golden/issue/milestones-list.json`. */
final class MilestoneDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden milestone listing decodes into three milestones"):
    assertEquals(milestones.size, 3)

  test("the first golden milestone converts field for field"):
    val milestone = milestones.head

    assertEquals(milestone.id.value, 3109L)
    assertEquals(milestone.title, "Forgejo v1.18.0-0")
    assertEquals(milestone.openIssueCount, 0L)
    assertEquals(milestone.closedIssueCount, 15L)
    assertEquals(milestone.dueOn, Some(Instant.parse("2022-12-06T23:59:59Z")))
    assertEquals(milestone.createdAt, Some(Instant.parse("2022-12-17T07:45:02Z")))

  test("a closed milestone carries its closing instant inside the state, not beside it"):
    assertEquals(milestones.head.state, LifecycleState.Closed(Some(Instant.parse("2023-01-08T00:08:47Z"))))

  test("an open milestone with a null closed_at is Open and carries nothing"):
    MilestoneDto(Some(1L), Some("v1.x"), None, Some("open"), Some(10L), Some(1L), None, None, None, None).toDomain match
      case Right(milestone) => assertEquals(milestone.state, LifecycleState.Open)
      case Left(failure)    => fail(s"expected a milestone, got $failure")

  test("absent counts become zero, which is the answer the instance gives for an empty milestone"):
    MilestoneDto(Some(1L), Some("v1.x"), None, Some("open"), None, None, None, None, None, None).toDomain match
      case Right(milestone) =>
        assertEquals(milestone.openIssueCount, 0L)
        assertEquals(milestone.closedIssueCount, 0L)
      case Left(failure)    => fail(s"expected a milestone, got $failure")

  test("a milestone with no id is a decoding failure at $.id"):
    assertEquals(
      failingPath(MilestoneDto(None, Some("v1"), None, Some("open"), None, None, None, None, None, None)),
      "$.id",
    )

  test("a milestone with no title is a decoding failure at $.title"):
    assertEquals(
      failingPath(MilestoneDto(Some(1L), None, None, Some("open"), None, None, None, None, None, None)),
      "$.title",
    )

  test("a milestone with no state is a decoding failure at $.state, because there is no third case to put it in"):
    assertEquals(
      failingPath(MilestoneDto(Some(1L), Some("v1"), None, None, None, None, None, None, None, None)),
      "$.state",
    )

  test("a state Forgejo has never sent is a decoding failure at $.state"):
    assertEquals(
      failingPath(MilestoneDto(Some(1L), Some("v1"), None, Some("merged"), None, None, None, None, None, None)),
      "$.state",
    )

  test("a failing element reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[MilestoneDto]]("""[{"id":1,"title":"a","state":"open"},{"id":2,"title":"b"}]""")
      .flatMap(dtos => MilestoneDto.toDomainAll(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].state")
      case Right(value)  => fail(s"expected a failure, converted $value")

  private def milestones: Vector[Milestone] =
    Json
      .decode[Vector[MilestoneDto]](golden("issue/milestones-list.json"))
      .flatMap(dtos => MilestoneDto.toDomainAll(JsonPath.Root, dtos)) match
      case Right(values) => values
      case Left(failure) => fail(s"could not decode the milestone listing: $failure")

  private def failingPath(dto: MilestoneDto): String =
    dto.toDomain match
      case Left(failure) => failure.path.render
      case Right(value)  => fail(s"expected a failure, converted $value")
