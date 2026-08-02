package com.worxbend.codeberg4s.organizations

/** What `PATCH /teams/{id}` is told — Forgejo's `EditTeamOption`.
  *
  * {{{
  * TeamName.from("reviewers").map(name => EditTeam.named(name).permitted(TeamPermission.Write))
  * }}}
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured request''' — see [[CreateTeam]] for why no team payload
  * was captured at all.
  *
  * ==The name is mandatory, and that is what makes every edit a rename==
  *
  * `EditTeamOption` is one of the few request models in the pinned spec with a `required` list, and it names `name`.
  * There is no way to spell "change the description and leave the name alone": the name travels on every request, so
  * every edit asserts what the team is called. That is why [[EditTeam.named]] takes a [[TeamName]] and there is no
  * `Empty` — a command that changes nothing cannot be expressed, and pretending otherwise would produce a request
  * Forgejo rejects.
  *
  * It is also why [[OrganizationTeamApi.edit]] is never retried; the reasoning is stated there.
  *
  * ==Everything else is "leave it alone"==
  *
  * The six remaining properties are optional and are emitted only when set, which is why the two flags are
  * `Option[Boolean]`: on an edit, `false` has to be able to mean "turn this off".
  *
  * @param name
  *   what the team is called after this call. Required by the model, so it is required here
  * @param description
  *   the description to store
  * @param permission
  *   the overall access level to store; see [[CreateTeam.permission]] for which three the instance accepts
  * @param units
  *   the unit names to store, replacing the team's list. Empty sends no `units` key, which leaves the list alone —
  *   there is no way to spell "reach nothing" through this property, and [[TeamPermission.NoAccess]] entries in
  *   [[unitPermissions]] are how that is said instead
  * @param unitPermissions
  *   the per-unit levels to store; empty sends no `units_map` key
  * @param canCreateOrgRepo
  *   whether members may create repositories in the organisation
  * @param includesAllRepositories
  *   whether the team reaches every repository of the organisation, present and future. Turning this on retroactively
  *   grants the team every existing repository in one call
  */
final case class EditTeam(
    name: TeamName,
    description: Option[String],
    permission: Option[TeamPermission],
    units: Vector[String],
    unitPermissions: Map[String, TeamPermission],
    canCreateOrgRepo: Option[Boolean],
    includesAllRepositories: Option[Boolean],
):

  /** Replaces the description. */
  def describedAs(text: String): EditTeam = copy(description = Some(text))

  /** Replaces the team's overall access level. */
  def permitted(level: TeamPermission): EditTeam = copy(permission = Some(level))

  /** Replaces the units the team reaches at its overall level. */
  def reaching(names: String*): EditTeam = copy(units = names.toVector)

  /** Sets one unit's level; see [[CreateTeam]] for why that is a different statement from [[reaching]]. */
  def reachingAt(unit: String, level: TeamPermission): EditTeam =
    copy(unitPermissions = unitPermissions.updated(unit, level))

  /** Turns "members may create repositories" on or off. */
  def creatingRepositories(allowed: Boolean): EditTeam = copy(canCreateOrgRepo = Some(allowed))

  /** Turns "the team reaches every repository" on or off; see [[includesAllRepositories]]. */
  def includingAllRepositories(included: Boolean): EditTeam = copy(includesAllRepositories = Some(included))

object EditTeam:

  /** Starts an edit from the name the team will have afterwards.
    *
    * There is deliberately no `Empty`: the model requires `name`, so the smallest legal edit is one that restates it. A
    * caller who wants to change only the description reads the team with [[OrganizationApi.getTeam]], converts
    * [[Team.name]] through [[TeamName.from]], and passes it here — which is verbose exactly because it is a rename
    * every time, and hiding that would be worse.
    */
  def named(name: TeamName): EditTeam =
    EditTeam(
      name                    = name,
      description             = None,
      permission              = None,
      units                   = Vector.empty,
      unitPermissions         = Map.empty,
      canCreateOrgRepo        = None,
      includesAllRepositories = None,
    )
