package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{GoldenFixtures, Json, WireModel}
import com.worxbend.codeberg4s.issues.Label

import munit.FunSuite

/** [[LabelDto]] against `golden/issue/labels-repo.json` and `golden/issue/labels-on-issue-empty.json`. */
final class LabelDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden repository label listing decodes into five labels"):
    assertEquals(labels("issue/labels-repo.json").size, 5)

  test("the first golden label converts field for field"):
    val label = labels("issue/labels-repo.json").head

    assertEquals(label.id.value, 171368L)
    assertEquals(label.name, "accessibility")
    assertEquals(label.color.map(_.value), Some("eb6420"))
    assertEquals(label.isExclusive, false)
    assertEquals(label.isArchived, false)
    assertEquals(label.url, Some("https://codeberg.org/api/v1/repos/Codeberg/Community/labels/171368"))

  test("an exclusive scoped label keeps its flag — the fixture's bug/infrastructure"):
    assertEquals(labels("issue/labels-repo.json").filter(_.isExclusive).map(_.name), Vector("bug/infrastructure"))

  test("an unlabelled issue's labels endpoint is an empty array, not a failure"):
    assertEquals(labels("issue/labels-on-issue-empty.json"), Vector.empty[Label])

  test("a colour Forgejo has never sent does not cost the caller the label"):
    LabelDto(Some(1L), Some("bug"), Some("cornflower"), None, None, None, None).toDomain match
      case Right(label)  => assertEquals(label.color, None)
      case Left(failure) => fail(s"expected a label, got $failure")

  test("a label with no id is a decoding failure at $.id"):
    assertEquals(failingPath(LabelDto(None, Some("bug"), None, None, None, None, None)), "$.id")

  test("a non-positive id is rejected by the smart constructor, reported at the same path"):
    assertEquals(failingPath(LabelDto(Some(0L), Some("bug"), None, None, None, None, None)), "$.id")

  test("a label with no name is a decoding failure at $.name"):
    assertEquals(failingPath(LabelDto(Some(1L), None, None, None, None, None, None)), "$.name")

  test("a failing element reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[LabelDto]]("""[{"id":1,"name":"a"},{"id":2}]""")
      .flatMap(dtos => WireModel.all(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].name")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("absent, null and wrong-kind are one case for every field"):
    assertEquals(
      Json.decode[LabelDto]("""{"id":1,"name":"bug"}"""),
      Json.decode[LabelDto]("""{"id":1,"name":"bug","description":null,"exclusive":"yes"}"""),
    )

  private def labels(path: String): Vector[Label] =
    Json.decode[Vector[LabelDto]](golden(path)).flatMap(dtos => WireModel.all(JsonPath.Root, dtos)) match
      case Right(values) => values
      case Left(failure) => fail(s"could not decode $path: $failure")

  private def failingPath(dto: LabelDto): String =
    dto.toDomain match
      case Left(failure) => failure.path.render
      case Right(value)  => fail(s"expected a failure, converted $value")
