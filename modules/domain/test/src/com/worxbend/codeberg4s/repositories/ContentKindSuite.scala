package com.worxbend.codeberg4s.repositories

import munit.FunSuite

/** [[ContentKind]] decides which other fields of a content entry mean anything, so unlike the descriptive enums it is
  * not allowed to be guessed. The spelling that matters most is `dir`: the wire abbreviates it and the domain does not,
  * and `golden/repository/contents-dir.json` is the ground truth for both spellings it carries.
  */
final class ContentKindSuite extends FunSuite:

  test("a blob is spelled file"):
    assertEquals(ContentKind.File.wireName, "file")

  test("a tree is spelled dir on the wire, abbreviated, and not directory"):
    assertEquals(ContentKind.Directory.wireName, "dir")

  test("a symlink is spelled symlink, one word"):
    assertEquals(ContentKind.Symlink.wireName, "symlink")

  test("a gitlink is spelled submodule"):
    assertEquals(ContentKind.Submodule.wireName, "submodule")

  test("the enum is exactly the four types the contents endpoint declares"):
    assertEquals(ContentKind.values.toList.map(_.wireName).sorted, List("dir", "file", "submodule", "symlink"))

  test("every kind round-trips from its own wire spelling"):
    val roundTripped = ContentKind.values.toList.map(kind => ContentKind.parse(kind.wireName))

    assertEquals(roundTripped, ContentKind.values.toList.map(Some.apply))

  test("both spellings golden/repository/contents-dir.json shows parse to the cases they name"):
    assertEquals(ContentKind.parse("file"), Some(ContentKind.File))
    assertEquals(ContentKind.parse("dir"), Some(ContentKind.Directory))

  test("the unabbreviated spelling is not accepted, because the wire never sends it"):
    assertEquals(ContentKind.parse("directory"), None)

  test("parsing is case-insensitive and trims"):
    assertEquals(ContentKind.parse("  Dir "), Some(ContentKind.Directory))
    assertEquals(ContentKind.parse("SUBMODULE"), Some(ContentKind.Submodule))

  test("an unrecognised type is absent, which the DTO turns into a decoding failure rather than a wrong entry"):
    assertEquals(ContentKind.parse("commit"), None)

  test("a blank type is absent"):
    assertEquals(ContentKind.parse("   "), None)
