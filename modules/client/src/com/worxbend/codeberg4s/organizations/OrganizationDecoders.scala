package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.organizations.wire.OrganizationDto
import com.worxbend.codeberg4s.organizations.wire.TeamDto
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.wire.Elements
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto

/** Every response shape [[OrganizationApi]] can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call: a decoder is a function
  * from a body to a value, and allocating a new one per request would be waste with no upside.
  *
  * Unlike the repository group, this one meets no envelope at all — every listing here is a bare JSON array, which
  * `golden/organization/org-list.json` and `org-repos-list.json` confirm and which is why the array decoders below all
  * report failures from [[com.worxbend.codeberg4s.JsonPath.Root]] rather than from a `data` field. Two of the element
  * types belong to earlier waves and are imported rather than redefined, per `docs/LEDGER.md`:
  * [[com.worxbend.codeberg4s.users.User]] for the membership listings and
  * [[com.worxbend.codeberg4s.repositories.Repository]] for the repository listings.
  */
private[organizations] object OrganizationDecoders:

  /** One organisation object, as `GET /orgs/{org}` returns it. */
  val organization: Decode[Organization] =
    WireDecode.of(Json.decoder[OrganizationDto])(_.toDomain)

  /** A bare array of organisation objects, as `GET /orgs` and `GET /users/{username}/orgs` return it. */
  val organizations: Decode[Vector[Organization]] =
    WireDecode.of(Json.decoder[Vector[OrganizationDto]])(dtos => OrganizationDto.toDomainAll(JsonPath.Root, dtos))

  /** One team object, as `GET /teams/{id}` returns it. */
  val team: Decode[Team] =
    WireDecode.of(Json.decoder[TeamDto])(_.toDomain)

  /** A bare array of team objects, as `GET /orgs/{org}/teams` returns it. */
  val teams: Decode[Vector[Team]] =
    WireDecode.of(Json.decoder[Vector[TeamDto]])(dtos => TeamDto.toDomainAll(JsonPath.Root, dtos))

  /** A bare array of user objects, as the member and team-member listings return it. */
  val users: Decode[Vector[User]] =
    WireDecode.of(Json.decoder[Vector[UserDto]]): dtos =>
      Elements.convert(JsonPath.Root, dtos)((dto, at) => dto.toDomainAt(at))

  /** A bare array of repository objects, as the organisation and team repository listings return it. */
  val repositories: Decode[Vector[Repository]] =
    WireDecode.of(Json.decoder[Vector[RepositoryDto]]): dtos =>
      Elements.convert(JsonPath.Root, dtos)((dto, at) => dto.toDomainAt(at))
