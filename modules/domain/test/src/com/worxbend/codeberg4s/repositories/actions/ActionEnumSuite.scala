package com.worxbend.codeberg4s.repositories.actions

import munit.FunSuite

/** The three closed sets of this group, and the two questions a caller asks of them.
  *
  * The wire vocabulary is pinned against `spec/swagger.v1.json`, which enumerates `status` on both the run filter and
  * the runner model. Round-tripping every case is what stops a spelling drifting between the parser and the renderer —
  * a drift that would show up as an empty page rather than as a failure.
  */
final class ActionEnumSuite extends FunSuite:

  test("every action status the spec enumerates round-trips through its wire spelling"):
    ActionStatus.values.foreach: status =>
      assertEquals(ActionStatus.parse(status.wireValue), Some(status), status.toString)

  test("the eight spec-enumerated spellings are exactly the ones parsed"):
    assertEquals(
      ActionStatus.values.toVector.map(_.wireValue),
      Vector("unknown", "waiting", "running", "success", "failure", "cancelled", "skipped", "blocked"),
    )

  test("an action status is matched case-insensitively and trimmed"):
    assertEquals(ActionStatus.parse("  Failure \n"), Some(ActionStatus.Failure))

  test("an action status outside the set is absent, not a failure and not Unknown"):
    assertEquals(ActionStatus.parse("in_progress"), None)

  test("Unknown is a value the server sends, so it parses like any other"):
    assertEquals(ActionStatus.parse("unknown"), Some(ActionStatus.Unknown))

  test("a finished status is one the run will not leave"):
    assertEquals(ActionStatus.Success.isFinished, true)
    assertEquals(ActionStatus.Failure.isFinished, true)
    assertEquals(ActionStatus.Cancelled.isFinished, true)
    assertEquals(ActionStatus.Skipped.isFinished, true)

  test("a status that might still change is not finished, Unknown included"):
    assertEquals(ActionStatus.Waiting.isFinished, false)
    assertEquals(ActionStatus.Running.isFinished, false)
    assertEquals(ActionStatus.Blocked.isFinished, false)
    assertEquals(ActionStatus.Unknown.isFinished, false)

  test("every runner status round-trips through its wire spelling"):
    RunnerStatus.values.foreach: status =>
      assertEquals(RunnerStatus.parse(status.wireValue), Some(status), status.toString)

  test("the runner statuses are exactly the three the spec enumerates"):
    assertEquals(RunnerStatus.values.toVector.map(_.wireValue), Vector("offline", "idle", "active"))

  test("a runner status outside the set is absent rather than a failure"):
    assertEquals(RunnerStatus.parse("draining"), None)

  test("a connected runner is anything but offline"):
    assertEquals(RunnerStatus.Idle.isConnected, true)
    assertEquals(RunnerStatus.Active.isConnected, true)
    assertEquals(RunnerStatus.Offline.isConnected, false)

  test("runner visibility renders the boolean Forgejo expects, widening on true"):
    assertEquals(RunnerVisibility.AllVisible.wireValue, "true")
    assertEquals(RunnerVisibility.OwnedOnly.wireValue, "false")
