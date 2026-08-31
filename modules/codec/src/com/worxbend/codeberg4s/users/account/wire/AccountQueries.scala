package com.worxbend.codeberg4s.users.account.wire

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.users.account.{QuotaSubject, RepositoryOrder}

/** The query strings the `/user` account endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API classes for the reason
  * [[com.worxbend.codeberg4s.repositories.actions.wire.ActionQueries]] gives: `order_by`, `subject`, `page` and `limit`
  * are wire spellings, and rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written
  * exactly once. It also means the shape of a request can be asserted on directly, without a stub backend.
  *
  * '''The Actions and hook endpoints under `/user` do not use this object.''' They reuse
  * [[com.worxbend.codeberg4s.repositories.actions.wire.ActionQueries]] and
  * [[com.worxbend.codeberg4s.repositories.hooks.wire.HookQueries]], because they are the same parameters on the same
  * models and only the path differs. Copying `visible` or `labels` into a second renderer would be exactly the fork
  * `docs/LEDGER.md` treats as a defect.
  */
private[codeberg4s] object AccountQueries:

  /** The query parameter `GET /user/repos` takes its ordering from. */
  val OrderByParameter: String = "order_by"

  /** The query parameter `GET /user/quota/check` takes its subject from. */
  val SubjectParameter: String = "subject"

  /** The `page` and `limit` parameters of a paged listing.
    *
    * Both are always sent, and the pair is rendered by [[com.worxbend.codeberg4s.codec.PagingQuery.window]], which
    * carries the measurement behind that rule: a `limit` sent without a `page` is silently ignored by some Forgejo
    * endpoints, which is how a client accidentally pulls an unbounded collection.
    */
  def paging(params: PageParams): List[(String, String)] =
    PagingQuery.window(params)

  /** The `order_by` parameter of the repository listing.
    *
    * Empty for [[com.worxbend.codeberg4s.users.account.RepositoryOrder.Default]], which is how a caller asks for the
    * instance's own ordering — Forgejo answers `422` for an empty `order_by`, so "no preference" has to be spelled as
    * an absent parameter and not as a blank one.
    */
  def repositoryOrder(order: RepositoryOrder): List[(String, String)] =
    order.wireValue.toList.map(value => OrderByParameter -> value)

  /** The `subject` parameter of the quota check, which the spec marks required and which is therefore always sent. */
  def quotaCheck(subject: QuotaSubject): List[(String, String)] =
    List(SubjectParameter -> subject.value)
