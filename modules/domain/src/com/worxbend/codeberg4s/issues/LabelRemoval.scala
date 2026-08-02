package com.worxbend.codeberg4s.issues

import java.time.Instant

/** What a label removal may say beyond which label — Forgejo's `DeleteLabelsOption`, the body of both
  * `DELETE /repos/{owner}/{repo}/issues/{index}/labels` and `DELETE …/labels/{identifier}`.
  *
  * '''Derived from `spec/swagger.v1.json`''', which gives that model exactly one property, `updated_at`. No golden
  * capture exists.
  *
  * A one-field type rather than a bare `Option[Instant]` parameter, for the reason [[CreateComment]] gives: an
  * `Option[Instant]` in fourth position at a call site tells a reader nothing about what the instant means, and the
  * body is a JSON object that Forgejo may well grow a second property on.
  *
  * @param updatedAt
  *   the timestamp to record the removal under. For an importer replaying history; Forgejo requires elevated permission
  *   to honour it and ignores it otherwise
  */
final case class LabelRemoval(updatedAt: Option[Instant]):

  /** Records the removal as having happened at `moment`; see [[updatedAt]]. */
  def recordedAt(moment: Instant): LabelRemoval = copy(updatedAt = Some(moment))

object LabelRemoval:

  /** Say nothing beyond which label — the ordinary case, which sends an empty JSON object. */
  val Empty: LabelRemoval = LabelRemoval(updatedAt = None)
