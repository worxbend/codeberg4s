package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

import java.time.Instant

/** What `POST /repos/{owner}/{repo}/milestones` is told — Forgejo's `CreateMilestoneOption`.
  *
  * '''Derived from `spec/swagger.v1.json`''', which declares four properties and — surprisingly — marks '''none''' of
  * them required, `title` included. This library requires a title anyway: a milestone with no name cannot be shown, and
  * [[CreateMilestone.of]] refuses one rather than letting the instance answer with a `422` whose message is a raw Go
  * string (`docs/HAZARDS.md` §4). No golden capture of this request exists; the response is a `Milestone`, which
  * `golden/issue/milestones-list.json` covers.
  *
  * '''Only what is set is sent''', so an instance default is never overwritten by this library's idea of one.
  *
  * @param title
  *   the milestone name. A plain `String` and deliberately not [[MilestoneTitle]] — that type exists to survive a
  *   '''comma-separated filter''' and refuses a comma, which is a restriction the create endpoint does not have and
  *   which would stop a caller creating the perfectly legal milestone `v1.0, hardening`
  * @param description
  *   free text shown under the title
  * @param dueOn
  *   the milestone deadline
  * @param state
  *   whether to create the milestone already closed; absent leaves the instance's default of open. See
  *   [[IssueStateChange]], which is the same two-valued transition and is shared rather than duplicated
  */
final case class CreateMilestone(
    title: String,
    description: Option[String],
    dueOn: Option[Instant],
    state: Option[IssueStateChange],
):

  /** Sets the description. */
  def describedAs(text: String): CreateMilestone = copy(description = Some(text))

  /** Gives the milestone a deadline. */
  def dueBy(moment: Instant): CreateMilestone = copy(dueOn = Some(moment))

  /** Creates the milestone already closed — for importing history, not for ordinary use. */
  def createdClosed: CreateMilestone = copy(state = Some(IssueStateChange.Close))

object CreateMilestone:

  /** Starts a command from the title.
    *
    * Trims the title and rejects a blank one; see the type note for why that is stricter than the spec.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"title"` field
    */
  def of(title: String): Either[ValidationError, CreateMilestone] =
    val trimmed = title.trim
    if trimmed.isEmpty then Left(ValidationError("title", "must not be blank"))
    else Right(CreateMilestone(title = trimmed, description = None, dueOn = None, state = None))
