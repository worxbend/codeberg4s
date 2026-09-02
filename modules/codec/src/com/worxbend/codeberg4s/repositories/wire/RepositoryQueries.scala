package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.RepositorySearchQuery

/** The query strings this group's endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API class for the reason
  * [[com.worxbend.codeberg4s.issues.wire.IssueQueries]] gives: `q`, `includeDesc`, `uid`, `is_private` and the rest are
  * wire spellings, and rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written
  * exactly once. It also means the shape of a request can be asserted on directly, without a stub backend.
  *
  * '''Only parameters the caller set are emitted.''' Repository search has no filter whose absent value this library
  * can supply: `is_private`, `template` and `archived` each default to "both kinds", and inventing a value for them
  * would narrow a search the caller did not narrow.
  *
  * Parameter order is fixed rather than incidental. Forgejo does not care, but a stable order makes a recorded request
  * comparable between runs.
  */
private[codeberg4s] object RepositoryQueries:

  /** The parameters of `GET /repos/search`, in the order the spec declares them, then the paging window.
    *
    * '''The three switches emit only a `true`.''' `topic`, `includeDesc` and `exclusive` behave without them exactly as
    * they behave with a `false`, so sending three explicit falsehoods would add nothing but noise to the query string;
    * see [[com.worxbend.codeberg4s.repositories.RepositorySearchQuery]].
    *
    * '''The four three-valued filters emit whichever `Boolean` the caller chose''', including `false` — `is_private`,
    * `template` and `archived` each mean "only the others" when false, which is a request that has no other spelling.
    */
  def search(query: RepositorySearchQuery, params: PageParams): List[(String, String)] =
    List(
      query.text.map(keywords => "q" -> keywords),
      Option.when(query.topicOnly)("topic"                -> "true"),
      Option.when(query.includeDescription)("includeDesc" -> "true"),
      query.ownerId.map(id              => "uid" -> id.toString),
      query.priorityOwnerId.map(id      => "priority_owner_id" -> id.toString),
      query.teamId.map(id               => "team_id" -> id.toString),
      query.starredById.map(id          => "starredBy" -> id.toString),
      query.includePrivate.map(included => "private" -> included.toString),
      query.onlyPrivate.map(only        => "is_private" -> only.toString),
      query.onlyTemplate.map(only       => "template" -> only.toString),
      query.onlyArchived.map(only       => "archived" -> only.toString),
      query.mode.map(kind               => "mode" -> kind.wireValue),
      Option.when(query.exclusive)("exclusive" -> "true"),
      query.sort.map(attribute  => "sort" -> attribute.wireValue),
      query.order.map(direction => "order" -> direction.wireValue),
    ).flatten ++ PagingQuery.window(params)
