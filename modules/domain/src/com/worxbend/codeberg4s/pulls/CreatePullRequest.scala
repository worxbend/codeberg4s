package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.{LabelId, MilestoneId}
import com.worxbend.codeberg4s.repositories.BranchName

import java.time.Instant

/** Everything `POST /repos/{owner}/{repo}/pulls` may be told, as one value.
  *
  * A command type rather than an eight-parameter method: the parameters are almost all optional and almost all
  * `Option[String]` or `Option[Long]`, and a call site passing five `None`s in a fixed order is a defect waiting to
  * happen. Built by naming what should be set:
  *
  * {{{
  * for command <- CreatePullRequest.of("fix the hook quoting", head, base)
  * yield command.withBody("backport of #13679").labelled(Vector(bug))
  * }}}
  *
  * '''Only what is set is sent.''' An unset field contributes no JSON key, so the instance applies its own default
  * rather than this library's idea of one.
  *
  * @param title
  *   the pull request title; validated by [[CreatePullRequest.of]]
  * @param head
  *   the branch to merge from, in the one spelling Forgejo accepts; see [[PullRequestHead]]
  * @param base
  *   the branch to merge into. A [[com.worxbend.codeberg4s.repositories.BranchName]] and not a [[PullRequestHead]],
  *   because a base is always a branch of the repository being posted to — Forgejo has no cross-repository base
  * @param assignees
  *   the logins to assign, empty for none. Forgejo silently ignores a login the caller may not assign
  * @param labels
  *   the labels to attach, by [[com.worxbend.codeberg4s.issues.LabelId]] — `CreatePullRequestOption.labels` is a list
  *   of ids, not of names
  * @param dueDate
  *   a deadline for the pull request
  */
final case class CreatePullRequest(
    title: String,
    head: PullRequestHead,
    base: BranchName,
    body: Option[String],
    assignees: Vector[String],
    labels: Vector[LabelId],
    milestone: Option[MilestoneId],
    dueDate: Option[Instant],
):

  /** Sets the pull request description, as Markdown source. */
  def withBody(text: String): CreatePullRequest = copy(body = Some(text))

  /** Assigns the pull request to `logins`; an empty vector leaves it unassigned. */
  def assignedTo(logins: Vector[String]): CreatePullRequest = copy(assignees = logins)

  /** Attaches `ids`; an empty vector leaves the pull request unlabelled. */
  def labelled(ids: Vector[LabelId]): CreatePullRequest = copy(labels = ids)

  /** Puts the pull request in the milestone `id`. */
  def inMilestone(id: MilestoneId): CreatePullRequest = copy(milestone = Some(id))

  /** Gives the pull request a deadline. */
  def dueBy(moment: Instant): CreatePullRequest = copy(dueDate = Some(moment))

object CreatePullRequest:

  /** Starts a command from the three things a pull request cannot be opened without.
    *
    * Trims the title and rejects a blank one. That check is here rather than left to the instance because a blank title
    * costs a round trip to learn about and comes back as a `422` whose `message` is a raw Go string (`docs/HAZARDS.md`
    * §4). The two branches need no checking: both arrive as types that have already validated themselves.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"title"` field
    */
  def of(title: String, head: PullRequestHead, base: BranchName): Either[ValidationError, CreatePullRequest] =
    val trimmed = title.trim
    if trimmed.isEmpty then Left(ValidationError("title", "must not be blank"))
    else
      Right(
        CreatePullRequest(
          title     = trimmed,
          head      = head,
          base      = base,
          body      = None,
          assignees = Vector.empty,
          labels    = Vector.empty,
          milestone = None,
          dueDate   = None,
        )
      )
