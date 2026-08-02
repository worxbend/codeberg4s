package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.users.User

import java.time.Instant

/** One inline remark attached to a diff line, as `GET /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments`
  * reports it.
  *
  * Forgejo's `PullReviewComment`. This is '''not''' [[com.worxbend.codeberg4s.issues.Comment]]: a pull request's
  * ordinary conversation lives on the issue endpoints and carries no path, no diff hunk and no line numbers, whereas
  * every value here is anchored to a position in a file. The two are deliberately separate models, because merging them
  * would produce one whose half-populated fields depend on which endpoint answered.
  *
  * ==Derived from the pinned spec, not from a capture==
  *
  * `golden/MANIFEST.md` records no review-comment fixture — the anonymous harvest could reach `/pulls/{n}/reviews` but
  * every review of the harvested pull requests carried `comments_count: 0`, so there was nothing to capture. The
  * fifteen fields below are the fifteen properties of the pinned spec's `PullReviewComment` definition, and
  * `docs/HAZARDS.md` §1 is why every one of them is treated as absent-able. Should a capture ever contradict this
  * model, the capture wins.
  *
  * ==The two positions, and what `0` means==
  *
  * Forgejo types `position` and `original_position` as `uint64` and uses `0` as "not this side of the diff": a comment
  * on an added line has a [[position]] and `0` for [[originalPosition]], and a comment on a removed line has it the
  * other way round. That sentinel is preserved rather than folded into an `Option`, because `0` is also what an absent
  * key decodes to and the two are genuinely indistinguishable on the wire. Read them together with [[path]]: a comment
  * with neither position set is a remark about the file rather than about a line.
  *
  * @param id
  *   the instance-wide identifier, and the only way to address the comment; see [[ReviewCommentId]]
  * @param reviewId
  *   the review the comment was written as part of, absent when the instance sent nothing or sent Forgejo's `0`
  *   placeholder for a comment not yet attached to a submitted review
  * @param body
  *   the remark itself, as Markdown source
  * @param path
  *   the file the remark is about, relative to the repository root
  * @param position
  *   the line in the '''new''' side of the diff, `0` when the comment is not on the new side
  * @param originalPosition
  *   the line in the '''old''' side of the diff, `0` when the comment is not on the old side
  * @param extraLinesCount
  *   how many further lines after [[position]] the remark covers; `0` is a single-line comment, which is Forgejo's own
  *   reading of the field
  * @param diffHunk
  *   the excerpt of the diff the remark is anchored to, as Forgejo rendered it at the time
  * @param commit
  *   the commit the comment currently applies to
  * @param originalCommit
  *   the commit the comment was written against, which differs from [[commit]] once the branch has moved
  * @param author
  *   the account that wrote the remark
  * @param resolver
  *   the account that marked the conversation resolved, absent while it is still open
  */
final case class ReviewComment(
    id: ReviewCommentId,
    reviewId: Option[ReviewId],
    body: Option[String],
    path: Option[String],
    position: Long,
    originalPosition: Long,
    extraLinesCount: Long,
    diffHunk: Option[String],
    commit: Option[CommitSha],
    originalCommit: Option[CommitSha],
    author: Option[User],
    resolver: Option[User],
    htmlUrl: Option[String],
    pullRequestUrl: Option[String],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
):

  /** Whether the conversation this comment starts has been marked resolved.
    *
    * Derived from [[resolver]] rather than from a field of its own, because Forgejo publishes no `resolved` flag: the
    * presence of a resolver '''is''' the flag.
    */
  def isResolved: Boolean = resolver.isDefined
