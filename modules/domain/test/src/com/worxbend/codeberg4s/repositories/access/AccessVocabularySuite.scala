package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.organizations.TeamPermission

import munit.FunSuite

/** The identifiers, names and closed sets this group introduces.
  *
  * The subject is what each type refuses. On the surface that decides who may push, a value that should not exist is
  * cheaper to reject here than to discover from a `422` — or, worse, not to discover at all because the instance
  * accepted it and did something other than what the caller meant.
  */
final class AccessVocabularySuite extends FunSuite:

  // --- identifiers ----------------------------------------------------------

  test("a tag protection id is a positive number and reports its own field when it is not"):
    assertEquals(TagProtectionId.from(17L).map(_.value), Right(17L))
    assertEquals(TagProtectionId.from(0L).swap.toOption.map(_.field), Some("tagProtectionId"))
    assertEquals(TagProtectionId.from(-1L).swap.toOption.map(_.field), Some("tagProtectionId"))

  test("a deploy key id is a positive number and reports its own field when it is not"):
    assertEquals(DeployKeyId.from(4L).map(_.value), Right(4L))
    assertEquals(DeployKeyId.from(0L).swap.toOption.map(_.field), Some("deployKeyId"))

  test("an approval count accepts zero, because no approvals required is a real setting"):
    assertEquals(ApprovalCount.from(0L).map(_.value), Right(0L))
    assertEquals(ApprovalCount.None.value, 0L)

  test("an approval count rejects a negative, which Forgejo would answer 422 to"):
    assertEquals(ApprovalCount.from(-1L).swap.toOption.map(_.field), Some("requiredApprovals"))

  // --- branch rule names ----------------------------------------------------

  test("a branch rule name keeps the glob characters a rule is made of"):
    assertEquals(BranchRuleName.from("release-*").map(_.value), Right("release-*"))
    assertEquals(BranchRuleName.from("*").map(_.value), Right("*"))
    assertEquals(BranchRuleName.from("v?.x").map(_.value), Right("v?.x"))

  test("a branch rule name is trimmed"):
    assertEquals(BranchRuleName.from("  main  ").map(_.value), Right("main"))

  test("a branch rule name with a slash is refused, because the route matches one segment"):
    assertEquals(BranchRuleName.from("release/next").swap.toOption.map(_.field), Some("branchRuleName"))

  test("a blank or control-bearing branch rule name is refused"):
    assertEquals(BranchRuleName.from("   ").swap.toOption.map(_.field), Some("branchRuleName"))
    assertEquals(BranchRuleName.from("main\nrelease").swap.toOption.map(_.field), Some("branchRuleName"))

  // --- tag name patterns ----------------------------------------------------

  test("a tag name pattern keeps its glob and accepts a slash, because it never becomes a path segment"):
    assertEquals(TagNamePattern.from("v*").map(_.value), Right("v*"))
    assertEquals(TagNamePattern.from("release/v*").map(_.value), Right("release/v*"))

  test("a blank tag name pattern is refused, because a rule matching nothing protects nothing"):
    assertEquals(TagNamePattern.from("  ").swap.toOption.map(_.field), Some("tagNamePattern"))

  test("a control-bearing tag name pattern is refused"):
    assertEquals(TagNamePattern.from("v1\t*").swap.toOption.map(_.field), Some("tagNamePattern"))

  // --- team names -----------------------------------------------------------

  test("a team name is one path segment, trimmed"):
    assertEquals(TeamName.from(" owners ").map(_.value), Right("owners"))
    assertEquals(TeamName.from("org/owners").swap.toOption.map(_.field), Some("teamName"))
    assertEquals(TeamName.from("").swap.toOption.map(_.field), Some("teamName"))

  // --- collaborator permission ----------------------------------------------

  test("a collaborator permission spells exactly the three values the spec's enum declares"):
    assertEquals(CollaboratorPermission.Read.wireName, "read")
    assertEquals(CollaboratorPermission.Write.wireName, "write")
    assertEquals(CollaboratorPermission.Admin.wireName, "admin")
    assertEquals(CollaboratorPermission.values.length, 3)

  test("a collaborator permission parses case-insensitively and refuses what its enum does not declare"):
    assertEquals(CollaboratorPermission.parse("WRITE"), Some(CollaboratorPermission.Write))
    assertEquals(CollaboratorPermission.parse(" admin "), Some(CollaboratorPermission.Admin))
    assertEquals(CollaboratorPermission.parse("owner"), None)
    assertEquals(CollaboratorPermission.parse("none"), None)

  test("the collaborator set is narrower than the team set, which is why it is a separate type"):
    val collaboratorNames = CollaboratorPermission.values.toVector.map(_.wireName)
    val teamNames         = TeamPermission.values.toVector.map(_.wireName)

    assertEquals(collaboratorNames, Vector("read", "write", "admin"))
    assertEquals(teamNames, Vector("none", "read", "write", "admin", "owner"))
    assert(
      teamNames.exists(name => !collaboratorNames.contains(name)),
      "the two closed sets coincided, which would make the fork unjustified",
    )
