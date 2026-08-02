package com.worxbend.codeberg4s.organizations

/** What `POST /orgs/{org}/teams` is told — Forgejo's `CreateTeamOption`.
  *
  * {{{
  * TeamName.from("reviewers").map: name =>
  *   CreateTeam
  *     .named(name)
  *     .describedAs("may approve, may not push")
  *     .permitted(TeamPermission.Read)
  *     .reaching("repo.code", "repo.pulls")
  * }}}
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured request.''' `golden/MANIFEST.md` records
  * `GET /orgs/{org}/teams` answering `401` anonymously, so no team payload of any kind was captured — see [[Team]]. The
  * seven properties below are the seven the spec declares, and it marks `name` required.
  *
  * ==Two ways to say what a team reaches, and they are not equivalent==
  *
  * [[units]] lists unit names at the team's overall [[permission]]. [[unitPermissions]] states a level '''per''' unit
  * and is what Forgejo's own example of `units_map` shows: `repo.code` at `read` while `repo.issues` is at `write`.
  * Sending both is legal — the spec declares both properties on one model — and Forgejo resolves the overlap itself.
  * This type does not merge, reconcile or de-duplicate them, because guessing at a resolution the API documents nowhere
  * would put a permission decision in a client library.
  *
  * ==Unit names stay strings==
  *
  * `repo.code`, `repo.issues`, `repo.actions` and the rest are instance configuration, not a closed set: an instance
  * with packages disabled has fewer, and a Forgejo release that adds a feature adds one. An enum here would be
  * incomplete on the day it shipped, which is why [[Team.units]] carries strings too.
  *
  * ==`permission` accepts three of the five levels==
  *
  * The spec's `enum` for this property is `read`, `write`, `admin` — [[TeamPermission.NoAccess]] and
  * [[TeamPermission.Owner]] are not offered, because a team with no access is pointless and ownership is not something
  * a team is created with. The type is still [[TeamPermission]] rather than a second three-case enum: forking the
  * vocabulary would mean two types for one Forgejo concept, which `docs/LEDGER.md` calls a defect. Sending one of the
  * other two earns a `422` from the instance.
  *
  * @param name
  *   what the team will be called, unique within the organisation
  * @param description
  *   the team's description
  * @param permission
  *   the team's overall access level. Absent leaves Forgejo's default, which is `read`
  * @param units
  *   the unit names the team reaches at [[permission]]; empty sends no `units` key at all
  * @param unitPermissions
  *   per-unit levels, keyed by unit name; empty sends no `units_map` key at all
  * @param canCreateOrgRepo
  *   whether members may create repositories in the organisation
  * @param includesAllRepositories
  *   whether the team reaches every repository of the organisation, present and future, rather than an explicit list.
  *   Turning this on is a broad grant: repositories created later are included without anybody deciding so again
  */
final case class CreateTeam(
    name: TeamName,
    description: Option[String],
    permission: Option[TeamPermission],
    units: Vector[String],
    unitPermissions: Map[String, TeamPermission],
    canCreateOrgRepo: Boolean,
    includesAllRepositories: Boolean,
):

  /** Describes the team. */
  def describedAs(text: String): CreateTeam = copy(description = Some(text))

  /** Sets the team's overall access level; see the type note on which three the instance accepts. */
  def permitted(level: TeamPermission): CreateTeam = copy(permission = Some(level))

  /** Names the units the team reaches at its overall level, replacing whatever was named before. */
  def reaching(names: String*): CreateTeam = copy(units = names.toVector)

  /** Sets one unit's level, which is a different statement from naming it in [[reaching]]; see the type note. */
  def reachingAt(unit: String, level: TeamPermission): CreateTeam =
    copy(unitPermissions = unitPermissions.updated(unit, level))

  /** Lets members create repositories in the organisation. */
  def creatingRepositories: CreateTeam = copy(canCreateOrgRepo = true)

  /** Gives the team every repository the organisation has or will have; see [[includesAllRepositories]]. */
  def includingAllRepositories: CreateTeam = copy(includesAllRepositories = true)

object CreateTeam:

  /** Starts a command from the one thing Forgejo requires.
    *
    * Cannot fail: the argument is an already-validated [[TeamName]]. The two flags start `false`, which is what an
    * unmentioned Boolean means on a create, and both collections start empty so that no `units` or `units_map` key is
    * sent unless the caller says something about one.
    */
  def named(name: TeamName): CreateTeam =
    CreateTeam(
      name                    = name,
      description             = None,
      permission              = None,
      units                   = Vector.empty,
      unitPermissions         = Map.empty,
      canCreateOrgRepo        = false,
      includesAllRepositories = false,
    )
