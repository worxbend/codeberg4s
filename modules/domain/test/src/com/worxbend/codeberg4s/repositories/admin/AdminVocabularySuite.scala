package com.worxbend.codeberg4s.repositories.admin

import munit.FunSuite

/** The six closed sets this group models, and the one rule they all share: every case round-trips through its own wire
  * spelling, and anything outside the set is `None` rather than a failure.
  *
  * The round trip is asserted over `values` rather than case by case, so a case added later without a
  * [[ActivityOperation.parse]] arm fails this suite instead of silently decoding as absent.
  */
final class AdminVocabularySuite extends FunSuite:

  test("every object format round-trips through its wire spelling"):
    ObjectFormat.values.foreach(format => assertEquals(ObjectFormat.parse(format.wireValue), Some(format)))

  test("every trust model round-trips, including the unhyphenated compound one"):
    TrustModel.values.foreach(model => assertEquals(TrustModel.parse(model.wireValue), Some(model)))

  test("the compound trust model is one word on the wire, as Forgejo spells it"):
    assertEquals(TrustModel.CollaboratorCommitter.wireValue, "collaboratorcommitter")

  test("every merge style round-trips through its hyphenated wire spelling"):
    MergeStyle.values.foreach(style => assertEquals(MergeStyle.parse(style.wireValue), Some(style)))

  test("every update style round-trips"):
    UpdateStyle.values.foreach(style => assertEquals(UpdateStyle.parse(style.wireValue), Some(style)))

  test("every migration service round-trips"):
    MigrationService.values.foreach(service => assertEquals(MigrationService.parse(service.wireValue), Some(service)))

  test("every activity operation round-trips through its snake_case wire spelling"):
    ActivityOperation.values.foreach(op => assertEquals(ActivityOperation.parse(op.wireValue), Some(op)))

  test("all twenty-seven activity operations the spec enumerates are modelled"):
    assertEquals(ActivityOperation.values.length, 27)

  test("parsing is case-insensitive, because only the spec promises the casing"):
    assertEquals(MergeStyle.parse("Rebase-Merge"), Some(MergeStyle.RebaseMerge))

  test("parsing trims, because a value read from configuration often has not been"):
    assertEquals(MigrationService.parse("  gitlab \n"), Some(MigrationService.GitLab))

  test("a value outside the set is absent, not a failure, so one new case cannot cost a whole page"):
    assertEquals(ActivityOperation.parse("teleported_repo"), None)
    assertEquals(MergeStyle.parse("cherry-pick"), None)
    assertEquals(ObjectFormat.parse("sha512"), None)

  test("a trust model spelled the way a reader would guess is refused, since Forgejo spells it as one word"):
    assertEquals(TrustModel.parse("collaborator-committer"), None)
    assertEquals(TrustModel.parse("collaborator_committer"), None)
    assertEquals(TrustModel.parse(""), None)

  test("an update style is a narrower set than a merge style, so a merge spelling is not an update style"):
    assertEquals(UpdateStyle.parse("rebase-merge"), None)
    assertEquals(UpdateStyle.parse("squash"), None)
    assertEquals(UpdateStyle.values.length, 2)

  test("a forge Forgejo cannot migrate from is absent rather than a near miss of one it can"):
    assertEquals(MigrationService.parse("bitbucket"), None)
    assertEquals(MigrationService.parse("gitbucket"), Some(MigrationService.GitBucket))

  test("a push spells itself commit_repo, which is the one name a reader would not guess"):
    assertEquals(ActivityOperation.CommitRepo.wireValue, "commit_repo")
