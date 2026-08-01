package com.worxbend.codeberg4s.issues

/** A label, as it belongs to a repository and as it appears on an issue.
  *
  * Owned by this group per `docs/LEDGER.md`, and consumed unchanged by pull requests: Forgejo returns the same `Label`
  * model inside a `PullRequest` as inside an `Issue`, so nothing here may grow an issue-specific meaning.
  *
  * Organisation-level labels — `GET /orgs/{org}/labels`, captured in `golden/organization/org-labels-list.json` — are
  * this same model, field for field. The difference is which endpoint served it, not what it is.
  *
  * @param id
  *   the instance-wide identifier; the only way to address a label, since names repeat across repositories
  * @param name
  *   the label text, verbatim from the instance. A plain `String` rather than [[LabelName]] on purpose — see
  *   [[LabelName]] for why reading is more permissive than writing
  * @param color
  *   the background colour, absent when the instance sent something that is not a hexadecimal triplet
  * @param isExclusive
  *   whether Forgejo enforces that an issue carries at most one label from this label's `scope/` prefix
  * @param isArchived
  *   whether the label is retained for existing issues but no longer offered for new ones
  * @param url
  *   the API URL of the label itself, not a browser URL — Forgejo sends no `html_url` for labels
  */
final case class Label(
    id: LabelId,
    name: String,
    color: Option[LabelColor],
    description: Option[String],
    isExclusive: Boolean,
    isArchived: Boolean,
    url: Option[String],
)
