package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.paging.PageParams

/** How a [[com.worxbend.codeberg4s.paging.PageParams]] is written into a query string.
  *
  * A paging window has three spellings across the Forgejo API and no more, so all three live here and each wire name —
  * `page`, `limit`, `per_page` — is written exactly once in the library, as rule 4 of [[WireConventions]] requires. The
  * per-group `*Queries` objects keep their own `paging` method as the name their API class calls, but the pair of
  * strings itself comes from here.
  *
  * '''Why one renderer rather than one per endpoint group.''' Before this object the same two-element list appeared in
  * sixteen places. Each copy was a chance for one group to drift — to send a `limit` without a `page`, or to keep the
  * usual spelling on the one route that does not accept it — and a drifted copy fails silently: Forgejo answers `200`
  * with the wrong number of items rather than an error. The three functions below are therefore the whole vocabulary,
  * and a route that needs a fourth spelling is a route that needs a reviewed addition here.
  *
  * @see
  *   [[com.worxbend.codeberg4s.paging.PageParams]] for the validated window this renders
  */
private[codeberg4s] object PagingQuery:

  /** The `page` and `limit` parameters, as almost every Forgejo listing spells them.
    *
    * '''Both, always.''' `golden/MANIFEST.md` records that `limit` alone is silently ignored on some Forgejo endpoints
    * — `?limit=2` against `/forks` returned all 862 forks, and adding `page=1` made the limit take effect — so sending
    * a limit without a page is how a client accidentally pulls an unbounded collection. That measurement is the reason
    * this function takes a whole [[com.worxbend.codeberg4s.paging.PageParams]] and emits both halves, instead of
    * offering a caller the chance to send one of them.
    *
    * The order is `page` then `limit`, which is the order Forgejo's own `Link` header writes them in. Forgejo does not
    * care, but a fixed order makes a recorded request comparable between runs.
    */
  def window(params: PageParams): List[(String, String)] =
    List("page" -> params.page.value.toString, "limit" -> params.size.value.toString)

  /** The `page` parameter on its own, for a route that declares no size parameter at all.
    *
    * Only `repoGetWikiPageRevisions` is in this shape. Sending it a `limit` on the chance that Forgejo reads it would
    * be exactly the guesswork `docs/HAZARDS.md` warns against, so the instance chooses the page size and the caller's
    * [[com.worxbend.codeberg4s.paging.PageParams.size]] does not reach the wire; where the collection ends is still
    * decided by the `Link` header, as it is everywhere else.
    */
  def pageOnly(params: PageParams): List[(String, String)] =
    List("page" -> params.page.value.toString)

  /** The `page` and `per_page` parameters, which one route spells that way.
    *
    * `GET /repos/{owner}/{repo}/git/trees/{sha}` is the odd one out: it declares `per_page` and ignores `limit`
    * entirely, so sending the usual spelling there returns the instance's default page size and no error at all. That
    * single word is why this function exists rather than [[window]] being reused.
    */
  def perPageWindow(params: PageParams): List[(String, String)] =
    List("page" -> params.page.value.toString, "per_page" -> params.size.value.toString)
