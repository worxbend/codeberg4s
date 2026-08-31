package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.organizations.{CreateTeam, EditTeam, TeamPermission}

/** Forgejo's `CreateTeamOption` and `EditTeamOption` request models — the bodies of `POST /orgs/{org}/teams` and
  * `PATCH /teams/{id}`.
  *
  * One object for two models because the spec declares them with identical property sets, and rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once. An object rather
  * than a case class, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]] gives.
  *
  * '''Derived from `spec/swagger.v1.json`.''' No capture of a team request or response exists anywhere in this
  * repository — `golden/MANIFEST.md` records `GET /orgs/{org}/teams` answering `401` anonymously, and
  * `golden/error/401-org-teams.json` is the only artefact of that probe.
  *
  * ==Where the two renderings differ==
  *
  * `CreateTeamOption` requires `name`; `EditTeamOption` requires it too, which is unusual for a `PATCH` and is why
  * [[com.worxbend.codeberg4s.organizations.EditTeam]] has no empty value. So both renderings always emit `name`.
  *
  * The two Booleans differ. On a create they are plain and always emitted, so a created team's settings are a property
  * of the request rather than of the Forgejo version answering it. On an edit they are optional and emitted only when
  * set, because `false` there has to mean "turn this off" rather than "leave the default".
  *
  * ==`units` and `units_map` are emitted only when non-empty==
  *
  * Sending `"units": []` would ask Forgejo to store an empty unit list, which is a different request from not
  * mentioning units — and on an edit it would strip a team of everything it reaches. An empty collection therefore
  * emits no key at all. Both are rendered in a fixed order — the vector as written, the map by key — so a rendered body
  * is reproducible and a test can compare it.
  */
private[codeberg4s] object TeamOptionDto:

  /** The wire key of the team's name, on both models. */
  val NameKey: String = "name"

  /** The wire key of the unit list. */
  val UnitsKey: String = "units"

  /** The wire key of the per-unit levels. */
  val UnitsMapKey: String = "units_map"

  /** The wire key of the team's overall access level. */
  val PermissionKey: String = "permission"

  /** The wire key deciding whether members may create repositories in the organisation. */
  val CanCreateOrgRepoKey: String = "can_create_org_repo"

  /** The wire key deciding whether the team reaches every repository of the organisation. */
  val IncludesAllRepositoriesKey: String = "includes_all_repositories"

  /** Renders `command` as the JSON body to `POST /orgs/{org}/teams`. See the object note for what is unconditional. */
  def renderCreate(command: CreateTeam): String =
    val fields = List(
      Some(NameKey                    -> JsonValue.Str(command.name.value)),
      Some(CanCreateOrgRepoKey        -> JsonValue.Bool(command.canCreateOrgRepo)),
      Some(IncludesAllRepositoriesKey -> JsonValue.Bool(command.includesAllRepositories)),
      command.description.map(text => "description" -> JsonValue.Str(text)),
      command.permission.map(level => PermissionKey -> JsonValue.Str(level.wireName)),
      units(command.units),
      unitPermissions(command.unitPermissions),
    ).flatten

    Json.render(JsonValue.Obj.from(fields))

  /** Renders `command` as the JSON body to `PATCH /teams/{id}`. `name` always travels; see the object note. */
  def renderEdit(command: EditTeam): String =
    val fields = List(
      Some(NameKey -> JsonValue.Str(command.name.value)),
      command.description.map(text             => "description" -> JsonValue.Str(text)),
      command.permission.map(level             => PermissionKey -> JsonValue.Str(level.wireName)),
      units(command.units),
      unitPermissions(command.unitPermissions),
      command.canCreateOrgRepo.map(flag        => CanCreateOrgRepoKey -> JsonValue.Bool(flag)),
      command.includesAllRepositories.map(flag => IncludesAllRepositoriesKey -> JsonValue.Bool(flag)),
    ).flatten

    Json.render(JsonValue.Obj.from(fields))

  /** The `units` array, in the order the caller wrote it; absent when there is nothing to say. */
  private def units(names: Vector[String]): Option[(String, JsonValue)] =
    Option.when(names.nonEmpty)(UnitsKey -> JsonValue.Arr.from(names.map(JsonValue.Str.apply)))

  /** The `units_map` object, in key order so the body is reproducible; absent when there is nothing to say. */
  private def unitPermissions(levels: Map[String, TeamPermission]): Option[(String, JsonValue)] =
    Option.when(levels.nonEmpty):
      UnitsMapKey -> JsonValue.Obj.from(
        levels.toVector.sortBy((unit, _) => unit).map((unit, level) => unit -> JsonValue.Str(level.wireName))
      )
