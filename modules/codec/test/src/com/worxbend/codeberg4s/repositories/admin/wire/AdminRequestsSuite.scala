package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.organizations.TeamId
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.ContentPath
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.admin.AvatarImage
import com.worxbend.codeberg4s.repositories.admin.ChangeFiles
import com.worxbend.codeberg4s.repositories.admin.CommitDates
import com.worxbend.codeberg4s.repositories.admin.CommitIdentity
import com.worxbend.codeberg4s.repositories.admin.CommitOptions
import com.worxbend.codeberg4s.repositories.admin.CreateBranch
import com.worxbend.codeberg4s.repositories.admin.CreateFile
import com.worxbend.codeberg4s.repositories.admin.CreatePushMirror
import com.worxbend.codeberg4s.repositories.admin.CreateRepository
import com.worxbend.codeberg4s.repositories.admin.DeleteFile
import com.worxbend.codeberg4s.repositories.admin.EditRepository
import com.worxbend.codeberg4s.repositories.admin.ExternalWikiSettings
import com.worxbend.codeberg4s.repositories.admin.FileBytes
import com.worxbend.codeberg4s.repositories.admin.FileOperation
import com.worxbend.codeberg4s.repositories.admin.InternalTrackerSettings
import com.worxbend.codeberg4s.repositories.admin.MergeStyle
import com.worxbend.codeberg4s.repositories.admin.MigrateRepository
import com.worxbend.codeberg4s.repositories.admin.MigrationService
import com.worxbend.codeberg4s.repositories.admin.ObjectFormat
import com.worxbend.codeberg4s.repositories.admin.RemoteCredential
import com.worxbend.codeberg4s.repositories.admin.RenameBranch
import com.worxbend.codeberg4s.repositories.admin.TransferRepository
import com.worxbend.codeberg4s.repositories.admin.UpdateFile

import munit.FunSuite

import java.time.Instant

/** The request bodies this group renders.
  *
  * These are asserted as exact strings rather than as re-parsed objects, for the reason `ActionOptionDtoSuite` gives:
  * the point of a renderer is the bytes. A key that moved, an optional field that started being emitted, or an input
  * order that stopped being stable is a change in what the instance receives, and a round trip through a parser would
  * hide all three.
  *
  * '''Every expected body was derived from `spec/swagger.v1.json`.''' No golden capture of any request in this group
  * exists.
  */
