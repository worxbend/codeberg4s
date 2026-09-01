package com.worxbend.codeberg4s.examples

import com.worxbend.codeberg4s.auth.{ApiToken, Auth}
import com.worxbend.codeberg4s.issues.{CreateIssue, Issue}
import com.worxbend.codeberg4s.{BaseUri, CodebergClient, CodebergConfig, CodebergError, Owner, RepoName, ValidationError}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

/** A write path end to end: validate the inputs, build the command, create the issue, read it back.
  *
  * ==Running it==
  *
  * {{{
  * export CODEBERG_TOKEN=…
  * export CODEBERG_OWNER=your-account
  * export CODEBERG_REPO=your-scratch-repository
  * ./mill modules.examples.runMain com.worxbend.codeberg4s.examples.CreatingAnIssue
  * }}}
  *
  * ==Environment==
  *
  *   - `CODEBERG_TOKEN` — a personal access token with `write:issue` on the target repository. '''Required.'''
  *   - `CODEBERG_OWNER` — the account or organisation that owns the repository. '''Required.'''
  *   - `CODEBERG_REPO` — the repository name, without the owner. '''Required.'''
  *   - `CODEBERG_BASE_URI` — the API root of a self-hosted instance, for example `https://forge.example/api/v1`.
  *     Optional; defaults to [[com.worxbend.codeberg4s.BaseUri.Codeberg]].
  *
  * '''This program writes.''' It opens a real issue in a real repository, and nothing here deletes it afterwards. Point
  * it at a scratch repository you own. When any required variable is missing it explains what to set and exits
  * normally, without building a client and without throwing.
  *
  * ==What it shows==
  *
  *   - assembling several validated values in one `for`-comprehension over `Either`, so the first bad input stops the
  *     program with a field name and a reason instead of a stack trace;
  *   - [[com.worxbend.codeberg4s.issues.CreateIssue]] as a command type rather than a nine-parameter method: only what
  *     is set reaches the JSON body, so the instance applies its own defaults rather than this library's guesses;
  *   - the typed rail on a mutating call, which is where it earns its keep — a `422` from a write is a value to
  *     inspect, not an exception to catch;
  *   - reading the issue back by [[com.worxbend.codeberg4s.issues.IssueNumber]], the per-repository counter, and not by
  *     `Issue.id`, the instance-wide row identifier no endpoint accepts.
  *
  * '''A `POST` is not retried.''' Forgejo offers no idempotency keys, so the retry engine only replays safe methods. A
  * transport failure on the create below may or may not have created an issue; that is the API's nature, not this
  * library's choice.
  */
