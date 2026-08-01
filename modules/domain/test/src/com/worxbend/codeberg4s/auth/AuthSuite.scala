package com.worxbend.codeberg4s.auth

import munit.FunSuite

final class AuthSuite extends FunSuite:

  private val Secret: String = "cb-0123456789abcdef-secret"

  test("a token credential renders the mask instead of the token"):
    val rendered = Auth.Token(token(Secret)).toString

    assert(!rendered.contains(Secret), "Auth.Token leaked the token")
    assert(rendered.contains(ApiToken.Redacted), rendered)

  test("basic credentials render the username but mask the password"):
    val rendered = Auth.Basic("dependabot", password(Secret)).toString

    assert(!rendered.contains(Secret), "Auth.Basic leaked the password")
    assert(rendered.contains("dependabot"), rendered)
    assert(rendered.contains(Password.Redacted), rendered)

  test("anonymous carries no credential at all"):
    assertEquals(Auth.Anonymous.toString, "Anonymous")

  private def token(value: String): ApiToken =
    ApiToken.from(value) match
      case Right(parsed) => parsed
      case Left(error)   => fail(s"invalid token in test setup: ${error.message}")

  private def password(value: String): Password =
    Password.from(value) match
      case Right(parsed) => parsed
      case Left(error)   => fail(s"invalid password in test setup: ${error.message}")
