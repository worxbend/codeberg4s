package com.worxbend.codeberg4s.organizations

/** What one account may do in one organisation — Forgejo's `OrganizationPermissions`, as
  * `GET /users/{username}/orgs/{org}/permissions` reports it.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' The route needs a token and the golden
  * harvest was anonymous. All five properties the spec declares are here, and — per `docs/HAZARDS.md` §1 — none of them
  * is declared required, so an absent flag is read as `false`: "the instance did not say this account may" is treated
  * as "it may not", which is the safe direction for a permission.
  *
  * ==This is the effective answer, not a role==
  *
  * Forgejo computes these five booleans from everything that grants access — organisation ownership, team membership,
  * site administration — and reports the result. There is no field saying '''why''' an account can write, and asking
  * which team granted it is a different question, answered by walking [[OrganizationApi.teams]] and
  * [[OrganizationTeamApi.member]].
  *
  * The flags are not nested: [[isOwner]] does not imply [[canWrite]] as a matter of this type, even though Forgejo
  * makes them agree in practice. Read the flag that answers the question being asked rather than deriving one from
  * another.
  *
  * @param isOwner
  *   whether the account owns the organisation — the highest level, and the one that can delete it
  * @param isAdmin
  *   whether the account administers the organisation. A site administrator reads `true` here for organisations they
  *   are not a member of
  * @param canWrite
  *   whether the account may write to the organisation's repositories
  * @param canRead
  *   whether the account may read them
  * @param canCreateRepository
  *   whether the account may create a repository under the organisation
  */
final case class OrganizationPermissions(
    isOwner: Boolean,
    isAdmin: Boolean,
    canWrite: Boolean,
    canRead: Boolean,
    canCreateRepository: Boolean,
)
