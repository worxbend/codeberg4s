package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.{ArrayElements, Json}
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.organizations.Team
import com.worxbend.codeberg4s.organizations.wire.TeamDto
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.actions.wire.{
  ActionRunJobDto,
  ActionRunnerDto,
  ActionVariableDto,
  RegisteredRunnerDto,
  RegistrationTokenDto
}
import com.worxbend.codeberg4s.repositories.actions.{
  ActionRunJob,
  ActionRunner,
  ActionVariable,
  RegisteredRunner,
  RunnerRegistrationToken
}
import com.worxbend.codeberg4s.repositories.hooks.Webhook
import com.worxbend.codeberg4s.repositories.hooks.wire.WebhookDto
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto
import com.worxbend.codeberg4s.users.account.wire.{
  EmailDto,
  OAuth2ApplicationDto,
  QuotaInfoDto,
  QuotaUsedArtifactDto,
  QuotaUsedAttachmentDto,
  QuotaUsedPackageDto,
  UserSettingsDto
}

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

  /** One OAuth2 application object as a '''read''' returns it — never with a secret in it.
    *
    * Forgejo stores the secret hashed, so `GET /user/applications/oauth2/{id}` cannot carry one. That is why this keeps
    * the ordinary body excerpt on a decoding failure while [[issuedApplication]] does not: the two responses have the
    * same shape and different consequences, and one decoder for both would have to be as cautious as the more dangerous
    * of them.
    */
  val application: Decode[OAuth2Application] =
    WireDecode.single(Json.decoder[OAuth2ApplicationDto])(_.toDomain)

  /** The same object as [[application]], from the responses that '''do''' carry `client_secret`.
    *
    * Those are the `201` of a registration and the `200` of an update — the spec does not say whether an update
    * re-issues the secret, so this treats it as though it does, which is the safe direction to be wrong in. Marked
    * [[com.worxbend.codeberg4s.core.Decode.sensitive]], so a payload that does not decode reports
    * [[com.worxbend.codeberg4s.core.ApiPipeline.redactedSnippet]] rather than an excerpt containing the one copy of
    * that credential.
    */
  val issuedApplication: Decode[OAuth2Application] =
    Decode.sensitive(application)

  /** A bare array of OAuth2 application objects, as the listing returns it — never with a secret in it. */
  val applications: Decode[Vector[OAuth2Application]] =
    WireDecode.vector(Json.decoder[Vector[OAuth2ApplicationDto]])(OAuth2ApplicationDto.toDomainAll)

  /** A bare array of email objects, which is what both the listing and the `201` of an add return. */
  val emails: Decode[Vector[Email]] =
    WireDecode.vector(Json.decoder[Vector[EmailDto]])(EmailDto.toDomainAll)

  /** The account's settings object, returned by both the read and the update. */
  val settings: Decode[UserSettings] =
    WireDecode.single(Json.decoder[UserSettingsDto])(_.toDomain)

  /** The account's quota report, with its nested `used` tree already flattened. */
  val quota: Decode[QuotaInfo] =
    WireDecode.single(Json.decoder[QuotaInfoDto])(_.toDomain)

  /** A bare array of quota-counting artifacts. */
  val quotaArtifacts: Decode[Vector[QuotaUsedArtifact]] =
    WireDecode.vector(Json.decoder[Vector[QuotaUsedArtifactDto]])(QuotaUsedArtifactDto.toDomainAll)

  /** A bare array of quota-counting attachments. */
  val quotaAttachments: Decode[Vector[QuotaUsedAttachment]] =
    WireDecode.vector(Json.decoder[Vector[QuotaUsedAttachmentDto]])(QuotaUsedAttachmentDto.toDomainAll)

  /** A bare array of quota-counting package versions. */
  val quotaPackages: Decode[Vector[QuotaUsedPackage]] =
    WireDecode.vector(Json.decoder[Vector[QuotaUsedPackageDto]])(QuotaUsedPackageDto.toDomainAll)

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
    WireDecode.single(Json.decoder[RepositoryDto])(_.toDomain)

  /** A bare array of repository objects, as the account's repository listing returns it. */
  val repositories: Decode[Vector[Repository]] =
    WireDecode.vector(Json.decoder[Vector[RepositoryDto]]): (at, dtos) =>
      ArrayElements.convert(at, dtos)(_.toDomainAt(_))

  /** A bare array of team objects, as the account's team listing returns it. */
  val teams: Decode[Vector[Team]] =
    WireDecode.vector(Json.decoder[Vector[TeamDto]])(TeamDto.toDomainAll)

  /** One runner object. */
  val runner: Decode[ActionRunner] =
    WireDecode.single(Json.decoder[ActionRunnerDto])(_.toDomain)

  /** A bare array of runner objects. */
  val runners: Decode[Vector[ActionRunner]] =
    WireDecode.vector(Json.decoder[Vector[ActionRunnerDto]])(ActionRunnerDto.toDomainAll)

  /** A bare array of job objects, as the runner job search returns it. */
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

  /** One variable object. */
  val variable: Decode[ActionVariable] =
    WireDecode.single(Json.decoder[ActionVariableDto])(_.toDomain)

  /** A bare array of variable objects. */
  val variables: Decode[Vector[ActionVariable]] =
    WireDecode.vector(Json.decoder[Vector[ActionVariableDto]])(ActionVariableDto.toDomainAll)

  /** One webhook object. */
  val webhook: Decode[Webhook] =
    WireDecode.single(Json.decoder[WebhookDto])(_.toDomain)

  /** A bare array of webhook objects, as the account's hook listing returns it. */
  val webhooks: Decode[Vector[Webhook]] =
    WireDecode.vector(Json.decoder[Vector[WebhookDto]])(WebhookDto.toDomainAll)
