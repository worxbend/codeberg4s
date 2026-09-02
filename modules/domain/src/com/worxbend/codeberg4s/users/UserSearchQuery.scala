package com.worxbend.codeberg4s.users

import com.worxbend.codeberg4s.{SearchKeyword, ValidationError}

/** Everything `GET /users/search` may be asked, as one value.
  *
  * '''Derived from `spec/swagger.v1.json`''': the operation declares `q`, `uid` and `sort` beside the paging window,
  * and marks none of them required. Until now this library offered the keyword alone, so the other two were
  * unreachable.
  *
  * '''Matching is the instance's business.''' Forgejo matches the keyword against login, full name and email — the
  * `golden/user/user-search.json` capture of `q=earl` returns `0x20fearless` and `3pattipearl` — so this is a substring
  * search across several fields and not a prefix match on the login.
  *
  * '''An id and a keyword are two different searches.''' [[userId]] is the account's instance-wide numeric id and
  * selects at most one account; the keyword searches. Setting both is accepted and answers the intersection, which is
  * rarely what anybody wants.
  *
  * @param text
  *   the `q` keyword; blank means "every visible account", see [[com.worxbend.codeberg4s.SearchKeyword]]
  * @param userId
  *   only the account with this instance-wide id — Forgejo's `uid`. A raw `Long` because
  *   [[com.worxbend.codeberg4s.users.Username]] is a handle rather than an id, and this library owns no type for the
  *   id. An id that matches nothing is an empty page, not an error
  * @param sort
  *   the ordering; absent leaves it to the instance, see [[UserSearchSort]]
  */
final case class UserSearchQuery private[codeberg4s] (
    text: Option[String],
    userId: Option[Long],
    sort: Option[UserSearchSort],
):

  /** Restricts the search to the account with the instance-wide id `id`. */
  def forUserId(id: Long): UserSearchQuery = copy(userId = Some(id))

  /** Orders the results; see [[UserSearchSort]]. */
  def sortedBy(order: UserSearchSort): UserSearchQuery = copy(sort = Some(order))

object UserSearchQuery:

  /** No keyword and no filters at all — every account the credentials can see, page by page. */
  val Empty: UserSearchQuery = UserSearchQuery(text = None, userId = None, sort = None)

  /** Builds a search for `keyword`, with the other two filters unset.
    *
    * The keyword is trimmed, a blank one is treated as no keyword at all, and a control character is rejected — see
    * [[com.worxbend.codeberg4s.SearchKeyword]] for each of those.
    *
    * @return
    *   the query, or a [[ValidationError]] on the `"text"` field
    */
  def of(keyword: String): Either[ValidationError, UserSearchQuery] =
    SearchKeyword.normalize("text", keyword).map(normalized => Empty.copy(text = normalized))
