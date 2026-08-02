package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.pulls.DiffRequest
import com.worxbend.codeberg4s.pulls.PullRequestQuery
import com.worxbend.codeberg4s.pulls.UpdateStyle

/** The query strings this group's listing endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API class for the same reason the DTOs do: `state`, `sort`,
  * `poster` and `labels` are wire spellings, and rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a
  * wire spelling is written exactly once. It also means the shape of a request can be asserted on directly, without a
  * stub backend.
  *
  * '''Only parameters the caller set are emitted.''' An absent filter is not the same request as an empty one —
  * omitting `state` gets Forgejo's default of open pull requests, and `state=` gets a `400` — so nothing here has a
  * default to fall back on.
  *
  * Parameter order is fixed rather than incidental. Forgejo does not care, but a stable order makes a recorded request
  * comparable between runs.
  */
private[codeberg4s] object PullRequestQueries:

  /** The `page` and `limit` parameters for a paged listing.
    *
    * '''Both, always.''' `golden/MANIFEST.md` records that `limit` alone is silently ignored on some Forgejo endpoints
    * — `?limit=2` against `/forks` returned all 862 forks, and adding `page=1` made the limit take effect — so sending
    * a limit without a page is how a client accidentally pulls an unbounded collection.
    */
  def paging(params: PageParams): List[(String, String)] =
    List("page" -> params.page.value.toString, "limit" -> params.size.value.toString)

  /** The filters of `GET /repos/{owner}/{repo}/pulls`.
    *
    * '''`labels` is repeated, not comma-joined.''' The spec declares `collectionFormat: multi` for this parameter, so
    * three labels become `labels=1&labels=2&labels=3` — which is why
    * [[com.worxbend.codeberg4s.core.CodebergRequest.query]] is a list of pairs rather than a map. Note that the issue
    * listing declares the '''opposite''' encoding for its own `labels`, a single comma-joined string; the two endpoints
    * genuinely disagree, and a client that shares one renderer between them sends one of them a filter it ignores.
    *
    * The scalar filters come in the order the spec declares them and `labels` is appended after them, rather than in
    * its declared position. Forgejo does not care about parameter order, and putting the one repeating parameter last
    * keeps this a single expression instead of two concatenations around it.
    *
    * `milestone` is an id here, unlike the issue listing's `milestones`, which takes titles. Also Forgejo's, also not
    * something this library can smooth over without lying about which it sent.
    */
  def pulls(query: PullRequestQuery): List[(String, String)] =
    List(
      query.state.map(filter  => "state" -> filter.wireValue),
      query.sort.map(ordering => "sort" -> ordering.wireValue),
      query.milestone.map(id  => "milestone" -> id.value.toString),
      query.poster.map(login  => "poster" -> login),
      query.base.map(branch   => "base" -> branch.value),
      query.head.map(branch   => "head" -> branch.value),
    ).flatten ++ query.labels.map(id => "labels" -> id.value.toString)

  /** The query of `GET /repos/{owner}/{repo}/pulls/{index}.{diffType}`.
    *
    * The format is a '''path''' segment, not a parameter, so the only thing that reaches the query string is `binary` —
    * and only when the caller asked for it. Forgejo reads an absent `binary` as `false`, so sending `binary=false`
    * would be an assertion the caller never made and would also cost the response its cacheability under a proxy that
    * keys on the full URI.
    */
  def diff(request: DiffRequest): List[(String, String)] =
    if request.includeBinary then List("binary" -> "true") else Nil

  /** The query of `POST /repos/{owner}/{repo}/pulls/{index}/update`.
    *
    * Always emitted. Forgejo's handler compares the parameter against the literal `rebase` and treats everything else —
    * including its absence — as a merge, so naming the style explicitly is exactly as expressive as omitting it and
    * makes the recorded request say which of the two was meant. See [[com.worxbend.codeberg4s.pulls.UpdateStyle]].
    */
  def update(style: UpdateStyle): List[(String, String)] =
    List("style" -> style.wireValue)
