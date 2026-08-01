package com.worxbend.codeberg4s.auth

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

final class ApiTokenSuite extends FunSuite:

  private val Secret: String = "cb-0123456789abcdef-secret"

  test("accepts a plausible token"):
    assertEquals(ApiToken.from(Secret).toOption.map(_.reveal), Some(Secret))

  test("trims the trailing newline an environment variable or file usually carries"):
    assertEquals(ApiToken.from(s"  $Secret\n").toOption.map(_.reveal), Some(Secret))

  test("rejects an empty value"):
    assertEquals(field(ApiToken.from("")), Some("apiToken"))

  test("rejects a blank value"):
    assertEquals(field(ApiToken.from("   ")), Some("apiToken"))

  test("rejects a header-splitting control character"):
    assertEquals(field(ApiToken.from("token\r\nX-Injected: 1")), Some("apiToken"))

  test("the rejection message never echoes the rejected input"):
    val message = ApiToken.from(s"$Secret\r\n").swap.toOption.map(_.message)
    assert(!message.exists(_.contains(Secret)), message)

  test("toString renders the mask, never the material"):
    assertEquals(token(Secret).toString, ApiToken.Redacted)

  test("string interpolation renders the mask, never the material"):
    val interpolated = s"${token(Secret)}"

    assert(!interpolated.contains(Secret), "interpolation leaked the token")
    assertEquals(interpolated, ApiToken.Redacted)

  test("redacted renders the mask"):
    assertEquals(token(Secret).redacted, ApiToken.Redacted)

  test("reveal is the only way to see the material"):
    assertEquals(token(Secret).reveal, Secret)

  test("tokens with the same material are equal"):
    assertEquals(token(Secret), token(Secret))
    assertEquals(token(Secret).hashCode(), token(Secret).hashCode())

  test("tokens with different material are not equal"):
    assertNotEquals(token(Secret), token("cb-other-secret"))

  private def token(value: String): ApiToken =
    ApiToken.from(value) match
      case Right(parsed) => parsed
      case Left(error)   => fail(s"invalid token in test setup: ${error.message}")

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
