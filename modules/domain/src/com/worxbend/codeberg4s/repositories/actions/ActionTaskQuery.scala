package com.worxbend.codeberg4s.repositories.actions

/** The filters of `GET /repos/{owner}/{repo}/actions/tasks`.
  *
  * The task listing takes exactly one filter, and it is the same enumerated `status` the run listing takes. As with
  * [[ActionRunQuery]], only what the caller set is sent: [[ActionTaskQuery.Empty]] renders to no parameters.
  *
  * @param statuses
  *   which lifecycle states to include, sent as a repeated query parameter because the spec declares it as an array
  */
final case class ActionTaskQuery(statuses: Vector[ActionStatus]):

  /** Adds one lifecycle state to the filter. */
  def withStatus(status: ActionStatus): ActionTaskQuery = copy(statuses = statuses.appended(status))

object ActionTaskQuery:

  /** No filter — every task the caller may see. */
  val Empty: ActionTaskQuery = ActionTaskQuery(Vector.empty)
