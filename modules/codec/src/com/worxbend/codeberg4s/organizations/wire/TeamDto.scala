package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.organizations.{Team, TeamId, TeamPermission}

/** Forgejo's `Team` model, field for field.
  *
  * ==Derived from the spec, not from a capture==
  *
  * Every other DTO in this module is checked against a verbatim response body. This one cannot be: `golden/MANIFEST.md`
  * records `GET /orgs/{org}/teams` answering `401 token is required` anonymously, so the only artefact of that probe is
  * `golden/error/401-org-teams.json`. The nine fields below are the nine properties of the pinned spec's `Team`
  * definition. Because `docs/HAZARDS.md` §1 measured that the spec asserts nothing about optionality, every one of them
  * is treated as absent-able — which is the same rule the captured models follow, so nothing special happens here; it
  * is only the evidence that is weaker.
  *
  * ==`units_map` is read through the raw value, not through an accessor==
  *
  * [[com.worxbend.codeberg4s.codec.JsonFields]] has accessors for scalars, objects and arrays but not for an object
  * used as a string-to-string map, which is what `units_map` is. Rather than widening a shared type for one field, this
  * DTO reads `JsonFields.value` and projects the object itself. An entry whose value is not a string is dropped,
  * matching the leniency the rest of the module already applies to a field of the wrong JSON kind.
  */
final case class TeamDto(
    id: Option[Long],
    name: Option[String],
    description: Option[String],
    organization: Option[OrganizationDto],
    permission: Option[String],
    units: Vector[String],
    unitsMap: Map[String, String],
    canCreateOrgRepo: Option[Boolean],
    includesAllRepositories: Option[Boolean],
) extends WireModel[Team]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Two things are required. `id` goes through [[com.worxbend.codeberg4s.organizations.TeamId.from]] because it is the
    * only way any endpoint addresses a team, and `name` is what a caller displays. Both are reported at their own path
    * — `$[2].id` for the third element of a listing, not `$`.
    *
    * `permission` and the values of `units_map` are parsed leniently: a level this library does not recognise is
    * dropped, never a failure, for the reason [[com.worxbend.codeberg4s.organizations.TeamPermission.parse]] gives. A
    * failure inside the embedded `organization` is reported at `$.organization.…`, by that model.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Team] =
    for
      identifier <- Wire.validated(at, "id", id)(TeamId.from)
      label      <- Wire.required(at, "name", name)
      owner      <- Wire.nested(at, "organization", organization)(_.toDomainAt(_))
    yield Team(
      id                      = identifier,
      name                    = label,
      description             = description,
      organization            = owner,
      permission              = permission.flatMap(TeamPermission.parse),
      units                   = units,
      unitPermissions         = TeamDto.parsedLevels(unitsMap),
      canCreateOrgRepo        = canCreateOrgRepo.getOrElse(false),
      includesAllRepositories = includesAllRepositories.getOrElse(false),
    )

object TeamDto:

  /** The key holding the per-unit access levels; see the class note on why it is read by hand. */
  private val UnitsMapKey: String = "units_map"

  /** Reads a `Team` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[TeamDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing [[OrganizationDto.fromFields]] for the embedded organisation so that
    * no field spelling is written twice.
    */
  def fromFields(fields: JsonFields): TeamDto =
    TeamDto(
      id                      = fields.number("id"),
      name                    = fields.text("name"),
      description             = fields.text("description"),
      organization            = fields.nested("organization").map(OrganizationDto.fromFields),
      permission              = fields.text("permission"),
      units                   = fields.texts("units"),
      unitsMap                = rawLevels(fields),
      canCreateOrgRepo        = fields.boolean("can_create_org_repo"),
      includesAllRepositories = fields.boolean("includes_all_repositories"),
    )

  /** Converts a decoded array of teams, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[TeamDto]): Either[DecodeFailure, Vector[Team]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))

  /** The `units_map` object as unit name to raw level, dropping any entry whose value is not a string. */
  private def rawLevels(fields: JsonFields): Map[String, String] =
    fields
      .value(UnitsMapKey)
      .flatMap(_.objOpt)
      .fold(Map.empty[String, String])(entries =>
        entries.toMap.flatMap((unit, level) => level.strOpt.map(text => (unit, text)))
      )

  /** The raw levels with each value parsed, dropping any level [[TeamPermission.parse]] does not recognise. */
  private def parsedLevels(raw: Map[String, String]): Map[String, TeamPermission] =
    raw.flatMap((unit, level) => TeamPermission.parse(level).map(parsed => (unit, parsed)))
