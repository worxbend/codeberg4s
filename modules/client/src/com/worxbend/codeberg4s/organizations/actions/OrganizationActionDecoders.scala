package com.worxbend.codeberg4s.organizations.actions

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.repositories.actions.ActionRunJob
import com.worxbend.codeberg4s.repositories.actions.ActionRunner
import com.worxbend.codeberg4s.repositories.actions.ActionSecret
import com.worxbend.codeberg4s.repositories.actions.ActionVariable
import com.worxbend.codeberg4s.repositories.actions.RegisteredRunner
import com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken
import com.worxbend.codeberg4s.repositories.actions.wire.ActionRunJobDto
import com.worxbend.codeberg4s.repositories.actions.wire.ActionRunnerDto
import com.worxbend.codeberg4s.repositories.actions.wire.ActionSecretDto
import com.worxbend.codeberg4s.repositories.actions.wire.ActionVariableDto
import com.worxbend.codeberg4s.repositories.actions.wire.RegisteredRunnerDto
import com.worxbend.codeberg4s.repositories.actions.wire.RegistrationTokenDto

/** Every response shape [[OrganizationActionApi]] can receive, decoded once and shared.
  *
  * ==Why this is not `RepositoryActionDecoders`==
  *
  * It would be, if it could be. The organisation Actions surface answers the '''same seven models''' as the repository
  * one — `ActionRunner`, `Secret`, `ActionVariable`, `ActionRunJob`, `RegisterRunnerResponse`, `RegistrationToken` —
  * and every DTO below is the one the repository group already owns, imported rather than copied: rule 1 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] makes forking a DTO a review-blocking defect, and nothing here
  * forks one.
  *
  * What is duplicated is the eight one-line compositions of `Json.decoder[…]` with `toDomain`.
  * `com.worxbend.codeberg4s.repositories.actions.RepositoryActionDecoders` is declared `private[actions]`, and that
  * qualifier names '''its''' `actions` package — `com.worxbend.codeberg4s.repositories.actions` — not this one, so it
  * is not reachable from here. Widening it to `private[codeberg4s]` would delete this file; that is a change to the
  * repository group's own source and belongs to whoever owns it. `docs/LEDGER.md` already tracks that kind of helper
  * under "helpers awaiting promotion".
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call.
  *
  * '''Every listing here is a bare JSON array.''' Neither the `{"total_count", "workflow_runs"}` envelope nor the
  * plain-text log body appears on the organisation surface, because the run, task and job-log endpoints are
  * repository-scoped only.
  */
private[actions] object OrganizationActionDecoders:

  /** One runner object. */
  val runner: Decode[ActionRunner] =
    WireDecode.of(Json.decoder[ActionRunnerDto])(_.toDomain)

  /** A bare array of runner objects, as the organisation's runner listing returns it. */
  val runners: Decode[Vector[ActionRunner]] =
    WireDecode.of(Json.decoder[Vector[ActionRunnerDto]])(dtos => ActionRunnerDto.toDomainAll(JsonPath.Root, dtos))

  /** The `{id, uuid, token}` object a runner registration returns. */
  val registeredRunner: Decode[RegisteredRunner] =
    WireDecode.of(Json.decoder[RegisteredRunnerDto])(_.toDomain)

  /** The one-key object the registration-token endpoint returns. */
  val registrationToken: Decode[RunnerRegistrationToken] =
    WireDecode.of(Json.decoder[RegistrationTokenDto])(_.toDomain)

  /** A bare array of job objects, as the runner job search returns it. */
  val jobs: Decode[Vector[ActionRunJob]] =
    WireDecode.of(Json.decoder[Vector[ActionRunJobDto]])(dtos => ActionRunJobDto.toDomainAll(JsonPath.Root, dtos))

  /** A bare array of secret objects — names and timestamps, never values. */
  val secrets: Decode[Vector[ActionSecret]] =
    WireDecode.of(Json.decoder[Vector[ActionSecretDto]])(dtos => ActionSecretDto.toDomainAll(JsonPath.Root, dtos))

  /** One variable object. */
  val variable: Decode[ActionVariable] =
    WireDecode.of(Json.decoder[ActionVariableDto])(_.toDomain)

  /** A bare array of variable objects. */
  val variables: Decode[Vector[ActionVariable]] =
    WireDecode.of(Json.decoder[Vector[ActionVariableDto]])(dtos => ActionVariableDto.toDomainAll(JsonPath.Root, dtos))
