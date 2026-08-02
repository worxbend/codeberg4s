package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** What [[CreatePushMirror]]'s builders change.
  *
  * Each is applied to a command with every property already set, so a builder that cleared a sibling fails here. The
  * sibling that matters is the credential: it is a password for a '''third-party''' host, and a builder that dropped it
  * would produce a mirror that authenticates as nobody and fails on every run — a failure Forgejo reports only in
  * [[PushMirror.lastError]], which no call ever fails on.
  */
final class PushMirrorCommandSuite extends FunSuite:

  private val Address: String = "https://codeberg.org/worxbend/codeberg4s.git"

  test("every push-mirror builder sets its own field and leaves every sibling alone"):
    val command = populated

    assertEquals(command.every("24h0m0s"), command.copy(interval = Some("24h0m0s")))
    assertEquals(command.onlyBranches("release/*"), command.copy(branchFilter = Some("release/*")))
    assertEquals(command.syncingOnCommit, command.copy(syncOnCommit = true))
    assertEquals(command.overSsh, command.copy(useSsh = true))

  test("authenticating sets the username and the credential together, and nothing else"):
    val command = CreatePushMirror.to(Address)
    val secret  = credential("hunter2")

    assertEquals(
      command.authenticatedAs("bot", secret),
      command.copy(remoteUsername = Some("bot"), remoteCredential = Some(secret)),
    )

  test("asking for an SSH key leaves an already-stated credential alone, because Forgejo judges the combination"):
    val secret  = credential("hunter2")
    val command = CreatePushMirror.to(Address).authenticatedAs("bot", secret).overSsh

    assertEquals(command.useSsh, true)
    assertEquals(command.remoteCredential, Some(secret))
    assertEquals(command.remoteUsername, Some("bot"))

  test("syncing on commit does not replace the interval, since a mirror can do both"):
    val command = CreatePushMirror.to(Address).every("8h0m0s").syncingOnCommit

    assertEquals(command.syncOnCommit, true)
    assertEquals(command.interval, Some("8h0m0s"))

  test("a branch filter is not a schedule, so setting one leaves the interval unstated"):
    val command = CreatePushMirror.to(Address).onlyBranches("main")

    assertEquals(command.branchFilter, Some("main"))
    assertEquals(command.interval, None)

  test("a fresh command names the remote and asks for nothing else"):
    val command = CreatePushMirror.to(Address)

    assertEquals(command.remoteAddress, Address)
    assertEquals(command.remoteUsername, None)
    assertEquals(command.remoteCredential, None)
    assertEquals(command.interval, None)
    assertEquals(command.branchFilter, None)
    assertEquals(command.syncOnCommit, false)
    assertEquals(command.useSsh, false)

  test("the remote address a command was started from survives every builder"):
    assertEquals(populated.every("1h0m0s").syncingOnCommit.overSsh.remoteAddress, Address)

  private def populated: CreatePushMirror =
    CreatePushMirror(
      remoteAddress    = Address,
      remoteUsername   = Some("original-user"),
      remoteCredential = Some(credential("original-password")),
      interval         = Some("8h0m0s"),
      branchFilter     = Some("main"),
      syncOnCommit     = false,
      useSsh           = false,
    )

  private def credential(value: String): RemoteCredential = orFail(RemoteCredential.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
