package com.worxbend.codeberg4s.pulls

import munit.FunSuite

/** [[MergeStyle]] is the only required property of a merge request body, and a wrong spelling is answered with a `405`
  * rather than being corrected. Every case therefore has its literal asserted one at a time: two of these styles
  * rewrite a branch's history and the difference between them is one hyphen.
  *
  * The same enum is a repository's `default_merge_style`, which is why it also parses and why it carries one member the
  * merge endpoint refuses.
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

  test("rebase-update-only carries both hyphens, and is the one member that is a repository default only"):
    assertEquals(MergeStyle.RebaseUpdateOnly.wireValue, "rebase-update-only")

  test("the enum is exactly the seven spellings Forgejo's default_merge_style accepts, no more and no fewer"):
    assertEquals(
      MergeStyle.values.toList.map(_.wireValue).sorted,
      List(
        "fast-forward-only",
        "manually-merged",
        "merge",
        "rebase",
        "rebase-merge",
        "rebase-update-only",
        "squash",
      ),
    )

  test("no two styles share a wire value, so asking for one cannot mean another"):
    val spellings = MergeStyle.values.toList.map(_.wireValue)

    assertEquals(spellings.distinct.length, spellings.length)

  test("every spelling is lowercase, because Forgejo matches the Do property exactly"):
    MergeStyle.values.foreach(style => assertEquals(style.wireValue, style.wireValue.toLowerCase))

  test("every merge style round-trips through its hyphenated wire spelling"):
    MergeStyle.values.foreach(style => assertEquals(MergeStyle.parse(style.wireValue), Some(style)))

  test("parsing is case-insensitive and trims, because only the spec promises the casing"):
    assertEquals(MergeStyle.parse("  Rebase-Merge \n"), Some(MergeStyle.RebaseMerge))

  test("a value outside the set is absent, not a failure, so one new case cannot cost a whole repository"):
    assertEquals(MergeStyle.parse("cherry-pick"), None)
    assertEquals(MergeStyle.parse(""), None)
