package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.issues.Comment
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.users.User

import java.time.Instant
import java.util.Locale

/** What happened, in a repository's activity feed — `op_type` on Forgejo's `Activity`.
  *
  * `spec/swagger.v1.json` declares all twenty-seven cases below as an `enum`, so this is a closed set the spec states
  * rather than one this library inferred. It is also a set that grows: Forgejo adds an action type whenever it adds a
  * feature that shows up in a feed, which is why [[ActivityOperation.parse]] answers `None` rather than failing.
  *
  * The vocabulary is Gitea's, warts included — `commit_repo` covers a push, `push_tag` and `delete_tag` are separate
  * from it, and pull-request review outcomes are three cases rather than one with a result.
  */
enum ActivityOperation:

  /** A repository was created. */
  case CreateRepo

  /** A repository was renamed. */
  case RenameRepo

  /** Somebody starred a repository. */
  case StarRepo

  /** Somebody started watching a repository. */
  case WatchRepo

  /** Commits were pushed to a branch. Forgejo's spelling for a push, despite the name. */
  case CommitRepo

  /** An issue was opened. */
  case CreateIssue

  /** A pull request was opened. */
  case CreatePullRequest

  /** A repository changed owner. */
  case TransferRepo

  /** A tag was pushed. */
  case PushTag

  /** A comment was added to an issue. */
  case CommentIssue

  /** A pull request was merged. */
  case MergePullRequest

  /** An issue was closed. */
  case CloseIssue

  /** A closed issue was reopened. */
  case ReopenIssue

  /** A pull request was closed without merging. */
  case ClosePullRequest

  /** A closed pull request was reopened. */
  case ReopenPullRequest

  /** A tag was deleted. */
  case DeleteTag

  /** A branch was deleted. */
  case DeleteBranch

  /** A mirror pushed new commits. */
  case MirrorSyncPush

  /** A mirror created a reference. */
  case MirrorSyncCreate

  /** A mirror removed a reference. */
  case MirrorSyncDelete

  /** A review approved a pull request. */
  case ApprovePullRequest

  /** A review requested changes on a pull request. */
  case RejectPullRequest

  /** A comment was added to a pull request. */
  case CommentPull

  /** A release was published. */
  case PublishRelease

  /** A review on a pull request was dismissed. */
  case PullReviewDismissed

  /** A draft pull request was marked ready for review. */
  case PullRequestReadyForReview

  /** A pull request merged itself once its conditions were met. */
  case AutoMergePullRequest

