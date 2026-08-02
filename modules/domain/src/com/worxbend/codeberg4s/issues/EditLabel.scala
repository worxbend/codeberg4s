package com.worxbend.codeberg4s.issues

/** What `PATCH /repos/{owner}/{repo}/labels/{id}` may be told — Forgejo's `EditLabelOption`.
  *
  * '''Derived from `spec/swagger.v1.json`''', which declares five properties and marks none of them required. No golden
  * capture of this request exists; the response is a `Label`, which `golden/issue/labels-repo.json` covers.
  *
  * '''Only what is set is sent.''' An unset field contributes no JSON key, so the instance leaves that property alone.
  * That is why [[isExclusive]] and [[isArchived]] are `Option[Boolean]` rather than `Boolean`: on a create, `false`
  * means "leave the default"; on an edit, it has to be able to mean "turn this off", and a plain `Boolean` cannot say
  * both.
  *
  * An [[EditLabel.Empty]] sent as-is is a well-formed request that changes nothing; it is not rejected here, for the
  * reason [[EditIssue]] gives.
  *
  * @param name
  *   the new label text; [[LabelName]] rather than `String` because it is the same wire-sensitive value [[CreateLabel]]
  *   sends
  * @param color
  *   the new background colour, sent in the `#rrggbb` form Forgejo documents for input
  * @param isExclusive
  *   whether Forgejo should enforce at most one label from this label's `scope/` prefix
  * @param isArchived
  *   whether the label is retained for existing issues but no longer offered for new ones
  */
final case class EditLabel(
    name: Option[LabelName],
    color: Option[LabelColor],
    description: Option[String],
    isExclusive: Option[Boolean],
    isArchived: Option[Boolean],
):

  /** Renames the label. */
  def renamedTo(text: LabelName): EditLabel = copy(name = Some(text))

  /** Recolours the label. */
  def colouredAs(shade: LabelColor): EditLabel = copy(color = Some(shade))

  /** Replaces the description. */
  def describedAs(text: String): EditLabel = copy(description = Some(text))

  /** Turns scope exclusivity on or off. */
  def exclusive(enabled: Boolean): EditLabel = copy(isExclusive = Some(enabled))

  /** Archives or unarchives the label. */
  def archived(enabled: Boolean): EditLabel = copy(isArchived = Some(enabled))

object EditLabel:

  /** An edit that changes nothing — the starting point for every label edit. */
  val Empty: EditLabel =
    EditLabel(name = None, color = None, description = None, isExclusive = None, isArchived = None)
