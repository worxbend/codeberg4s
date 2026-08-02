package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.miscellaneous.GitignoreTemplate

import munit.FunSuite

/** `GET /gitignore/templates/{name}`, whose body is derived from `spec/swagger.v1.json`: only the '''listing''' has a
  * golden capture, so every payload here was written from the `GitignoreTemplateInfo` definition.
  */
final class GitignoreTemplateDtoSuite extends FunSuite:

  test("a full body decodes field for field"):
    assertEquals(
      Json.decode[GitignoreTemplateDto]("""{"name":"Ada","source":"*.ali\n"}"""),
      Right(GitignoreTemplateDto(Some("Ada"), Some("*.ali\n"))),
    )

  test("the body converts to the domain"):
    assertEquals(
      Json.decode[GitignoreTemplateDto]("""{"name":"Ada","source":"*.ali\n"}""").flatMap(_.toDomain),
      Right(GitignoreTemplate(Some("Ada"), "*.ali\n")),
    )

  test("an absent key and an explicit null decode identically"):
    assertEquals(
      Json.decode[GitignoreTemplateDto]("""{}"""),
      Json.decode[GitignoreTemplateDto]("""{"name":null,"source":null}"""),
    )

  test("a template with no contents fails at $.source, because the contents are what the call asked for"):
    GitignoreTemplateDto(Some("Ada"), None).toDomain match
      case Left(failure) => assertEquals(failure.path.render, "$.source")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a template that echoes no name still converts, because the caller already knows the name"):
    assertEquals(
      GitignoreTemplateDto(None, Some("*.ali\n")).toDomain,
      Right(GitignoreTemplate(None, "*.ali\n")),
    )

  test("a genuinely empty template is empty contents, not an absent field"):
    assertEquals(
      Json.decode[GitignoreTemplateDto]("""{"name":"Empty","source":""}""").flatMap(_.toDomain),
      Right(GitignoreTemplate(Some("Empty"), "")),
    )

  test("an unknown key a future Forgejo adds does not break the decode"):
    assertEquals(
      Json.decode[GitignoreTemplateDto]("""{"source":"x","new_knob":true}"""),
      Right(GitignoreTemplateDto(None, Some("x"))),
    )

  test("a body that is not JSON is a DecodeFailure, not an exception"):
    assert(Json.decode[GitignoreTemplateDto]("<html>proxy error</html>").isLeft)
