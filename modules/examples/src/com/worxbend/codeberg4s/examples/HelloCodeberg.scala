package com.worxbend.codeberg4s.examples

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.{CodebergClient, CodebergConfig, Owner, RepoName, ServerVersion}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext}

/** The smallest complete codeberg4s program: two anonymous reads against the public Codeberg instance.
  *
  * ==Running it==
  *
  * {{{
  * ./mill modules.examples.runMain com.worxbend.codeberg4s.examples.HelloCodeberg
  * }}}
  *
  * ==Environment==
  *
  * None. Both endpoints used here are public on codeberg.org, so no token is read and none is needed. The program does
  * need outbound network access to `https://codeberg.org`.
  *
  * ==What it shows==
  *
  *   - building a [[com.worxbend.codeberg4s.CodebergConfig]] from nothing but an [[com.worxbend.codeberg4s.auth.Auth]],
  *   - writing identifiers down as string literals, which the compiler checks,
  *   - awaiting the `Future` at the edge of `main`, which is the only place a program should block,
  *   - closing the client exactly once, in a `finally`.
  *
  * This program uses the convenience rail, so a failed call throws [[com.worxbend.codeberg4s.CodebergException]] out of
  * `main` — a network outage prints a message and exits non-zero. That is deliberate: `HandlingErrors` is where failure
  * is handled properly, and mixing the two would make the smallest program not the smallest.
  */
object HelloCodeberg:

  /** How long the program is willing to block on one call.
    *
    * Generous compared with `CodebergConfig`'s own 30 s read timeout, because a call may be retried up to three times
    * under [[com.worxbend.codeberg4s.retry.RetryPolicy.Default]] and this bound has to cover all of them.
    */
  private val AwaitLimit: FiniteDuration = 2.minutes

  /** The repository this program reads: Forgejo's own, on Codeberg.
    *
    * Both names are written down here as string literals, so the compiler checks them: `Owner("forgejo")` '''is''' the
    * owner, with no `Either` to unwrap, and an invalid literal such as `Owner("forgejo/forgejo")` fails the build
    * rather than the program. A value that only exists at run time — an argument, a config entry — goes through
    * `Owner.from` instead, which returns `Either[ValidationError, Owner]`; `CreatingAnIssue` shows that shape.
    */
  private val owner: Owner = Owner("forgejo")

  /** The repository name, checked the same way as [[owner]]. */
  private val name: RepoName = RepoName("forgejo")

  def main(args: Array[String]): Unit =
    // Every call the client makes needs somewhere to run its continuations.
    // `global` is the right default for a program with no execution context of
    // its own; an application passes the one it already has.
    given ExecutionContext = ExecutionContext.global

    // CodebergConfig(auth) fills in the rest: BaseUri.Codeberg, the default
    // retry policy, the default user agent, a page size of 30, and 10 s / 30 s
    // timeouts. Copy the result to change one field.
    //
    // Auth.Anonymous is a real choice here rather than a placeholder — both
    // endpoints below are public — and it means this program is subject to the
    // instance's anonymous rate limit.
    val client: CodebergClient = CodebergClient(CodebergConfig(Auth.Anonymous))

    // A client owns an HTTP connection pool and a scheduler thread, so it is
    // built once for the lifetime of the program and closed exactly once at the
    // end. Building one per call would open a pool per call.
    try
      // `GET /version`: the cheapest way to prove the base URI really points at
      // a Forgejo API root.
      val version: ServerVersion = Await.result(client.version.get(), AwaitLimit)
      ExampleConsole.line(s"instance version: ${version.raw}")

      // Await at the edge, once. Everywhere else, compose the Future.
      val repository: Repository = Await.result(client.repos.get(owner, name), AwaitLimit)

      ExampleConsole.heading(repository.fullName)
      ExampleConsole.line(s"  stars:       ${repository.starsCount}")
      ExampleConsole.line(s"  forks:       ${repository.forksCount}")
      ExampleConsole.line(s"  open issues: ${repository.openIssuesCount}")
      ExampleConsole.line(s"  description: ${repository.description.getOrElse("(none)")}")
    finally client.close()
