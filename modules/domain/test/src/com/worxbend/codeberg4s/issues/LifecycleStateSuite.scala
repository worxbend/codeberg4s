package com.worxbend.codeberg4s.issues

import munit.FunSuite

import java.time.Instant

/** [[LifecycleState]] is what stops an issue being open and closed at once.
  *
  * The invariant under test is not "the parse works" but "an open state cannot carry a closing instant". That is the
  * whole reason the two wire fields are folded into one type.
  */
final class LifecycleStateSuite extends FunSuite:

  private val ClosedAt: Instant = Instant.parse("2026-08-01T09:46:45Z")

  test("'closed' with a timestamp keeps it"):
    assertEquals(LifecycleState.from("closed", Some(ClosedAt)), Right(LifecycleState.Closed(Some(ClosedAt))))

  test("'closed' without a timestamp is still closed, because an import may not have recorded one"):
    assertEquals(LifecycleState.from("closed", None), Right(LifecycleState.Closed(None)))

  test("'open' discards a closing timestamp rather than carrying a contradiction into the domain"):
    assertEquals(LifecycleState.from("open", Some(ClosedAt)), Right(LifecycleState.Open))

  test("the spelling is matched case-insensitively and after trimming"):
    assertEquals(LifecycleState.from("  OPEN ", None), Right(LifecycleState.Open))

  test("a third state is a validation failure on the state field, not a guess"):
    assertEquals(LifecycleState.from("merged", None).left.map(_.field), Left("state"))

  test("the failure message names both spellings the caller could have received"):
    LifecycleState.from("merged", None) match
      case Left(error)  => assert(error.message.contains("open") && error.message.contains("closed"), error.message)
      case Right(value) => fail(s"expected a failure, got $value")

  test("isOpen and isClosed disagree, whatever the closing instant is"):
    assert(LifecycleState.Open.isOpen)
    assert(!LifecycleState.Open.isClosed)
    assert(LifecycleState.Closed(None).isClosed)
    assert(!LifecycleState.Closed(Some(ClosedAt)).isOpen)