final class AdminRequestsSuite extends FunSuite:

  // --- create and edit ------------------------------------------------------

  test("a fresh create sends the name and the three flags, and nothing else"):
    assertEquals(
      RepositoryOptionDto.renderCreate(CreateRepository.named(repo("codeberg4s"))),
      """{"name":"codeberg4s","private":false,"auto_init":false,"template":false}""",
    )

  test("a create emits every optional field the caller set, in the order the renderer declares"):
    val command = CreateRepository
      .named(repo("codeberg4s"))
      .asPrivate
      .initialised
      .describedAs("a client")
      .defaultingTo(branch("main"))
      .withLicense("MIT")
      .using(ObjectFormat.Sha256)

    assertEquals(
      RepositoryOptionDto.renderCreate(command),
      """{"name":"codeberg4s","private":true,"auto_init":true,"template":false,"description":"a client",""" +
        """"default_branch":"main","license":"MIT","object_format_name":"sha256"}""",
    )

  test("an empty edit renders as an empty object, which changes nothing"):
    assertEquals(RepositoryOptionDto.renderEdit(EditRepository.Empty), "{}")

  test("an edit emits only the flags the caller set, so an unmentioned unit is not turned off"):
    assertEquals(
      RepositoryOptionDto.renderEdit(EditRepository.Empty.withIssues(false)),
      """{"has_issues":false}""",
    )

  test("an edit renders a merge style by its hyphenated wire spelling"):
    assertEquals(
      RepositoryOptionDto.renderEdit(EditRepository.Empty.mergingBy(MergeStyle.FastForwardOnly)),
      """{"default_merge_style":"fast-forward-only"}""",
    )

  test("an edit renders the nested tracker objects only when they were set"):
    val command = EditRepository.Empty
      .hostingWikiAt(ExternalWikiSettings(Some("https://wiki.test")))
      .trackingIssuesWith(InternalTrackerSettings(Some(true), None, None))

    assertEquals(
      RepositoryOptionDto.renderEdit(command),
      """{"external_wiki":{"external_wiki_url":"https://wiki.test"},""" +
        """"internal_tracker":{"enable_time_tracker":true}}""",
    )

  test("a rename is one key, and it is the key the edit model uses"):
    assertEquals(RepositoryOptionDto.renderEdit(EditRepository.Empty.renamedTo(repo("moved"))), """{"name":"moved"}""")

  // --- migrate --------------------------------------------------------------

  test("a fresh migrate sends the two required properties and the eight flags"):
    assertEquals(
      MigrateRepoOptionsDto.render(MigrateRepository.from("https://github.com/a/b.git", repo("b"))),
      """{"clone_addr":"https://github.com/a/b.git","repo_name":"b","private":false,"mirror":false,"lfs":false,""" +
        """"issues":false,"labels":false,"milestones":false,"pull_requests":false,"releases":false,"wiki":false}""",
    )

  test("a migrate carries the remote credential in the body, which is the only place it may appear"):
    val command = MigrateRepository
      .from("https://github.com/a/b.git", repo("b"))
      .usingService(MigrationService.GitHub)
      .authenticatedAs("octocat", credential("ghp_SECRET"))

    val body = MigrateRepoOptionsDto.render(command)

    assert(body.contains(""""auth_username":"octocat""""), body)
    assert(body.contains(""""auth_password":"ghp_SECRET""""), body)

  test("a migrate emits both credential keys when both were set, because Forgejo decides which it wants"):
    val command = MigrateRepository
      .from("https://github.com/a/b.git", repo("b"))
      .authenticatedAs("octocat", credential("pw"))
      .authenticatedWith(credential("tok"))

    val body = MigrateRepoOptionsDto.render(command)

    assert(body.contains(""""auth_password":"pw""""), body)
    assert(body.contains(""""auth_token":"tok""""), body)

  test("a credential is escaped into the JSON string and cannot break out of it"):
    val command = MigrateRepository
      .from("https://example.test/a.git", repo("a"))
      .authenticatedWith(credential("line\nwith \"quotes\" and \\ backslash"))

    assert(
      MigrateRepoOptionsDto.render(command).contains(""""auth_token":"line\nwith \"quotes\" and \\ backslash""""),
      "the credential must be escaped, not concatenated",
    )

  test("a command that carries no credential emits neither credential key"):
    val body = MigrateRepoOptionsDto.render(MigrateRepository.from("https://example.test/a.git", repo("a")))

    assert(!body.contains("auth_password"), body)
    assert(!body.contains("auth_token"), body)

  // --- transfer and push mirror --------------------------------------------

  test("a transfer sends the new owner alone when no team was granted"):
    assertEquals(TransferRepoOptionDto.render(TransferRepository.to(owner("forgejo"))), """{"new_owner":"forgejo"}""")

  test("a transfer sends team ids as an array, in the order they were granted"):
    val command = TransferRepository.to(owner("forgejo")).grantedTo(team(7L)).grantedTo(team(9L))

    assertEquals(TransferRepoOptionDto.render(command), """{"new_owner":"forgejo","team_ids":[7,9]}""")

  test("a fresh push mirror sends the address and the two flags"):
    assertEquals(
      PushMirrorOptionDto.render(CreatePushMirror.to("https://example.test/a/b.git")),
      """{"remote_address":"https://example.test/a/b.git","sync_on_commit":false,"use_ssh":false}""",
    )

  test("a push mirror carries its remote password in the body under the key the spec names"):
    val command = CreatePushMirror.to("https://example.test/a.git").authenticatedAs("bot", credential("pw"))

    assert(PushMirrorOptionDto.render(command).contains(""""remote_password":"pw""""))

  // --- branches and avatar --------------------------------------------------

  test("a branch create sends new_branch_name, and old_ref_name only when a start point was named"):
    assertEquals(
      BranchOptionDto.renderCreate(CreateBranch.named(branch("feature/x"))),
      """{"new_branch_name":"feature/x"}""",
    )
    assertEquals(
      BranchOptionDto.renderCreate(CreateBranch.named(branch("hotfix")).startingAt("v1.2.0")),
      """{"new_branch_name":"hotfix","old_ref_name":"v1.2.0"}""",
    )

  test("a branch rename sends 'name', which is deliberately not the create's spelling"):
    assertEquals(BranchOptionDto.renderRename(RenameBranch(branch("renamed"))), """{"name":"renamed"}""")

  test("an avatar is base64 in a JSON object, not a multipart part"):
    assertEquals(AvatarOptionDto.render(avatar("aGk=")), """{"image":"aGk="}""")

  // --- contents -------------------------------------------------------------

  test("a file create sends the base64 content and the two commit flags"):
    assertEquals(
      FileOptionsDto.renderCreate(CreateFile.of(FileBytes.ofText("hello"))),
      """{"content":"aGVsbG8=","signoff":false,"force_overwrite_new_branch":false}""",
    )

  test("a file create emits the commit settings the caller set"):
    val command = CreateFile
      .of(FileBytes.ofText("hello"))
      .committing(CommitOptions.Default.on(branch("main")).describedAs("add a file").signedOff)

    assertEquals(
      FileOptionsDto.renderCreate(command),
      """{"content":"aGVsbG8=","signoff":true,"force_overwrite_new_branch":false,"branch":"main",""" +
        """"message":"add a file"}""",
    )

  test("a file update always sends the sha guard, which is what makes a concurrent edit a conflict"):
    assertEquals(
      FileOptionsDto.renderUpdate(UpdateFile.of(FileBytes.ofText("hi"), sha("abcd1234"))),
      """{"content":"aGk=","sha":"abcd1234","signoff":false,"force_overwrite_new_branch":false}""",
    )

  test("a file update sends from_path only when it is also moving the file"):
    val command = UpdateFile.of(FileBytes.ofText("hi"), sha("abcd1234")).movedFrom(path("old/name.txt"))

    assert(FileOptionsDto.renderUpdate(command).contains(""""from_path":"old/name.txt""""))

  test("a file delete sends the sha guard and the commit settings, and no content"):
    val body = FileOptionsDto.renderDelete(DeleteFile.of(sha("abcd1234")))

    assertEquals(body, """{"sha":"abcd1234","signoff":false,"force_overwrite_new_branch":false}""")

  test("commit identities render as the two-field Identity the spec declares"):
    val command = CreateFile
      .of(FileBytes.ofText(""))
      .committing(CommitOptions.Default.authoredBy(CommitIdentity(Some("Ada"), Some("ada@example.test"))))

    assert(FileOptionsDto.renderCreate(command).contains(""""author":{"name":"Ada","email":"ada@example.test"}"""))

  test("commit dates are omitted entirely when neither was set, because an empty object is the Go zero time"):
    assert(!FileOptionsDto.renderCreate(CreateFile.of(FileBytes.ofText(""))).contains("dates"))

  test("commit dates render as RFC-3339 with second precision when they were set"):
    val when    = CommitDates(Some(Instant.parse("2026-08-01T06:00:00Z")), None)
    val command = CreateFile.of(FileBytes.ofText("")).committing(CommitOptions.Default.dated(when))

    assert(FileOptionsDto.renderCreate(command).contains(""""dates":{"author":"2026-08-01T06:00:00Z"}"""))

  test("a batch derives each operation's word from its case and keeps the caller's order"):
    val batch = ChangeFiles
      .of(FileOperation.Create(path("a.txt"), FileBytes.ofText("a")))
      .and(FileOperation.Delete(path("b.txt"), sha("bbbb")))

    assertEquals(
      FileOptionsDto.renderChange(batch),
      """{"files":[{"operation":"create","path":"a.txt","content":"YQ=="},""" +
        """{"operation":"delete","path":"b.txt","sha":"bbbb"}],""" +
        """"signoff":false,"force_overwrite_new_branch":false}""",
    )

  test("a batch update carries its own sha guard, exactly as the single-file update does"):
    val batch = ChangeFiles.of(FileOperation.Update(path("a.txt"), FileBytes.ofText("a"), sha("aaaa"), None))

    assert(FileOptionsDto.renderChange(batch).contains(""""operation":"update","path":"a.txt","content":"YQ==","sha":"aaaa""""))

  private def credential(value: String): RemoteCredential =
    RemoteCredential.from(value) match
      case Right(secret) => secret
      case Left(error)   => fail(s"invalid fixture: ${error.field} ${error.message}")

  private def avatar(value: String): AvatarImage = orFail(AvatarImage.ofBase64(value))

  private def repo(value: String): RepoName = orFail(RepoName.from(value))

  private def owner(value: String): Owner = orFail(Owner.from(value))

  private def branch(value: String): BranchName = orFail(BranchName.from(value))

  private def path(value: String): ContentPath = orFail(ContentPath.from(value))

  private def sha(value: String): CommitSha = orFail(CommitSha.from(value))

  private def team(value: Long): TeamId = orFail(TeamId.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
