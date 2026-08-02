package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.organizations.Team
import com.worxbend.codeberg4s.organizations.wire.TeamDto
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.actions.ActionRunJob
import com.worxbend.codeberg4s.repositories.actions.ActionRunner
import com.worxbend.codeberg4s.repositories.actions.ActionVariable
import com.worxbend.codeberg4s.repositories.actions.RegisteredRunner
import com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken
import com.worxbend.codeberg4s.repositories.actions.wire.ActionRunJobDto
import com.worxbend.codeberg4s.repositories.actions.wire.ActionRunnerDto
import com.worxbend.codeberg4s.repositories.actions.wire.ActionVariableDto
import com.worxbend.codeberg4s.repositories.actions.wire.RegisteredRunnerDto
import com.worxbend.codeberg4s.repositories.actions.wire.RegistrationTokenDto
import com.worxbend.codeberg4s.repositories.hooks.Webhook
import com.worxbend.codeberg4s.repositories.hooks.wire.WebhookDto
import com.worxbend.codeberg4s.repositories.wire.Elements
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto
import com.worxbend.codeberg4s.users.account.wire.EmailDto
import com.worxbend.codeberg4s.users.account.wire.OAuth2ApplicationDto
import com.worxbend.codeberg4s.users.account.wire.QuotaInfoDto
import com.worxbend.codeberg4s.users.account.wire.QuotaUsedArtifactDto
import com.worxbend.codeberg4s.users.account.wire.QuotaUsedAttachmentDto
import com.worxbend.codeberg4s.users.account.wire.QuotaUsedPackageDto
import com.worxbend.codeberg4s.users.account.wire.UserSettingsDto

/** Every response shape the five API classes of this package can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call, exactly as
  * [[com.worxbend.codeberg4s.repositories.RepositoryDecoders]] does.
  *
  * ==Most of these read someone else's model, on purpose==
  *
  * The `/user/actions` routes answer the same `ActionRunner`, `ActionVariable`, `RunJob`, `RegisterRunnerResponse` and
  * `RegistrationToken` payloads as their repository-scoped twins, and `/user/hooks` answers the same `Hook`;
  * `/user/repos` answers `Repository` and `/user/teams` answers `Team`. Only the path differs, so this object reaches
  * for the existing DTOs rather than defining a second set — `docs/LEDGER.md` treats a fork of a shared model as a
  * review-blocking defect, and two decoders for one payload would drift the first time Forgejo added a field.
  *
  * The five shapes that genuinely are new to this group — an OAuth2 application, an email address, the account's
  * settings, its quota, and the three quota usage listings — live in [[com.worxbend.codeberg4s.users.account.wire]].
  */
