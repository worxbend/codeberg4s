package com.worxbend.codeberg4s.issues

import java.time.Instant

/** The labels an add or a replace names — Forgejo's `IssueLabelsOption`, and the body of both `POST` and `PUT` on
  * `/repos/{owner}/{repo}/issues/{index}/labels`.
  *
  * '''Derived from `spec/swagger.v1.json`.''' No golden capture of either request exists; the response shape is a bare
  * array of `Label`, which `golden/issue/labels-on-issue-empty.json` does cover.
  *
  * ==The same body means two different things==
  *
  * `POST` '''adds''' these labels to whatever the issue already has. `PUT` '''replaces''' the whole set with these, so
  * an empty vector under `PUT` clears the issue. That difference is not visible in this type, because it is not in the
  * body — it is the method, and it is stated on the two methods that send it. It also decides whether either call may
  * be repeated after a transport failure; see [[com.worxbend.codeberg4s.issues.IssueLabelApi]].
  *
  * @param labels
  *   the labels, each named by id or by name; see [[LabelRef]] for why both are offered and why the id is better
  * @param updatedAt
  *   the timestamp to record the change under. For an importer replaying history; Forgejo requires elevated permission
  *   to honour it and ignores it otherwise
  */
final case class LabelUpdate(labels: Vector[LabelRef], updatedAt: Option[Instant]):

  /** Records the change as having happened at `moment`; see [[updatedAt]]. */
  def recordedAt(moment: Instant): LabelUpdate = copy(updatedAt = Some(moment))

object LabelUpdate:

  /** Names labels by their identifiers, which is the unambiguous form. */
  def byId(ids: Vector[LabelId]): LabelUpdate =
    LabelUpdate(labels = ids.map(LabelRef.ById.apply), updatedAt = None)

  /** Names labels by their names; see [[LabelRef]] for the caveat that comes with that. */
  def byName(names: Vector[LabelName]): LabelUpdate =
    LabelUpdate(labels = names.map(LabelRef.ByName.apply), updatedAt = None)

  /** Names labels however the caller has them, mixing the two spellings if need be. */
  def of(labels: Vector[LabelRef]): LabelUpdate =
    LabelUpdate(labels = labels, updatedAt = None)

  /** No labels at all. Meaningful under `PUT`, where it clears the issue; a no-op under `POST`. */
  val Empty: LabelUpdate = LabelUpdate(labels = Vector.empty, updatedAt = None)
