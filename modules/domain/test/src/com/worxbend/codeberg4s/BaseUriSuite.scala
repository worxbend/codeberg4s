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

  test("rejects user information, which would otherwise be reproduced in every error"):
    assertEquals(field(BaseUri.from("https://u:p@h/api/v1")), Some("baseUri"))

  test("the rejection of user information does not echo the credential back"):
    val rejected = message(BaseUri.from("https://user:hunter2@h/api/v1"))

    assert(rejected.isDefined, "a base URI carrying a password must be rejected")
    assert(!rejected.exists(_.contains("hunter2")), s"the message repeated the password: $rejected")

  test("rejects a query string"):
    assertEquals(field(BaseUri.from("https://h/api/v1?token=x")), Some("baseUri"))

  test("rejects a fragment"):
    assertEquals(field(BaseUri.from("https://h/api/v1#frag")), Some("baseUri"))

  test("an at sign inside the path is not user information"):
    assertEquals(value(BaseUri.from("https://h/api/v1/@me")), Some("https://h/api/v1/@me"))

  test("the Codeberg constant points at the public api root"):
    assertEquals(BaseUri.Codeberg.value, "https://codeberg.org/api/v1")

  private def value(result: Either[ValidationError, BaseUri]): Option[String] =
    result.toOption.map(_.value)

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)

  private def message(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.message)
