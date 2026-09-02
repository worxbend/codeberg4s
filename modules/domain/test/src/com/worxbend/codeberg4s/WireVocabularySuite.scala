package com.worxbend.codeberg4s

import java.util.Locale

import com.worxbend.codeberg4s.organizations.TeamPermission
import com.worxbend.codeberg4s.repositories.access.CollaboratorPermission
import com.worxbend.codeberg4s.repositories.gitdata.CommitStatusState
import com.worxbend.codeberg4s.repositories.gitdata.GitObjectKind
import com.worxbend.codeberg4s.repositories.{CommitFileStatus, ContentKind}
import com.worxbend.codeberg4s.users.UserVisibility
import munit.FunSuite

/** The lookup every round-trippable enum's `parse` now delegates to.
  *
  * Each of those enums keeps its own suite for its own vocabulary; this one pins the behaviour they share, so that
  * normalisation is stated once here rather than re-asserted seven times.
  */
final class WireVocabularySuite extends FunSuite:

  private val vocabularies: List[(String, Array[? <: WireVocabulary])] =
    List(
      "TeamPermission"          -> TeamPermission.values,
      "UserVisibility"          -> UserVisibility.values,
      "ContentKind"             -> ContentKind.values,
      "CommitFileStatus"        -> CommitFileStatus.values,
      "CollaboratorPermission"  -> CollaboratorPermission.values,
      "GitObjectKind"           -> GitObjectKind.values,
      "CommitStatusState"       -> CommitStatusState.values,
    )

  test("a wire name maps back to the value that carries it"):
    assertEquals(WireVocabulary.parse(ContentKind.values, "dir"), Some(ContentKind.Directory))

  test("an unknown word answers None rather than failing"):
    assertEquals(WireVocabulary.parse(ContentKind.values, "fifo"), None)

  test("surrounding whitespace is trimmed before the lookup"):
    assertEquals(WireVocabulary.parse(UserVisibility.values, "  private  "), Some(UserVisibility.Private))

  test("matching is case-insensitive, because only observation fixes Forgejo's casing"):
    assertEquals(WireVocabulary.parse(CommitStatusState.values, "FAILURE"), Some(CommitStatusState.Failure))

  test("every value round-trips through its own wire name"):
    vocabularies.foreach: (name, values) =>
      val lost = values.toList.filterNot(value => WireVocabulary.parse(values, value.wireName).contains(value))
      assertEquals(lost.map(_.wireName), Nil, s"$name lost a value on the way back")

  test("no two values in one vocabulary share a wire name, which would make the lookup ambiguous"):
    vocabularies.foreach: (name, values) =>
      val names = values.toList.map(_.wireName)
      assertEquals(names.distinct, names, s"$name spells two of its values the same way")

  test("wire names are already lowercase, so a parsed value renders back to what arrived"):
    vocabularies.foreach: (_, values) =>
      values.foreach(value => assertEquals(value.wireName, value.wireName.toLowerCase(Locale.ROOT)))
