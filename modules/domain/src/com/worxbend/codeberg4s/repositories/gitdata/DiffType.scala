package com.worxbend.codeberg4s.repositories.gitdata

/** Which rendering of a commit `GET /repos/{owner}/{repo}/git/commits/{sha}.{diffType}` should return.
  *
  * Like [[ArchiveFormat]] this is a path suffix rather than a parameter — the route is `{sha}.diff` or `{sha}.patch` —
  * and the spec declares exactly these two values as an enum. Neither is JSON: the endpoint produces `text/plain`.
  */
enum DiffType:

  /** A unified diff of the commit against its first parent. */
  case Diff

  /** A mailbox-format patch, with the commit's authorship and message in the headers — what `git format-patch` emits. */
  case Patch

object DiffType:

  extension (diffType: DiffType)

    /** The suffix Forgejo appends to the sha, without the leading dot. */
    def suffix: String =
      diffType match
        case Diff  => "diff"
        case Patch => "patch"
