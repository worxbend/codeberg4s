package com.worxbend.codeberg4s.miscellaneous

import munit.FunSuite

/** [[TemplateName]] is the widest path type in the library, and the tests are what keep "wide" from becoming
  * "unvalidated".
  */
final class TemplateNameSuite extends FunSuite:

  test("an ordinary template name is accepted"):
    assertEquals(TemplateName.from("Actionscript").map(_.value), Right("Actionscript"))

  test("a name carrying spaces is accepted, because license names genuinely carry them"):
    assertEquals(
      TemplateName.from("GNU Affero General Public License v3.0").map(_.value),
      Right("GNU Affero General Public License v3.0"),
    )

  test("a name carrying a slash is accepted, and stays one path segment for the transport to encode"):
    assertEquals(TemplateName.from("Global/Anjuta").map(_.value), Right("Global/Anjuta"))

  test("surrounding whitespace is trimmed, so it never reaches a request path"):
    assertEquals(TemplateName.from("  Ada\n").map(_.value), Right("Ada"))

  test("a blank name is rejected on the templateName field"):
    TemplateName.from("   ") match
      case Left(error)  =>
        assertEquals(error.field, "templateName")
        assertEquals(error.message, "must not be blank")
      case Right(value) => fail(s"expected a rejection, got $value")

  test("an empty name is rejected"):
    assert(TemplateName.from("").isLeft)

  test("a control character is rejected, because it would corrupt the request line"):
    TemplateName.from("Ada\u0007Script") match
      case Left(error)  => assertEquals(error.message, "must not contain a control character")
      case Right(value) => fail(s"expected a rejection, got $value")

  test("a traversal part is rejected, because '..' survives percent-encoding in some routers"):
    TemplateName.from("..") match
      case Left(error)  => assertEquals(error.message, "must not contain a '.' or '..' part")
      case Right(value) => fail(s"expected a rejection, got $value")

  test("a traversal hidden inside a slashed name is rejected too"):
    assert(TemplateName.from("Global/../../admin").isLeft)
    assert(TemplateName.from("./Ada").isLeft)

  test("a dot inside a name is not a traversal and is accepted"):
    assertEquals(TemplateName.from("Apache-2.0").map(_.value), Right("Apache-2.0"))

  test("two names that differ only in surrounding whitespace compare equal after parsing"):
    assertEquals(TemplateName.from(" MIT ").map(_.value), TemplateName.from("MIT").map(_.value))
