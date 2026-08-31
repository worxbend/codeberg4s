package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.organizations.Team
import com.worxbend.codeberg4s.organizations.wire.TeamDto
import com.worxbend.codeberg4s.repositories.access.wire.BranchProtectionDto
import com.worxbend.codeberg4s.repositories.access.wire.CollaboratorAccessDto
import com.worxbend.codeberg4s.repositories.access.wire.DeployKeyDto
import com.worxbend.codeberg4s.repositories.access.wire.TagProtectionDto
import com.worxbend.codeberg4s.repositories.wire.Elements
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto

/** Every response shape [[RepositoryAccessApi]] can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call, exactly as
  * [[com.worxbend.codeberg4s.repositories.RepositoryDecoders]] does.
  *
  * ==Every body in this group is a bare object or a bare array==
  *
  * Unlike the Actions group, nothing here wraps its results in an envelope: `BranchProtectionList`,
  * `TagProtectionList`, `UserList`, `DeployKeyList` and `TeamListWithoutPagination` are all plain JSON arrays in
  * `spec/swagger.v1.json`, so a bad element is reported at `$[n].field` and not under a key. Two operations return no
  * body at all — `repoCheckCollaborator` and both team mutations answer `204` — and those go through
  * [[com.worxbend.codeberg4s.core.ApiPipeline.callUnit]], which never looks at a body and therefore needs no decoder
  * here.
  *
  * ==Two models are borrowed, not forked==
  *
  * The collaborator listing answers `UserList`, and the team endpoints answer `Team` and a list of them. Both models
  * are owned by other groups per `docs/LEDGER.md` and are reused verbatim through their own DTOs; this group adds no
  * user-shaped or team-shaped type of its own.
  */
private[access] object RepositoryAccessDecoders:

  /** One branch protection rule. */
  val branchProtection: Decode[BranchProtection] =
    WireDecode.of(Json.decoder[BranchProtectionDto])(_.toDomain)

  /** A bare array of branch protection rules, as the unpaged listing returns it. */
  val branchProtections: Decode[Vector[BranchProtection]] =
    WireDecode.vector(Json.decoder[Vector[BranchProtectionDto]])(BranchProtectionDto.toDomainAll)

  /** One tag protection rule. */
  val tagProtection: Decode[TagProtection] =
    WireDecode.of(Json.decoder[TagProtectionDto])(_.toDomain)

  /** A bare array of tag protection rules. */
  val tagProtections: Decode[Vector[TagProtection]] =
    WireDecode.vector(Json.decoder[Vector[TagProtectionDto]])(TagProtectionDto.toDomainAll)

  /** A bare array of users, which is what the collaborator listing answers.
    *
    * `UserDto` has no `toDomainAll` of its own — it is not this group's model — so the element fold is spelled out
    * here, exactly as [[com.worxbend.codeberg4s.organizations.OrganizationDecoders]] and
    * [[com.worxbend.codeberg4s.issues.IssueDecoders]] spell it out. One bad element still fails the page, and reports
    * its position.
    */
  val collaborators: Decode[Vector[User]] =
    WireDecode.vector(Json.decoder[Vector[UserDto]]): (at, dtos) =>
      Elements.convert(at, dtos)(_.toDomainAt(_))

  /** The `{permission, role_name, user}` object the collaborator permission endpoint answers. */
  val collaboratorAccess: Decode[CollaboratorAccess] =
    WireDecode.of(Json.decoder[CollaboratorAccessDto])(_.toDomain)

  /** One deploy key. */
  val deployKey: Decode[DeployKey] =
    WireDecode.of(Json.decoder[DeployKeyDto])(_.toDomain)

  /** A bare array of deploy keys. */
  val deployKeys: Decode[Vector[DeployKey]] =
    WireDecode.vector(Json.decoder[Vector[DeployKeyDto]])(DeployKeyDto.toDomainAll)

  /** One team, which is what the team check answers rather than the `204` its siblings answer. */
  val team: Decode[Team] =
    WireDecode.of(Json.decoder[TeamDto])(_.toDomain)

  /** A bare array of teams, as `TeamListWithoutPagination` returns it. */
  val teams: Decode[Vector[Team]] =
    WireDecode.vector(Json.decoder[Vector[TeamDto]])(TeamDto.toDomainAll)
