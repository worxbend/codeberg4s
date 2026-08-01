package com.worxbend.codeberg4s.auth

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

final class PasswordSuite extends FunSuite:

  private val Secret: String = "correct horse battery staple"

  test("accepts a plausible password"):
    assertEquals(Password.from(Secret).toOption.map(_.reveal), Some(Secret))

  test("keeps significant surrounding whitespace"):
    assertEquals(Password.from(s" $Secret ").toOption.map(_.reveal), Some(s" $Secret "))

  test("rejects an empty value"):
    assertEquals(field(Password.from("")), Some("password"))

  test("rejects a header-splitting control character"):
    assertEquals(field(Password.from("pass\r\nX-Injected: 1")), Some("password"))

  test("toString renders the mask, never the material"):
    assertEquals(password(Secret).toString, Password.Redacted)

  test("string interpolation renders the mask, never the material"):
    val interpolated = s"${password(Secret)}"

    assert(!interpolated.contains(Secret), "interpolation leaked the password")
    assertEquals(interpolated, Password.Redacted)

  test("redacted renders the mask"):
    assertEquals(password(Secret).redacted, Password.Redacted)

  test("passwords with the same material are equal"):
    assertEquals(password(Secret), password(Secret))

  private def password(value: String): Password =
    Password.from(value) match
      case Right(parsed) => parsed
      case Left(error)   => fail(s"invalid password in test setup: ${error.message}")

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
