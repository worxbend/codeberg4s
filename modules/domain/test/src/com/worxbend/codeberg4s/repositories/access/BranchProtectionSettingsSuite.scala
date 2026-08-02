package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.users.Username

import munit.FunSuite

/** The twenty-three builders of [[BranchProtectionSettings]], asserted against a settings bag that already states
  * '''every''' one of its fields.
  *
  * That is the only arrangement in which the defect this type is most exposed to can be seen. A `PATCH` sends exactly
  * what the caller stated, so a builder that reset a sibling on its way past does not fail loudly — it produces a rule
  * that still exists, is still named, and no longer protects what it did a minute earlier. Applied to
  * [[BranchProtectionSettings.Unchanged]] such a builder looks perfectly correct, because a field that was never stated
  * cannot be observed to be cleared.
  *
  * The three whitelists are the other subject. Each has a switch and a list, and the switch is what makes the list
  * consulted at all; the builders are deliberately separate so that stating a list never silently enables it.
  */
final class BranchProtectionSettingsSuite extends FunSuite:

  private val Reviewers: Vector[Username] = Vector(user("ada"), user("grace"))

  private val Maintainers: Vector[Username] = Vector(user("linus"))

  test("every branch protection builder sets its own field and leaves all twenty-two others alone"):
    val settings = populated

    assertEquals(settings.pushing(true), settings.copy(enablePush = Some(true)))
    assertEquals(settings.pushWhitelisting(true), settings.copy(enablePushWhitelist = Some(true)))
    assertEquals(settings.pushWhitelistedTo(Maintainers), settings.copy(pushWhitelistUsernames = Some(Maintainers)))
    assertEquals(
      settings.pushWhitelistedToTeams(Vector("ops")),
      settings.copy(pushWhitelistTeams = Some(Vector("ops"))),
    )
    assertEquals(settings.pushWhitelistingDeployKeys(true), settings.copy(pushWhitelistDeployKeys = Some(true)))
    assertEquals(settings.mergeWhitelisting(true), settings.copy(enableMergeWhitelist = Some(true)))
    assertEquals(settings.mergeWhitelistedTo(Maintainers), settings.copy(mergeWhitelistUsernames = Some(Maintainers)))
    assertEquals(
      settings.mergeWhitelistedToTeams(Vector("ops")),
      settings.copy(mergeWhitelistTeams = Some(Vector("ops"))),
    )
    assertEquals(settings.statusChecking(true), settings.copy(enableStatusCheck = Some(true)))
    assertEquals(
      settings.statusCheckingContexts(Vector("ci/build")),
      settings.copy(statusCheckContexts = Some(Vector("ci/build"))),
    )
    assertEquals(settings.requiringApprovals(approvals(3L)), settings.copy(requiredApprovals = Some(approvals(3L))))
    assertEquals(settings.approvalWhitelisting(true), settings.copy(enableApprovalsWhitelist = Some(true)))
    assertEquals(
      settings.approvalWhitelistedTo(Maintainers),
      settings.copy(approvalsWhitelistUsernames = Some(Maintainers)),
    )
    assertEquals(
      settings.approvalWhitelistedToTeams(Vector("ops")),
      settings.copy(approvalsWhitelistTeams = Some(Vector("ops"))),
    )
    assertEquals(settings.blockingOnRejectedReviews(true), settings.copy(blockOnRejectedReviews = Some(true)))
    assertEquals(
      settings.blockingOnOfficialReviewRequests(true),
      settings.copy(blockOnOfficialReviewRequests = Some(true)),
    )
    assertEquals(settings.blockingOnOutdatedBranch(true), settings.copy(blockOnOutdatedBranch = Some(true)))
    assertEquals(settings.dismissingStaleApprovals(true), settings.copy(dismissStaleApprovals = Some(true)))
    assertEquals(settings.ignoringStaleApprovals(true), settings.copy(ignoreStaleApprovals = Some(true)))
    assertEquals(settings.requiringSignedCommits(true), settings.copy(requireSignedCommits = Some(true)))
    assertEquals(settings.protectingFiles("*.lock"), settings.copy(protectedFilePatterns = Some("*.lock")))
    assertEquals(settings.unprotectingFiles("docs/**"), settings.copy(unprotectedFilePatterns = Some("docs/**")))
    assertEquals(settings.applyingToAdmins(true), settings.copy(applyToAdmins = Some(true)))

  test("stating a whitelist does not switch it on, because an inert list is the caller's decision to make"):
    val stated = BranchProtectionSettings.Unchanged
      .pushWhitelistedTo(Reviewers)
      .mergeWhitelistedTo(Reviewers)
      .approvalWhitelistedTo(Reviewers)

    assertEquals(stated.enablePushWhitelist, None)
    assertEquals(stated.enableMergeWhitelist, None)
    assertEquals(stated.enableApprovalsWhitelist, None)

  test("switching a whitelist on does not invent its list, so an enabled whitelist stays as the instance has it"):
    val switched = BranchProtectionSettings.Unchanged
      .pushWhitelisting(true)
      .mergeWhitelisting(true)
      .approvalWhitelisting(true)

    assertEquals(switched.pushWhitelistUsernames, None)
    assertEquals(switched.mergeWhitelistUsernames, None)
    assertEquals(switched.approvalsWhitelistUsernames, None)

  test("the three whitelists are three fields, so stating the push one leaves merge and approvals alone"):
    val stated = BranchProtectionSettings.Unchanged.pushWhitelistedTo(Reviewers)

    assertEquals(stated.pushWhitelistUsernames.map(_.map(_.value)), Some(Vector("ada", "grace")))
    assertEquals(stated.mergeWhitelistUsernames, None)
    assertEquals(stated.approvalsWhitelistUsernames, None)

  test("a user whitelist and a team whitelist are separate lists, not two ways of writing one"):
    val stated = BranchProtectionSettings.Unchanged.pushWhitelistedTo(Reviewers).pushWhitelistedToTeams(Vector("ops"))

    assertEquals(stated.pushWhitelistUsernames.map(_.length), Some(2))
    assertEquals(stated.pushWhitelistTeams, Some(Vector("ops")))

  test("deploy keys bypassing the push whitelist is its own switch, and not implied by enabling the whitelist"):
    val stated = BranchProtectionSettings.Unchanged.pushWhitelisting(true)

    assertEquals(stated.pushWhitelistDeployKeys, None)
    assertEquals(stated.pushWhitelistingDeployKeys(false).pushWhitelistDeployKeys, Some(false))

  test("stating a switch as false is a request to turn it off, and it survives the builders that follow"):
    val relaxed = BranchProtectionSettings.Unchanged
      .requiringSignedCommits(false)
      .applyingToAdmins(true)
      .blockingOnRejectedReviews(false)

    assertEquals(relaxed.requireSignedCommits, Some(false))
    assertEquals(relaxed.blockOnRejectedReviews, Some(false))
    assertEquals(relaxed.applyToAdmins, Some(true))

  test("dismissing and ignoring stale approvals are different settings, because they differ in what is kept"):
    val stated = BranchProtectionSettings.Unchanged.dismissingStaleApprovals(true)

    assertEquals(stated.dismissStaleApprovals, Some(true))
    assertEquals(stated.ignoreStaleApprovals, None)
    assertEquals(BranchProtectionSettings.Unchanged.ignoringStaleApprovals(true).dismissStaleApprovals, None)

  test("the protected and unprotected file globs are separate lists, so a carve-out cannot erase the rule"):
    val stated = BranchProtectionSettings.Unchanged.protectingFiles("go.sum;*.lock").unprotectingFiles("docs/**")

    assertEquals(stated.protectedFilePatterns, Some("go.sum;*.lock"))
    assertEquals(stated.unprotectedFilePatterns, Some("docs/**"))

  test("requiring no approvals at all is a stated zero rather than an unstated field"):
    val stated = BranchProtectionSettings.Unchanged.requiringApprovals(ApprovalCount.None)

    assertEquals(stated.requiredApprovals.map(_.value), Some(0L))
    assertEquals(BranchProtectionSettings.Unchanged.requiredApprovals, None)

  test("a settings bag built one builder at a time states exactly the fields the builders named"):
    val settings = BranchProtectionSettings.Unchanged
      .pushing(false)
      .requiringApprovals(approvals(2L))
      .applyingToAdmins(true)

    assertEquals(settings.enablePush, Some(false))
    assertEquals(settings.requiredApprovals.map(_.value), Some(2L))
    assertEquals(settings.applyToAdmins, Some(true))
    assertEquals(settings.enableStatusCheck, None)
    assertEquals(settings.statusCheckContexts, None)

  private def populated: BranchProtectionSettings =
    BranchProtectionSettings(
      enablePush                    = Some(false),
      enablePushWhitelist           = Some(false),
      pushWhitelistUsernames        = Some(Reviewers),
      pushWhitelistTeams            = Some(Vector("release-managers")),
      pushWhitelistDeployKeys       = Some(false),
      enableMergeWhitelist          = Some(false),
      mergeWhitelistUsernames       = Some(Reviewers),
      mergeWhitelistTeams           = Some(Vector("release-managers")),
      enableStatusCheck             = Some(false),
      statusCheckContexts           = Some(Vector("ci/test")),
      requiredApprovals             = Some(approvals(1L)),
      enableApprovalsWhitelist      = Some(false),
      approvalsWhitelistUsernames   = Some(Reviewers),
      approvalsWhitelistTeams       = Some(Vector("release-managers")),
      blockOnRejectedReviews        = Some(false),
      blockOnOfficialReviewRequests = Some(false),
      blockOnOutdatedBranch         = Some(false),
      dismissStaleApprovals         = Some(false),
      ignoreStaleApprovals          = Some(false),
      requireSignedCommits          = Some(false),
      protectedFilePatterns         = Some("*.pem"),
      unprotectedFilePatterns       = Some("README.md"),
      applyToAdmins                 = Some(false),
    )

  private def approvals(value: Long): ApprovalCount = orFail(ApprovalCount.from(value))

  private def user(value: String): Username = orFail(Username.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
