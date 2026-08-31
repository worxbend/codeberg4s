package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.organizations.{OrganizationLabelSort, QuotaSubject}
import com.worxbend.codeberg4s.paging.PageParams

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The query strings this group's endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API classes, for the reason
  * [[com.worxbend.codeberg4s.repositories.hooks.wire.HookQueries]] gives: `limit`, `sort`, `date`, `subject`,
  * `include_desc` and `q` are wire spellings, and rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a
  * wire spelling is written exactly once. It also means the shape of a request can be asserted on directly, without a
  * stub backend.
  *
  * '''Only parameters the caller set are emitted.''' An absent filter is not the same request as an empty one: omitting
  * `sort` lets Forgejo order labels its own way, and `sort=` is a value the endpoint's `enum` does not contain.
  */
private[codeberg4s] object OrganizationQueries:

  /** The date format Forgejo's activity feeds take — the spec declares the parameter `type: string, format: date`,
    * which is ISO-8601's `yyyy-MM-dd` and nothing else.
    */
  private val ActivityDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

  /** The `page` and `limit` parameters of a paged listing.
    *
    * Both are always sent, and the pair is rendered by [[com.worxbend.codeberg4s.codec.PagingQuery.window]], which
    * carries the measurement behind that rule: a `limit` sent without a `page` is silently ignored by some Forgejo
    * endpoints, which is how a client accidentally pulls an unbounded collection.
    */
  def paging(params: PageParams): List[(String, String)] =
    PagingQuery.window(params)

  /** The parameters of `GET /orgs/{org}/labels`: an optional ordering, then the window.
    *
    * `sort` is omitted entirely when the caller named no ordering; see
    * [[com.worxbend.codeberg4s.organizations.OrganizationLabelSort]] for why there is no case meaning "the default".
    */
  def labels(sort: Option[OrganizationLabelSort], params: PageParams): List[(String, String)] =
    sort.toList.map(order => "sort" -> order.wireValue) ++ paging(params)

  /** The parameters of the two activity feeds: an optional day, then the window.
    *
    * '''A day, not an instant.''' The spec declares `format: date`, so the parameter selects a calendar day rather than
    * a point in time, and it carries no zone — which day that is depends on the instance's own clock. A caller who
    * needs a range asks for one day at a time.
    */
  def activities(date: Option[LocalDate], params: PageParams): List[(String, String)] =
    date.toList.map(day => "date" -> day.format(ActivityDate)) ++ paging(params)

  /** The parameters of `GET /orgs/{org}/teams/search`: the optional text, the optional description flag, the window.
    *
    * `q` is omitted when the caller passed no text, which asks Forgejo for every team it would list anyway.
    * `include_desc` is omitted rather than sent as `false`, because the spec does not say which way the instance
    * defaults and asserting one would be a guess.
    */
  def teamSearch(
      text: Option[String],
      includeDescription: Option[Boolean],
      params: PageParams,
  ): List[(String, String)] =
    text.toList.map(keywords => "q" -> keywords)
      ++ includeDescription.toList.map(flag => "include_desc" -> flag.toString)
      ++ paging(params)

  /** The one required parameter of `GET /orgs/{org}/quota/check`.
    *
    * Always emitted: the spec marks `subject` required, and an omitted one is a `422` rather than a default.
    */
  def quotaCheck(subject: QuotaSubject): List[(String, String)] =
    List("subject" -> subject.value)
