package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.repositories.actions.ActionSecret
import com.worxbend.codeberg4s.repositories.actions.ActionVariable

import munit.FunSuite

import java.time.Instant

/** Decoding the two configuration models, and the contrast between them.
  *
  * A secret comes back as a name and a timestamp; a variable comes back with its value. That asymmetry is the whole
  * security story of this group, so it is asserted rather than described.
  *
  * '''Payloads written by hand from `spec/swagger.v1.json`, not captured'''; see [[ActionArtifactDto]].
  */
final class ActionConfigurationDtoSuite extends FunSuite:

  test("a secret decodes to a name and a timestamp"):
    val secret = domainSecret("""{"name":"DEPLOY_KEY","created_at":"2026-07-30T21:14:15+02:00"}""")

    assertEquals(secret.name.value, "DEPLOY_KEY")
    assertEquals(secret.createdAt, Some(Instant.parse("2026-07-30T19:14:15Z")))

  test("a secret payload carrying a value key cannot smuggle it into the model"):
    val secret = domainSecret("""{"name":"DEPLOY_KEY","data":"hunter2","value":"hunter2"}""")

    assert(!secret.toString.contains("hunter2"), s"a value reached the secret model: $secret")

  test("a secret without a name cannot be converted"):
    assertEquals(secretFailure("""{"created_at":"2026-07-30T21:14:15+02:00"}"""), Some("$.name"))

  test("a secret whose name could forge a path cannot be converted"):
    assertEquals(secretFailure("""{"name":"a/b"}"""), Some("$.name"))

  test("a secret whose created_at is the zero-time sentinel reports no timestamp"):
    assertEquals(domainSecret("""{"name":"K","created_at":"0001-01-01T00:00:00Z"}""").createdAt, None)

  test("JSON null and an absent key decode identically for a secret"):
    assertEquals(
      decodeSecret("""{"name":"K","created_at":null}"""),
      decodeSecret("""{"name":"K"}"""),
    )

  test("a bad element of a secret array reports its own position"):
    val dtos = Json.decode[Vector[ActionSecretDto]]("""[{"name":"A"},{"created_at":null}]""") match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the array did not decode: ${failure.message}")

    assertEquals(ActionSecretDto.toDomainAll(JsonPath.Root, dtos).swap.toOption.map(_.path.render), Some("$[1].name"))

  test("a variable decodes with its value, which arrives under 'data' and not 'value'"):
    val variable = domainVariable("""{"name":"ENVIRONMENT","data":"staging","owner_id":0,"repo_id":12}""")

    assertEquals(variable.name.value, "ENVIRONMENT")
    assertEquals(variable.value, "staging")
    assertEquals(variable.ownerId, None)
    assertEquals(variable.repoId, Some(12L))

  test("a variable deliberately set to the empty string is not an absent variable"):
    assertEquals(domainVariable("""{"name":"EMPTY","data":""}""").value, "")

  test("a variable without a value cannot be converted"):
    assertEquals(variableFailure("""{"name":"ENVIRONMENT"}"""), Some("$.data"))

  test("a variable without a name cannot be converted"):
    assertEquals(variableFailure("""{"data":"staging"}"""), Some("$.name"))

  test("a variable whose name could forge a path cannot be converted"):
    assertEquals(variableFailure("""{"name":"a/b","data":"x"}"""), Some("$.name"))

  test("a bad element of a variable array reports its own position"):
    val dtos = Json.decode[Vector[ActionVariableDto]]("""[{"name":"A","data":"1"},{"name":"B"}]""") match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the array did not decode: ${failure.message}")

    assertEquals(
      ActionVariableDto.toDomainAll(JsonPath.Root, dtos).swap.toOption.map(_.path.render),
      Some("$[1].data"),
    )

  private def decodeSecret(body: String): ActionSecretDto =
    Json.decode[ActionSecretDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domainSecret(body: String): ActionSecret =
    decodeSecret(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def secretFailure(body: String): Option[String] =
    decodeSecret(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeVariable(body: String): ActionVariableDto =
    Json.decode[ActionVariableDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domainVariable(body: String): ActionVariable =
    decodeVariable(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def variableFailure(body: String): Option[String] =
    decodeVariable(body).toDomain.swap.toOption.map(_.path.render)
