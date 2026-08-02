package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.access.ApprovalCount
import com.worxbend.codeberg4s.repositories.access.BranchProtectionSettings
import com.worxbend.codeberg4s.repositories.access.BranchRuleName
import com.worxbend.codeberg4s.repositories.access.CollaboratorPermission
import com.worxbend.codeberg4s.repositories.access.CreateBranchProtection
import com.worxbend.codeberg4s.repositories.access.CreateDeployKey
import com.worxbend.codeberg4s.repositories.access.CreateTagProtection
import com.worxbend.codeberg4s.repositories.access.EditBranchProtection
import com.worxbend.codeberg4s.repositories.access.EditTagProtection
import com.worxbend.codeberg4s.repositories.access.TagNamePattern
import com.worxbend.codeberg4s.users.Username

import munit.FunSuite

/** The five request bodies this group renders, asserted '''one property at a time'''.
  *
  * ==Why not a round trip==
  *
  * Every other request suite in this repository asserts a rendered body as one exact string, and the bodies here are
  * asserted that way too where they are small. It is not enough on its own for branch protection: a single misspelled
  * key in a twenty-five-property body is a rule Forgejo accepts with a `201`, lists back happily, and does not enforce
  * the way the caller asked. A blanket comparison against a string a previous version of this file produced would agree
  * with itself forever.
  *
  * So each property is checked against the literal name `spec/swagger.v1.json` declares for it, spelled out here rather
  * than read from [[BranchProtectionWire]] — a test that took the constant from the code under test could only ever
  * prove the code agrees with itself. The literals below are the spec's; if one of them is wrong, the spec is where to
  * look.
  *
  * '''No golden fixture backs any of this'''; see
  * [[com.worxbend.codeberg4s.repositories.access.wire.BranchProtectionDto]].
  */