private[account] object UserAccountDecoders:

  /** One OAuth2 application object, which on a creation response carries the client secret. */
  val application: Decode[OAuth2Application] =
    WireDecode.of(Json.decoder[OAuth2ApplicationDto])(_.toDomain)

  /** A bare array of OAuth2 application objects, as the listing returns it — never with a secret in it. */
  val applications: Decode[Vector[OAuth2Application]] =
    WireDecode.of(Json.decoder[Vector[OAuth2ApplicationDto]]): dtos =>
      OAuth2ApplicationDto.toDomainAll(JsonPath.Root, dtos)

  /** A bare array of email objects, which is what both the listing and the `201` of an add return. */
  val emails: Decode[Vector[Email]] =
    WireDecode.of(Json.decoder[Vector[EmailDto]])(dtos => EmailDto.toDomainAll(JsonPath.Root, dtos))

  /** The account's settings object, returned by both the read and the update. */
  val settings: Decode[UserSettings] =
    WireDecode.of(Json.decoder[UserSettingsDto])(_.toDomain)

  /** The account's quota report, with its nested `used` tree already flattened. */
  val quota: Decode[QuotaInfo] =
    WireDecode.of(Json.decoder[QuotaInfoDto])(_.toDomain)

  /** A bare array of quota-counting artifacts. */
  val quotaArtifacts: Decode[Vector[QuotaUsedArtifact]] =
    WireDecode.of(Json.decoder[Vector[QuotaUsedArtifactDto]]): dtos =>
      QuotaUsedArtifactDto.toDomainAll(JsonPath.Root, dtos)

  /** A bare array of quota-counting attachments. */
  val quotaAttachments: Decode[Vector[QuotaUsedAttachment]] =
    WireDecode.of(Json.decoder[Vector[QuotaUsedAttachmentDto]]): dtos =>
      QuotaUsedAttachmentDto.toDomainAll(JsonPath.Root, dtos)

  /** A bare array of quota-counting package versions. */
  val quotaPackages: Decode[Vector[QuotaUsedPackage]] =
    WireDecode.of(Json.decoder[Vector[QuotaUsedPackageDto]]): dtos =>
      QuotaUsedPackageDto.toDomainAll(JsonPath.Root, dtos)

  /** The bare JSON boolean `GET /user/quota/check` answers.
    *
    * '''The whole body is the value.''' This is the only endpoint in the group whose response is not an object or an
    * array, and there is nothing to convert: `true` means the instance would accept the action, `false` that it would
    * refuse it for quota reasons. A body that is neither becomes
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$`, like any other payload that does not fit.
    */
  val quotaVerdict: Decode[Boolean] =
    Json.decoder[Boolean]

  /** One repository object, as the creation returns it. */
  val repository: Decode[Repository] =
    WireDecode.of(Json.decoder[RepositoryDto])(_.toDomain)

  /** A bare array of repository objects, as the account's repository listing returns it. */
  val repositories: Decode[Vector[Repository]] =
    WireDecode.of(Json.decoder[Vector[RepositoryDto]]): dtos =>
      Elements.convert(JsonPath.Root, dtos)((dto, path) => dto.toDomainAt(path))

  /** A bare array of team objects, as the account's team listing returns it. */
  val teams: Decode[Vector[Team]] =
    WireDecode.of(Json.decoder[Vector[TeamDto]])(dtos => TeamDto.toDomainAll(JsonPath.Root, dtos))

  /** One runner object. */
  val runner: Decode[ActionRunner] =
    WireDecode.of(Json.decoder[ActionRunnerDto])(_.toDomain)

  /** A bare array of runner objects. */
  val runners: Decode[Vector[ActionRunner]] =
    WireDecode.of(Json.decoder[Vector[ActionRunnerDto]])(dtos => ActionRunnerDto.toDomainAll(JsonPath.Root, dtos))

  /** A bare array of job objects, as the runner job search returns it. */
  val jobs: Decode[Vector[ActionRunJob]] =
    WireDecode.of(Json.decoder[Vector[ActionRunJobDto]])(dtos => ActionRunJobDto.toDomainAll(JsonPath.Root, dtos))

  /** The `{id, uuid, token}` object a runner registration returns. */
  val registeredRunner: Decode[RegisteredRunner] =
    WireDecode.of(Json.decoder[RegisteredRunnerDto])(_.toDomain)

  /** The one-key object the registration-token endpoint returns. */
  val registrationToken: Decode[RunnerRegistrationToken] =
    WireDecode.of(Json.decoder[RegistrationTokenDto])(_.toDomain)

  /** One variable object. */
  val variable: Decode[ActionVariable] =
    WireDecode.of(Json.decoder[ActionVariableDto])(_.toDomain)

  /** A bare array of variable objects. */
  val variables: Decode[Vector[ActionVariable]] =
    WireDecode.of(Json.decoder[Vector[ActionVariableDto]])(dtos => ActionVariableDto.toDomainAll(JsonPath.Root, dtos))

  /** One webhook object. */
  val webhook: Decode[Webhook] =
    WireDecode.of(Json.decoder[WebhookDto])(_.toDomain)

  /** A bare array of webhook objects, as the account's hook listing returns it. */
  val webhooks: Decode[Vector[Webhook]] =
    WireDecode.of(Json.decoder[Vector[WebhookDto]])(dtos => WebhookDto.toDomainAll(JsonPath.Root, dtos))
