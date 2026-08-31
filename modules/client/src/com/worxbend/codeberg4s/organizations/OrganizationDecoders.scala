package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.issues.Label
import com.worxbend.codeberg4s.issues.wire.LabelDto
import com.worxbend.codeberg4s.organizations.wire.BlockedUserDto
import com.worxbend.codeberg4s.organizations.wire.OrganizationDto
import com.worxbend.codeberg4s.organizations.wire.OrganizationPermissionsDto
import com.worxbend.codeberg4s.organizations.wire.QuotaArtifactDto
import com.worxbend.codeberg4s.organizations.wire.QuotaAttachmentDto
import com.worxbend.codeberg4s.organizations.wire.QuotaInfoDto
import com.worxbend.codeberg4s.organizations.wire.QuotaPackageDto
import com.worxbend.codeberg4s.organizations.wire.TeamDto
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.admin.RepositoryActivity
import com.worxbend.codeberg4s.repositories.admin.wire.ActivityDto
import com.worxbend.codeberg4s.repositories.hooks.Webhook
import com.worxbend.codeberg4s.repositories.hooks.wire.WebhookDto
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto
import com.worxbend.codeberg4s.wire.SearchEnvelopeDto

/** Every response shape the five API classes of this package can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call: a decoder is a function
  * from a body to a value, and allocating a new one per request would be waste with no upside.
  *
  * ==One envelope, and only one==
  *
  * Every listing in this group is a bare JSON array — `golden/organization/org-list.json`, `org-repos-list.json` and
  * `org-labels-list.json` confirm three of them — which is why the array decoders below report failures from
  * [[com.worxbend.codeberg4s.JsonPath.Root]]. The single exception is `GET /orgs/{org}/teams/search`, whose `200` the
  * spec types as an inline object with `data` and `ok`: the same `{"ok", "data"}` wrapper `docs/HAZARDS.md` §3 measured
  * on `/repos/search`. [[teamSearchResults]] is the one decoder here that unwraps, and it reports its elements at
  * `$.data[n]`.
  *
  * ==Most of the element types belong to earlier waves==
  *
  * `docs/LEDGER.md` makes the first wave that needs a shared model its owner and calls a forked copy a review-blocking
  * defect, so this group imports rather than redefines: [[com.worxbend.codeberg4s.users.User]] for the membership
  * listings, [[com.worxbend.codeberg4s.repositories.Repository]] for the repository listings,
  * [[com.worxbend.codeberg4s.issues.Label]] for organisation labels — which `golden/organization/org-labels-list.json`
  * proves are the repository label model field for field — [[com.worxbend.codeberg4s.repositories.hooks.Webhook]] for
  * organisation webhooks, which are Forgejo's one `Hook` model served from a second path, and
  * [[com.worxbend.codeberg4s.repositories.admin.RepositoryActivity]] for the two activity feeds, which are Forgejo's
  * one `Activity` model. That last name reads oddly on an organisation feed; it is still the right type, because the
  * payload is identical and a second copy of a twenty-seven-case action vocabulary is precisely the defect the ledger
  * names.
  */
