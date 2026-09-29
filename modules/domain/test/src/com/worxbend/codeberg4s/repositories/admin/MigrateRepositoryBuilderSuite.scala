package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.organizations.TeamId

import munit.FunSuite

/** What [[MigrateRepository]]'s builders change, and the two that deliberately change more than one field.
  *
  * Every builder is applied to a command with '''every''' property already set, so a builder that dropped a sibling
  * fails here rather than sending a quietly different request. That matters more on this command than on most: the
  * siblings include the credential for somebody else's forge, and a builder that cleared it would turn an authenticated
  * migration into a mysterious `401` from a host this library has never heard of.
  *
  * The two multi-field builders are asserted for what they set '''together''': `authenticatedAs` is a username and a
  * credential, and `withLfsFrom` is an endpoint '''and''' the switch that makes the endpoint mean anything. The two
  * authentication builders are also asserted for what they '''replace''': a password and a token are alternatives, and
  * a command can hold one of them at a time.
  */
final class MigrateRepositoryBuilderSuite extends FunSuite:

  private val Address: String = "https://github.com/forgejo/forgejo.git"

  test("every migrate builder sets its own field and leaves every sibling alone"):
    val command = populated

    assertEquals(command.ownedBy(owner("worxbend")), command.copy(repoOwner = Some(owner("worxbend"))))
    assertEquals(command.describedAs("replaced"), command.copy(description = Some("replaced")))
    assertEquals(
      command.usingService(MigrationService.GitLab),
      command.copy(service = Some(MigrationService.GitLab)),
    )
    assertEquals(command.asPrivate, command.copy(isPrivate = true))
    assertEquals(command.asMirror, command.copy(isMirror = true))
    assertEquals(command.every("24h0m0s"), command.copy(mirrorInterval = Some("24h0m0s")))
    assertEquals(command.withLfs, command.copy(lfs = true))
    assertEquals(command.withIssues, command.copy(includesIssues = true))
    assertEquals(command.withLabels, command.copy(includesLabels = true))
    assertEquals(command.withMilestones, command.copy(includesMilestones = true))
    assertEquals(command.withPullRequests, command.copy(includesPullRequests = true))
    assertEquals(command.withReleases, command.copy(includesReleases = true))
    assertEquals(command.withWiki, command.copy(includesWiki = true))

  test("authenticating as a user sets the username and the credential together, and nothing else"):
    val command   = populated
    val secret    = credential("gitlab-token")
    val requested = command.authenticatedAs("octocat", secret)

    assertEquals(requested, command.copy(auth = Some(RemoteAuth.Password("octocat", secret))))

  test("authenticating with a token sets the token and nothing else"):
    val command = populated
    val secret  = credential("ghp_token")

    assertEquals(command.authenticatedWith(secret), command.copy(auth = Some(RemoteAuth.Token(secret))))

  test("a password and a token are alternatives, so the later builder replaces the earlier"):
    // The previous shape kept them in separate fields and sent both when both
    // had been set, leaving Forgejo to choose. One RemoteAuth field is what
    // makes a command that asks for both impossible to build.
    val password = credential("hunter2")
    val token    = credential("ghp_token")
    val start    = MigrateRepository.from(Address, repo("forgejo"))

    assertEquals(start.authenticatedAs("octocat", password).authenticatedWith(token).auth, Some(RemoteAuth.Token(token)))
    assertEquals(
      start.authenticatedWith(token).authenticatedAs("octocat", password).auth,
      Some(RemoteAuth.Password("octocat", password)),
    )

  test("naming an LFS endpoint also turns LFS on, because the endpoint alone would be a silent no-op"):
    val command = MigrateRepository.from(Address, repo("forgejo")).withLfsFrom("https://lfs.example/forgejo")

    assertEquals(command.lfs, true)
    assertEquals(command.lfsEndpoint, Some("https://lfs.example/forgejo"))
    assertEquals(
      populated.withLfsFrom("https://lfs.example/x"),
      populated.copy(lfs = true, lfsEndpoint = Some("https://lfs.example/x")),
    )

  test("asking for LFS does not invent an endpoint, which would override the default for the clone address"):
    val command = MigrateRepository.from(Address, repo("forgejo")).withLfs

    assertEquals(command.lfs, true)
    assertEquals(command.lfsEndpoint, None)

  test("a mirror interval does not make the repository a mirror, and asking for a mirror does not invent an interval"):
    assertEquals(MigrateRepository.from(Address, repo("forgejo")).every("8h0m0s").isMirror, false)
    assertEquals(MigrateRepository.from(Address, repo("forgejo")).asMirror.mirrorInterval, None)

  test("withEverything turns on the six content flags without touching the credentials it travels beside"):
    val secret  = credential("ghp_token")
    val command = MigrateRepository.from(Address, repo("forgejo")).authenticatedAs("octocat", secret).withEverything

    assertEquals(command.auth, Some(RemoteAuth.Password("octocat", secret)))
    assertEquals(command.lfs, false)
    assertEquals(command.isPrivate, false)

  test("each content flag is its own field, so asking for issues does not also ask for the wiki"):
    val command = MigrateRepository.from(Address, repo("forgejo")).withIssues

    assertEquals(command.includesIssues, true)
    assertEquals(command.includesLabels, false)
    assertEquals(command.includesMilestones, false)
    assertEquals(command.includesPullRequests, false)
    assertEquals(command.includesReleases, false)
    assertEquals(command.includesWiki, false)

  test("the clone address and the new name a command was started from survive every builder"):
    val command = populated.asMirror.withEverything.asPrivate

    assertEquals(command.cloneAddress, "https://origin.example/a/b.git")
    assertEquals(command.repoName.value, "forgejo")

  // --- transfer -------------------------------------------------------------

  test("granting a team access appends rather than replacing, so two grants are two teams"):
    val command = TransferRepository.to(owner("worxbend")).grantedTo(team(7L)).grantedTo(team(9L))

    assertEquals(command.grantedTo(team(11L)).teamIds.map(_.value), Vector(7L, 9L, 11L))
    assertEquals(command.newOwner.value, "worxbend")

  private def populated: MigrateRepository =
    MigrateRepository(
      cloneAddress         = "https://origin.example/a/b.git",
      repoName             = repo("forgejo"),
      repoOwner            = Some(owner("original")),
      description          = Some("original description"),
      service              = Some(MigrationService.GitHub),
      auth                 = Some(RemoteAuth.Password("original-user", credential("original-password"))),
      isPrivate            = false,
      isMirror             = false,
      mirrorInterval       = Some("8h0m0s"),
      lfs                  = false,
      lfsEndpoint          = Some("https://original.example/lfs"),
      includesIssues       = false,
      includesLabels       = false,
      includesMilestones   = false,
      includesPullRequests = false,
      includesReleases     = false,
      includesWiki         = false,
    )

  private def credential(value: String): RemoteCredential = orFail(RemoteCredential.from(value))

  private def repo(value: String): RepoName = orFail(RepoName.from(value))

  private def owner(value: String): Owner = orFail(Owner.from(value))

  private def team(value: Long): TeamId = orFail(TeamId.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