object ActivityOperation:

  /** Parses Forgejo's `snake_case` spelling.
    *
    * Answers `None` for anything outside the enumerated set rather than failing, for the reason
    * [[com.worxbend.codeberg4s.repositories.actions.ActionStatus.parse]] gives: an action type a later Forgejo release
    * adds must not cost the caller the rest of the feed. Matching is case-insensitive and trims, because nothing but
    * the spec guarantees the casing.
    */
  def parse(value: String): Option[ActivityOperation] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "create_repo"                   => Some(CreateRepo)
      case "rename_repo"                   => Some(RenameRepo)
      case "star_repo"                     => Some(StarRepo)
      case "watch_repo"                    => Some(WatchRepo)
      case "commit_repo"                   => Some(CommitRepo)
      case "create_issue"                  => Some(CreateIssue)
      case "create_pull_request"           => Some(CreatePullRequest)
      case "transfer_repo"                 => Some(TransferRepo)
      case "push_tag"                      => Some(PushTag)
      case "comment_issue"                 => Some(CommentIssue)
      case "merge_pull_request"            => Some(MergePullRequest)
      case "close_issue"                   => Some(CloseIssue)
      case "reopen_issue"                  => Some(ReopenIssue)
      case "close_pull_request"            => Some(ClosePullRequest)
      case "reopen_pull_request"           => Some(ReopenPullRequest)
      case "delete_tag"                    => Some(DeleteTag)
      case "delete_branch"                 => Some(DeleteBranch)
      case "mirror_sync_push"              => Some(MirrorSyncPush)
      case "mirror_sync_create"            => Some(MirrorSyncCreate)
      case "mirror_sync_delete"            => Some(MirrorSyncDelete)
      case "approve_pull_request"          => Some(ApprovePullRequest)
      case "reject_pull_request"           => Some(RejectPullRequest)
      case "comment_pull"                  => Some(CommentPull)
      case "publish_release"               => Some(PublishRelease)
      case "pull_review_dismissed"         => Some(PullReviewDismissed)
      case "pull_request_ready_for_review" => Some(PullRequestReadyForReview)
      case "auto_merge_pull_request"       => Some(AutoMergePullRequest)
      case _                               => None

  extension (operation: ActivityOperation)

    /** The `snake_case` spelling Forgejo uses on the wire. */
    def wireValue: String =
      operation match
        case CreateRepo                => "create_repo"
        case RenameRepo                => "rename_repo"
        case StarRepo                  => "star_repo"
        case WatchRepo                 => "watch_repo"
        case CommitRepo                => "commit_repo"
        case CreateIssue               => "create_issue"
        case CreatePullRequest         => "create_pull_request"
        case TransferRepo              => "transfer_repo"
        case PushTag                   => "push_tag"
        case CommentIssue              => "comment_issue"
        case MergePullRequest          => "merge_pull_request"
        case CloseIssue                => "close_issue"
        case ReopenIssue               => "reopen_issue"
        case ClosePullRequest          => "close_pull_request"
        case ReopenPullRequest         => "reopen_pull_request"
        case DeleteTag                 => "delete_tag"
        case DeleteBranch              => "delete_branch"
        case MirrorSyncPush            => "mirror_sync_push"
        case MirrorSyncCreate          => "mirror_sync_create"
        case MirrorSyncDelete          => "mirror_sync_delete"
        case ApprovePullRequest        => "approve_pull_request"
        case RejectPullRequest         => "reject_pull_request"
        case CommentPull               => "comment_pull"
        case PublishRelease            => "publish_release"
        case PullReviewDismissed       => "pull_review_dismissed"
        case PullRequestReadyForReview => "pull_request_ready_for_review"
        case AutoMergePullRequest      => "auto_merge_pull_request"

/** One entry in a repository's activity feed — Forgejo's `Activity`.
  *
  * The feed is what the repository's home page shows under "Activity": a flat, reverse-chronological list of things
  * that happened, each one a [[ActivityOperation]] and a subject. It is '''not''' the Git history and not the audit log
  * — an entry exists because Forgejo decided the event was worth showing a human, and there is no promise that every
  * state change produces one.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' The endpoint is repository-scoped and the
  * golden harvest was anonymous, so no fixture backs the field set.
  *
  * ==What `content` means depends on `operation`==
  *
  * Forgejo packs a different payload into `content` per action type: a push writes a JSON blob describing the commits,
  * an issue comment writes the comment's own id and text, a rename writes the previous name. It is left as a raw string
  * here rather than parsed into a union, because the shapes are Gitea implementation details with no schema in the spec
  * and no compatibility promise. Read it only after matching on [[operation]], and be ready for it to change.
  *
  * @param id
  *   the entry's identifier
  * @param actor
  *   who did it, when the instance reports the account. Absent for an action attributed to no account
  * @param operation
  *   what happened, or `None` when the instance sent an action type this release does not know — see
  *   [[ActivityOperation.parse]]
  * @param refName
  *   the branch or tag the action touched, for the action types that touch one
  * @param content
  *   the action-specific payload; see the note above before reading it
  * @param repository
  *   the repository the action happened in. Always the repository that was asked, but Forgejo sends it per entry
  * @param comment
  *   the comment the action produced, for the comment action types
  * @param isPrivate
  *   whether the entry describes something in a private repository, and is therefore hidden from anonymous callers
  * @param createdAt
  *   when it happened
  */
final case class RepositoryActivity(
    id: ActivityId,
    actor: Option[User],
    operation: Option[ActivityOperation],
    refName: Option[String],
    content: Option[String],
    repository: Option[Repository],
    comment: Option[Comment],
    isPrivate: Boolean,
    createdAt: Option[Instant],
)
