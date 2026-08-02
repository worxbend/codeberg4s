package com.worxbend.codeberg4s.issues

/** How a caller names a label to Forgejo — by its identifier, or by its name.
  *
  * '''Both spellings are real, and the spec says so.''' `IssueLabelsOption.labels` is declared as an array with an
  * '''empty''' item schema and the description "Labels can be a list of integers representing label IDs or a list of
  * strings representing label names". The `{identifier}` path segment of
  * `DELETE /repos/{owner}/{repo}/issues/{index}/labels/{identifier}` is the same choice. Modelling that as
  * `Vector[Any]` or as two overloads would both be worse than naming the two arms.
  *
  * '''Prefer [[LabelRef.ById]].''' A name is not unique — `golden/organization/org-labels-list.json` and
  * `golden/issue/labels-repo.json` both contain a label called `bug`, with different ids — and it is freely renamed.
  * [[LabelRef.ByName]] exists because Forgejo offers it and because a caller who has only a name should not have to
  * list every label to use it, not because it is the better identifier.
  *
  * ==A name in a path segment==
  *
  * [[LabelRef.ByName]] is also what addresses a single label for removal, where it becomes one URL path segment.
  * [[LabelName]] permits `/` — Forgejo's scoped labels are spelled `scope/value` — and the transport percent-encodes
  * each segment, so such a name is sent as `scope%2Fvalue`. Whether the instance's router accepts that is Forgejo's
  * decision, not this library's; [[LabelRef.ById]] avoids the question entirely.
  */
enum LabelRef:

  /** The label's instance-wide identifier. The unambiguous form. */
  case ById(id: LabelId)

  /** The label's name, as [[LabelName]] validates one for wire use. */
  case ByName(name: LabelName)

  /** The label as one URL path segment, before percent-encoding; see the type note on names containing `/`. */
  def pathSegment: String =
    this match
      case ById(id)     => id.value.toString
      case ByName(name) => name.value
