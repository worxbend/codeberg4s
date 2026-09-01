package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, JsonDecoder, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.organizations.TeamPermission
import com.worxbend.codeberg4s.repositories.access.BranchProtection
import com.worxbend.codeberg4s.repositories.access.CollaboratorAccess
import com.worxbend.codeberg4s.repositories.access.DeployKey
import com.worxbend.codeberg4s.repositories.access.TagProtection

import munit.FunSuite

/** Decoding the four response shapes this group receives.
  *
  * '''Payloads written by hand from `spec/swagger.v1.json`, not captured'''; see [[BranchProtectionDto]] for the
  * evidence note. Every one of them exercises the three wire shapes `docs/HAZARDS.md` §1 says must decode identically:
  * the key present, the key present as JSON `null`, and the key absent altogether.
  */
final class AccessResponsesSuite extends FunSuite:

  // --- branch protection ----------------------------------------------------

  test("a full branch protection decodes field for field"):
    val dto = decodeBranch(AccessResponsesSuite.BranchBody)

    assertEquals(dto.ruleName, Some("main"))
    assertEquals(dto.branchName, Some("legacy-main"))
    assertEquals(dto.enablePush, Some(true))
    assertEquals(dto.enablePushWhitelist, Some(true))
    assertEquals(dto.pushWhitelistUsernames, Vector("alice"))
    assertEquals(dto.pushWhitelistTeams, Vector("ops"))
    assertEquals(dto.pushWhitelistDeployKeys, Some(true))
    assertEquals(dto.enableMergeWhitelist, Some(true))
    assertEquals(dto.mergeWhitelistUsernames, Vector("bob"))
    assertEquals(dto.mergeWhitelistTeams, Vector("review"))
    assertEquals(dto.enableStatusCheck, Some(true))
    assertEquals(dto.statusCheckContexts, Vector("ci/build"))
    assertEquals(dto.requiredApprovals, Some(2L))
    assertEquals(dto.enableApprovalsWhitelist, Some(true))
    assertEquals(dto.approvalsWhitelistUsernames, Vector("carol"))
    assertEquals(dto.approvalsWhitelistTeams, Vector("leads"))
    assertEquals(dto.blockOnRejectedReviews, Some(true))
    assertEquals(dto.blockOnOfficialReviewRequests, Some(true))
    assertEquals(dto.blockOnOutdatedBranch, Some(true))
    assertEquals(dto.dismissStaleApprovals, Some(true))
    assertEquals(dto.ignoreStaleApprovals, Some(true))
    assertEquals(dto.requireSignedCommits, Some(true))
    assertEquals(dto.protectedFilePatterns, Some("go.mod;go.sum"))
    assertEquals(dto.unprotectedFilePatterns, Some("docs;CHANGELOG.md"))
    assertEquals(dto.applyToAdmins, Some(true))
    assertEquals(dto.createdAt, Some("2026-07-30T21:14:15+02:00"))
    assertEquals(dto.updatedAt, Some("2026-07-31T09:02:00+02:00"))

  test("the approvals whitelist is read from the singular key, which is the one Forgejo sends"):
    assertEquals(
      decodeBranch("""{"rule_name":"main","approvals_whitelist_username":["carol"]}""").approvalsWhitelistUsernames,
      Vector("carol"),
    )
    assertEquals(
      decodeBranch("""{"rule_name":"main","approvals_whitelist_usernames":["carol"]}""").approvalsWhitelistUsernames,
      Vector.empty[String],
    )

  test("JSON null and an absent key decode identically for every branch protection field"):
    assertEquals(decodeBranch(AccessResponsesSuite.NullBranchBody), decodeBranch("""{"rule_name":"main"}"""))

  test("a null whitelist becomes an empty vector rather than aborting the read"):
    assertEquals(
      decodeBranch("""{"rule_name":"main","push_whitelist_usernames":null}""").pushWhitelistUsernames,
      Vector.empty[String],
    )

  test("an absent flag is a restriction that is not in force, and an absent count is no approvals required"):
    val rule = branch("""{"rule_name":"main"}""")

    assertEquals(rule.requireSignedCommits, false)
    assertEquals(rule.applyToAdmins, false)
    assertEquals(rule.enableApprovalsWhitelist, false)
    assertEquals(rule.requiredApprovals, 0L)
    assertEquals(rule.protectedFilePatterns, None)

  test("a payload carrying only the deprecated branch_name still yields a named rule"):
    val rule = branch("""{"branch_name":"main"}""")

    assertEquals(rule.ruleName, "main")
    assertEquals(rule.legacyBranchName, Some("main"))

  test("rule_name wins over branch_name when the payload carries both"):
    assertEquals(branch("""{"rule_name":"release","branch_name":"main"}""").ruleName, "release")

  test("a rule with neither name cannot be converted, and says so at the property it wanted"):
    assertEquals(branchFailure("""{"enable_push":true}"""), Some("$.rule_name"))

  test("a rule name containing a slash is kept, because a listing must not lose a rule it cannot address"):
    assertEquals(branch("""{"rule_name":"release/next"}""").ruleName, "release/next")

  test("timestamps become instants, and the zero-time sentinel becomes absence"):
    val rule =
      branch("""{"rule_name":"main","created_at":"2026-07-30T21:14:15+02:00","updated_at":"0001-01-01T00:00:00Z"}""")

    assertEquals(rule.createdAt.map(_.toString), Some("2026-07-30T19:14:15Z"))
    assertEquals(rule.updatedAt, None)

  test("a bad element of a branch protection array reports its own position"):
    val dtos = decodeAll[BranchProtectionDto]("""[{"rule_name":"main"},{"enable_push":true}]""")

    assertEquals(
      WireModel.all(JsonPath.Root, dtos).swap.toOption.map(_.path.render),
      Some("$[1].rule_name"),
    )

  // --- tag protection -------------------------------------------------------

  test("a full tag protection decodes field for field"):
    val dto = decodeTag(AccessResponsesSuite.TagBody)

    assertEquals(dto.id, Some(17L))
    assertEquals(dto.namePattern, Some("v*"))
    assertEquals(dto.whitelistUsernames, Vector("alice"))
    assertEquals(dto.whitelistTeams, Vector("release"))
    assertEquals(dto.createdAt, Some("2026-07-30T21:14:15+02:00"))
    assertEquals(dto.updatedAt, Some("2026-07-31T09:02:00+02:00"))

  test("JSON null and an absent key decode identically for every tag protection field"):
    assertEquals(decodeTag(AccessResponsesSuite.NullTagBody), decodeTag("""{"id":17,"name_pattern":"v*"}"""))

  test("a tag protection without an id cannot be converted"):
    assertEquals(tagFailure("""{"name_pattern":"v*"}"""), Some("$.id"))

  test("a tag protection with a non-positive id is refused by the identifier's own constructor"):
    assertEquals(tagFailure("""{"id":0,"name_pattern":"v*"}"""), Some("$.id"))

  test("a tag protection without a pattern cannot be converted, because it would protect nothing"):
    assertEquals(tagFailure("""{"id":17}"""), Some("$.name_pattern"))

  test("an empty whitelist is nobody exempt, which is this rule's strictest state"):
    val rule = tag("""{"id":17,"name_pattern":"v*"}""")

    assertEquals(rule.whitelistUsernames, Vector.empty[String])
    assertEquals(rule.whitelistTeams, Vector.empty[String])

  test("a bad element of a tag protection array reports its own position"):
    val dtos = decodeAll[TagProtectionDto]("""[{"id":1,"name_pattern":"v*"},{"name_pattern":"x"}]""")

    assertEquals(WireModel.all(JsonPath.Root, dtos).swap.toOption.map(_.path.render), Some("$[1].id"))

  // --- deploy keys ----------------------------------------------------------

  test("a full deploy key decodes field for field"):
    val dto = decodeKey(AccessResponsesSuite.DeployKeyBody)

    assertEquals(dto.id, Some(4L))
    assertEquals(dto.keyId, Some(91L))
    assertEquals(dto.key, Some("ssh-ed25519 AAAAC3Nz deploy@ci"))
    assertEquals(dto.title, Some("ci runner"))
    assertEquals(dto.fingerprint, Some("SHA256:abc"))
    assertEquals(dto.url, Some("https://forge.example/api/v1/repos/forgejo/forgejo/keys/4"))
    assertEquals(dto.repository.flatMap(_.fullName), Some("forgejo/forgejo"))
    assertEquals(dto.readOnly, Some(true))
    assertEquals(dto.createdAt, Some("2026-07-30T21:14:15+02:00"))

  test("JSON null and an absent key decode identically for every deploy key field"):
    assertEquals(decodeKey(AccessResponsesSuite.NullDeployKeyBody), decodeKey(AccessResponsesSuite.MinimalDeployKey))

  test("a deploy key without an id cannot be converted"):
    assertEquals(keyFailure("""{"key":"ssh-ed25519 AAAA"}"""), Some("$.id"))

  test("a deploy key without material cannot be converted, because it authorises nothing"):
    assertEquals(keyFailure("""{"id":4}"""), Some("$.key"))

  test("an absent read_only reads as read-write, which over-states a key rather than under-stating it"):
    assertEquals(key(AccessResponsesSuite.MinimalDeployKey).isReadOnly, false)
    assertEquals(key("""{"id":4,"key":"ssh-ed25519 AAAA","read_only":true}""").isReadOnly, true)

  test("the key material is carried in the clear, because it is the public half"):
    assertEquals(key(AccessResponsesSuite.MinimalDeployKey).key, "ssh-ed25519 AAAA")
    assertEquals(key(AccessResponsesSuite.MinimalDeployKey).toString.contains("ssh-ed25519 AAAA"), true)

  test("the grant id and the underlying key id are kept apart"):
    val decoded = key(AccessResponsesSuite.DeployKeyBody)

    assertEquals(decoded.id.value, 4L)
    assertEquals(decoded.keyId, Some(91L))

  test("a failure inside the embedded repository is reported at that nested path"):
    assertEquals(keyFailure("""{"id":4,"key":"ssh-ed25519 AAAA","repository":{"name":"x"}}"""), Some("$.repository.id"))

  test("a bad element of a deploy key array reports its own position"):
    val dtos = decodeAll[DeployKeyDto]("""[{"id":1,"key":"a"},{"key":"b"}]""")

    assertEquals(WireModel.all(JsonPath.Root, dtos).swap.toOption.map(_.path.render), Some("$[1].id"))

  // --- collaborator permission ----------------------------------------------

  test("a full collaborator permission decodes field for field"):
    val dto = decodeAccess(AccessResponsesSuite.CollaboratorBody)

    assertEquals(dto.permission, Some("write"))
    assertEquals(dto.roleName, Some("Collaborator"))
    assertEquals(dto.user.flatMap(_.login), Some("alice"))

  test("JSON null and an absent key decode identically for every collaborator permission field"):
    assertEquals(
      decodeAccess(AccessResponsesSuite.NullCollaboratorBody),
      decodeAccess(AccessResponsesSuite.MinimalCollaborator),
    )

  test("the level is reported both parsed and verbatim"):
    val decoded = access(AccessResponsesSuite.CollaboratorBody)

    assertEquals(decoded.permission, Some(TeamPermission.Write))
    assertEquals(decoded.rawPermission, Some("write"))

  test("a level outside the vocabulary is kept verbatim rather than silently becoming no access"):
    val decoded = access("""{"permission":"maintainer","user":{"id":1,"login":"alice"}}""")

    assertEquals(decoded.permission, None)
    assertEquals(decoded.rawPermission, Some("maintainer"))

  test("the owner's level parses to the value the team vocabulary already names"):
    assertEquals(
      access("""{"permission":"owner","user":{"id":1,"login":"alice"}}""").permission,
      Some(TeamPermission.Owner),
    )

  test("a permission naming nobody cannot be converted"):
    assertEquals(accessFailure("""{"permission":"write"}"""), Some("$.user"))

  test("a failure inside the embedded user is reported at that nested path"):
    assertEquals(accessFailure("""{"permission":"write","user":{"login":"alice"}}"""), Some("$.user.id"))

  // --- harness --------------------------------------------------------------

  private def decodeBranch(body: String): BranchProtectionDto =
    decodeOne[BranchProtectionDto](body)

  private def decodeTag(body: String): TagProtectionDto =
    decodeOne[TagProtectionDto](body)

  private def decodeKey(body: String): DeployKeyDto =
    decodeOne[DeployKeyDto](body)

  private def decodeAccess(body: String): CollaboratorAccessDto =
    decodeOne[CollaboratorAccessDto](body)

  private def branch(body: String): BranchProtection =
    converted(decodeBranch(body).toDomain)

  private def tag(body: String): TagProtection =
    converted(decodeTag(body).toDomain)

  private def key(body: String): DeployKey =
    converted(decodeKey(body).toDomain)

  private def access(body: String): CollaboratorAccess =
    converted(decodeAccess(body).toDomain)

  private def branchFailure(body: String): Option[String] =
    decodeBranch(body).toDomain.swap.toOption.map(_.path.render)

  private def tagFailure(body: String): Option[String] =
    decodeTag(body).toDomain.swap.toOption.map(_.path.render)

  private def keyFailure(body: String): Option[String] =
    decodeKey(body).toDomain.swap.toOption.map(_.path.render)

  private def accessFailure(body: String): Option[String] =
    decodeAccess(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeOne[A: JsonDecoder](body: String): A =
    Json.decode[A](body) match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the body did not decode at ${failure.path.render}: ${failure.message}")

  private def decodeAll[A: JsonDecoder](body: String): Vector[A] =
    decodeOne[Vector[A]](body)

  private def converted[A](result: Either[DecodeFailure, A]): A =
    result match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert at ${failure.path.render}: ${failure.message}")

/** The response bodies this suite decodes, kept out of the test bodies so each test reads as one behaviour.
  *
  * All of them are hand-written from `spec/swagger.v1.json`; no endpoint in this group has a golden capture.
  */
object AccessResponsesSuite:

  val BranchBody: String =
    """{
      |  "rule_name": "main",
      |  "branch_name": "legacy-main",
      |  "enable_push": true,
      |  "enable_push_whitelist": true,
      |  "push_whitelist_usernames": ["alice"],
      |  "push_whitelist_teams": ["ops"],
      |  "push_whitelist_deploy_keys": true,
      |  "enable_merge_whitelist": true,
      |  "merge_whitelist_usernames": ["bob"],
      |  "merge_whitelist_teams": ["review"],
      |  "enable_status_check": true,
      |  "status_check_contexts": ["ci/build"],
      |  "required_approvals": 2,
      |  "enable_approvals_whitelist": true,
      |  "approvals_whitelist_username": ["carol"],
      |  "approvals_whitelist_teams": ["leads"],
      |  "block_on_rejected_reviews": true,
      |  "block_on_official_review_requests": true,
      |  "block_on_outdated_branch": true,
      |  "dismiss_stale_approvals": true,
      |  "ignore_stale_approvals": true,
      |  "require_signed_commits": true,
      |  "protected_file_patterns": "go.mod;go.sum",
      |  "unprotected_file_patterns": "docs;CHANGELOG.md",
      |  "apply_to_admins": true,
      |  "created_at": "2026-07-30T21:14:15+02:00",
      |  "updated_at": "2026-07-31T09:02:00+02:00"
      |}""".stripMargin

  /** Every property present and `null`, except the one the domain requires. */
  val NullBranchBody: String =
    """{
      |  "rule_name": "main",
      |  "branch_name": null,
      |  "enable_push": null,
      |  "enable_push_whitelist": null,
      |  "push_whitelist_usernames": null,
      |  "push_whitelist_teams": null,
      |  "push_whitelist_deploy_keys": null,
      |  "enable_merge_whitelist": null,
      |  "merge_whitelist_usernames": null,
      |  "merge_whitelist_teams": null,
      |  "enable_status_check": null,
      |  "status_check_contexts": null,
      |  "required_approvals": null,
      |  "enable_approvals_whitelist": null,
      |  "approvals_whitelist_username": null,
      |  "approvals_whitelist_teams": null,
      |  "block_on_rejected_reviews": null,
      |  "block_on_official_review_requests": null,
      |  "block_on_outdated_branch": null,
      |  "dismiss_stale_approvals": null,
      |  "ignore_stale_approvals": null,
      |  "require_signed_commits": null,
      |  "protected_file_patterns": null,
      |  "unprotected_file_patterns": null,
      |  "apply_to_admins": null,
      |  "created_at": null,
      |  "updated_at": null
      |}""".stripMargin

  val TagBody: String =
    """{
      |  "id": 17,
      |  "name_pattern": "v*",
      |  "whitelist_usernames": ["alice"],
      |  "whitelist_teams": ["release"],
      |  "created_at": "2026-07-30T21:14:15+02:00",
      |  "updated_at": "2026-07-31T09:02:00+02:00"
      |}""".stripMargin

  val NullTagBody: String =
    """{
      |  "id": 17,
      |  "name_pattern": "v*",
      |  "whitelist_usernames": null,
      |  "whitelist_teams": null,
      |  "created_at": null,
      |  "updated_at": null
      |}""".stripMargin

  val DeployKeyBody: String =
    """{
      |  "id": 4,
      |  "key_id": 91,
      |  "key": "ssh-ed25519 AAAAC3Nz deploy@ci",
      |  "title": "ci runner",
      |  "fingerprint": "SHA256:abc",
      |  "url": "https://forge.example/api/v1/repos/forgejo/forgejo/keys/4",
      |  "repository": {
      |    "id": 12,
      |    "name": "forgejo",
      |    "full_name": "forgejo/forgejo",
      |    "owner": {"id": 3, "login": "forgejo"}
      |  },
      |  "read_only": true,
      |  "created_at": "2026-07-30T21:14:15+02:00"
      |}""".stripMargin

  val MinimalDeployKey: String =
    """{"id": 4, "key": "ssh-ed25519 AAAA"}"""

  val NullDeployKeyBody: String =
    """{
      |  "id": 4,
      |  "key_id": null,
      |  "key": "ssh-ed25519 AAAA",
      |  "title": null,
      |  "fingerprint": null,
      |  "url": null,
      |  "repository": null,
      |  "read_only": null,
      |  "created_at": null
      |}""".stripMargin

  val CollaboratorBody: String =
    """{
      |  "permission": "write",
      |  "role_name": "Collaborator",
      |  "user": {"id": 1, "login": "alice"}
      |}""".stripMargin

  val MinimalCollaborator: String =
    """{"user": {"id": 1, "login": "alice"}}"""

  val NullCollaboratorBody: String =
    """{
      |  "permission": null,
      |  "role_name": null,
      |  "user": {"id": 1, "login": "alice"}
      |}""".stripMargin
