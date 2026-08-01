package com.worxbend.codeberg4s.organizations

/** A team inside an organisation — the unit Forgejo grants repository access to.
  *
  * Curated, not generated (ADR-0001), and owned by this group per `docs/LEDGER.md`. Every key of Forgejo's `Team`
  * definition is represented.
  *
  * ==No golden fixture proves this shape==
  *
  * `golden/MANIFEST.md` records that codeberg.org answers `401 token is required` to an anonymous
  * `GET /orgs/{org}/teams`, and the capture kept for it is the error body, `golden/error/401-org-teams.json`. Team
  * membership is not public information on that deployment, so every team endpoint in this group needs credentials and
  * this model is derived from the pinned spec rather than from a measured payload. `docs/HAZARDS.md` §1 is the reason
  * that matters: the spec declares nothing about optionality, so the decoder treats every field as absent-able, which
  * is the safe direction to be wrong in. Should a capture ever contradict this model, the capture wins.
  *
  * ==Two permission fields, and they mean different things==
  *
  * [[permission]] is the team's overall level. [[unitPermissions]] is per-unit — `repo.code` at `read` while
  * `repo.issues` is at `write` — and the spec's own example for `units_map` shows exactly that mixture. A caller
  * deciding whether a team may push must read the unit it cares about, not the overall level. [[units]] is the plain
  * list of unit names the team reaches, which is the same information without the levels.
  *
  * @param id
  *   the instance-wide identifier, and the only way to address the team; see [[TeamId]]
  * @param name
  *   the team's name, unique within its organisation but not across the instance
  * @param organization
  *   the organisation the team belongs to, as Forgejo embeds it. Absent on a payload that omitted it — notably a team
  *   read through an organisation-scoped listing, where the organisation is already known from the request
  * @param permission
  *   the team's overall access level, absent when Forgejo sent nothing or sent a level this library does not recognise;
  *   see [[TeamPermission.parse]]
  * @param units
  *   the unit names the team reaches, such as `repo.code` and `repo.issues`; empty when the payload carried none.
  *   Forgejo's unit vocabulary is instance configuration, so these stay strings rather than becoming an enum that a new
  *   Forgejo release would make incomplete
  * @param unitPermissions
  *   the per-unit levels, keyed by the same unit names. An entry whose level Forgejo spells in a way
  *   [[TeamPermission.parse]] does not recognise is dropped rather than failing the whole team, so this map may be
  *   smaller than [[units]]
  * @param canCreateOrgRepo
  *   whether members may create repositories in the organisation; `false` when the instance did not say
  * @param includesAllRepositories
  *   whether the team reaches every repository of the organisation, present and future, rather than an explicit list
  */
final case class Team(
    id: TeamId,
    name: String,
    description: Option[String],
    organization: Option[Organization],
    permission: Option[TeamPermission],
    units: Vector[String],
    unitPermissions: Map[String, TeamPermission],
    canCreateOrgRepo: Boolean,
    includesAllRepositories: Boolean,
)
