package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.LicenseTemplate
import com.worxbend.codeberg4s.miscellaneous.LicenseTemplateSummary

import munit.FunSuite

/** The license catalogue and one of its entries, both derived from `spec/swagger.v1.json`.
  *
  * `golden/MANIFEST.md` records that `GET /licenses` was probed against codeberg.org and deliberately not stored — it
  * answered roughly 80 KB with no `limit` support — so every payload here was written from the
  * `LicensesTemplateListEntry` and `LicenseTemplateInfo` definitions.
  */
final class LicenseTemplateDtoSuite extends FunSuite:

  // --- the catalogue --------------------------------------------------------

  test("a catalogue entry decodes field for field"):
    assertEquals(
      Json.decode[LicenseTemplateSummaryDto]("""{"key":"MIT","name":"MIT","url":"https://forge/licenses/MIT"}"""),
      Right(LicenseTemplateSummaryDto(Some("MIT"), Some("MIT"), Some("https://forge/licenses/MIT"))),
    )

  test("a license name carrying spaces converts, because that is what license names look like"):
    assertEquals(
      LicenseTemplateSummaryDto(Some("AGPL-3.0"), Some("GNU Affero General Public License v3.0"), None).toDomain
        .map(_.name.value),
      Right("GNU Affero General Public License v3.0"),
    )

  test("an entry naming nothing fails at $.name, because nothing could be fetched with it"):
    LicenseTemplateSummaryDto(Some("MIT"), None, None).toDomain match
      case Left(failure) => assertEquals(failure.path.render, "$.name")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("an entry whose name could not address the endpoint fails at $.name too"):
    LicenseTemplateSummaryDto(None, Some(".."), None).toDomain match
      case Left(failure) => assertEquals(failure.path.render, "$.name")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a bad entry in a catalogue is reported at its own index"):
    catalogue("""[{"name":"MIT"},{"key":"X"}]""") match
      case Left(failure) => assertEquals(failure.path.render, "$[1].name")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("an absent key and an explicit null decode identically for a catalogue entry"):
    assertEquals(
      Json.decode[LicenseTemplateSummaryDto]("""{}"""),
      Json.decode[LicenseTemplateSummaryDto]("""{"key":null,"name":null,"url":null}"""),
    )

  test("a whole catalogue converts, keeping wire order"):
    assertEquals(
      catalogue("""[{"name":"MIT"},{"name":"0BSD"}]""").map(_.map(_.name.value)),
      Right(Vector("MIT", "0BSD")),
    )

  // --- one license ----------------------------------------------------------

  test("a full license body decodes field for field"):
    assertEquals(
      Json.decode[LicenseTemplateDto](
        """{"body":"Copyright (C) [year]","implementation":"Create a text file","key":"MIT","name":"MIT",
          |"url":"https://forge/licenses/MIT"}""".stripMargin.replace("\n", "")
      ),
      Right(
        LicenseTemplateDto(
          Some("Copyright (C) [year]"),
          Some("Create a text file"),
          Some("MIT"),
          Some("MIT"),
          Some("https://forge/licenses/MIT"),
        )
      ),
    )

  test("the body converts to the domain with its placeholders untouched"):
    assertEquals(
      LicenseTemplateDto(Some("Copyright (C) [year] [fullname]"), None, None, Some("MIT"), None).toDomain,
      Right(LicenseTemplate("Copyright (C) [year] [fullname]", Some("MIT"), None, None, None)),
    )

  test("a license with no text fails at $.body, because the text is what the call asked for"):
    LicenseTemplateDto(None, None, Some("MIT"), Some("MIT"), None).toDomain match
      case Left(failure) => assertEquals(failure.path.render, "$.body")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("an absent key and an explicit null decode identically for a license"):
    assertEquals(
      Json.decode[LicenseTemplateDto]("""{}"""),
      Json.decode[LicenseTemplateDto](
        """{"body":null,"implementation":null,"key":null,"name":null,"url":null}"""
      ),
    )

  test("an unknown key a future Forgejo adds does not break the decode"):
    assertEquals(
      Json.decode[LicenseTemplateDto]("""{"body":"x","spdx_id":"MIT"}""").flatMap(_.toDomain).map(_.body),
      Right("x"),
    )

  test("a body that is not JSON is a DecodeFailure, not an exception"):
    assert(Json.decode[LicenseTemplateDto]("<html>proxy error</html>").isLeft)

  private def catalogue(body: String): Either[DecodeFailure, Vector[LicenseTemplateSummary]] =
    Json
      .decode[Vector[LicenseTemplateSummaryDto]](body)
      .flatMap(dtos => LicenseTemplateSummaryDto.toDomainAll(JsonPath.Root, dtos))
