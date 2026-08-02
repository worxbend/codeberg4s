package com.worxbend.codeberg4s.pulls

import munit.FunSuite

/** [[MergeStyle]] is the only required property of a merge request body, and a wrong spelling is answered with a `405`
  * rather than being corrected. Every case therefore has its literal asserted one at a time: two of these styles
  * rewrite a branch's history and the difference between them is one hyphen.
  */
final class MergeStyleSuite extends FunSuite:

  test("merge is spelled merge"):
    assertEquals(MergeStyle.Merge.wireValue, "merge")

  test("rebase is spelled rebase, with no suffix"):
    assertEquals(MergeStyle.Rebase.wireValue, "rebase")

  test("rebase-merge is hyphenated, and is not rebase"):
    assertEquals(MergeStyle.RebaseMerge.wireValue, "rebase-merge")

  test("squash is spelled squash, not squash-merge"):
    assertEquals(MergeStyle.Squash.wireValue, "squash")

  test("fast-forward-only carries both hyphens"):
    assertEquals(MergeStyle.FastForwardOnly.wireValue, "fast-forward-only")

  test("manually-merged is hyphenated, not underscored"):
    assertEquals(MergeStyle.ManuallyMerged.wireValue, "manually-merged")

  test("the enum is exactly Forgejo's six accepted spellings, no more and no fewer"):
    assertEquals(
      MergeStyle.values.toList.map(_.wireValue).sorted,
      List("fast-forward-only", "manually-merged", "merge", "rebase", "rebase-merge", "squash"),
    )

  test("no two styles share a wire value, so asking for one cannot mean another"):
    val spellings = MergeStyle.values.toList.map(_.wireValue)

    assertEquals(spellings.distinct.length, spellings.length)

  test("every spelling is lowercase, because Forgejo matches the Do property exactly"):
    MergeStyle.values.foreach(style => assertEquals(style.wireValue, style.wireValue.toLowerCase))
