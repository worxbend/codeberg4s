package com.worxbend.codeberg4s.issues

import java.time.Instant

/** The time window `GET /repos/{owner}/{repo}/issues/comments` and `GET …/issues/{index}/timeline` accept.
  *
  * '''Derived from `spec/swagger.v1.json`''': both operations declare `since` and `before` beside their paging
  * parameters, and nothing else worth naming. One type serves both because the two filters mean the same thing on each
  * — "updated at or after" and "updated at or before".
  *
  * '''Only what is set is sent''', for the reason [[com.worxbend.codeberg4s.issues.wire.IssueQueries]] gives: a
  * malformed timestamp comes back as a `422` carrying a raw Go parse error, and an empty one is malformed.
  *
  * @param since
  *   only comments updated at or after this instant
  * @param before
  *   only comments updated at or before this instant
  */
final case class CommentQuery(since: Option[Instant], before: Option[Instant]):

  /** Restricts the listing to comments updated at or after `moment`. */
  def updatedSince(moment: Instant): CommentQuery = copy(since = Some(moment))

  /** Restricts the listing to comments updated at or before `moment`. */
  def updatedBefore(moment: Instant): CommentQuery = copy(before = Some(moment))

object CommentQuery:

  /** No window at all — every comment the token may see. */
  val Empty: CommentQuery = CommentQuery(since = None, before = None)
