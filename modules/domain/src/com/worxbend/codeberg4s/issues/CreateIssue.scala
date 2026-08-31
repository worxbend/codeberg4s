package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

import java.time.Instant

/** Everything `POST /repos/{owner}/{repo}/issues` may be told, as one value.
  *
  * A command type rather than a nine-parameter method: the parameters are almost all optional, they are almost all
  * `Option[String]` or `Option[Long]`, and a call site passing seven `None`s in a fixed order is a defect waiting to
  * happen. Built by naming what should be set:
  *
  * {{{
  * for command <- CreateIssue.of("the build is red")
  * yield command.withBody("since 1bdb1938").labelled(Vector(bug)).dueBy(friday)
  * }}}
  *
  * '''Only what is set is sent.''' An unset field contributes no JSON key, so the instance applies its own default
  * rather than this library's idea of one.
  *
  * @param title
  *   the issue title; the one thing Forgejo requires, validated by [[CreateIssue.of]]
  * @param assignees
  *   the logins to assign, empty for none. Forgejo silently ignores a login the caller may not assign
  * @param labels
  *   the labels to attach, by [[LabelId]] — `CreateIssueOption.labels` is a list of ids, not of names
  * @param dueDate
  *   a deadline for the issue
  * @param ref
  *   the Git reference the issue is filed against
  * @param closed
  *   whether the issue is created already closed. `false` for the ordinary case, and it is a plain `Boolean` rather
  *   than an enum because it is a property of the issue being created, not an argument a reader has to decode at a call
  *   site — see [[CreateIssue.createdClosed]]
  */
final case class CreateIssue private[codeberg4s] (
    title: String,
    body: Option[String],
    assignees: Vector[String],
    labels: Vector[LabelId],
    milestone: Option[MilestoneId],
    dueDate: Option[Instant],
    ref: Option[String],
    closed: Boolean,
):

  /** Sets the issue description, as Markdown source. */
  def withBody(text: String): CreateIssue = copy(body = Some(text))

  /** Assigns the issue to `logins`; an empty vector leaves it unassigned. */
  def assignedTo(logins: Vector[String]): CreateIssue = copy(assignees = logins)

  /** Attaches `ids`; an empty vector leaves the issue unlabelled. */
  def labelled(ids: Vector[LabelId]): CreateIssue = copy(labels = ids)

  /** Puts the issue in the milestone `id`. */
  def inMilestone(id: MilestoneId): CreateIssue = copy(milestone = Some(id))

  /** Gives the issue a deadline. */
  def dueBy(moment: Instant): CreateIssue = copy(dueDate = Some(moment))

  /** Files the issue against a Git reference. */
  def onRef(reference: String): CreateIssue = copy(ref = Some(reference))

  /** Creates the issue already closed — for importing history, not for ordinary use. */
  def createdClosed: CreateIssue = copy(closed = true)

object CreateIssue:

  /** Starts a command from the only thing Forgejo insists on.
    *
    * Trims the title and rejects a blank one. That check is here rather than left to the instance because a blank title
    * costs a round trip to learn about, comes back as a `422` whose `message` is a raw Go string (`docs/HAZARDS.md`
    * §4), and is the single most likely thing to be wrong about a programmatically built issue.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"title"` field
    */
  def of(title: String): Either[ValidationError, CreateIssue] =
    val trimmed = title.trim
    if trimmed.isEmpty then Left(ValidationError("title", "must not be blank"))
    else
      Right(
        CreateIssue(
          title     = trimmed,
          body      = None,
          assignees = Vector.empty,
          labels    = Vector.empty,
          milestone = None,
          dueDate   = None,
          ref       = None,
          closed    = false,
        )
      )
