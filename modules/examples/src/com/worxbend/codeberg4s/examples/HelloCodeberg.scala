package com.worxbend.codeberg4s.examples

import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.ServerVersion
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.Repository

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

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
  *   - turning `String` arguments into validated identifiers with `Owner.from` and `RepoName.from`,
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
    * `Owner.from` and `RepoName.from` return `Either` rather than the value, because a string containing `/` would
    * forge a request path. `Owner("forgejo")` does not compile — the types are opaque and have no public apply. The two
    * are combined in a `for`-comprehension, which is how validated values are usually assembled.
    */
  private val target: Either[ValidationError, (Owner, RepoName)] =
    for
      owner <- Owner.from("forgejo")
      name  <- RepoName.from("forgejo")
    yield (owner, name)

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

      target match
        case Left(problem) =>
          // Unreachable for the literals above, but the compiler does not know
          // that, and pretending otherwise is what an unsafe extraction is for.
          ExampleConsole.line(s"invalid ${problem.field}: ${problem.message}")

        case Right((owner, name)) =>
          // Await at the edge, once. Everywhere else, compose the Future.
          val repository: Repository = Await.result(client.repos.get(owner, name), AwaitLimit)

          ExampleConsole.heading(repository.fullName)
          ExampleConsole.line(s"  stars:       ${repository.starsCount}")
          ExampleConsole.line(s"  forks:       ${repository.forksCount}")
          ExampleConsole.line(s"  open issues: ${repository.openIssuesCount}")
          ExampleConsole.line(s"  description: ${repository.description.getOrElse("(none)")}")
    finally client.close()
