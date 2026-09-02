package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.{SearchKeyword, ValidationError}

/** Everything `GET /repos/search` may be asked, as one value.
  *
  * '''Derived from `spec/swagger.v1.json`''': the operation declares fifteen parameters beside the paging window, of
  * which every single one is optional. A method taking them positionally would be a fifteen-argument method, and a
  * method taking only the keyword — which is what this library offered until now — hides fourteen of them from the
  * caller entirely.
  *
  * ==Three shapes of filter==
  *
  * '''The keyword''' is [[text]], the `q` parameter. It is validated by [[RepositorySearchQuery.of]] and dropped when
  * blank; see [[com.worxbend.codeberg4s.SearchKeyword]] for why.
  *
  * '''Three switches default to off.''' [[topicOnly]], [[includeDescription]] and [[exclusive]] are plain `Boolean`s
  * rather than options because absent and `false` are the same request: Forgejo's behaviour without them is the
  * behaviour of `false`, so only a `true` is emitted and the query string stays readable.
  *
  * '''Four filters are genuinely three-valued''' and are therefore `Option[Boolean]`. [[includePrivate]],
  * [[onlyPrivate]], [[onlyTemplate]] and [[onlyArchived]] each mean "only those" when `true`, "only the others" when
  * `false`, and "both kinds" when absent — the spec spells that out as "defaults to all". Collapsing them to a
  * `Boolean` would make "either kind" unreachable.
  *
  * ==The four ids are raw `Long`s==
  *
  * [[ownerId]], [[priorityOwnerId]], [[teamId]] and [[starredById]] are instance-wide numeric ids of an account or a
  * team, and this library owns no type for them: [[com.worxbend.codeberg4s.users.Username]] is a handle, not an id. A
  * wrong id is not an error — the endpoint answers an empty page — so there is nothing a smart constructor could check
  * beyond the type.
  *
  * @param text
  *   the `q` keyword, matched against repository names and, with [[includeDescription]], descriptions too
  * @param topicOnly
  *   match the keyword against the repository's topics instead of its name — Forgejo's `topic`
  * @param includeDescription
  *   also match the keyword against the description — Forgejo's `includeDesc`
  * @param ownerId
  *   only repositories this account id owns or contributes to — Forgejo's `uid`
  * @param priorityOwnerId
  *   rank this account id's repositories first, without excluding anybody else's
  * @param teamId
  *   only repositories belonging to this team id
  * @param starredById
  *   only repositories this account id has starred
  * @param includePrivate
  *   include private repositories the credentials may see; the instance's own default is to include them
  * @param onlyPrivate
  *   `true` for private repositories only, `false` for public only, absent for both — Forgejo's `is_private`
  * @param onlyTemplate
  *   `true` for template repositories only, `false` for non-templates only, absent for both
  * @param onlyArchived
  *   `true` for archived repositories only, `false` for live ones only, absent for both
  * @param mode
  *   the kind of repository wanted; see [[RepositorySearchMode]]
  * @param exclusive
  *   with [[ownerId]] set, restrict the results to repositories that account '''owns''', dropping the ones it merely
  *   contributes to. Without [[ownerId]] the parameter does nothing
  * @param sort
  *   the attribute to order by; see [[RepositorySearchSort]]
  * @param order
  *   which way round that ordering runs; ignored by Forgejo unless [[sort]] is set, see [[SortDirection]]
  */
