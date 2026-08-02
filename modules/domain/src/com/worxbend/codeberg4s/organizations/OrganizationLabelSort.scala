package com.worxbend.codeberg4s.organizations

import java.util.Locale

/** How `GET /orgs/{org}/labels` orders its results — the `sort` parameter of that endpoint.
  *
  * ==This one the spec does enumerate==
  *
  * Unlike [[QuotaSubject]], the `sort` parameter carries an explicit `enum` in `spec/swagger.v1.json`: `mostissues`,
  * `leastissues`, `reversealphabetically`. Those three are the three cases below, verbatim, so this is a closed set the
  * spec states rather than one this library inferred.
  *
  * '''There is no case for the default.''' The parameter is optional, and omitting it is not the same request as
  * sending any value — Forgejo then orders labels its own way, which the spec does not describe. That is why
  * [[OrganizationLabelApi.list]] takes an `Option` and sends nothing when it is `None`, rather than this enum carrying
  * an `Alphabetically` case that would have to guess the wire spelling of a value the spec does not list.
  *
  * ==Why it lives in this package==
  *
  * `GET /repos/{owner}/{repo}/labels` declares the identical `enum`, so this ordering is not organisation-specific.
  * `docs/LEDGER.md` makes the wave that first needs a shared model its owner, and no repository-label listing in this
  * library sends `sort` today — [[com.worxbend.codeberg4s.issues.IssueApi]] does not offer it — so there is nothing to
  * reuse and nothing yet to conflict with. If the issues group later grows a sorted label listing, this type moves
  * there and this package imports it; what must not happen is a second copy.
  */
enum OrganizationLabelSort:

  /** Labels carrying the most issues first — `mostissues`. */
  case MostIssues

  /** Labels carrying the fewest issues first — `leastissues`. */
  case LeastIssues

  /** Reverse alphabetical order — `reversealphabetically`. */
  case ReverseAlphabetically

object OrganizationLabelSort:

  /** Reads the wire spelling.
    *
    * Answers `None` for anything outside the enumerated set, rather than failing: this is only ever needed to
    * round-trip a value a caller wrote down, and there is no response field carrying it, so nothing decodes through
    * here. Matching trims and is case-insensitive because the spec declares the spelling and no capture proves it.
    */
  def parse(value: String): Option[OrganizationLabelSort] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "mostissues"            => Some(MostIssues)
      case "leastissues"           => Some(LeastIssues)
      case "reversealphabetically" => Some(ReverseAlphabetically)
      case _                       => None

  extension (sort: OrganizationLabelSort)

    /** The lowercase spelling the `sort` parameter takes. */
    def wireValue: String =
      sort match
        case MostIssues            => "mostissues"
        case LeastIssues           => "leastissues"
        case ReverseAlphabetically => "reversealphabetically"
