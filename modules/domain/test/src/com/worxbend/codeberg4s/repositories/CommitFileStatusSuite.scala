package com.worxbend.codeberg4s.repositories

import munit.FunSuite

/** [[CommitFileStatus]] is read from Forgejo's own status letters and only one of its values — `changed` in
  * `golden/pull/files-list.json`, `modified` in `golden/repository/commits-list.json` — has ever been observed. The
  * rest are transcribed from Forgejo's source, so each spelling is asserted individually; a typo in one of them would
  * make that status silently unparseable.
  */
final class CommitFileStatusSuite extends FunSuite:

  test("added is spelled added"):
    assertEquals(CommitFileStatus.Added.wireName, "added")

  test("modified is spelled modified, the value the commits fixture shows"):
    assertEquals(CommitFileStatus.Modified.wireName, "modified")

  test("removed is spelled removed, not deleted"):
    assertEquals(CommitFileStatus.Removed.wireName, "removed")

  test("renamed is spelled renamed"):
    assertEquals(CommitFileStatus.Renamed.wireName, "renamed")

  test("copied is spelled copied"):
    assertEquals(CommitFileStatus.Copied.wireName, "copied")

  test("changed is spelled changed, the value the pull files fixture shows"):
    assertEquals(CommitFileStatus.Changed.wireName, "changed")

  test("unchanged is spelled unchanged, and is distinct from changed"):
    assertEquals(CommitFileStatus.Unchanged.wireName, "unchanged")
    assertNotEquals(CommitFileStatus.Unchanged.wireName, CommitFileStatus.Changed.wireName)

  test("the enum is exactly the seven statuses Forgejo derives from Git"):
    assertEquals(
      CommitFileStatus.values.toList.map(_.wireName).sorted,
      List("added", "changed", "copied", "modified", "removed", "renamed", "unchanged"),
    )

  test("every spelling is lowercase, unlike ReviewState on the same API"):
    CommitFileStatus.values.foreach(status => assertEquals(status.wireName, status.wireName.toLowerCase))

  test("every status round-trips from its own wire spelling"):
    val roundTripped = CommitFileStatus.values.toList.map(status => CommitFileStatus.parse(status.wireName))

    assertEquals(roundTripped, CommitFileStatus.values.toList.map(Some.apply))

  test("the two statuses the golden fixtures actually show parse to the cases they name"):
    assertEquals(CommitFileStatus.parse("modified"), Some(CommitFileStatus.Modified))
    assertEquals(CommitFileStatus.parse("changed"), Some(CommitFileStatus.Changed))

  test("parsing is case-insensitive and trims, because only observation claims the casing"):
    assertEquals(CommitFileStatus.parse("  Added "), Some(CommitFileStatus.Added))
    assertEquals(CommitFileStatus.parse("REMOVED"), Some(CommitFileStatus.Removed))

  test("a status this library has not seen is absent rather than costing the caller the whole commit"):
    assertEquals(CommitFileStatus.parse("typechanged"), None)

  test("a blank status is absent"):
    assertEquals(CommitFileStatus.parse("   "), None)

  test("a near miss is absent rather than being rounded to the case it resembles"):
    assertEquals(CommitFileStatus.parse("deleted"), None)
    assertEquals(CommitFileStatus.parse("add"), None)
