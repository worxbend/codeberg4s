package com.worxbend.codeberg4s.auth

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

final class AuthSuite extends FunSuite:

  private val Secret: String = "cb-0123456789abcdef-secret"

  test("a token credential renders the mask instead of the token"):
    val rendered = Auth.Token(token(Secret)).toString

    assert(!rendered.contains(Secret), "Auth.Token leaked the token")
    assert(rendered.contains(ApiToken.Redacted), rendered)

  test("basic credentials render the username but mask the password"):
    val rendered = basic("dependabot", password(Secret)).toString

    assert(!rendered.contains(Secret), "Auth.Basic leaked the password")
    assert(rendered.contains("dependabot"), rendered)
    assert(rendered.contains(Password.Redacted), rendered)

  test("basic credentials keep the username they were given"):
    assertEquals(basic("dependabot", password(Secret)), Auth.Basic("dependabot", password(Secret)))

  test("a basic username is trimmed, because it is usually read from an environment variable"):
    assertEquals(basic("  dependabot\n", password(Secret)), Auth.Basic("dependabot", password(Secret)))

  test("a basic username must not be empty"):
    assertEquals(field(Auth.basic("", password(Secret))), Some("username"))

  test("a basic username must not be blank"):
    assertEquals(field(Auth.basic("   ", password(Secret))), Some("username"))

  test("a basic username must not contain a colon, which would make the instance read a different user"):
    assertEquals(field(Auth.basic("a:b", password(Secret))), Some("username"))
    assertEquals(
      Auth.basic("a:b", password(Secret)).swap.toOption.map(_.message),
      Some("must not contain a colon"),
    )

  test("a basic username must not contain a header-splitting control character"):
    assertEquals(field(Auth.basic("dependabot\r\nX-Injected: 1", password(Secret))), Some("username"))

  test("a basic username rejection never echoes the rejected input"):
    val message = Auth.basic("dependabot:leaked", password(Secret)).swap.toOption.map(_.message)

    assert(!message.exists(_.contains("dependabot")), message)

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

  private def basic(username: String, secret: Password): Auth =
    Auth.basic(username, secret) match
      case Right(auth) => auth
      case Left(error) => fail(s"invalid basic credentials in test setup: ${error.field} ${error.message}")

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
