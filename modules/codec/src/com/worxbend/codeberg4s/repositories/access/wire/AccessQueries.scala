package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.access.DeployKeyQuery

/** The query strings this group's endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API class for the reason
  * [[com.worxbend.codeberg4s.issues.wire.IssueQueries]] gives: `key_id` and `fingerprint` are wire spellings, and rule
  * 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once. It also means
  * the shape of a request can be asserted on directly, without a stub backend.
  *
  * ==Only two endpoints in this group take a query at all==
  *
  * `GET /repos/{owner}/{repo}/collaborators` and `GET /repos/{owner}/{repo}/keys` declare `page` and `limit`; the keys
  * listing adds `key_id` and `fingerprint`. The other three listings — branch protections, tag protections and teams —
  * declare '''no''' parameters whatsoever in `spec/swagger.v1.json`, which is why
  * [[com.worxbend.codeberg4s.repositories.access.RepositoryAccessApi]] returns a `Vector` for those and a
  * [[com.worxbend.codeberg4s.paging.Page]] for these two.
  */
private[codeberg4s] object AccessQueries:

  /** The `page` and `limit` parameters for a paged listing.
    *
    * '''Both, always''', for the reason [[com.worxbend.codeberg4s.issues.wire.IssueQueries.paging]] states: a limit
    * sent without a page is silently ignored by some Forgejo endpoints, which is how a client accidentally pulls an
    * unbounded collection.
    */
  def paging(params: PageParams): List[(String, String)] =
    List("page" -> params.page.value.toString, "limit" -> params.size.value.toString)

  /** The filters of the deploy key listing, in the order the spec declares them.
    *
    * Only what the caller set is emitted, so [[com.worxbend.codeberg4s.repositories.access.DeployKeyQuery.Empty]]
    * renders to nothing. Note that `key_id` is the identifier of the underlying SSH key row and '''not''' a
    * [[com.worxbend.codeberg4s.repositories.access.DeployKeyId]]; passing the wrong one yields an empty page rather
    * than an error, which is why the domain type says so at length.
    */
  def deployKeys(query: DeployKeyQuery): List[(String, String)] =
    List(
      query.keyId.map(id                => "key_id" -> id.toString),
      query.fingerprint.map(fingerprint => "fingerprint" -> fingerprint),
    ).flatten