object CreatingAnIssue:

  /** The token, which needs `write:issue` on the target repository. */
  val TokenVariable: String = "CODEBERG_TOKEN"

  /** The account or organisation owning the target repository. */
  val OwnerVariable: String = "CODEBERG_OWNER"

  /** The target repository name, without the owner. */
  val RepoVariable: String = "CODEBERG_REPO"

  /** The API root of a self-hosted instance. Optional. */
  val BaseUriVariable: String = "CODEBERG_BASE_URI"

  private val AwaitLimit: FiniteDuration = 2.minutes

  private val Title: String = "codeberg4s example issue"

  private val Body: String =
    "Opened by modules/examples/CreatingAnIssue. Safe to close."

  /** Everything the program needs, validated. Assembled once so that a bad value is reported before a client exists. */
  private final case class Target(auth: Auth, baseUri: BaseUri, owner: Owner, name: RepoName, command: CreateIssue)

  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global

    required() match
      case None =>
        // Degrade, do not throw: an unset variable is a setup step, not a bug.
        ExampleConsole.line("This example writes to a repository, so it needs all of:")
        ExampleConsole.line(s"  $TokenVariable  a token with write:issue on the target repository")
        ExampleConsole.line(s"  $OwnerVariable  the owning account or organisation")
        ExampleConsole.line(s"  $RepoVariable   the repository name, without the owner")
        ExampleConsole.line(s"Optionally $BaseUriVariable for a self-hosted instance.")

      case Some((rawToken, rawOwner, rawRepo)) =>
        validate(rawToken, rawOwner, rawRepo) match
          case Left(problem) =>
            ExampleConsole.line(s"invalid ${problem.field}: ${problem.message}")

          case Right(target) =>
            val client = CodebergClient(configFor(target))
            try Await.result(createThenRead(client, target), AwaitLimit)
            finally client.close()

  /** The three mandatory variables, or `None` when any of them is absent or blank. */
  private def required(): Option[(String, String, String)] =
    for
      token <- present(TokenVariable)
      owner <- present(OwnerVariable)
      repo  <- present(RepoVariable)
    yield (token, owner, repo)

  private def present(variable: String): Option[String] =
    sys.env.get(variable).map(value => value.trim).filter(value => value.nonEmpty)

  /** Turns three strings into validated domain values and a command.
    *
    * Every smart constructor here returns `Either[ValidationError, _]`, so they compose in one `for`-comprehension and
    * the first failure short-circuits with a stable field name — `"apiToken"`, `"owner"`, `"repoName"`, `"title"`.
    * `from` is the right constructor here precisely because these three values come from the environment: the
    * compile-time form `Owner("forgejo")` only accepts a literal, and handing it a run-time `String` is itself a
    * compile error.
    *
    * The optional base URI is folded in afterwards rather than in the comprehension, because "absent" and "invalid" are
    * different answers and only the second is an error.
    */
  private def validate(rawToken: String, rawOwner: String, rawRepo: String): Either[ValidationError, Target] =
    for
      token   <- ApiToken.from(rawToken)
      owner   <- Owner.from(rawOwner)
      name    <- RepoName.from(rawRepo)
      baseUri <- sys.env
                   .get(BaseUriVariable)
                   .fold[Either[ValidationError, BaseUri]](Right(BaseUri.Codeberg))(raw => BaseUri.from(raw))
      // CreateIssue.of validates the one field Forgejo insists on. Everything
      // else is added by naming it, and an unset field contributes no JSON key.
      command <- CreateIssue.of(Title)
    yield Target(Auth.Token(token), baseUri, owner, name, command.withBody(Body))

  /** The Codeberg defaults with one field replaced. `copy` is how a config is adjusted; there is no builder. */
  private def configFor(target: Target): CodebergConfig =
    CodebergConfig(target.auth).copy(baseUri = target.baseUri)

  /** `POST` the issue, then `GET` it back by the number the instance assigned.
    *
    * Both calls use the typed rail, so neither `Future` fails: a `403` from a token without `write:issue`, or a `404`
    * from a repository name with a typo in it, arrives as a `Left` that the caller decides what to do with.
    */
  private def createThenRead(client: CodebergClient, target: Target)(using ExecutionContext): Future[Unit] =
    client.issues.attempt
      .create(target.owner, target.name, target.command)
      .flatMap:
        case Left(error) =>
          ExampleConsole.line(s"could not create the issue: ${error.describe}")
          explain(error)
          Future.successful(())

        case Right(created) =>
          ExampleConsole.heading(s"created #${created.number.value}")
          ExampleConsole.line(s"  title: ${created.title}")
          ExampleConsole.line(s"  url:   ${created.htmlUrl.getOrElse("(the instance sent no html_url)")}")
          readBack(client, target, created)

  /** Reads the issue that was just written, by its per-repository number. */
  private def readBack(client: CodebergClient, target: Target, created: Issue)(using ExecutionContext): Future[Unit] =
    client.issues.attempt
      .get(target.owner, target.name, created.number)
      .map:
        case Left(error) =>
          ExampleConsole.line(s"created it, but could not read it back: ${error.describe}")

        case Right(issue) =>
          ExampleConsole.heading("read back")
          ExampleConsole.line(s"  state:  ${issue.state}")
          ExampleConsole.line(s"  author: ${issue.author.fold("unknown")(user => user.login)}")
          ExampleConsole.line(s"  body:   ${issue.body.getOrElse("(empty)")}")

  /** Turns the statuses a write actually produces into the next thing to try. */
  private def explain(error: CodebergError): Unit =
    error match
      case CodebergError.Api(_, 401, _, _) => ExampleConsole.line(s"  $TokenVariable was not accepted at all")
      case CodebergError.Api(_, 403, _, _) => ExampleConsole.line("  the token lacks write:issue on this repository")
      case CodebergError.Api(_, 404, _, _) => ExampleConsole.line("  no such repository, or the token cannot see it")
      case CodebergError.Api(_, 422, _, _) => ExampleConsole.line("  the instance rejected the payload")
      case _                               => ExampleConsole.line("  see the description above")
