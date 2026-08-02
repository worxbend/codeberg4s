package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.organizations.OrgName
import com.worxbend.codeberg4s.organizations.Organization
import com.worxbend.codeberg4s.repositories.wire.Elements
import com.worxbend.codeberg4s.users.UserVisibility

/** Forgejo's `Organization` model, field for field.
  *
  * Every key of the pinned spec's twelve-property definition is here, and every key observed on
  * `golden/organization/org-single.json` and the three elements of `golden/organization/org-list.json` is one of those
  * twelve — the two sets agree exactly, which is unusual enough in this API to be worth recording.
  *
  * `username` is kept even though the spec marks it `deprecated` and it duplicates `name` on all four captured
  * organisations. Keeping it costs nothing and means the DTO can be diffed against a captured payload without a mental
  * exception list; it is dropped in conversion.
  *
  * ==Empty strings, not nulls==
  *
  * This model is the clearest instance of the `""`-for-absent convention `docs/HAZARDS.md` §1 describes. `forgejo`
  * sends `"email": ""` and `"location": ""`; the three organisations of `org-list.json` send `""` for `full_name`,
  * `email`, `description`, `website` and `location` alike. `JsonFields.text` folds blank into absent, so nothing
  * downstream has to know the trick. `created` stays a raw string here and becomes an instant during conversion.
  */
final case class OrganizationDto(
    id: Option[Long],
    name: Option[String],
    fullName: Option[String],
    email: Option[String],
    avatarUrl: Option[String],
    description: Option[String],
    website: Option[String],
    location: Option[String],
    visibility: Option[String],
    repoAdminChangeTeamAccess: Option[Boolean],
    created: Option[String],
    username: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Two things are required. `id` is what distinguishes two organisations across a rename, and `name` goes through
    * [[com.worxbend.codeberg4s.organizations.OrgName.from]] because it is the argument every other organisation
    * endpoint takes — an organisation that cannot address itself would be useless. A `name` the constructor rejects is
    * reported at `$.name` exactly like a missing one, per [[com.worxbend.codeberg4s.codec.Wire.validated]].
    *
    * Everything else is optional or defaulted: `repo_admin_change_team_access` absent becomes `false`, an unrecognised
    * `visibility` becomes `None` rather than a failure, and `created` that is blank, unparseable or the Go zero-time
    * sentinel becomes `None` per [[com.worxbend.codeberg4s.codec.Timestamps]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Organization] =
    for
      identifier <- Wire.required(at, "id", id)
      handle     <- Wire.validated(at, "name", name)(OrgName.from)
    yield Organization(
      id                        = identifier,
      name                      = handle,
      fullName                  = fullName,
      email                     = email,
      avatarUrl                 = avatarUrl,
      description               = description,
      website                   = website,
      location                  = location,
      visibility                = visibility.flatMap(UserVisibility.parse),
      repoAdminChangeTeamAccess = repoAdminChangeTeamAccess.getOrElse(false),
      createdAt                 = Timestamps.parseOptional(created),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, Organization] =
    toDomainAt(JsonPath.Root)

object OrganizationDto:

  /** Reads an `Organization` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[OrganizationDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. Used both by the reader above and by
    * [[com.worxbend.codeberg4s.organizations.wire.TeamDto]], which embeds an organisation, so the field spellings exist
    * in exactly one place.
    */
  def fromFields(fields: JsonFields): OrganizationDto =
    OrganizationDto(
      id                        = fields.number("id"),
      name                      = fields.text("name"),
      fullName                  = fields.text("full_name"),
      email                     = fields.text("email"),
      avatarUrl                 = fields.text("avatar_url"),
      description               = fields.text("description"),
      website                   = fields.text("website"),
      location                  = fields.text("location"),
      visibility                = fields.text("visibility"),
      repoAdminChangeTeamAccess = fields.boolean("repo_admin_change_team_access"),
      created                   = fields.text("created"),
      username                  = fields.text("username"),
    )

  /** Converts a decoded array of organisations, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[OrganizationDto]): Either[DecodeFailure, Vector[Organization]] =
    Elements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
