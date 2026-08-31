package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.gitdata.RefName

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The query strings this group's endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API class for the reason
  * [[com.worxbend.codeberg4s.issues.wire.IssueQueries]] gives: `ref`, `date` and `q` are wire spellings, and rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once. It also means the
  * shape of a request can be asserted on directly, without a stub backend.
  *
  * The tracked-time filters are '''not''' here. `GET /repos/{owner}/{repo}/times` takes the same `user`, `since` and
  * `before` as the per-issue listing, and [[com.worxbend.codeberg4s.issues.wire.IssueQueries.trackedTimes]] already
  * renders them; duplicating that would put one wire spelling in two files.
  */
private[codeberg4s] object AdminQueries:

  /** The `page` and `limit` parameters of a paged listing.
    *
    * Both are always sent, and the pair is rendered by [[com.worxbend.codeberg4s.codec.PagingQuery.window]], which
    * carries the measurement behind that rule: a `limit` sent without a `page` is silently ignored by some Forgejo
    * endpoints, which is how a client accidentally pulls an unbounded collection.
    */
  def paging(params: PageParams): List[(String, String)] =
    PagingQuery.window(params)

  /** The `ref` parameter of the root contents listing.
    *
    * Empty when the caller named no reference, which asks for the repository's default branch. Sending `ref=` would ask
    * for a branch called nothing, which is a `404`.
    */
  def contents(ref: Option[RefName]): List[(String, String)] =
    ref.toList.map(value => "ref" -> value.value)

  /** The `date` parameter of the activity feed.
    *
    * The spec declares it `type: string, format: date`, which is a calendar day and not an instant — so this takes a
    * `java.time.LocalDate` and renders it as `yyyy-MM-dd`. Passing an instant would force this code to pick a time zone
    * on the caller's behalf, and the answer would silently differ by one day either side of midnight.
    */
  def activities(date: Option[LocalDate]): List[(String, String)] =
    date.toList.map(day => "date" -> AdminQueries.DayFormat.format(day))

  /** The `q` parameter of the topic search, which the spec marks required. */
  def topicSearch(keyword: String): List[(String, String)] =
    List("q" -> keyword)

  private val DayFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
