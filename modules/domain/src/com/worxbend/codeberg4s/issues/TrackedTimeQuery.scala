package com.worxbend.codeberg4s.issues

import java.time.Instant

/** The filters `GET /repos/{owner}/{repo}/issues/{index}/times` accepts.
  *
  * '''Derived from `spec/swagger.v1.json`''': the operation declares `user`, `since` and `before` beside the paging
  * parameters. No golden capture exists.
  *
  * '''Only what is set is sent.''' An absent filter is not the same request as an empty one — omitting `user` asks for
  * everybody's entries, and `user=` asks for the entries of an account with no name.
  *
  * @param userName
  *   restrict to one account's entries, by login
  * @param since
  *   only entries recorded at or after this instant
  * @param before
  *   only entries recorded at or before this instant
  */
final case class TrackedTimeQuery(userName: Option[String], since: Option[Instant], before: Option[Instant]):

  /** Restricts the listing to `login`'s entries. */
  def forUser(login: String): TrackedTimeQuery = copy(userName = Some(login))

  /** Restricts the listing to entries recorded at or after `moment`. */
  def recordedSince(moment: Instant): TrackedTimeQuery = copy(since = Some(moment))

  /** Restricts the listing to entries recorded at or before `moment`. */
  def recordedBefore(moment: Instant): TrackedTimeQuery = copy(before = Some(moment))

object TrackedTimeQuery:

  /** No filters at all — every entry the token may see. */
  val Empty: TrackedTimeQuery = TrackedTimeQuery(userName = None, since = None, before = None)
