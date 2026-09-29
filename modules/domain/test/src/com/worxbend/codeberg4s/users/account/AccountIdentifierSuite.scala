package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** The validated values this group introduces, and what each of them refuses.
  *
  * Every one of them exists to stop a value that would otherwise reach the wire and be rejected there. The assertions
  * are the contract each smart constructor's Scaladoc states.
  */
final class AccountIdentifierSuite extends FunSuite:

  // --- application ids ------------------------------------------------------

  test("an application id accepts a positive row id"):
    assertEquals(OAuth2ApplicationId.from(7L).toOption.map(_.value), Some(7L))

  test("an application id rejects zero, which addresses nothing"):
    assertEquals(OAuth2ApplicationId.from(0L).swap.toOption.map(_.field), Some("oauth2ApplicationId"))

  test("an application id rejects a negative value"):
    assert(OAuth2ApplicationId.from(-1L).isLeft, "a negative application id must be refused")

  // --- email addresses ------------------------------------------------------

  test("an email address keeps its value verbatim"):
    assertEquals(address("maintainer@example.org").value, "maintainer@example.org")

  test("an email address is trimmed"):
    assertEquals(address("  maintainer@example.org  ").value, "maintainer@example.org")

  test("an email address rejects a blank value"):
    assertEquals(EmailAddress.from("   ").swap.toOption.map(_.field), Some("emailAddress"))

  test("an email address rejects internal whitespace"):
    assert(EmailAddress.from("main tainer@example.org").isLeft, "internal whitespace must be refused")

  test("an email address rejects a control character"):
    assert(EmailAddress.from("maintainer@exa\nmple.org").isLeft, "a control character must be refused")

  test("an email address rejects a value with no separator"):
    assert(EmailAddress.from("maintainer.example.org").isLeft, "a value with no separator must be refused")

  test("an email address rejects a value with two separators"):
    assert(EmailAddress.from("a@b@c").isLeft, "a value with two separators must be refused")

  test("an email address rejects an empty local part"):
    assert(EmailAddress.from("@example.org").isLeft, "an empty local part must be refused")

  test("an email address rejects an empty domain"):
    assert(EmailAddress.from("maintainer@").isLeft, "an empty domain must be refused")

  test("an email address accepts a plus tag, because Forgejo does"):
    assert(EmailAddress.from("maintainer+forge@example.org").isRight, "a plus tag must be accepted")

  private def address(value: String): EmailAddress =
    orFail(EmailAddress.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
