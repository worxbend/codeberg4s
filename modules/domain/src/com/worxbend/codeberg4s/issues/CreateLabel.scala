package com.worxbend.codeberg4s.issues

/** What `POST /repos/{owner}/{repo}/labels` is told.
  *
  * Both of Forgejo's required fields are already validated types, so unlike [[CreateIssue.of]] and [[CreateComment.of]]
  * there is nothing left for [[CreateLabel.of]] to reject and it returns the command directly:
  *
  * {{{
  * for
  *   name  <- LabelName.from("needs-triage")
  *   color <- LabelColor.from("#eb6420")
  * yield CreateLabel.of(name, color).describedAs("nobody has looked at this yet")
  * }}}
  *
  * @param name
  *   the label text. A `/` in it is not a path problem here — the label is created by `POST`, not addressed by name —
  *   but Forgejo does read a `scope/value` prefix as a scope when [[isExclusive]] is set
  * @param isExclusive
  *   whether an issue may carry at most one label sharing this label's `scope/` prefix
  * @param isArchived
  *   whether the label is created already archived: kept on existing issues, not offered for new ones
  */
final case class CreateLabel(
    name: LabelName,
    color: LabelColor,
    description: Option[String],
    isExclusive: Boolean,
    isArchived: Boolean,
):

  /** Sets the label description shown in the label picker. */
  def describedAs(text: String): CreateLabel = copy(description = Some(text))

  /** Makes the label exclusive within its `scope/` prefix. */
  def exclusive: CreateLabel = copy(isExclusive = true)

  /** Creates the label already archived. */
  def archived: CreateLabel = copy(isArchived = true)

object CreateLabel:

  /** Starts a command from the two fields Forgejo requires. */
  def of(name: LabelName, color: LabelColor): CreateLabel =
    CreateLabel(name = name, color = color, description = None, isExclusive = false, isArchived = false)
