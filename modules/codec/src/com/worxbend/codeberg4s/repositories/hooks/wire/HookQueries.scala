package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.paging.PageParams

/** The query strings this group's endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API classes for the reason
  * [[com.worxbend.codeberg4s.repositories.actions.wire.ActionQueries]] gives: `limit` and `ref` are wire spellings, and
  * rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once. It also
  * means the shape of a request can be asserted on directly, without a stub backend.
  */
private[codeberg4s] object HookQueries:

  /** The `page` and `limit` parameters of a paged listing.
    *
    * Both are always sent, and the pair is rendered by [[com.worxbend.codeberg4s.codec.PagingQuery.window]], which
    * carries the measurement behind that rule: a `limit` sent without a `page` is silently ignored by some Forgejo
    * endpoints, which is how a client accidentally pulls an unbounded collection.
    */
  def paging(params: PageParams): List[(String, String)] =
    PagingQuery.window(params)

  /** The `page` parameter of the wiki revision listing, which takes no `limit`.
    *
    * '''`limit` is deliberately not sent.''' `spec/swagger.v1.json` declares `page` and nothing else for
    * `repoGetWikiPageRevisions`, unlike every other paged operation in this group, which is why the window comes from
    * [[com.worxbend.codeberg4s.codec.PagingQuery.pageOnly]] and not from [[paging]]. The requested window is still
    * carried onto the returned page, and where the collection ends is decided by the `Link` header as it is everywhere
    * else.
    */
  def revisionPaging(params: PageParams): List[(String, String)] =
    PagingQuery.pageOnly(params)

  /** The `ref` parameter of the webhook test route.
    *
    * Empty when the caller named no reference, which asks Forgejo to load the commit it would have chosen itself.
    */
  def hookTest(ref: Option[String]): List[(String, String)] =
    ref.toList.map(value => "ref" -> value)
