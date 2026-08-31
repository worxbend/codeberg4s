package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.BranchName

import munit.FunSuite

/** The opaque identifiers this group introduces, and the one spelling it has to render itself. */
final class PullRequestIdentifiersSuite extends FunSuite:

  test("a pull-request number is accepted from one upward"):
    assertEquals(PullRequestNumber.from(13731L).map(_.value), Right(13731L))
    assertEquals(PullRequestNumber.from(1L).map(_.value), Right(1L))

  test("a pull-request number below one is rejected, so /pulls/0 is never requested"):
    assertEquals(PullRequestNumber.from(0L), Left(ValidationError("pullRequestNumber", "must be at least 1")))
    assertEquals(PullRequestNumber.from(-1L), Left(ValidationError("pullRequestNumber", "must be at least 1")))

  test("a review id is validated the same way, under its own field name"):
    assertEquals(ReviewId.from(1654076L).map(_.value), Right(1654076L))
    assertEquals(ReviewId.from(0L), Left(ValidationError("reviewId", "must be at least 1")))

  test("a same-repository head is the bare branch name"):
    assertEquals(PullRequestHead.branch(orFail(BranchName.from("fix-pep691"))).value, "fix-pep691")

  test("a cross-repository head is Forgejo's owner:branch"):
    val head = PullRequestHead.crossRepository(orFail(Owner.from("trim21")), orFail(BranchName.from("fix-pep691")))

    assertEquals(head.value, "trim21:fix-pep691")

  test("a slashed branch survives into a cross-repository head, because only the owner half is a segment"):
    val head = PullRequestHead.crossRepository(orFail(Owner.from("forgejo")), orFail(BranchName.from("v16.0/forgejo")))

    assertEquals(head.value, "forgejo:v16.0/forgejo")

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
