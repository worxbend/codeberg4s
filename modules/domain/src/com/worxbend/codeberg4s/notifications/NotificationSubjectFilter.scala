package com.worxbend.codeberg4s.notifications

/** A value of the `subject-type` filter on the notification listings.
  *
  * ==Why this is not [[NotificationSubjectType]]==
  *
  * Because the API is asymmetric, and modelling the asymmetry away would hand callers a query Forgejo cannot answer.
  * `spec/swagger.v1.json` declares the `subject-type` parameter with a real enum — `["issue", "pull", "repository"]`,
  * lowercase — while the `subject.type` field it filters on is an unconstrained string whose observed values are
  * capitalised and whose documented set includes `Commit`. So:
  *
  *   - there is no way to ask for commit notifications, even though a thread can be one;
  *   - the spellings differ in case between the filter and the field.
  *
  * One enum covering both would have to carry a `Commit` case that silently produces an invalid request, or an `Other`
  * case that does the same. A separate, closed enum makes the reachable filters exactly the reachable filters.
  *
  * ==Evidence==
  *
  * The enum list is the spec's own, which makes this the '''best'''-evidenced type in the group — and it is still
  * unverified against a live instance, like everything else here. See [[NotificationThread]].
  */
enum NotificationSubjectFilter:

  /** Threads whose subject is an issue. */
  case Issue

  /** Threads whose subject is a pull request. */
  case Pull

  /** Threads whose subject is a repository. */
  case Repository

  /** The value to put in a `subject-type` query parameter. */
  def wireValue: String =
    this match
      case Issue      => "issue"
      case Pull       => "pull"
      case Repository => "repository"