private[organizations] object OrganizationDecoders:

  /** One organisation object, as `GET /orgs/{org}` returns it. */
  val organization: Decode[Organization] =
    WireDecode.single(Json.decoder[OrganizationDto])(_.toDomain)

  /** A bare array of organisation objects, as `GET /orgs` and `GET /users/{username}/orgs` return it. */
  val organizations: Decode[Vector[Organization]] =
    WireDecode.vector(Json.decoder[Vector[OrganizationDto]])(OrganizationDto.toDomainAll)

  /** One team object, as `GET /teams/{id}` returns it. */
  val team: Decode[Team] =
    WireDecode.single(Json.decoder[TeamDto])(_.toDomain)

  /** A bare array of team objects, as `GET /orgs/{org}/teams` returns it. */
  val teams: Decode[Vector[Team]] =
    WireDecode.vector(Json.decoder[Vector[TeamDto]])(TeamDto.toDomainAll)

  /** A bare array of user objects, as the member and team-member listings return it. */
  val users: Decode[Vector[User]] =
    WireDecode.vector(Json.decoder[Vector[UserDto]]): (at, dtos) =>
      ArrayElements.convert(at, dtos)(_.toDomainAt(_))

  /** A bare array of repository objects, as the organisation and team repository listings return it. */
  val repositories: Decode[Vector[Repository]] =
    WireDecode.vector(Json.decoder[Vector[RepositoryDto]]): (at, dtos) =>
      ArrayElements.convert(at, dtos)(_.toDomainAt(_))

  /** One user object, as `GET /teams/{id}/members/{username}` returns it. */
  val user: Decode[User] =
    WireDecode.single(Json.decoder[UserDto])(_.toDomain)

  /** One repository object, as `GET /teams/{id}/repos/{org}/{repo}` returns it. */
  val repository: Decode[Repository] =
    WireDecode.single(Json.decoder[RepositoryDto])(_.toDomain)

  /** The `{"ok", "data"}` envelope the team search returns; see the object note. */
  val teamSearchResults: Decode[Vector[Team]] =
    WireDecode.single(Json.decoder[SearchEnvelopeDto[TeamDto]]): envelope =>
      ArrayElements.convert(JsonPath.Root.field("data"), envelope.data)((dto, at) => dto.toDomainAt(at))

  /** One webhook object, as the organisation hook routes return it. */
  val webhook: Decode[Webhook] =
    WireDecode.single(Json.decoder[WebhookDto])(_.toDomain)

  /** A bare array of webhook objects, as `GET /orgs/{org}/hooks` returns it. */
  val webhooks: Decode[Vector[Webhook]] =
    WireDecode.vector(Json.decoder[Vector[WebhookDto]])(WebhookDto.toDomainAll)

  /** One label object, as the organisation label routes return it. */
  val label: Decode[Label] =
    WireDecode.single(Json.decoder[LabelDto])(_.toDomain)

  /** A bare array of label objects; `golden/organization/org-labels-list.json` is a capture of exactly this. */
  val labels: Decode[Vector[Label]] =
    WireDecode.vector(Json.decoder[Vector[LabelDto]])(LabelDto.toDomainAll)

  /** A bare array of activity entries, as the organisation and team feeds return them. */
  val activities: Decode[Vector[RepositoryActivity]] =
    WireDecode.vector(Json.decoder[Vector[ActivityDto]])(ActivityDto.toDomainAll)

  /** A bare array of block records, as `GET /orgs/{org}/list_blocked` returns it. */
  val blockedUsers: Decode[Vector[BlockedUser]] =
    WireDecode.vector(Json.decoder[Vector[BlockedUserDto]])(BlockedUserDto.toDomainAll)

  /** The five effective permission flags, as `GET /users/{username}/orgs/{org}/permissions` returns them. */
  val permissions: Decode[OrganizationPermissions] =
    WireDecode.single(Json.decoder[OrganizationPermissionsDto])(_.toDomain)

  /** The quota tree, as `GET /orgs/{org}/quota` returns it. */
  val quotaInfo: Decode[QuotaInfo] =
    WireDecode.single(Json.decoder[QuotaInfoDto])(_.toDomain)

  /** The bare JSON boolean `GET /orgs/{org}/quota/check` answers with.
    *
    * The only decoder in this package with no DTO behind it, because there is no object: the spec types the `200` as
    * `{"type": "boolean"}` and the body really is the literal `true` or `false`. A body that is neither becomes a
    * [[com.worxbend.codeberg4s.core.DecodeFailure]] at the root, like any other shape mismatch.
    */
  val quotaCheck: Decode[Boolean] =
    Json.decoder[Boolean]

  /** A bare array of artifact usage entries. */
  val quotaArtifacts: Decode[Vector[QuotaArtifact]] =
    WireDecode.vector(Json.decoder[Vector[QuotaArtifactDto]])(QuotaArtifactDto.toDomainAll)

  /** A bare array of attachment usage entries. */
  val quotaAttachments: Decode[Vector[QuotaAttachment]] =
    WireDecode.vector(Json.decoder[Vector[QuotaAttachmentDto]])(QuotaAttachmentDto.toDomainAll)

  /** A bare array of package usage entries. */
  val quotaPackages: Decode[Vector[QuotaPackage]] =
    WireDecode.vector(Json.decoder[Vector[QuotaPackageDto]])(QuotaPackageDto.toDomainAll)
