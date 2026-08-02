package com.worxbend.codeberg4s.issues

/** Which of the two things a cross-repository search should return.
  *
  * `GET /repos/issues/search` serves issues and pull requests from one endpoint, exactly as a repository's own issue
  * listing does — see [[Issue.isPullRequest]]. The difference is that this endpoint can be '''told''': the spec
  * declares a `type` parameter with the `enum` `["issues", "pulls"]`, and that closed set is why this is an enum where
  * [[ReactionContent]] is not.
  *
  * Leaving the filter unset is a third thing and not a synonym for either case: Forgejo then returns both kinds mixed
  * together. That is why [[IssueSearchQuery.kind]] is an `Option` of this type.
  */
enum IssueKind:

  /** Plain issues only. */
  case Issues

  /** Pull requests only. */
  case Pulls

  /** The value to put in the `type` query parameter. */
  def wireValue: String =
    this match
      case Issues => "issues"
      case Pulls  => "pulls"
