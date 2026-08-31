package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.issues.IssueNumber
import com.worxbend.codeberg4s.issues.LifecycleState
import com.worxbend.codeberg4s.issues.Milestone
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `Issue` model, field for field.
  *
  * Every key observed across the thirteen issues in the golden fixtures — `golden/issue/single.json`, `list-open.json`,
  * `list-closed.json`, `list-labelled.json` and `search.json` — is represented, except two:
  *
  *   - `assets` is an array of Forgejo's `Attachment` model, which no wave owns. It is `[]` on twelve of the thirteen
  *     and carries one attachment on the thirteenth, so the shape is known but the model has no home yet; it belongs to
  *     whichever wave first needs to read an attachment.
  *   - `pull_request` is Forgejo's `PullRequestMeta`, which `docs/LEDGER.md` puts in the pull-request wave. Only its
  *     '''presence''' is read, into [[isPullRequest]], because a listing of issues contains pull requests
  *     (`list-labelled.json` is entirely pull requests) and a caller has to be able to tell. The payload itself —
  *     draft, merged, merged_at — is not decoded here.
  *
  * [[com.worxbend.codeberg4s.codec.JsonFields]] ignores keys the DTO does not name, so a payload carrying either still
  * decodes.
  *
  * ==Nulls==
  *
  * `docs/HAZARDS.md` §1 was measured against this exact model: `assignee`, `assignees`, `closed_at`, `due_date` and
  * `milestone` arrive as JSON `null` on the first issue of the first page. `assignees` is declared `type: array` and
  * still arrives as `null`, which a derived codec aborts on — hence the hand-written reader over `JsonFields`, where a
  * null array is an empty `Vector`.
  *
  * `assignee`, the singular field, is kept here and dropped in conversion: it duplicates the first element of
  * `assignees`, and a domain model with both would invite a caller to read one and miss the rest.
  */
final case class IssueDto(
    id: Option[Long],
    number: Option[Long],
    title: Option[String],
    body: Option[String],
    state: Option[String],
    user: Option[UserDto],
    originalAuthor: Option[String],
    originalAuthorId: Option[Long],
    assignee: Option[UserDto],
    assignees: Vector[UserDto],
    labels: Vector[LabelDto],
    milestone: Option[MilestoneDto],
    repository: Option[RepositoryMetaDto],
    comments: Option[Long],
    isLocked: Option[Boolean],
    isPullRequest: Boolean,
    pinOrder: Option[Long],
    ref: Option[String],
    htmlUrl: Option[String],
    url: Option[String],
    dueDate: Option[String],
    closedAt: Option[String],
    createdAt: Option[String],
    updatedAt: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Four things are required, because without them there is no issue to speak of: `id`, `number`, `title` and `state`.
    * `number` goes through [[com.worxbend.codeberg4s.issues.IssueNumber.from]] because it is what every other issue
    * endpoint takes as its argument — an issue that cannot address itself would be useless — and `state` is required
    * because [[com.worxbend.codeberg4s.issues.LifecycleState]] has no case for "unknown", by design.
    *
    * `closed_at` is consumed by `state` and does not survive as a field; see
    * [[com.worxbend.codeberg4s.issues.LifecycleState]].
    *
    * Everything else is optional or defaulted. `comments` absent becomes `0`, the flags become `false`, and the two
    * arrays become empty. A failure inside `user`, `assignees`, `labels` or `milestone` is reported at that nested path
    * — `$.labels[1].id`, not `$` — while a `repository` that cannot be turned into a slug is silently dropped, for the
    * reason [[RepositoryMetaDto.toSlug]] gives.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Issue] =
    for
      identifier <- Wire.required(at, "id", id)
      index      <- Wire.validated(at, "number", number)(IssueNumber.from)
      headline   <- Wire.required(at, "title", title)
      lifecycle  <- Wire.validated(at, "state", state)(value =>
                      LifecycleState.from(value, Timestamps.parseOptional(closedAt))
                    )
      author     <- authorAt(at)
      assigned   <- assigneesAt(at)
      attached   <- LabelDto.toDomainAll(at.field("labels"), labels)
      target     <- milestoneAt(at)
    yield Issue(
      id             = identifier,
      number         = index,
      title          = headline,
      body           = body,
      state          = lifecycle,
      author         = author,
      originalAuthor = originalAuthor,
      assignees      = assigned,
      labels         = attached,
      milestone      = target,
      repository     = repository.flatMap(_.toSlug),
      commentCount   = comments.getOrElse(0L),
      isLocked       = isLocked.getOrElse(false),
      isPullRequest  = isPullRequest,
      htmlUrl        = htmlUrl,
      url            = url,
      ref            = ref,
      dueDate        = Timestamps.parseOptional(dueDate),
      createdAt      = Timestamps.parseOptional(createdAt),
      updatedAt      = Timestamps.parseOptional(updatedAt),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, Issue] =
    toDomainAt(JsonPath.Root)

  private def authorAt(at: JsonPath): Either[DecodeFailure, Option[User]] =
    user.fold(Right(None))(dto => dto.toDomainAt(at.field("user")).map(Some.apply))

  private def assigneesAt(at: JsonPath): Either[DecodeFailure, Vector[User]] =
    ArrayElements.convert(at.field("assignees"), assignees)((dto, path) => dto.toDomainAt(path))

  private def milestoneAt(at: JsonPath): Either[DecodeFailure, Option[Milestone]] =
    milestone.fold(Right(None))(dto => dto.toDomainAt(at.field("milestone")).map(Some.apply))

object IssueDto:

  /** The key whose mere presence marks an issue as a pull request; see the class note. */
  private val PullRequestKey: String = "pull_request"

  /** Reads an `Issue` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[IssueDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing the `fromFields` of every model it embeds so that no field spelling is
    * written twice.
    */
  def fromFields(fields: JsonFields): IssueDto =
    IssueDto(
      id               = fields.number("id"),
      number           = fields.number("number"),
      title            = fields.text("title"),
      body             = fields.text("body"),
      state            = fields.text("state"),
      user             = fields.nested("user").map(UserDto.fromFields),
      originalAuthor   = fields.text("original_author"),
      originalAuthorId = fields.number("original_author_id"),
      assignee         = fields.nested("assignee").map(UserDto.fromFields),
      assignees        = fields.nestedAll("assignees").map(UserDto.fromFields),
      labels           = fields.nestedAll("labels").map(LabelDto.fromFields),
      milestone        = fields.nested("milestone").map(MilestoneDto.fromFields),
      repository       = fields.nested("repository").map(RepositoryMetaDto.fromFields),
      comments         = fields.number("comments"),
      isLocked         = fields.boolean("is_locked"),
      isPullRequest    = fields.nested(PullRequestKey).isDefined,
      pinOrder         = fields.number("pin_order"),
      ref              = fields.text("ref"),
      htmlUrl          = fields.text("html_url"),
      url              = fields.text("url"),
      dueDate          = fields.text("due_date"),
      closedAt         = fields.text("closed_at"),
      createdAt        = fields.text("created_at"),
      updatedAt        = fields.text("updated_at"),
    )

  /** Converts a decoded array of issues, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[IssueDto]): Either[DecodeFailure, Vector[Issue]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
