package com.worxbend.codeberg4s.repositories.actions

/** What `POST /repos/{owner}/{repo}/actions/variables/{variablename}` is told.
  *
  * The name is not part of the command: it is a path segment, and a [[VariableName]] the caller has already validated.
  * All that is left is the value.
  *
  * A separate type from [[UpdateVariable]] even though the payloads look alike, because they are not the same request:
  * creating cannot rename, and the field the two send differs in nothing but its meaning. Collapsing them into one type
  * would mean a `renamedTo` that is silently dropped on creation — the kind of quiet no-op this codebase does not ship.
  *
  * @param value
  *   the variable's content, sent verbatim. The empty string is a legitimate value, so nothing here rejects it; Forgejo
  *   normalises line endings to LF, and a caller who needs CRLF preserved must Base64-encode the value themselves
  */
final case class CreateVariable(value: String)

object CreateVariable:

  /** The command that sets a new variable to `value`.
    *
    * Total rather than validated: every string is a legal variable value, and the naming rules that can fail belong to
    * [[VariableName]] and to the instance.
    */
  def of(value: String): CreateVariable = CreateVariable(value)
