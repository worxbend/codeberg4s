package com.worxbend.codeberg4s.repositories.hooks

import java.util.Locale

/** One repository event a webhook subscribes to — the elements of a hook's `events` array.
  *
  * ==Why there is an `Other` case==
  *
  * The event vocabulary is a closed set as far as any one Forgejo release is concerned: a delivery carries exactly one
  * of these names in its `X-Forgejo-Event` header, and a hook that subscribes to a name the instance does not know is
  * rejected. It is nevertheless '''not''' closed in `spec/swagger.v1.json`, which declares both `Hook.events` and
  * `CreateHookOption.events` as a bare `type: array` of `type: string` with no `enum` — the spec enumerates hook
  * '''types''' and not hook events. The named cases below are therefore Forgejo's documented event names, written down
  * here so that the common ones are spelled once and checked by the compiler, and [[HookEvent.Other]] is what keeps a
  * name this library has not heard of usable rather than lost.
  *
  * This is the same shape [[com.worxbend.codeberg4s.repositories.FileContent]] uses for the same reason: recognise what
  * is known, keep what is not, invent nothing. A caller reading a hook back therefore never loses an event, and a
  * caller subscribing to an event a later Forgejo release adds never has to wait for this library to catch up.
  *
  * [[HookEvent.parse]] and [[HookEvent.wireValue]] round-trip: parsing a wire name and rendering it again yields the
  * name that was parsed, `Other` included.
  */
enum HookEvent:

  /** A branch or tag was created — `create`. */
  case Create

  /** A branch or tag was deleted — `delete`. */
  case Delete

  /** The repository was forked — `fork`. */
  case Fork

  /** Commits were pushed — `push`. */
  case Push

  /** An issue was opened, closed or reopened — `issues`. */
  case Issues

  /** An issue's assignees changed — `issue_assign`. */
  case IssueAssign

  /** An issue's labels changed — `issue_label`. */
  case IssueLabel

  /** An issue's milestone changed — `issue_milestone`. */
  case IssueMilestone

  /** A comment on an issue was written, edited or removed — `issue_comment`. */
  case IssueComment

  /** A pull request was opened, closed, reopened or merged — `pull_request`. */
  case PullRequest

  /** A pull request's assignees changed — `pull_request_assign`. */
  case PullRequestAssign

  /** A pull request's labels changed — `pull_request_label`. */
  case PullRequestLabel

  /** A pull request's milestone changed — `pull_request_milestone`. */
  case PullRequestMilestone

  /** A comment on a pull request was written, edited or removed — `pull_request_comment`. */
  case PullRequestComment

  /** A review approved a pull request — `pull_request_review_approved`. */
  case PullRequestReviewApproved

  /** A review requested changes on a pull request — `pull_request_review_rejected`. */
  case PullRequestReviewRejected

  /** A review left a comment on a pull request — `pull_request_review_comment`. */
  case PullRequestReviewComment

  /** A review was requested from someone — `pull_request_review_request`. */
  case PullRequestReviewRequest

  /** A pull request's head branch was synchronised — `pull_request_sync`. */
  case PullRequestSync

  /** A wiki page was created, edited or deleted — `wiki`. */
  case Wiki

  /** The repository itself was created or deleted — `repository`. */
  case Repository

  /** A release was published, updated or deleted — `release`. */
  case Release

  /** A package was published or removed — `package`. */
  case Package

  /** A commit status was set — `status`. */
  case Status

  /** An event name this library does not recognise, kept exactly as the instance spelled it.
    *
    * Not an error and not a fallback for a malformed value: it is how a vocabulary the spec never enumerated stays
    * open. The name is carried verbatim, so it renders back onto the wire unchanged.
    *
    * @param name
    *   the wire spelling, trimmed and lower-cased by [[HookEvent.parse]] when it came from a response
    */
  case Other(name: String)

object HookEvent:

  /** Reads Forgejo's lowercase wire spelling.
    *
    * '''Total.''' A name outside the recognised set becomes [[HookEvent.Other]] rather than `None`, because dropping an
    * event would cost the caller a subscription the instance actually holds. Matching trims and is case-insensitive,
    * since nothing but Forgejo's own convention guarantees the casing; the value carried by `Other` is the trimmed,
    * lower-cased spelling, so two spellings of one unknown event compare equal.
    */
  def parse(value: String): HookEvent =
    value.trim.toLowerCase(Locale.ROOT) match
      case "create"                       => Create
      case "delete"                       => Delete
      case "fork"                         => Fork
      case "push"                         => Push
      case "issues"                       => Issues
      case "issue_assign"                 => IssueAssign
      case "issue_label"                  => IssueLabel
      case "issue_milestone"              => IssueMilestone
      case "issue_comment"                => IssueComment
      case "pull_request"                 => PullRequest
      case "pull_request_assign"          => PullRequestAssign
      case "pull_request_label"           => PullRequestLabel
      case "pull_request_milestone"       => PullRequestMilestone
      case "pull_request_comment"         => PullRequestComment
      case "pull_request_review_approved" => PullRequestReviewApproved
      case "pull_request_review_rejected" => PullRequestReviewRejected
      case "pull_request_review_comment"  => PullRequestReviewComment
      case "pull_request_review_request"  => PullRequestReviewRequest
      case "pull_request_sync"            => PullRequestSync
      case "wiki"                         => Wiki
      case "repository"                   => Repository
      case "release"                      => Release
      case "package"                      => Package
      case "status"                       => Status
      case unrecognised                   => Other(unrecognised)

  extension (event: HookEvent)

    /** The wire spelling, which is what a request body sends and what a response carries. */
    def wireValue: String =
      event match
        case Create                    => "create"
        case Delete                    => "delete"
        case Fork                      => "fork"
        case Push                      => "push"
        case Issues                    => "issues"
        case IssueAssign               => "issue_assign"
        case IssueLabel                => "issue_label"
        case IssueMilestone            => "issue_milestone"
        case IssueComment              => "issue_comment"
        case PullRequest               => "pull_request"
        case PullRequestAssign         => "pull_request_assign"
        case PullRequestLabel          => "pull_request_label"
        case PullRequestMilestone      => "pull_request_milestone"
        case PullRequestComment        => "pull_request_comment"
        case PullRequestReviewApproved => "pull_request_review_approved"
        case PullRequestReviewRejected => "pull_request_review_rejected"
        case PullRequestReviewComment  => "pull_request_review_comment"
        case PullRequestReviewRequest  => "pull_request_review_request"
        case PullRequestSync           => "pull_request_sync"
        case Wiki                      => "wiki"
        case Repository                => "repository"
        case Release                   => "release"
        case Package                   => "package"
        case Status                    => "status"
        case Other(name)               => name
