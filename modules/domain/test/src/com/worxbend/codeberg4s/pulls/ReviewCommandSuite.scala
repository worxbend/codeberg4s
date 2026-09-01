package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.organizations.Organization
import com.worxbend.codeberg4s.organizations.Team
import com.worxbend.codeberg4s.organizations.TeamId
import com.worxbend.codeberg4s.organizations.TeamPermission
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.Username

import munit.FunSuite

/** The review-shaped values this group added after its first eight operations.
  *
  * These are pure domain types, so the subject is what a caller can and cannot express: which side of a diff a remark
  * lands on, what a blank body is rejected as, and which flags are statements rather than defaults.
  */
final class ReviewCommandSuite extends FunSuite:

  private val Sha: CommitSha = orFail(CommitSha.from("48079baa8d387f3ab770cc144c367409ddc2a879"))

  private val Reviewer: Username = orFail(Username.from("mfenniak"))

  private val Second: Username = orFail(Username.from("Gusted"))

  private val Reviewers: Team = team("reviewers")

  // --- inline remarks -------------------------------------------------------

  test("a remark on the new side of the diff carries a new position and no old one"):
    val remark = orFail(NewReviewComment.onNewLine("modules/git/hook.go", 42L, "still wrong"))

    assertEquals(remark.newPosition, Some(42L))
    assertEquals(remark.oldPosition, None)

  test("a remark on the old side is the mirror image, which is why there is no constructor taking both"):
    val remark = orFail(NewReviewComment.onOldLine("modules/git/hook.go", 42L, "this used to be right"))

    assertEquals(remark.oldPosition, Some(42L))
    assertEquals(remark.newPosition, None)

  test("a remark about the file as a whole is anchored to neither side"):
    val remark = orFail(NewReviewComment.onFile("modules/git/hook.go", "the whole file needs rewriting"))

    assertEquals(remark.newPosition, None)
    assertEquals(remark.oldPosition, None)

  test("the body and the path are trimmed, so trailing whitespace never reaches the wire"):
    val remark = orFail(NewReviewComment.onNewLine("  modules/git/hook.go  ", 1L, "  still wrong  "))

    assertEquals(remark.path, "modules/git/hook.go")
    assertEquals(remark.body, "still wrong")

  test("a blank body is rejected on the body field"):
    assertEquals(fieldOf(NewReviewComment.onNewLine("a.go", 1L, "   ")), "body")

  test("a blank path is rejected on the path field, and before the body is even looked at"):
    assertEquals(fieldOf(NewReviewComment.onNewLine("", 1L, "")), "path")

  test("line zero is rejected — Forgejo uses it as the sentinel for the other side of the diff"):
    assertEquals(fieldOf(NewReviewComment.onOldLine("a.go", 0L, "x")), "line")

  test("a span is set only when it is positive; a zero span is what an absent key already means"):
    val remark = orFail(NewReviewComment.onNewLine("a.go", 1L, "x"))

    assertEquals(remark.spanning(3L).extraLinesCount, Some(3L))
    assertEquals(remark.spanning(0L).extraLinesCount, None)
    assertEquals(remark.spanning(-2L).extraLinesCount, None)

  // --- creating and submitting ----------------------------------------------

  test("an empty review says nothing at all, which is a pending draft"):
    assertEquals(CreateReview.Empty.event, None)
    assertEquals(CreateReview.Empty.body, None)
    assertEquals(CreateReview.Empty.comments, Vector.empty[NewReviewComment])

  test("commenting appends, so remarks accumulate in the order they were added"):
    val first  = orFail(NewReviewComment.onNewLine("a.go", 1L, "one"))
    val second = orFail(NewReviewComment.onNewLine("b.go", 2L, "two"))

    assertEquals(
      CreateReview.Empty.commenting(first).commenting(second).comments.map(_.body),
      Vector("one", "two"),
    )

  test("commentingAll replaces, which is how a caller goes back to a summary-only review"):
    val remark = orFail(NewReviewComment.onNewLine("a.go", 1L, "one"))

    assertEquals(
      CreateReview.Empty.commenting(remark).commentingAll(Vector.empty).comments,
      Vector.empty[NewReviewComment],
    )

  test("a review can be pinned to the commit the reviewer actually read"):
    assertEquals(CreateReview.Empty.against(Sha).commit.map(_.value), Some(Sha.value))

  test("every review builder sets its own field and leaves every sibling alone"):
    val review = populatedReview

    assertEquals(review.withBody("replaced"), review.copy(body = Some("replaced")))
    assertEquals(review.saying(ReviewState.Approved), review.copy(event = Some(ReviewState.Approved)))
    assertEquals(review.against(Moved), review.copy(commit = Some(Moved)))
    assertEquals(review.commentingAll(Vector.empty), review.copy(comments = Vector.empty))

  test("a summary and a verdict are separate statements, because Forgejo answers 422 to a comment review with none"):
    assertEquals(CreateReview.Empty.saying(ReviewState.Comment).body, None)
    assertEquals(CreateReview.Empty.withBody("looks good").event, None)

  test("the last verdict asked for is the one submitted, so a draft can be upgraded before it is sent"):
    assertEquals(
      CreateReview.Empty.saying(ReviewState.Pending).saying(ReviewState.Approved).event,
      Some(ReviewState.Approved),
    )

  // --- inline remarks that came back ----------------------------------------

  test("a conversation is resolved exactly when a resolver came back, since Forgejo publishes no resolved flag"):
    assertEquals(reviewComment(None).isResolved, false)
    assertEquals(reviewComment(Some(account)).isResolved, true)

  test("submitting requires an event, and the body stays optional"):
    val command = SubmitReview.saying(ReviewState.Approved)

    assertEquals(command.event, ReviewState.Approved)
    assertEquals(command.body, None)
    assertEquals(command.withBody("looks good").body, Some("looks good"))

  // --- dismissing -----------------------------------------------------------

  test("a dismissal reaches only the review it names unless the caller says otherwise"):
    assertEquals(DismissReview.Empty.priors, false)
    assertEquals(DismissReview.Empty.includingPriors.priors, true)

  test("a dismissal message is recorded when set, because it is all the reviewer ever sees"):
    assertEquals(DismissReview.Empty.withMessage("superseded").message, Some("superseded"))

  // --- review requests ------------------------------------------------------

  test("a request naming nobody knows that it names nobody"):
    assertEquals(ReviewRequest.Empty.isEmpty, true)
    assertEquals(ReviewRequest.of(Reviewer).isEmpty, false)
    assertEquals(ReviewRequest.ofTeam(Reviewers).isEmpty, false)

  test("accounts and teams stay in separate lists, because Forgejo will not look one up in the other"):
    val request = ReviewRequest.of(Reviewer).requestingTeam(Reviewers)

    assertEquals(request.reviewers.map(_.value), Vector("mfenniak"))
    assertEquals(request.teams, Vector("reviewers"))

  test("requesting appends rather than replacing"):
    assertEquals(
      ReviewRequest.Empty.requesting(Reviewer).requesting(Second).reviewers.map(_.value),
      Vector("mfenniak", "Gusted"),
    )

  test("a team contributes the name Forgejo knows it under, never its id"):
    val request = ReviewRequest.Empty.requestingAllTeams(Vector(Reviewers, team("owners")))

    assertEquals(request.teams, Vector("reviewers", "owners"))

  test("replacing the accounts wholesale is how a caller goes back to asking nobody"):
    val request = ReviewRequest.of(Reviewer).requestingAll(Vector.empty)

    assertEquals(request.reviewers, Vector.empty[Username])
    assertEquals(request.isEmpty, true)

  // --- identifiers and enums ------------------------------------------------

  test("a review comment id must be positive, for the reason every identifier in this group must be"):
    assertEquals(ReviewCommentId.from(0L), Left(ValidationError("reviewCommentId", "must be at least 1")))
    assertEquals(ReviewCommentId.from(9L).map(_.value), Right(9L))

  test("the diff formats spell themselves the way the path segment needs"):
    assertEquals(DiffFormat.values.toVector.map(_.wireValue), Vector("diff", "patch"))

  test("the update styles spell themselves the way the query parameter needs"):
    assertEquals(UpdateStyle.values.toVector.map(_.wireValue), Vector("merge", "rebase"))

  test("binary changes are left out until the caller asks, because they multiply the response size"):
    assertEquals(DiffRequest.of(DiffFormat.Diff).includeBinary, false)
    assertEquals(DiffRequest.of(DiffFormat.Diff).includingBinary.includeBinary, true)

  // --- harness --------------------------------------------------------------

  private val Moved: CommitSha = orFail(CommitSha.from("f00dcafe4242beadf00dcafe4242beadf00dcafe"))

  /** A review that already says everything, so a builder cannot drop one of the four unnoticed. */
  private def populatedReview: CreateReview =
    CreateReview.Empty
      .withBody("original")
      .saying(ReviewState.Pending)
      .against(Sha)
      .commenting(orFail(NewReviewComment.onNewLine("modules/git/hook.go", 42L, "still wrong")))

  private def account: User =
    User(
      id                       = 1L,
      login                    = Owner("mfenniak"),
      fullName                 = None,
      email                    = None,
      avatarUrl                = None,
      htmlUrl                  = None,
      language                 = None,
      location                 = None,
      pronouns                 = None,
      website                  = None,
      description              = None,
      visibility               = None,
      isAdmin                  = false,
      isActive                 = true,
      isRestricted             = false,
      isProhibitedFromLogin    = false,
      followersCount           = 0L,
      followingCount           = 0L,
      starredRepositoriesCount = 0L,
      createdAt                = None,
      lastLoginAt              = None,
    )

  private def reviewComment(resolvedBy: Option[User]): ReviewComment =
    ReviewComment(
      id               = orFail(ReviewCommentId.from(9L)),
      reviewId         = None,
      body             = Some("still wrong"),
      path             = Some("modules/git/hook.go"),
      position         = 42L,
      originalPosition = 0L,
      extraLinesCount  = 0L,
      diffHunk         = None,
      commit           = Some(Sha),
      originalCommit   = None,
      author           = Some(account),
      resolver         = resolvedBy,
      htmlUrl          = None,
      pullRequestUrl   = None,
      createdAt        = None,
      updatedAt        = None,
    )

  private def team(name: String): Team =
    Team(
      id                      = orFail(TeamId.from(1L)),
      name                    = name,
      description             = None,
      organization            = Option.empty[Organization],
      permission              = Option.empty[TeamPermission],
      units                   = Vector.empty,
      unitPermissions         = Map.empty,
      canCreateOrgRepo        = false,
      includesAllRepositories = false,
    )

  private def fieldOf[A](result: Either[ValidationError, A]): String =
    result match
      case Left(error)  => error.field
      case Right(value) => fail(s"expected a rejection, built $value")

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
