package com.worxbend.codeberg4s.notifications.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.notifications.NotificationSubject
import com.worxbend.codeberg4s.notifications.NotificationThread
import com.worxbend.codeberg4s.notifications.NotificationThreadId
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.wire.Elements
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto

/** Forgejo's `NotificationThread` model, field for field.
  *
  * ==Provenance: the spec, and nothing else==
  *
  * Derived from `definitions.NotificationThread` in `spec/swagger.v1.json` and '''unverified against a live
  * instance'''. `GET /notifications` needs a token, the golden harvest was anonymous, and
  * `golden/notification/list-synthetic.json` is hand-authored — `golden/MANIFEST.md` marks it `synthetic` and warns
  * that it is shape-only evidence, never evidence of optionality. Only the embedded `repository` object in it is real,
  * being a copy of `repository/repo-single-community.json`; that part at least exercises
  * [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]] against a genuine payload.
  *
  * Every field is therefore `Option`, per rule 2 of [[com.worxbend.codeberg4s.codec.WireConventions]], and here that is
  * a decision taken in the safe direction rather than a measurement. `docs/HAZARDS.md` §1 is the justification: no
  * response definition in the pinned spec declares anything `required`, and live payloads routinely send `null` where
  * the spec declares an object or an array.
  *
  * ==Shared helpers, not copies==
  *
  * `repository` recurses into the repository group's own DTO rather than a reduced copy — `docs/LEDGER.md` gives
  * `Repository` to that group and calls a fork a review-blocking defect — and [[toDomainAll]] reuses that group's
  * [[com.worxbend.codeberg4s.repositories.wire.Elements]] for the same reason. The dependency exists either way,
  * because the embedded repository already brings it.
  */
final case class NotificationThreadDto(
    id: Option[Long],
    pinned: Option[Boolean],
    unread: Option[Boolean],
    updatedAt: Option[String],
    url: Option[String],
    subject: Option[NotificationSubjectDto],
    repository: Option[RepositoryDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * One thing is required: `id`, which goes through
    * [[com.worxbend.codeberg4s.notifications.NotificationThreadId.from]]. Every other operation in this group addresses
    * a thread by that id and by nothing else, so a thread that cannot address itself would be useless.
    *
    * Everything else is optional or defaulted: the two flags become `false` when the instance did not say, and
    * `updated_at` goes through [[com.worxbend.codeberg4s.codec.Timestamps]], which folds Forgejo's zero-time and epoch
    * sentinels into absence.
    *
    * A failure inside `subject` or `repository` is reported at that nested path — `$[0].subject.type`, not `$[0]`.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, NotificationThread] =
    for
      identifier <- Wire.validated(at, "id", id)(NotificationThreadId.from)
      about      <- subjectAt(at)
      repo       <- repositoryAt(at)
    yield NotificationThread(
      id         = identifier,
      subject    = about,
      repository = repo,
      isUnread   = unread.getOrElse(false),
      isPinned   = pinned.getOrElse(false),
      url        = url,
      updatedAt  = Timestamps.parseOptional(updatedAt),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, NotificationThread] =
    toDomainAt(JsonPath.Root)

  private def subjectAt(at: JsonPath): Either[DecodeFailure, Option[NotificationSubject]] =
    subject.fold(Right(None))(dto => dto.toDomainAt(at.field("subject")).map(Some.apply))

  private def repositoryAt(at: JsonPath): Either[DecodeFailure, Option[Repository]] =
    repository.fold(Right(None))(dto => dto.toDomainAt(at.field("repository")).map(Some.apply))

object NotificationThreadDto:

  /** Reads a `NotificationThread` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[NotificationThreadDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing the `fromFields` of every model it embeds so that no field spelling is
    * written twice.
    */
  def fromFields(fields: JsonFields): NotificationThreadDto =
    NotificationThreadDto(
      id         = fields.number("id"),
      pinned     = fields.boolean("pinned"),
      unread     = fields.boolean("unread"),
      updatedAt  = fields.text("updated_at"),
      url        = fields.text("url"),
      subject    = fields.nested("subject").map(NotificationSubjectDto.fromFields),
      repository = fields.nested("repository").map(RepositoryDto.fromFields),
    )

  /** Converts a decoded array of threads, reporting the position of whichever element failed.
    *
    * One bad element fails the whole page, which is the contract every other list in this module has: a caller handed
    * nineteen of twenty threads would have no way to notice.
    */
  def toDomainAll(
      base: JsonPath,
      dtos: Vector[NotificationThreadDto],
  ): Either[DecodeFailure, Vector[NotificationThread]] =
    Elements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
