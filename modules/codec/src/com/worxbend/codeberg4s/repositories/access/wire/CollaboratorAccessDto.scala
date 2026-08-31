package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.organizations.TeamPermission
import com.worxbend.codeberg4s.repositories.access.CollaboratorAccess
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto

/** The wire spelling of every property `RepoCollaboratorPermission` and `AddCollaboratorOption` have.
  *
  * The two models share nothing but the word `permission`, and even that is a different vocabulary on each side — three
  * values going in, five coming back. Both spellings live here so that the response reader and the request renderer
  * cannot drift apart on the one key they do share; see
  * [[com.worxbend.codeberg4s.repositories.access.CollaboratorPermission]] for why the vocabularies differ.
  */
private[codeberg4s] object CollaboratorWire:

  /** The access level. Sent as one of `read`, `write`, `admin`; read back as Forgejo's full access vocabulary. */
  val Permission: String = "permission"

  /** Forgejo's display name for the role. Response-only. */
  val RoleName: String = "role_name"

  /** The account the permission describes, embedded whole. Response-only. */
  val User: String = "user"

/** Forgejo's `RepoCollaboratorPermission` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[BranchProtectionDto]] for the evidence note
  * that applies to every model in this group.
  *
  * `user` nests [[com.worxbend.codeberg4s.users.wire.UserDto]] rather than flattening a login out of it, per rule 6 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]].
  */
final case class CollaboratorAccessDto(
    permission: Option[String],
    roleName: Option[String],
    user: Option[UserDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `user` is required: a permission that names nobody describes nothing, and this endpoint is asked about exactly one
    * account. A failure inside it is reported at `$.user.…`, by that model.
    *
    * '''The level is kept twice.''' `permission` is parsed leniently into
    * [[com.worxbend.codeberg4s.organizations.TeamPermission]] — a value from a future Forgejo release becomes `None`
    * rather than failing the read — and the raw string is carried through unchanged beside it. Everywhere else in this
    * library a dropped enum value costs a caller a descriptive field; here it would cost them the answer, and a caller
    * who read `None` as "no access" would have inverted it. See
    * [[com.worxbend.codeberg4s.repositories.access.CollaboratorAccess]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, CollaboratorAccess] =
    accountAt(at).map(account =>
      CollaboratorAccess(
        user          = account,
        permission    = permission.flatMap(TeamPermission.parse),
        rawPermission = permission,
        roleName      = roleName,
      )
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, CollaboratorAccess] =
    toDomainAt(JsonPath.Root)

  private def accountAt(at: JsonPath): Either[DecodeFailure, User] =
    for
      dto     <- Wire.required(at, CollaboratorWire.User, user)
      account <- dto.toDomainAt(at.field(CollaboratorWire.User))
    yield account

object CollaboratorAccessDto:

  /** Reads a `RepoCollaboratorPermission` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[CollaboratorAccessDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing [[com.worxbend.codeberg4s.users.wire.UserDto.fromFields]] so the user
    * field spellings are not repeated here.
    */
  def fromFields(fields: JsonFields): CollaboratorAccessDto =
    CollaboratorAccessDto(
      permission = fields.text(CollaboratorWire.Permission),
      roleName   = fields.text(CollaboratorWire.RoleName),
      user       = fields.nested(CollaboratorWire.User).map(UserDto.fromFields),
    )
