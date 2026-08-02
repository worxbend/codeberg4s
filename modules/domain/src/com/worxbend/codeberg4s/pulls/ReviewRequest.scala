package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.organizations.Team
import com.worxbend.codeberg4s.users.Username

/** Who to ask for a review, or whose request to withdraw — Forgejo's `PullReviewRequestOptions`.
  *
  * One type for two operations, because Forgejo sends the same body to both:
  * [[com.worxbend.codeberg4s.pulls.PullRequestApi.requestReviews]] adds the named reviewers and
  * [[com.worxbend.codeberg4s.pulls.PullRequestApi.removeReviewRequests]] removes them. Nothing about the value says
  * which; the endpoint does.
  *
  * ==Accounts and teams are different keys, not one list==
  *
  * `reviewers` holds account handles and `team_reviewers` holds team '''names''', and Forgejo will not look a value up
  * in the other list. So the two are separate here as well, and each is built from an already-validated type:
  *
  *   - an account is a [[com.worxbend.codeberg4s.users.Username]], which has already rejected a blank handle and a
  *     handle containing a slash;
  *   - a team is a [[com.worxbend.codeberg4s.organizations.Team]] — the wave-5 model, consumed rather than
  *     re-described. Forgejo identifies a team by name and a name is easy to mistype, so this library takes the team
  *     value a caller read from `client.organizations` instead of a string it cannot check.
  *
  * '''An empty list contributes no key.''' Asking for nobody is not the same request as asking for the default, and a
  * `PullReviewRequestOptions` with both lists empty is a `422` rather than a no-op.
  *
  * @param reviewers
  *   the accounts to ask, in order
  * @param teams
  *   the team names to ask, in order
  */
final case class ReviewRequest(reviewers: Vector[Username], teams: Vector[String]):

  /** Adds one account to the request, keeping the ones already added. */
  def requesting(account: Username): ReviewRequest = copy(reviewers = reviewers.appended(account))

  /** Adds one team to the request, by the name Forgejo knows it under. */
  def requestingTeam(team: Team): ReviewRequest = copy(teams = teams.appended(team.name))

  /** Replaces the accounts wholesale; an empty vector asks no individual. */
  def requestingAll(accounts: Vector[Username]): ReviewRequest = copy(reviewers = accounts)

  /** Replaces the teams wholesale; an empty vector asks no team. */
  def requestingAllTeams(all: Vector[Team]): ReviewRequest = copy(teams = all.map(_.name))

  /** Whether this request names nobody at all, which Forgejo answers with a `422`. */
  def isEmpty: Boolean = reviewers.isEmpty && teams.isEmpty

object ReviewRequest:

  /** A request that names nobody. Sent as-is it is a `422`; see [[ReviewRequest.isEmpty]]. */
  val Empty: ReviewRequest = ReviewRequest(reviewers = Vector.empty, teams = Vector.empty)

  /** A request naming one account, which is the common case. */
  def of(account: Username): ReviewRequest = Empty.requesting(account)

  /** A request naming one team. */
  def ofTeam(team: Team): ReviewRequest = Empty.requestingTeam(team)
