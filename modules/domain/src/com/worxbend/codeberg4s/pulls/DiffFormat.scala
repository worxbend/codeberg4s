package com.worxbend.codeberg4s.pulls

/** Which non-JSON representation of a pull request to fetch — the `{diffType}` of
  * `/repos/{owner}/{repo}/pulls/{index}.{diffType}`.
  *
  * The two are not interchangeable, and the difference is not cosmetic:
  *
  *   - [[Diff]] is a plain unified diff of the whole pull request, with no commit metadata. It is what `git diff`
  *     produces and what `git apply` consumes;
  *   - [[Patch]] is a mailbox of one `git format-patch` message per commit, each with its own author, date and message.
  *     It is what `git am` consumes, and it preserves authorship that [[Diff]] discards.
  *
  * A caller that means to replay someone's commits wants [[Patch]]; a caller that means to look at the net change wants
  * [[Diff]]. Choosing wrongly produces a document that applies with the wrong tool and no error until it does.
  *
  * This is a path segment, not a query parameter, and it is the reason the endpoint's path has a dot in it.
  */
enum DiffFormat:

  /** A unified diff of the whole pull request; Forgejo's `diff`. */
  case Diff

  /** One `git format-patch` message per commit; Forgejo's `patch`. */
  case Patch

  /** The extension to append to the pull-request number in the path. */
  def wireValue: String =
    this match
      case Diff  => "diff"
      case Patch => "patch"
