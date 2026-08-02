package com.worxbend.codeberg4s.repositories.gitdata

import munit.FunSuite

/** The small closed vocabularies of the raw-git group: the two path suffixes and the three parsed words.
  *
  * Each of these decides either what a route matches or how a response reads, and each has one spelling that is easy to
  * get wrong — `tar.gz` has a dot inside it, `recentupdate` has no separator, and `dir` is not a Git object kind at
  * all.
  */
final class GitDataVocabularySuite extends FunSuite:

  test("the gzip archive suffix carries its own dot, so the archive name has two"):
    assertEquals(ArchiveFormat.TarGz.suffix, "tar.gz")

  test("the zip and bundle suffixes are single words"):
    assertEquals(ArchiveFormat.values.map(_.suffix).toList, List("zip", "tar.gz", "bundle"))

  test("a diff and a patch are distinguished only by the path suffix"):
    assertEquals(DiffType.values.map(_.suffix).toList, List("diff", "patch"))

  test("every Git object kind parses from the word Forgejo sends"):
    val parsed = GitObjectKind.values.toList.map(kind => GitObjectKind.parse(kind.wireName))

    assertEquals(parsed, GitObjectKind.values.toList.map(Some.apply))

  test("an object kind is parsed case-insensitively and trimmed"):
    assertEquals(GitObjectKind.parse("  Commit "), Some(GitObjectKind.Commit))

  test("an unknown object kind is absence, not a failure — it decides nothing about the rest of the object"):
    assertEquals(GitObjectKind.parse("gitlink"), None)

  test("'dir' is a content type and not a Git object kind, so it does not parse here"):
    assertEquals(GitObjectKind.parse("dir"), None)

  test("every status state parses from the word Forgejo sends"):
    val parsed = CommitStatusState.values.toList.map(state => CommitStatusState.parse(state.wireValue))

    assertEquals(parsed, CommitStatusState.values.toList.map(Some.apply))

  test("error and failure are different states, because a broken check is not a negative result"):
    assertEquals(CommitStatusState.parse("error"), Some(CommitStatusState.Error))
    assertEquals(CommitStatusState.parse("failure"), Some(CommitStatusState.Failure))

  test("an unrecognised status word is absence — the wire field has no enum to constrain it"):
    assertEquals(CommitStatusState.parse("cancelled"), None)

  test("the sort words are unseparated, which is how the spec's enum writes them"):
    assertEquals(
      CommitStatusSort.values.map(_.wireValue).toList,
      List("oldest", "recentupdate", "leastupdate", "leastindex", "highestindex"),
    )
