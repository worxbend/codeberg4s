package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.repositories.actions.wire.{
  ActionRunJobDto,
  ActionRunnerDto,
  ActionSecretDto,
  ActionVariableDto,
  RegisteredRunnerDto,
  RegistrationTokenDto
}

/** The Actions response shapes that '''every''' Actions surface can receive, decoded once and shared.
  *
  * Forgejo exposes the same runner, secret and variable models three times over — under `/repos/{owner}/{repo}/actions`,
  * under `/orgs/{org}/actions` and under `/user/actions` — with nothing but the path prefix differing. So the decoders
  * for those shapes live here, in one place that all three groups can reach, rather than being re-derived from the same
  * DTOs in each group. Re-deriving them is what this object exists to prevent: a security decision such as the
  * [[com.worxbend.codeberg4s.core.Decode.sensitive]] marking below has to hold on every surface, and three copies of it
  * can be changed one at a time.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call, exactly as
  * [[com.worxbend.codeberg4s.repositories.RepositoryDecoders]] does.
  *
  * '''Every listing here is a bare JSON array.''' The `{"total_count", "workflow_runs"}` envelope and the plain-text
  * job log belong to the repository surface alone, so they stay in [[RepositoryActionDecoders]] with the other
  * repository-only shapes (artifacts, runs, tasks, workflow dispatch) — this object holds only what all three surfaces
  * answer.
  *
  * Declared `private[codeberg4s]`: it is shared machinery for the groups that wrap these endpoints, not part of the
  * published API.
  */
private[codeberg4s] object ActionDecoders:

  /** One runner object. */
  val runner: Decode[ActionRunner] =
    WireDecode.single(Json.decoder[ActionRunnerDto])(_.toDomain)

  /** A bare array of runner objects, as every runner listing returns it. */
  val runners: Decode[Vector[ActionRunner]] =
    WireDecode.vector(Json.decoder[Vector[ActionRunnerDto]])(ActionRunnerDto.toDomainAll)

  /** A bare array of job objects, as both a run's job listing and the runner job search return it. */
  val jobs: Decode[Vector[ActionRunJob]] =
    WireDecode.vector(Json.decoder[Vector[ActionRunJobDto]])(ActionRunJobDto.toDomainAll)

  /** The `{id, uuid, token}` object a runner registration returns, whose `token` is a live credential.
    *
    * Marked [[com.worxbend.codeberg4s.core.Decode.sensitive]]: anyone holding that token can attach a runner that
    * executes workflow code, so a payload that does not decode must not put an excerpt of this body into
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]].
    */
  val registeredRunner: Decode[RegisteredRunner] =
    Decode.sensitive(WireDecode.single(Json.decoder[RegisteredRunnerDto])(_.toDomain))

  /** The one-key object the registration-token endpoint returns — the same credential with nothing around it, and
    * [[com.worxbend.codeberg4s.core.Decode.sensitive]] for the same reason as [[registeredRunner]].
    */
  val registrationToken: Decode[RunnerRegistrationToken] =
    Decode.sensitive(WireDecode.single(Json.decoder[RegistrationTokenDto])(_.toDomain))

  /** A bare array of secret objects — names and timestamps, never values. */
  val secrets: Decode[Vector[ActionSecret]] =
    WireDecode.vector(Json.decoder[Vector[ActionSecretDto]])(ActionSecretDto.toDomainAll)

  /** One variable object. */
  val variable: Decode[ActionVariable] =
    WireDecode.single(Json.decoder[ActionVariableDto])(_.toDomain)

  /** A bare array of variable objects. */
  val variables: Decode[Vector[ActionVariable]] =
    WireDecode.vector(Json.decoder[Vector[ActionVariableDto]])(ActionVariableDto.toDomainAll)
