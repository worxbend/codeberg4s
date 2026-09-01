package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.repositories.actions.ActionRunner
import com.worxbend.codeberg4s.repositories.actions.RegisteredRunner
import com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken
import com.worxbend.codeberg4s.repositories.actions.RunnerStatus

import munit.FunSuite

/** Decoding the three runner-shaped payloads: a runner, a registration result, and a bare registration token.
  *
  * '''Payloads written by hand from `spec/swagger.v1.json`, not captured'''; see [[ActionArtifactDto]].
  */
final class ActionRunnerDtoSuite extends FunSuite:

  test("a full runner decodes field for field"):
    val dto = decodeRunner(ActionRunnerDtoSuite.RunnerBody)

    assertEquals(dto.id, Some(37L))
    assertEquals(dto.uuid, Some("3f7c1a2e-0b44-4f11-9a76-1d2c3e4f5a6b"))
    assertEquals(dto.name, Some("build-box-3"))
    assertEquals(dto.status, Some("idle"))
    assertEquals(dto.labels, Vector("ubuntu-latest", "docker"))
    assertEquals(dto.ephemeral, Some(true))

  test("a runner's numeric id becomes the string identifier the path takes"):
    assertEquals(runner(ActionRunnerDtoSuite.RunnerBody).id.value, "37")

  test("a runner's status becomes an enum"):
    assertEquals(runner(ActionRunnerDtoSuite.RunnerBody).status, Some(RunnerStatus.Idle))

  test("a status outside the enumerated set becomes absence rather than a failure"):
    assertEquals(runner("""{"id":37,"status":"draining"}""").status, None)

  test("labels are read as strings, because a label read back must not be dropped for its spelling"):
    assertEquals(runner("""{"id":37,"labels":["a,b"]}""").labels, Vector("a,b"))

  test("owner_id and repo_id are zero for the ownership that does not apply, which is absence"):
    val decoded = runner("""{"id":37,"owner_id":0,"repo_id":12}""")

    assertEquals(decoded.ownerId, None)
    assertEquals(decoded.repoId, Some(12L))

  test("a runner without an id cannot be converted"):
    assertEquals(runnerFailure("""{"name":"nameless"}"""), Some("$.id"))

  test("JSON null and an absent key decode identically for every runner field"):
    assertEquals(decodeRunner(ActionRunnerDtoSuite.NullRunnerBody), decodeRunner("""{"id":37}"""))

  test("a null labels array becomes an empty vector rather than aborting the read"):
    assertEquals(decodeRunner("""{"id":37,"labels":null}""").labels, Vector.empty[String])

  test("a bad element of a runner array reports its own position"):
    val dtos = Json.decode[Vector[ActionRunnerDto]]("""[{"id":1},{"name":"no id"}]""") match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the array did not decode: ${failure.message}")

    assertEquals(WireModel.all(JsonPath.Root, dtos).swap.toOption.map(_.path.render), Some("$[1].id"))

  test("a registration result carries its token as a masked credential"):
    val result = registered("""{"id":37,"uuid":"abc","token":"QWERTY123"}""")

    assertEquals(result.id.map(_.value), Some("37"))
    assertEquals(result.uuid, Some("abc"))
    assertEquals(result.token.reveal, "QWERTY123")
    assertEquals(result.token.toString, RunnerRegistrationToken.Redacted)

  test("a registration result without a token is a failure, not a runner nobody can start"):
    assertEquals(registeredFailure("""{"id":37,"uuid":"abc"}"""), Some("$.token"))

  test("a registration result with a blank token is rejected at the token's own path"):
    assertEquals(registeredFailure("""{"token":"   "}"""), Some("$.token"))

  test("a registration result without an id is still usable, because the token is the point"):
    assertEquals(registered("""{"token":"QWERTY123"}""").id, None)

  test("a bare registration token decodes to the masked credential"):
    val token = Json.decode[RegistrationTokenDto]("""{"token":"  QWERTY123  "}""") match
      case Right(dto)    => dto.toDomain
      case Left(failure) => fail(s"the payload did not decode: ${failure.message}")

    assertEquals(token.toOption.map(_.reveal), Some("QWERTY123"))

  test("a registration-token response with no token fails at the token's own path"):
    val failure = Json.decode[RegistrationTokenDto]("""{}""") match
      case Right(dto)    => dto.toDomain.swap.toOption.map(_.path.render)
      case Left(problem) => fail(s"the payload did not decode: ${problem.message}")

    assertEquals(failure, Some("$.token"))

  private def decodeRunner(body: String): ActionRunnerDto =
    Json.decode[ActionRunnerDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def runner(body: String): ActionRunner =
    decodeRunner(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def runnerFailure(body: String): Option[String] =
    decodeRunner(body).toDomain.swap.toOption.map(_.path.render)

  private def registered(body: String): RegisteredRunner =
    decodeRegistered(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def registeredFailure(body: String): Option[String] =
    decodeRegistered(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeRegistered(body: String): RegisteredRunnerDto =
    Json.decode[RegisteredRunnerDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

/** The payloads this suite decodes, shaped to the spec's runner definitions. */
object ActionRunnerDtoSuite:

  private val RunnerBody: String =
    """{
      |  "id": 37,
      |  "uuid": "3f7c1a2e-0b44-4f11-9a76-1d2c3e4f5a6b",
      |  "name": "build-box-3",
      |  "description": "hetzner cx42",
      |  "status": "idle",
      |  "labels": ["ubuntu-latest", "docker"],
      |  "ephemeral": true,
      |  "owner_id": 0,
      |  "repo_id": 12
      |}""".stripMargin

  private val NullRunnerBody: String =
    """{
      |  "id": 37,
      |  "uuid": null,
      |  "name": null,
      |  "description": null,
      |  "status": null,
      |  "labels": null,
      |  "ephemeral": null,
      |  "owner_id": null,
      |  "repo_id": null
      |}""".stripMargin
