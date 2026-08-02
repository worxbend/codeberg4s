package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.organizations.OrganizationPermissions

/** Forgejo's `OrganizationPermissions` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' `GET /users/{u}/orgs/{org}/permissions`
  * needs a token and the golden harvest was anonymous. All five declared properties are here.
  *
  * ==Nothing is required, and absent reads as "may not"==
  *
  * [[toDomainAt]] cannot fail. There is no identifier in this payload — it answers a yes-or-no question about a pair
  * the '''request''' named — so there is nothing a missing field would make unaddressable, and a decoding failure here
  * would cost a caller the answer over a field that was never load-bearing.
  *
  * Every absent flag becomes `false`. That is the one direction a permission may be guessed in: reading silence as
  * "yes" would hand a caller a reason to try something the instance would refuse, while reading it as "no" only makes
  * the caller ask.
  */
final case class OrganizationPermissionsDto(
    isOwner: Option[Boolean],
    isAdmin: Option[Boolean],
    canWrite: Option[Boolean],
    canRead: Option[Boolean],
    canCreateRepository: Option[Boolean],
):

  /** Converts to the domain. Total; see the class note on why nothing here can fail.
    *
    * There is deliberately no `toDomainAt` companion to this method, unlike every other DTO in this package. That
    * overload exists so a failure can be reported at the element's own position inside an array, and this model is
    * never an array element and can never fail — a second method that could only ever be handed
    * [[com.worxbend.codeberg4s.JsonPath.Root]] would be ceremony.
    */
  def toDomain: Either[DecodeFailure, OrganizationPermissions] =
    Right(
      OrganizationPermissions(
        isOwner             = isOwner.getOrElse(false),
        isAdmin             = isAdmin.getOrElse(false),
        canWrite            = canWrite.getOrElse(false),
        canRead             = canRead.getOrElse(false),
        canCreateRepository = canCreateRepository.getOrElse(false),
      )
    )

object OrganizationPermissionsDto:

  /** Reads an `OrganizationPermissions` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given upickle.default.Reader[OrganizationPermissionsDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): OrganizationPermissionsDto =
    OrganizationPermissionsDto(
      isOwner             = fields.boolean("is_owner"),
      isAdmin             = fields.boolean("is_admin"),
      canWrite            = fields.boolean("can_write"),
      canRead             = fields.boolean("can_read"),
      canCreateRepository = fields.boolean("can_create_repository"),
    )
