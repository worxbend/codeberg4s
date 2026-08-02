package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

import java.time.Instant

/** The redaction guarantee of the two credential-bearing types in this group.
  *
  * This suite is the reason both are final classes rather than opaque aliases. An `opaque type` over `String` has `Any`
  * as its visible upper bound, so `toString` and interpolation would dispatch to `String` outside the defining scope
  * and print the material — the assertions below are what makes "a secret cannot reach a log" a property rather than a
  * comment. It mirrors `ApiTokenSuite`, deliberately.
  */
final class ActionSecrecySuite extends FunSuite:

  private val Material: String = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg\n-----END PRIVATE KEY-----"

  test("a secret value keeps its material verbatim, newlines included"):
    assertEquals(secret(Material).reveal, Material)

  test("a secret value is not trimmed, because whitespace in a secret can be significant"):
    assertEquals(secret("  padded  ").reveal, "  padded  ")

  test("a secret value accepts a control character, because a PEM block is full of them"):
    assert(SecretValue.from("line\r\nline").isRight, "a multi-line secret must be accepted")

  test("a secret value rejects an empty value"):
    assertEquals(SecretValue.from("").swap.toOption.map(_.field), Some("secretValue"))

  test("the rejection message never echoes the rejected input"):
    val message = SecretValue.from("").swap.toOption.map(_.message)

    assert(!message.exists(_.contains("BEGIN")), message)

  test("toString renders the mask, never the material"):
    assertEquals(secret(Material).toString, SecretValue.Redacted)

  test("string interpolation renders the mask, never the material"):
    val interpolated = s"${secret(Material)}"

    assert(!interpolated.contains("PRIVATE"), "interpolation leaked the secret")
    assertEquals(interpolated, SecretValue.Redacted)

  test("a case class holding a secret value cannot print it either"):
    val holder = SecretHolder(secret(Material))

    assert(!holder.toString.contains("PRIVATE"), s"the generated toString leaked the secret: $holder")

  test("redacted renders the mask"):
    assertEquals(secret(Material).redacted, SecretValue.Redacted)

  test("secret values with the same material are equal"):
    assertEquals(secret(Material), secret(Material))
    assertEquals(secret(Material).hashCode(), secret(Material).hashCode())

  test("secret values with different material are not equal"):
    assertNotEquals(secret(Material), secret("something else"))

  test("a registration token is trimmed, because it is copied into a shell command"):
    assertEquals(token("  QWERTY123  ").reveal, "QWERTY123")

  test("a registration token rejects a blank value"):
    assertEquals(
      RunnerRegistrationToken.from("   ").swap.toOption.map(_.field),
      Some("runnerRegistrationToken"),
    )

  test("a registration token renders the mask in every rendering path"):
    assertEquals(token("QWERTY123").toString, RunnerRegistrationToken.Redacted)
    assertEquals(s"${token("QWERTY123")}", RunnerRegistrationToken.Redacted)
    assertEquals(token("QWERTY123").redacted, RunnerRegistrationToken.Redacted)

  test("a registered runner's generated toString cannot print the token it carries"):
    val runner = RegisteredRunner(id = Some(RunnerId.of(9L)), uuid = Some("abc"), token = token("QWERTY123"))

    assert(!runner.toString.contains("QWERTY123"), s"the generated toString leaked the token: $runner")

  test("registration tokens with the same material are equal"):
    assertEquals(token("QWERTY123"), token("QWERTY123"))
    assertEquals(token("QWERTY123").hashCode(), token("QWERTY123").hashCode())

  test("registration tokens with different material are not equal"):
    assertNotEquals(token("QWERTY123"), token("ASDFGH456"))

  test("the read model of a secret has a name and a timestamp, and nowhere to put a value"):
    val name   = orFail(SecretName.from("DEPLOY_KEY"))
    val stored = ActionSecret(name = name, createdAt = Some(Instant.EPOCH.plusSeconds(1L)))

    assertEquals(stored.name.value, "DEPLOY_KEY")
    assert(!stored.toString.contains("PRIVATE"), stored.toString)

  private def secret(value: String): SecretValue =
    orFail(SecretValue.from(value))

  private def token(value: String): RunnerRegistrationToken =
    orFail(RunnerRegistrationToken.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** A holder whose generated `toString` would print its field, if the field let it. */
private final case class SecretHolder(value: SecretValue)
