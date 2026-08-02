package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.users.Username

import munit.FunSuite

/** The command builders this group offers, and the one property they all share: nothing is stated until a caller states
  * it.
  *
  * The rendering of these commands is asserted in `modules/codec`; what is asserted here is the '''shape''' — that a
  * fresh command carries no setting at all, that each builder sets exactly the field it names, and that an empty
  * collection is a stated empty collection rather than absence. Those three together are what makes a partial edit
  * partial.
  */
final class AccessCommandsSuite extends FunSuite:

  // --- branch protection ----------------------------------------------------

  test("a fresh branch protection command states its rule name and nothing else"):
    val command = CreateBranchProtection.on(rule("main"))

    assertEquals(command.ruleName.value, "main")
    assertEquals(command.legacyBranchName, None)
    assertEquals(command.settings, BranchProtectionSettings.Unchanged)

  test("the deprecated branch name is only ever sent when the caller asked for it"):
    val branch  = orFail(BranchName.from("release/16.0"))
    val command = CreateBranchProtection.on(rule("main")).alsoNamingBranch(branch)

    assertEquals(command.legacyBranchName.map(_.value), Some("release/16.0"))

  test("an unchanged settings bag has every one of its twenty-three fields unset"):
    val settings = BranchProtectionSettings.Unchanged

    assertEquals(settings.enablePush, None)
    assertEquals(settings.enablePushWhitelist, None)
    assertEquals(settings.pushWhitelistUsernames, None)
    assertEquals(settings.pushWhitelistTeams, None)
    assertEquals(settings.pushWhitelistDeployKeys, None)
    assertEquals(settings.enableMergeWhitelist, None)
    assertEquals(settings.mergeWhitelistUsernames, None)
    assertEquals(settings.mergeWhitelistTeams, None)
    assertEquals(settings.enableStatusCheck, None)
    assertEquals(settings.statusCheckContexts, None)
    assertEquals(settings.requiredApprovals.map(_.value), None)
    assertEquals(settings.enableApprovalsWhitelist, None)
    assertEquals(settings.approvalsWhitelistUsernames, None)
    assertEquals(settings.approvalsWhitelistTeams, None)
    assertEquals(settings.blockOnRejectedReviews, None)
    assertEquals(settings.blockOnOfficialReviewRequests, None)
    assertEquals(settings.blockOnOutdatedBranch, None)
    assertEquals(settings.dismissStaleApprovals, None)
    assertEquals(settings.ignoreStaleApprovals, None)
    assertEquals(settings.requireSignedCommits, None)
    assertEquals(settings.protectedFilePatterns, None)
    assertEquals(settings.unprotectedFilePatterns, None)
    assertEquals(settings.applyToAdmins, None)

  test("each builder sets exactly the field it names and leaves its neighbours unset"):
    val settings = BranchProtectionSettings.Unchanged.requiringSignedCommits(true)

    assertEquals(settings.requireSignedCommits, Some(true))
    assertEquals(settings.applyToAdmins, None)
    assertEquals(settings.enablePush, None)

  test("stating a switch as false is not the same as leaving it unset"):
    val stated = BranchProtectionSettings.Unchanged.applyingToAdmins(false)

    assertEquals(stated.applyToAdmins, Some(false))
    assertNotEquals(stated.applyToAdmins, Option.empty[Boolean])

  test("an emptied whitelist is a stated empty whitelist, because clearing one is a real intent"):
    val cleared = BranchProtectionSettings.Unchanged.pushWhitelistedTo(Vector.empty)

    assertEquals(cleared.pushWhitelistUsernames, Some(Vector.empty[Username]))

  test("an edit command carries only its settings, because this endpoint cannot rename a rule"):
    val settings = BranchProtectionSettings.Unchanged.requiringApprovals(approvals(2L))

    assertEquals(EditBranchProtection.Nothing.settings, BranchProtectionSettings.Unchanged)
    assertEquals(EditBranchProtection.of(settings).settings.requiredApprovals.map(_.value), Some(2L))
    assertEquals(EditBranchProtection.Nothing.withSettings(settings).settings, settings)

  // --- tag protection -------------------------------------------------------

  test("a fresh tag protection command exempts nobody"):
    val command = CreateTagProtection.matching(pattern("v*"))

    assertEquals(command.namePattern.value, "v*")
    assertEquals(command.whitelistUsernames, Vector.empty[Username])
    assertEquals(command.whitelistTeams, Vector.empty[String])

  test("a tag protection create takes plain vectors, because absent and empty mean the same thing there"):
    val command = CreateTagProtection.matching(pattern("v*")).exempting(Vector(user("alice"))).exemptingTeams(
      Vector("release")
    )

    assertEquals(command.whitelistUsernames.map(_.value), Vector("alice"))
    assertEquals(command.whitelistTeams, Vector("release"))

  test("a tag protection edit takes options, because absent and empty do not mean the same thing there"):
    assertEquals(EditTagProtection.Nothing.namePattern, None)
    assertEquals(EditTagProtection.Nothing.whitelistUsernames, None)
    assertEquals(EditTagProtection.Nothing.whitelistTeams, None)

    val cleared = EditTagProtection.Nothing.exempting(Vector.empty)

    assertEquals(cleared.whitelistUsernames, Some(Vector.empty[Username]))

  test("editing only the pattern leaves both whitelists unstated"):
    val command = EditTagProtection.Nothing.matching(pattern("v1.*"))

    assertEquals(command.namePattern.map(_.value), Some("v1.*"))
    assertEquals(command.whitelistUsernames, None)
    assertEquals(command.whitelistTeams, None)

  // --- deploy keys ----------------------------------------------------------

  test("a deploy key command defaults to the API's own grant, which is read-write"):
    val command = orFail(CreateDeployKey.of("ci runner", "ssh-ed25519 AAAAC3Nz deploy@ci"))

    assertEquals(command.title, "ci runner")
    assertEquals(command.key, "ssh-ed25519 AAAAC3Nz deploy@ci")
    assertEquals(command.isReadOnly, false)

  test("read-only and read-write are both one word"):
    val command = orFail(CreateDeployKey.of("ci", "ssh-ed25519 AAAA x"))

    assertEquals(command.readOnly.isReadOnly, true)
    assertEquals(command.readOnly.readWrite.isReadOnly, false)

  test("a deploy key command trims its two required properties and refuses a blank one"):
    assertEquals(orFail(CreateDeployKey.of("  ci  ", "  ssh-ed25519 AAAA x  ")).title, "ci")
    assertEquals(orFail(CreateDeployKey.of("  ci  ", "  ssh-ed25519 AAAA x  ")).key, "ssh-ed25519 AAAA x")
    assertEquals(CreateDeployKey.of(" ", "ssh-ed25519 AAAA").swap.toOption.map(_.field), Some("deployKeyTitle"))
    assertEquals(CreateDeployKey.of("ci", "  ").swap.toOption.map(_.field), Some("deployKey"))

  test("a deploy key's interior whitespace is left alone, because it delimits the key's own parts"):
    val command = orFail(CreateDeployKey.of("ci", "ssh-ed25519  AAAA  deploy@ci"))

    assertEquals(command.key, "ssh-ed25519  AAAA  deploy@ci")

  test("an empty deploy key query states no filter at all"):
    assertEquals(DeployKeyQuery.Empty.keyId, None)
    assertEquals(DeployKeyQuery.Empty.fingerprint, None)
    assertEquals(DeployKeyQuery.Empty.forKeyId(9L).keyId, Some(9L))
    assertEquals(DeployKeyQuery.Empty.withFingerprint("SHA256:abc").fingerprint, Some("SHA256:abc"))

  // --- fixtures -------------------------------------------------------------

  private def rule(value: String): BranchRuleName =
    orFail(BranchRuleName.from(value))

  private def pattern(value: String): TagNamePattern =
    orFail(TagNamePattern.from(value))

  private def user(value: String): Username =
    orFail(Username.from(value))

  private def approvals(value: Long): ApprovalCount =
    orFail(ApprovalCount.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
