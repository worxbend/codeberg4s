package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Timestamps, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.wire.{LabelDto, MilestoneDto}
import com.worxbend.codeberg4s.pulls.{PullRequest, PullRequestNumber, PullRequestState}
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `PullRequest` model, field for field.
  *
  * Every key observed across the five pull requests in the golden fixtures — `golden/pull/single-open.json`,
  * `single-merged.json`, `list-all.json` and `list-closed.json` — is represented, including the two the domain does not
  * model (`pin_order`, `flow`) and the two it folds away (`assignee`, `requested_reviewers_teams`). Keeping them costs
  * nothing and means the DTO can be diffed against a captured payload without a mental exception list. See
  * [[com.worxbend.codeberg4s.pulls.PullRequest]] for why each is dropped in conversion.
  *
  * ==Nulls==
  *
  * Measured on these fixtures rather than assumed. `assignee`, `assignees`, `milestone`, `due_date` and `closed_at` are
  * JSON `null` on the open pull request; `merged_at`, `merge_commit_sha` and `merged_by` are `null` on every pull
  * request that is not merged; and `assignees` is declared `type: array` while arriving as `null`, which a derived
  * codec aborts on. Hence the hand-written reader over [[com.worxbend.codeberg4s.codec.JsonFields]], where a null array
  * is an empty `Vector`.
  *
  * ==The one place the endpoints disagree==
  *
  * `merged_by` is a populated `User` on `golden/pull/single-merged.json` and `null` on the '''same''' pull request in
  * `golden/pull/list-closed.json`, which carries `merged: true`, a `merged_at` and a `merge_commit_sha` beside it. The
  * listing endpoint does not resolve the merging account. That measurement is why
  * [[com.worxbend.codeberg4s.pulls.PullRequestState.Merged]] keeps its three fields optional.
  */
