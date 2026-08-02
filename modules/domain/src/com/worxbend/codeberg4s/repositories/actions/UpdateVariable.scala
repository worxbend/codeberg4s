package com.worxbend.codeberg4s.repositories.actions

/** What `PUT /repos/{owner}/{repo}/actions/variables/{variablename}` is told.
  *
  * ==A rename changes what the request is==
  *
  * `UpdateVariableOption` carries an optional `name`, and setting it moves the variable to a different name. That turns
  * an otherwise idempotent `PUT` into one that is not: repeating it addresses a name that no longer exists.
  * [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi.updateVariable]] reads [[renamedTo]] to decide
  * whether the call may be retried at all, which is why the rename lives in the command rather than in a second method.
  *
  * @param value
  *   the variable's new content, sent verbatim and always sent — the spec marks it required, so a `PUT` that meant to
  *   change only the name still has to state the value it is keeping
  * @param renamedTo
  *   the name to move the variable to, absent to leave it where it is. Forgejo upper-cases the new name, so the
  *   variable may end up at a spelling the caller did not write
  */
final case class UpdateVariable(
    value: String,
    renamedTo: Option[VariableName],
):

  /** Moves the variable to `name` as part of this update; see the class note on what that costs.
    *
    * Named `movedTo` rather than `renamedTo` only because the field already owns that name — a `val` and a `def` cannot
    * share one in Scala.
    */
  def movedTo(name: VariableName): UpdateVariable = copy(renamedTo = Some(name))

object UpdateVariable:

  /** The command that sets an existing variable's content to `value` and leaves its name alone.
    *
    * Total rather than validated, for the reason [[CreateVariable.of]] gives.
    */
  def of(value: String): UpdateVariable = UpdateVariable(value = value, renamedTo = None)
