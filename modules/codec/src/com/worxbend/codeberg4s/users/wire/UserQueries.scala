package com.worxbend.codeberg4s.users.wire

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.users.UserSearchQuery

/** The query strings this group's endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API class for the reason
  * [[com.worxbend.codeberg4s.issues.wire.IssueQueries]] gives: `q`, `uid` and `sort` are wire spellings, and rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once. It also means the
  * shape of a request can be asserted on directly, without a stub backend.
  *
  * '''Only parameters the caller set are emitted.''' Account search declares no default for `sort`, so an omitted
  * ordering is a request this library cannot spell any other way.
  */
private[codeberg4s] object UserQueries:

  /** The parameters of `GET /users/search`, in the order the spec declares them, then the paging window.
    *
    * A blank keyword never reaches here as an empty `q`: it is dropped when the query is built, because omitting the
    * parameter and sending it empty ask the same question — see
    * [[com.worxbend.codeberg4s.users.UserSearchQuery.of]].
    */
  def search(query: UserSearchQuery, params: PageParams): List[(String, String)] =
    List(
      query.text.map(keywords => "q" -> keywords),
      query.userId.map(id     => "uid" -> id.toString),
      query.sort.map(order    => "sort" -> order.wireValue),
    ).flatten ++ PagingQuery.window(params)