final case class PullRequestDto(
    id: Option[Long],
    number: Option[Long],
    title: Option[String],
    body: Option[String],
    state: Option[String],
    draft: Option[Boolean],
    user: Option[UserDto],
    assignee: Option[UserDto],
    assignees: Vector[UserDto],
    requestedReviewers: Vector[UserDto],
    labels: Vector[LabelDto],
    milestone: Option[MilestoneDto],
    base: Option[PullRequestBranchDto],
    head: Option[PullRequestBranchDto],
    mergeBase: Option[String],
    mergeable: Option[Boolean],
    merged: Option[Boolean],
    mergedAt: Option[String],
    mergeCommitSha: Option[String],
    mergedBy: Option[UserDto],
    allowMaintainerEdit: Option[Boolean],
    isLocked: Option[Boolean],
    comments: Option[Long],
    reviewComments: Option[Long],
    additions: Option[Long],
    deletions: Option[Long],
    changedFiles: Option[Long],
    htmlUrl: Option[String],
    url: Option[String],
    diffUrl: Option[String],
    patchUrl: Option[String],
    dueDate: Option[String],
    closedAt: Option[String],
    createdAt: Option[String],
    updatedAt: Option[String],
    pinOrder: Option[Long],
    flow: Option[Long],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Four things are required, because without them there is no pull request to speak of: `id`, `number`, `title` and
    * `state`. `number` goes through [[com.worxbend.codeberg4s.pulls.PullRequestNumber.from]] because it is what every
    * other endpoint in this group takes as its argument, and `state` is required because
    * [[com.worxbend.codeberg4s.pulls.PullRequestState]] has no case for "unknown", by design.
    *
    * '''`state`, `merged`, `merged_at`, `merge_commit_sha`, `merged_by` and `closed_at` all collapse into one value''',
    * and none of them survives as a field. That fold is the whole reason the domain model cannot express "open and
    * merged" or "merged without a merge"; [[com.worxbend.codeberg4s.pulls.PullRequestState.from]] performs it, and
    * checks the merge evidence before `state` because a merged pull request reports `state: "closed"`.
    *
    * Everything else is optional or defaulted: the counts absent become `0`, the flags become `false`, and the three
    * arrays become empty. A failure inside `user`, `assignees`, `requested_reviewers`, `labels`, `milestone`, `base` or
    * `head` is reported at that nested path — `$.head.repo.owner.login`, not `$` — while `merge_base`,
    * `merge_commit_sha` and each branch's `sha` fail at their own path if they are present and not hexadecimal.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, PullRequest] =
    for
      identifier <- Wire.required(at, "id", id)
      index      <- Wire.validated(at, "number", number)(PullRequestNumber.from)
      headline   <- Wire.required(at, "title", title)
      author     <- Wire.nested(at, "user", user)(_.toDomainAt(_))
      merger     <- Wire.nested(at, "merged_by", mergedBy)(_.toDomainAt(_))
      mergeSha   <- Wire.optional(at, "merge_commit_sha", mergeCommitSha)(CommitSha.from)
      ancestor   <- Wire.optional(at, "merge_base", mergeBase)(CommitSha.from)
      lifecycle  <- Wire.validated(at, "state", state)(value =>
                      PullRequestState.from(
                        state       = value,
                        merged      = merged.getOrElse(false),
                        mergedAt    = Timestamps.parseOptional(mergedAt),
                        mergedBy    = merger,
                        mergeCommit = mergeSha,
                        closedAt    = Timestamps.parseOptional(closedAt),
                      )
                    )
      assigned   <- usersAt(at, "assignees", assignees)
      reviewers  <- usersAt(at, "requested_reviewers", requestedReviewers)
      attached   <- LabelDto.toDomainAll(at.field("labels"), labels)
      target     <- Wire.nested(at, "milestone", milestone)(_.toDomainAt(_))
      into       <- Wire.nested(at, "base", base)(_.toDomainAt(_))
      from       <- Wire.nested(at, "head", head)(_.toDomainAt(_))
    yield PullRequest(
      id                   = identifier,
      number               = index,
      title                = headline,
      body                 = body,
      state                = lifecycle,
      isDraft              = draft.getOrElse(false),
      author               = author,
      assignees            = assigned,
      requestedReviewers   = reviewers,
      labels               = attached,
      milestone            = target,
      base                 = into,
      head                 = from,
      mergeBase            = ancestor,
      isMergeable          = mergeable,
      allowsMaintainerEdit = allowMaintainerEdit.getOrElse(false),
      isLocked             = isLocked.getOrElse(false),
      commentCount         = comments.getOrElse(0L),
      reviewCommentCount   = reviewComments.getOrElse(0L),
      additions            = additions.getOrElse(0L),
      deletions            = deletions.getOrElse(0L),
      changedFileCount     = changedFiles.getOrElse(0L),
      htmlUrl              = htmlUrl,
      url                  = url,
      diffUrl              = diffUrl,
      patchUrl             = patchUrl,
      dueDate              = Timestamps.parseOptional(dueDate),
      createdAt            = Timestamps.parseOptional(createdAt),
      updatedAt            = Timestamps.parseOptional(updatedAt),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, PullRequest] =
    toDomainAt(JsonPath.Root)

  private def usersAt(at: JsonPath, field: String, dtos: Vector[UserDto]): Either[DecodeFailure, Vector[User]] =
    ArrayElements.convert(at.field(field), dtos)((dto, path) => dto.toDomainAt(path))

object PullRequestDto:

  /** Reads a `PullRequest` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[PullRequestDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing the `fromFields` of every model it embeds so that no field spelling is
    * written twice.
    */
  def fromFields(fields: JsonFields): PullRequestDto =
    PullRequestDto(
      id                  = fields.number("id"),
      number              = fields.number("number"),
      title               = fields.text("title"),
      body                = fields.text("body"),
      state               = fields.text("state"),
      draft               = fields.boolean("draft"),
      user                = fields.nested("user").map(UserDto.fromFields),
      assignee            = fields.nested("assignee").map(UserDto.fromFields),
      assignees           = fields.nestedAll("assignees").map(UserDto.fromFields),
      requestedReviewers  = fields.nestedAll("requested_reviewers").map(UserDto.fromFields),
      labels              = fields.nestedAll("labels").map(LabelDto.fromFields),
      milestone           = fields.nested("milestone").map(MilestoneDto.fromFields),
      base                = fields.nested("base").map(PullRequestBranchDto.fromFields),
      head                = fields.nested("head").map(PullRequestBranchDto.fromFields),
      mergeBase           = fields.text("merge_base"),
      mergeable           = fields.boolean("mergeable"),
      merged              = fields.boolean("merged"),
      mergedAt            = fields.text("merged_at"),
      mergeCommitSha      = fields.text("merge_commit_sha"),
      mergedBy            = fields.nested("merged_by").map(UserDto.fromFields),
      allowMaintainerEdit = fields.boolean("allow_maintainer_edit"),
      isLocked            = fields.boolean("is_locked"),
      comments            = fields.number("comments"),
      reviewComments      = fields.number("review_comments"),
      additions           = fields.number("additions"),
      deletions           = fields.number("deletions"),
      changedFiles        = fields.number("changed_files"),
      htmlUrl             = fields.text("html_url"),
      url                 = fields.text("url"),
      diffUrl             = fields.text("diff_url"),
      patchUrl            = fields.text("patch_url"),
      dueDate             = fields.text("due_date"),
      closedAt            = fields.text("closed_at"),
      createdAt           = fields.text("created_at"),
      updatedAt           = fields.text("updated_at"),
      pinOrder            = fields.number("pin_order"),
      flow                = fields.number("flow"),
    )

  /** Converts a decoded array of pull requests, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[PullRequestDto]): Either[DecodeFailure, Vector[PullRequest]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
