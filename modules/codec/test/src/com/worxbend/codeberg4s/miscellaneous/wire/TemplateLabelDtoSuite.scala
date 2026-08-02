package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.TemplateLabel

import munit.FunSuite

/** `GET /label/templates/{name}`, whose body is an array of Forgejo's `LabelTemplate` model.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' Neither label-template endpoint was
  * harvested, so every payload here was written from that definition.
  */
final class TemplateLabelDtoSuite extends FunSuite:

  test("a full entry decodes field for field"):
    assertEquals(
      Json.decode[TemplateLabelDto](
        """{"color":"00aabb","description":"Something is broken","exclusive":true,"name":"bug"}"""
      ),
      Right(TemplateLabelDto(Some("00aabb"), Some("Something is broken"), Some(true), Some("bug"))),
    )

  test("the entry converts to the domain, colour parsed"):
    assertEquals(
      TemplateLabelDto(Some("00aabb"), Some("broken"), Some(true), Some("bug")).toDomain.map(_.color.map(_.value)),
      Right(Some("00aabb")),
    )

  test("a colour Forgejo spelled with a leading hash is normalised, as it is everywhere else"):
    assertEquals(
      TemplateLabelDto(Some("#EE0701"), None, None, Some("bug")).toDomain.map(_.color.map(_.value)),
      Right(Some("ee0701")),
    )

  test("a colour that is not hexadecimal costs the colour and not the label"):
    assertEquals(
      TemplateLabelDto(Some("cornflower"), None, None, Some("bug")).toDomain,
      Right(TemplateLabel("bug", None, None, isExclusive = false)),
    )

  test("an entry naming no label fails at $.name"):
    TemplateLabelDto(Some("00aabb"), None, None, None).toDomain match
      case Left(failure) => assertEquals(failure.path.render, "$.name")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("an absent exclusive flag is not exclusive, which is the conservative reading"):
    assertEquals(
      TemplateLabelDto(None, None, None, Some("bug")).toDomain.map(_.isExclusive),
      Right(false),
    )

  test("an absent key and an explicit null decode identically"):
    assertEquals(
      Json.decode[TemplateLabelDto]("""{}"""),
      Json.decode[TemplateLabelDto]("""{"color":null,"description":null,"exclusive":null,"name":null}"""),
    )

  test("a bad element of a template is reported at its own index"):
    template("""[{"name":"bug"},{"color":"00aabb"}]""") match
      case Left(failure) => assertEquals(failure.path.render, "$[1].name")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a whole template converts, keeping wire order"):
    assertEquals(
      template("""[{"name":"bug"},{"name":"duplicate"}]""").map(_.map(_.name)),
      Right(Vector("bug", "duplicate")),
    )

  test("an empty template is an empty vector, not a failure"):
    assertEquals(template("[]").map(_.size), Right(0))

  test("a body that is not an array is a DecodeFailure, not an exception"):
    assert(Json.decode[Vector[TemplateLabelDto]]("""{"labels":[]}""").isLeft)

  private def template(body: String): Either[DecodeFailure, Vector[TemplateLabel]] =
    Json.decode[Vector[TemplateLabelDto]](body).flatMap(dtos => TemplateLabelDto.toDomainAll(JsonPath.Root, dtos))
