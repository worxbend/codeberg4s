package com.worxbend.codeberg4s.core

import munit.FunSuite

final class RedactionSuite extends FunSuite:

  private val base: String = "https://codeberg.org/api/v1"

  test("a token query parameter never reaches the rendered uri"):
    val rendered = Redaction.uri(base, List("repos"), List("token" -> "s3cret-token"))

    assertEquals(rendered, s"$base/repos?token=***")
    assert(!rendered.contains("s3cret-token"))

  test("every spelling of a credential query parameter is masked"):
    val query    = List("access_token" -> "a", "private_token" -> "b", "password" -> "c", "sudo" -> "root")
    val rendered = Redaction.uri(base, Nil, query)

    assertEquals(rendered, s"$base?access_token=***&private_token=***&password=***&sudo=***")

  test("a credential parameter is matched case-insensitively"):
    assertEquals(Redaction.uri(base, Nil, List("Token" -> "s3cret")), s"$base?Token=***")

  test("an ordinary query value is percent-encoded"):
    assertEquals(Redaction.uri(base, Nil, List("q" -> "needs triage")), s"$base?q=needs%20triage")

  test("query parameters keep their order and a repeated key survives"):
    val query = List("state" -> "open", "labels" -> "bug", "labels" -> "ux")

    assertEquals(Redaction.uri(base, Nil, query), s"$base?state=open&labels=bug&labels=ux")

  test("a path segment is percent-encoded"):
    assertEquals(Redaction.uri(base, List("repos", "my repo"), Nil), s"$base/repos/my%20repo")

  test("a slash inside a segment is encoded and cannot forge a path"):
    assertEquals(Redaction.uri(base, List("repos", "owner/other"), Nil), s"$base/repos/owner%2Fother")

  test("a non-ascii segment is encoded as its utf-8 octets"):
    assertEquals(Redaction.uri(base, List("ä"), Nil), s"$base/%C3%A4")

  test("unreserved characters survive untouched"):
    assertEquals(Redaction.uri(base, List("a-b_c.d~e9"), Nil), s"$base/a-b_c.d~e9")

  test("no path and no query renders the base uri alone"):
    assertEquals(Redaction.uri(base, Nil, Nil), base)

  test("user information in the base uri never reaches the rendered uri"):
    val rendered = Redaction.uri("https://user:hunter2@forge.example/api/v1", List("repos"), Nil)

    assertEquals(rendered, "https://forge.example/api/v1/repos")
    assert(!rendered.contains("hunter2"), s"the password survived in: $rendered")

  test("a query already on the base uri is dropped rather than rendered"):
    assertEquals(Redaction.uri(s"$base?token=s3cret", List("repos"), Nil), s"$base/repos")

  test("a fragment already on the base uri is dropped rather than rendered"):
    assertEquals(Redaction.uri(s"$base#frag", Nil, Nil), base)

  test("an authorization header is masked"):
    val masked = Redaction.headers(List("authorization" -> "token s3cret", "accept" -> "application/json"))

    assertEquals(masked, List("authorization" -> "***", "accept" -> "application/json"))

  test("a credential header is matched case-insensitively"):
    assertEquals(Redaction.headers(List("Authorization" -> "token s3cret")), List("Authorization" -> "***"))

  test("cookies are masked too"):
    assertEquals(Redaction.headers(List("cookie" -> "i_like_gitea=abc")), List("cookie" -> "***"))
