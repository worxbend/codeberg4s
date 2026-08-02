package com.worxbend.codeberg4s.repositories.actions

/** An Actions variable: named configuration a workflow reads, and which the API '''does''' hand back.
  *
  * The counterpart to [[ActionSecret]], and the contrast is the point. A variable's value is returned by
  * `GET /repos/{owner}/{repo}/actions/variables/{variablename}` and by the listing, because a variable is
  * configuration, not a credential. That is why [[value]] is a plain `String` here while [[ActionSecret]] has no value
  * field at all — and why putting a credential in a variable makes it readable by anyone who can read the repository's
  * Actions configuration.
  *
  * '''Derived from `spec/swagger.v1.json`'s `ActionVariable` definition, not from a captured response'''; see
  * [[ActionArtifact]] for why.
  *
  * @param name
  *   what addresses the variable. Forgejo upper-cases names, as it does for secrets
  * @param value
  *   the variable's content, verbatim. The empty string is a legitimate value and is preserved
  * @param ownerId
  *   the account the variable belongs to; `0` on the wire, and absent here, for a repository-level variable
  * @param repoId
  *   the repository the variable belongs to; `0` on the wire, and absent here, for an owner-level variable
  */
final case class ActionVariable(
    name: VariableName,
    value: String,
    ownerId: Option[Long],
    repoId: Option[Long],
)
