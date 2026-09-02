package com.worxbend.codeberg4s.organizations

/** Everything `GET /orgs/{org}/labels` may be asked, as one value.
  *
  * '''Derived from `spec/swagger.v1.json`''': beside the paging window the operation declares exactly one parameter,
  * `sort`, and does not mark it required. So this query carries one filter today — the point of it is not the size of
  * the type, it is that the listing takes the same shape of argument as every other filtered listing in this library
  * ([[com.worxbend.codeberg4s.users.UserSearchQuery]], [[com.worxbend.codeberg4s.repositories.RepositorySearchQuery]]),
  * and that a parameter Forgejo adds later can be added here without changing the signature of
  * [[OrganizationLabelApi.list]] again.
  *
  * '''An unset filter is not an empty one.''' `sort` is left out of the query string entirely when it is `None`, which
  * asks Forgejo for its own ordering; `sort=` is a value the endpoint's `enum` does not contain. See
  * [[OrganizationLabelSort]] for why there is no case meaning "the default".
  *
  * @param sort
  *   how to order the results; absent leaves the ordering to the instance
  */
final case class OrganizationLabelQuery private[codeberg4s] (sort: Option[OrganizationLabelSort]):

  /** Orders the results; see [[OrganizationLabelSort]]. */
  def sortedBy(order: OrganizationLabelSort): OrganizationLabelQuery = copy(sort = Some(order))

object OrganizationLabelQuery:

  /** No filter set at all — the organisation's labels in whatever order the instance returns them. */
  val Empty: OrganizationLabelQuery = OrganizationLabelQuery(sort = None)

  /** A listing ordered by `order`, with nothing else set.
    *
    * There is nothing here to reject — the ordering is an enum, so the only spellings that exist are the three the spec
    * enumerates — which is why this answers the query itself rather than an
    * `Either[com.worxbend.codeberg4s.ValidationError, OrganizationLabelQuery]`.
    */
  def of(order: OrganizationLabelSort): OrganizationLabelQuery = Empty.sortedBy(order)