final case class RepositorySearchQuery private[codeberg4s] (
    text: Option[String],
    topicOnly: Boolean,
    includeDescription: Boolean,
    ownerId: Option[Long],
    priorityOwnerId: Option[Long],
    teamId: Option[Long],
    starredById: Option[Long],
    includePrivate: Option[Boolean],
    onlyPrivate: Option[Boolean],
    onlyTemplate: Option[Boolean],
    onlyArchived: Option[Boolean],
    mode: Option[RepositorySearchMode],
    exclusive: Boolean,
    sort: Option[RepositorySearchSort],
    order: Option[SortDirection],
):

  /** Matches the keyword against topics rather than against repository names. */
  def asTopic: RepositorySearchQuery = copy(topicOnly = true)

  /** Matches the keyword against repository descriptions as well as names. */
  def includingDescriptions: RepositorySearchQuery = copy(includeDescription = true)

  /** Restricts the search to repositories the account `userId` owns or contributes to. */
  def ownedBy(userId: Long): RepositorySearchQuery = copy(ownerId = Some(userId))

  /** Ranks the account `userId`'s repositories first, without excluding anybody else's. */
  def prioritisingOwner(userId: Long): RepositorySearchQuery = copy(priorityOwnerId = Some(userId))

  /** Restricts the search to repositories belonging to the team `teamId`. */
  def inTeam(teamId: Long): RepositorySearchQuery = copy(teamId = Some(teamId))

  /** Restricts the search to repositories the account `userId` has starred. */
  def starredBy(userId: Long): RepositorySearchQuery = copy(starredById = Some(userId))

  /** States explicitly whether private repositories the credentials may see are included. */
  def withPrivate(included: Boolean): RepositorySearchQuery = copy(includePrivate = Some(included))

  /** Restricts the search to private repositories, or to public ones; see [[onlyPrivate]]. */
  def restrictedToPrivate(only: Boolean): RepositorySearchQuery = copy(onlyPrivate = Some(only))

  /** Restricts the search to template repositories, or to non-templates; see [[onlyTemplate]]. */
  def restrictedToTemplates(only: Boolean): RepositorySearchQuery = copy(onlyTemplate = Some(only))

  /** Restricts the search to archived repositories, or to live ones; see [[onlyArchived]]. */
  def restrictedToArchived(only: Boolean): RepositorySearchQuery = copy(onlyArchived = Some(only))

  /** Restricts the search to one kind of repository; see [[RepositorySearchMode]]. */
  def onlyOf(kind: RepositorySearchMode): RepositorySearchQuery = copy(mode = Some(kind))

  /** With [[ownedBy]] set, drops the repositories that account only contributes to. */
  def ownedExclusively: RepositorySearchQuery = copy(exclusive = true)

  /** Orders the results by `attribute`; see [[RepositorySearchSort]]. */
  def sortedBy(attribute: RepositorySearchSort): RepositorySearchQuery = copy(sort = Some(attribute))

  /** Runs the ordering `direction`; Forgejo reads it only when [[sortedBy]] was used too. */
  def inOrder(direction: SortDirection): RepositorySearchQuery = copy(order = Some(direction))

object RepositorySearchQuery:

  /** No keyword and no filters at all — every repository the credentials can see, page by page. */
  val Empty: RepositorySearchQuery =
    RepositorySearchQuery(
      text               = None,
      topicOnly          = false,
      includeDescription = false,
      ownerId            = None,
      priorityOwnerId    = None,
      teamId             = None,
      starredById        = None,
      includePrivate     = None,
      onlyPrivate        = None,
      onlyTemplate       = None,
      onlyArchived       = None,
      mode               = None,
      exclusive          = false,
      sort               = None,
      order              = None,
    )

  /** Builds a search for `keyword`, with every other filter unset.
    *
    * The keyword is trimmed, a blank one is treated as no keyword at all, and a control character is rejected — see
    * [[com.worxbend.codeberg4s.SearchKeyword]] for each of those. Everything else is added by the methods on the
    * returned value, so a filtered search reads as one expression:
    *
    * ```scala
    * RepositorySearchQuery.of("forgejo").map(_.includingDescriptions.sortedBy(RepositorySearchSort.Stars))
    * ```
    *
    * @return
    *   the query, or a [[ValidationError]] on the `"text"` field
    */
  def of(keyword: String): Either[ValidationError, RepositorySearchQuery] =
    SearchKeyword.normalize("text", keyword).map(normalized => Empty.copy(text = normalized))
