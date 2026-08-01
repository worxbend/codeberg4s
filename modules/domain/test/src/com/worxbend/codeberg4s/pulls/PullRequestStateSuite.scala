package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.CommitSha

import munit.FunSuite

import java.time.Instant

/** [[PullRequestState.from]] — the reassembly of six wire fields into one lifecycle value.
  *
  * The interesting cases are all about disagreement between those fields, because the wire supplies them independently
  * and nothing on Forgejo's side keeps them consistent.
  */
final class PullRequestStateSuite extends FunSuite:

  private val Merged: Instant = Instant.parse("2026-08-01T17:15:31Z")

  private val Closed: Instant = Instant.parse("2026-08-01T19:12:33Z")

  test("an open pull request is Open"):
    assertEquals(reassemble("open"), Right(PullRequestState.Open))

  test("state is matched case-insensitively after trimming, as the rest of the library does"):
    assertEquals(reassemble("  OPEN "), Right(PullRequestState.Open))

  test("a closed pull request keeps the instant it was closed"):
    assertEquals(
      PullRequestState.from("closed", false, None, None, None, Some(Closed)),
      Right(PullRequestState.Closed(Some(Closed))),
    )

  test("a closed pull request with no recorded instant is still Closed"):
    assertEquals(reassemble("closed"), Right(PullRequestState.Closed(None)))

  /** The trap the whole type exists for: Forgejo reports `state: "closed"` for a merged pull request. */
  test("merged wins over a state of closed, which is what Forgejo sends for a merge"):
    val state = PullRequestState.from("closed", true, Some(Merged), None, None, Some(Merged))

    assertEquals(state, Right(PullRequestState.Merged(Some(Merged), None, None)))

  test("a merged_at with no merged flag is still a merge, so a flagless instance is read correctly"):
    assertEquals(
      PullRequestState.from("closed", false, Some(Merged), None, None, Some(Merged)),
      Right(PullRequestState.Merged(Some(Merged), None, None)),
    )

  test("merge evidence outranks a state this library does not recognise, because a merge is not ambiguous"):
    assertEquals(
      PullRequestState.from("nonsense", true, None, None, None, None),
      Right(PullRequestState.Merged(None, None, None)),
    )

  test("a merge with no merge commit is legal, because a fast-forward merge produces none"):
    assertEquals(
      PullRequestState.from("closed", true, Some(Merged), None, None, None),
      Right(PullRequestState.Merged(Some(Merged), None, None)),
    )

  test("a merge commit survives into the state rather than sitting beside it"):
    val sha   = orFail(CommitSha.from("38615e78ed86c1eaaadd086f00a807ea4cc96a19"))
    val state = PullRequestState.from("closed", true, Some(Merged), None, Some(sha), None)

    assertEquals(state, Right(PullRequestState.Merged(Some(Merged), None, Some(sha))))

  test("a closing instant sent beside an open state is dropped, not smuggled into the domain"):
    assertEquals(
      PullRequestState.from("open", false, None, None, None, Some(Closed)),
      Right(PullRequestState.Open),
    )

  test("an unrecognised state with no merge evidence is a validation failure on the state field"):
    assertEquals(reassemble("draft"), Left(ValidationError("state", "must be 'open' or 'closed', not 'draft'")))

  test("Open is open, Closed and Merged are not"):
    assertEquals(PullRequestState.Open.isOpen, true)
    assertEquals(PullRequestState.Closed(None).isOpen, false)
    assertEquals(PullRequestState.Merged(None, None, None).isOpen, false)

  test("isClosed means 'no longer open', so a merged pull request answers true"):
    assertEquals(PullRequestState.Merged(None, None, None).isClosed, true)
    assertEquals(PullRequestState.Closed(None).isClosed, true)
    assertEquals(PullRequestState.Open.isClosed, false)

  test("only a merge is merged"):
    assertEquals(PullRequestState.Merged(None, None, None).isMerged, true)
    assertEquals(PullRequestState.Closed(Some(Closed)).isMerged, false)
    assertEquals(PullRequestState.Open.isMerged, false)

  private def reassemble(state: String): Either[ValidationError, PullRequestState] =
    PullRequestState.from(state, false, None, None, None, None)

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