final class AccessRequestsSuite extends FunSuite:

  // --- create branch protection ---------------------------------------------

  test("creating a branch protection sends the rule name under the property the spec declares"):
    val body = CreateBranchProtectionOptionDto.render(CreateBranchProtection.on(rule("main")))

    assertEquals(rendered(body, "rule_name"), "\"main\"")

  test("a create that states nothing else sends nothing else, so Forgejo's own defaults apply"):
    assertEquals(
      CreateBranchProtectionOptionDto.render(CreateBranchProtection.on(rule("main"))),
      """{"rule_name":"main"}""",
    )

  test("the deprecated branch_name is sent only when the caller asked for it"):
    val command = CreateBranchProtection.on(rule("main")).alsoNamingBranch(orFail(BranchName.from("release/16.0")))

    assertEquals(rendered(CreateBranchProtectionOptionDto.render(command), "branch_name"), "\"release/16.0\"")

  test("every property of CreateBranchProtectionOption is sent under the spelling the spec declares"):
    val body = CreateBranchProtectionOptionDto.render(
      CreateBranchProtection.on(rule("main")).withSettings(EverySetting)
    )

    assertEquals(rendered(body, "rule_name"), "\"main\"")
    assertEquals(rendered(body, "enable_push"), "true")
    assertEquals(rendered(body, "enable_push_whitelist"), "true")
    assertEquals(rendered(body, "push_whitelist_usernames"), """["alice"]""")
    assertEquals(rendered(body, "push_whitelist_teams"), """["ops"]""")
    assertEquals(rendered(body, "push_whitelist_deploy_keys"), "true")
    assertEquals(rendered(body, "enable_merge_whitelist"), "true")
    assertEquals(rendered(body, "merge_whitelist_usernames"), """["bob"]""")
    assertEquals(rendered(body, "merge_whitelist_teams"), """["review"]""")
    assertEquals(rendered(body, "enable_status_check"), "true")
    assertEquals(rendered(body, "status_check_contexts"), """["ci/build"]""")
    assertEquals(rendered(body, "required_approvals"), "2")
    assertEquals(rendered(body, "enable_approvals_whitelist"), "true")
    assertEquals(rendered(body, "approvals_whitelist_username"), """["carol"]""")
    assertEquals(rendered(body, "approvals_whitelist_teams"), """["leads"]""")
    assertEquals(rendered(body, "block_on_rejected_reviews"), "true")
    assertEquals(rendered(body, "block_on_official_review_requests"), "true")
    assertEquals(rendered(body, "block_on_outdated_branch"), "true")
    assertEquals(rendered(body, "dismiss_stale_approvals"), "true")
    assertEquals(rendered(body, "ignore_stale_approvals"), "true")
    assertEquals(rendered(body, "require_signed_commits"), "true")
    assertEquals(rendered(body, "protected_file_patterns"), "\"go.mod;go.sum\"")
    assertEquals(rendered(body, "unprotected_file_patterns"), "\"docs;CHANGELOG.md\"")
    assertEquals(rendered(body, "apply_to_admins"), "true")

  test("the approvals whitelist is singular where the push and merge whitelists are plural"):
    val body = CreateBranchProtectionOptionDto.render(
      CreateBranchProtection.on(rule("main")).withSettings(EverySetting)
    )
    val keys = ujson.read(body).obj.keys.toVector

    assert(keys.contains("approvals_whitelist_username"), s"the singular spelling was not sent: $keys")
    assert(!keys.contains("approvals_whitelist_usernames"), s"the plural spelling was sent instead: $keys")
    assert(keys.contains("push_whitelist_usernames"), s"the push whitelist lost its plural: $keys")
    assert(keys.contains("merge_whitelist_usernames"), s"the merge whitelist lost its plural: $keys")

  test("the constant the reader and both renderers share is the spec's own spelling"):
    assertEquals(BranchProtectionWire.ApprovalsWhitelistUsernames, "approvals_whitelist_username")
    assertEquals(BranchProtectionWire.PushWhitelistUsernames, "push_whitelist_usernames")
    assertEquals(BranchProtectionWire.MergeWhitelistUsernames, "merge_whitelist_usernames")

  test("a create body states no property the spec does not declare"):
    val body = CreateBranchProtectionOptionDto.render(
      CreateBranchProtection
        .on(rule("main"))
        .alsoNamingBranch(orFail(BranchName.from("main")))
        .withSettings(EverySetting)
    )

    assertEquals(ujson.read(body).obj.keys.toSet.diff(CreateProperties), Set.empty[String])
    assertEquals(CreateProperties.diff(ujson.read(body).obj.keys.toSet), Set.empty[String])

  // --- edit branch protection -----------------------------------------------

  test("an edit that states nothing renders to an empty object, which changes nothing"):
    assertEquals(EditBranchProtectionOptionDto.render(EditBranchProtection.Nothing), "{}")

  test("an edit sends only the properties the caller stated, so the rest are left as they are"):
    val command = EditBranchProtection.of(BranchProtectionSettings.Unchanged.requiringSignedCommits(true))

    assertEquals(EditBranchProtectionOptionDto.render(command), """{"require_signed_commits":true}""")

  test("an edit that states a switch as false sends false, which is a change and not a silence"):
    val command = EditBranchProtection.of(BranchProtectionSettings.Unchanged.applyingToAdmins(false))

    assertEquals(EditBranchProtectionOptionDto.render(command), """{"apply_to_admins":false}""")

  test("an edit that empties a whitelist sends an empty array, not nothing"):
    val command = EditBranchProtection.of(BranchProtectionSettings.Unchanged.pushWhitelistedTo(Vector.empty))

    assertEquals(EditBranchProtectionOptionDto.render(command), """{"push_whitelist_usernames":[]}""")

  test("every property of EditBranchProtectionOption is sent under the spelling the spec declares"):
    val body = EditBranchProtectionOptionDto.render(EditBranchProtection.of(EverySetting))

    assertEquals(rendered(body, "enable_push"), "true")
    assertEquals(rendered(body, "enable_push_whitelist"), "true")
    assertEquals(rendered(body, "push_whitelist_usernames"), """["alice"]""")
    assertEquals(rendered(body, "push_whitelist_teams"), """["ops"]""")
    assertEquals(rendered(body, "push_whitelist_deploy_keys"), "true")
    assertEquals(rendered(body, "enable_merge_whitelist"), "true")
    assertEquals(rendered(body, "merge_whitelist_usernames"), """["bob"]""")
    assertEquals(rendered(body, "merge_whitelist_teams"), """["review"]""")
    assertEquals(rendered(body, "enable_status_check"), "true")
    assertEquals(rendered(body, "status_check_contexts"), """["ci/build"]""")
    assertEquals(rendered(body, "required_approvals"), "2")
    assertEquals(rendered(body, "enable_approvals_whitelist"), "true")
    assertEquals(rendered(body, "approvals_whitelist_username"), """["carol"]""")
    assertEquals(rendered(body, "approvals_whitelist_teams"), """["leads"]""")
    assertEquals(rendered(body, "block_on_rejected_reviews"), "true")
    assertEquals(rendered(body, "block_on_official_review_requests"), "true")
    assertEquals(rendered(body, "block_on_outdated_branch"), "true")
    assertEquals(rendered(body, "dismiss_stale_approvals"), "true")
    assertEquals(rendered(body, "ignore_stale_approvals"), "true")
    assertEquals(rendered(body, "require_signed_commits"), "true")
    assertEquals(rendered(body, "protected_file_patterns"), "\"go.mod;go.sum\"")
    assertEquals(rendered(body, "unprotected_file_patterns"), "\"docs;CHANGELOG.md\"")
    assertEquals(rendered(body, "apply_to_admins"), "true")

  test("an edit body can never carry a rule name, because the spec's edit model has no such property"):
    val body = EditBranchProtectionOptionDto.render(EditBranchProtection.of(EverySetting))
    val keys = ujson.read(body).obj.keys.toSet

    assert(!keys.contains("rule_name"), s"an edit tried to rename the rule: $keys")
    assert(!keys.contains("branch_name"), s"an edit tried to rename the rule: $keys")
    assertEquals(keys, CreateProperties -- Set("rule_name", "branch_name"))

  // --- tag protection -------------------------------------------------------

  test("every property of CreateTagProtectionOption is sent under the spelling the spec declares"):
    val command = CreateTagProtection.matching(pattern("v*")).exempting(Vector(user("alice"))).exemptingTeams(
      Vector("release")
    )
    val body    = TagProtectionOptionDto.renderCreate(command)

    assertEquals(rendered(body, "name_pattern"), "\"v*\"")
    assertEquals(rendered(body, "whitelist_usernames"), """["alice"]""")
    assertEquals(rendered(body, "whitelist_teams"), """["release"]""")

  test("a tag protection create states its empty whitelists rather than omitting them"):
    assertEquals(
      TagProtectionOptionDto.renderCreate(CreateTagProtection.matching(pattern("v*"))),
      """{"name_pattern":"v*","whitelist_usernames":[],"whitelist_teams":[]}""",
    )

  test("a tag protection edit that states nothing renders to an empty object"):
    assertEquals(TagProtectionOptionDto.renderEdit(EditTagProtection.Nothing), "{}")

  test("a tag protection edit sends only what it states, so a whitelist is not cleared by accident"):
    assertEquals(
      TagProtectionOptionDto.renderEdit(EditTagProtection.Nothing.matching(pattern("v1.*"))),
      """{"name_pattern":"v1.*"}""",
    )

  test("every property of EditTagProtectionOption is sent under the spelling the spec declares"):
    val command = EditTagProtection.Nothing
      .matching(pattern("v1.*"))
      .exempting(Vector(user("alice")))
      .exemptingTeams(Vector("release"))
    val body    = TagProtectionOptionDto.renderEdit(command)

    assertEquals(rendered(body, "name_pattern"), "\"v1.*\"")
    assertEquals(rendered(body, "whitelist_usernames"), """["alice"]""")
    assertEquals(rendered(body, "whitelist_teams"), """["release"]""")

  test("a tag protection edit that empties a whitelist sends an empty array, not nothing"):
    assertEquals(
      TagProtectionOptionDto.renderEdit(EditTagProtection.Nothing.exemptingTeams(Vector.empty)),
      """{"whitelist_teams":[]}""",
    )

  // --- deploy keys ----------------------------------------------------------

  test("every property of CreateKeyOption is sent under the spelling the spec declares"):
    val body = CreateKeyOptionDto.render(orFail(CreateDeployKey.of("ci runner", "ssh-ed25519 AAAA deploy@ci")))

    assertEquals(rendered(body, "title"), "\"ci runner\"")
    assertEquals(rendered(body, "key"), "\"ssh-ed25519 AAAA deploy@ci\"")
    assertEquals(rendered(body, "read_only"), "false")

  test("the grant is always stated, so a key never gets push access from an omitted property"):
    val readWrite = CreateKeyOptionDto.render(orFail(CreateDeployKey.of("ci", "ssh-ed25519 AAAA")))
    val readOnly  = CreateKeyOptionDto.render(orFail(CreateDeployKey.of("ci", "ssh-ed25519 AAAA")).readOnly)

    assertEquals(readWrite, """{"title":"ci","key":"ssh-ed25519 AAAA","read_only":false}""")
    assertEquals(readOnly, """{"title":"ci","key":"ssh-ed25519 AAAA","read_only":true}""")

  test("a key containing a quote or a backslash cannot break out of the JSON string"):
    val body = CreateKeyOptionDto.render(orFail(CreateDeployKey.of("ci", """ssh-ed25519 AAAA "quoted\ comment""")))

    assertEquals(rendered(body, "key"), """"ssh-ed25519 AAAA \"quoted\\ comment"""")

  // --- collaborators --------------------------------------------------------

  test("adding a collaborator sends the level under the property the spec declares"):
    assertEquals(rendered(AddCollaboratorOptionDto.render(CollaboratorPermission.Write), "permission"), "\"write\"")

  test("each of the three levels renders as the spec's own enum value, and the body carries nothing else"):
    assertEquals(AddCollaboratorOptionDto.render(CollaboratorPermission.Read), """{"permission":"read"}""")
    assertEquals(AddCollaboratorOptionDto.render(CollaboratorPermission.Write), """{"permission":"write"}""")
    assertEquals(AddCollaboratorOptionDto.render(CollaboratorPermission.Admin), """{"permission":"admin"}""")

  // --- fixtures the tests render -------------------------------------------

  /** Every one of the twenty-three shared tunables, each stated with a value distinguishable from its neighbours. */
  private val EverySetting: BranchProtectionSettings = BranchProtectionSettings.Unchanged
    .pushing(true)
    .pushWhitelisting(true)
    .pushWhitelistedTo(Vector(user("alice")))
    .pushWhitelistedToTeams(Vector("ops"))
    .pushWhitelistingDeployKeys(true)
    .mergeWhitelisting(true)
    .mergeWhitelistedTo(Vector(user("bob")))
    .mergeWhitelistedToTeams(Vector("review"))
    .statusChecking(true)
    .statusCheckingContexts(Vector("ci/build"))
    .requiringApprovals(approvals(2L))
    .approvalWhitelisting(true)
    .approvalWhitelistedTo(Vector(user("carol")))
    .approvalWhitelistedToTeams(Vector("leads"))
    .blockingOnRejectedReviews(true)
    .blockingOnOfficialReviewRequests(true)
    .blockingOnOutdatedBranch(true)
    .dismissingStaleApprovals(true)
    .ignoringStaleApprovals(true)
    .requiringSignedCommits(true)
    .protectingFiles("go.mod;go.sum")
    .unprotectingFiles("docs;CHANGELOG.md")
    .applyingToAdmins(true)

  /** The twenty-five properties `definitions.CreateBranchProtectionOption` declares, transcribed from
    * `spec/swagger.v1.json`.
    */
  private val CreateProperties: Set[String] = Set(
    "rule_name",
    "branch_name",
    "enable_push",
    "enable_push_whitelist",
    "push_whitelist_usernames",
    "push_whitelist_teams",
    "push_whitelist_deploy_keys",
    "enable_merge_whitelist",
    "merge_whitelist_usernames",
    "merge_whitelist_teams",
    "enable_status_check",
    "status_check_contexts",
    "required_approvals",
    "enable_approvals_whitelist",
    "approvals_whitelist_username",
    "approvals_whitelist_teams",
    "block_on_rejected_reviews",
    "block_on_official_review_requests",
    "block_on_outdated_branch",
    "dismiss_stale_approvals",
    "ignore_stale_approvals",
    "require_signed_commits",
    "protected_file_patterns",
    "unprotected_file_patterns",
    "apply_to_admins",
  )

  private def approvals(value: Long): ApprovalCount =
    orFail(ApprovalCount.from(value))

  // --- fixtures -------------------------------------------------------------

  /** The value at `name`, rendered back to JSON so one property can be asserted without re-parsing the whole body. */
  private def rendered(body: String, name: String): String =
    ujson.read(body).objOpt.flatMap(entries => entries.get(name)) match
      case Some(value) => ujson.write(value)
      case None        => fail(s"the body carried no '$name': $body")

  private def rule(value: String): BranchRuleName =
    orFail(BranchRuleName.from(value))

  private def pattern(value: String): TagNamePattern =
    orFail(TagNamePattern.from(value))

  private def user(value: String): Username =
    orFail(Username.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
