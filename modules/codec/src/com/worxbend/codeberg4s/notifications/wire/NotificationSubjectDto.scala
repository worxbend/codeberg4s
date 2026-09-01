package com.worxbend.codeberg4s.notifications.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.notifications.{NotificationSubject, NotificationSubjectType}

/** Forgejo's `NotificationSubject` model, field for field.
  *
  * ==Provenance: the spec, and nothing else==
  *
  * Every other DTO in this module was written against a captured response body. This one was written against
  * `definitions.NotificationSubject` in `spec/swagger.v1.json`, because `GET /notifications` answers
  * `401 token is required` without credentials and the anonymous harvest could not reach it. The one fixture that
  * exists, `golden/notification/list-synthetic.json`, is hand-authored from that same definition and is marked
  * `synthetic` in `golden/MANIFEST.md`. A test that decodes it therefore proves the reader agrees with the spec — it
  * proves nothing about what an instance actually sends.
  *
  * Consequences, spelled out because they are easy to forget once the tests are green:
  *
  *   - every field is `Option`, per rule 2 of [[com.worxbend.codeberg4s.codec.WireConventions]], and here that is a
  *     guess in the safe direction rather than a measurement;
  *   - the seven keys below are the complete set the spec declares, but a live payload may well carry more.
  *     [[com.worxbend.codeberg4s.codec.JsonFields]] ignores unnamed keys, so that costs nothing;
  *   - `type` is the only key conversion insists on, and it is the one whose exact spelling is least certain. See
  *     [[com.worxbend.codeberg4s.notifications.NotificationSubjectType]], which matches case-insensitively for that
  *     reason.
  *
  * `state` is declared `StateType`, a bare string with no enum, and is carried through as one; the synthetic fixture
  * contains `"merged"`, which the issue lifecycle cannot represent.
  *
  * @param subjectType
  *   the `type` key. Named `subjectType` because `type` is a Scala keyword; rule 4 of
  *   [[com.worxbend.codeberg4s.codec.WireConventions]] keeps the wire spelling in the reader, once
  */
final case class NotificationSubjectDto(
    title: Option[String],
    subjectType: Option[String],
    state: Option[String],
    url: Option[String],
    htmlUrl: Option[String],
    latestCommentUrl: Option[String],
    latestCommentHtmlUrl: Option[String],
) extends WireModel[NotificationSubject]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * One thing is required: `type`. Without the discriminator the subject cannot be interpreted at all — the same
    * object means an issue, a pull request, a commit or a repository depending on it — so an absent `type` is a
    * [[com.worxbend.codeberg4s.core.DecodeFailure]] at `at.field("type")` rather than a silently degraded value.
    *
    * An unrecognised `type` is '''not''' a failure: it becomes
    * [[com.worxbend.codeberg4s.notifications.NotificationSubjectType.Other]] carrying the raw string, because the spec
    * enumerates no values and Forgejo may add one at any release.
    *
    * Everything else is optional and passes through untouched. The URLs stay strings deliberately; see
    * [[com.worxbend.codeberg4s.notifications.NotificationSubject]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, NotificationSubject] =
    Wire
      .required(at, "type", subjectType)
      .map: raw =>
        NotificationSubject(
          subjectType          = NotificationSubjectType.from(raw),
          title                = title,
          state                = state,
          url                  = url,
          htmlUrl              = htmlUrl,
          latestCommentUrl     = latestCommentUrl,
          latestCommentHtmlUrl = latestCommentHtmlUrl,
        )

object NotificationSubjectDto:

  /** Reads a `NotificationSubject` object. Absent, `null` and blank are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]]. That blank fold is load-bearing here — the synthetic fixture sends
    * `""` for both comment URLs on the thread that has no comments, which is the convention Forgejo uses everywhere
    * else for unset text.
    */
  given JsonDecoder[NotificationSubjectDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so that the enclosing thread's reader can reuse it without re-spelling a
    * single wire name.
    */
  def fromFields(fields: JsonFields): NotificationSubjectDto =
    NotificationSubjectDto(
      title                = fields.text("title"),
      subjectType          = fields.text("type"),
      state                = fields.text("state"),
      url                  = fields.text("url"),
      htmlUrl              = fields.text("html_url"),
      latestCommentUrl     = fields.text("latest_comment_url"),
      latestCommentHtmlUrl = fields.text("latest_comment_html_url"),
    )
