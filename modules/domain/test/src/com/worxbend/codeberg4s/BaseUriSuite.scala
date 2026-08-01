package com.worxbend.codeberg4s

import munit.FunSuite

final class BaseUriSuite extends FunSuite:

  test("accepts an https api root"):
    assertEquals(value(BaseUri.from("https://codeberg.org/api/v1")), Some("https://codeberg.org/api/v1"))

  test("accepts a plain http root, for a local instance"):
    assertEquals(value(BaseUri.from("http://localhost:3000/api/v1")), Some("http://localhost:3000/api/v1"))

  test("removes trailing slashes"):
    assertEquals(value(BaseUri.from("https://codeberg.org/api/v1///")), Some("https://codeberg.org/api/v1"))

  test("trims surrounding whitespace"):
    assertEquals(value(BaseUri.from("  https://codeberg.org/api/v1  ")), Some("https://codeberg.org/api/v1"))

  test("rejects a blank value"):
    assertEquals(field(BaseUri.from("   ")), Some("baseUri"))

  test("rejects a scheme this library cannot speak"):
    assertEquals(field(BaseUri.from("ftp://codeberg.org")), Some("baseUri"))

  test("rejects a relative uri"):
    assertEquals(field(BaseUri.from("codeberg.org/api/v1")), Some("baseUri"))

  test("rejects a scheme with no host"):
    assertEquals(field(BaseUri.from("https://")), Some("baseUri"))

  test("rejects an embedded control character"):
    assertEquals(field(BaseUri.from("https://codeberg.org/api\nv1")), Some("baseUri"))

  test("the Codeberg constant points at the public api root"):
    assertEquals(BaseUri.Codeberg.value, "https://codeberg.org/api/v1")

  private def value(result: Either[ValidationError, BaseUri]): Option[String] =
    result.toOption.map(_.value)

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
