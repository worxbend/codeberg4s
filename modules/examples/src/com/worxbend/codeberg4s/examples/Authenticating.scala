package com.worxbend.codeberg4s.examples

import com.worxbend.codeberg4s.auth.{ApiToken, Auth, Password}
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.{CodebergClient, CodebergConfig, ValidationError}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext}

/** The three ways this library authenticates, and what happens when the credential is malformed.
  *
  * ==Running it==
  *
  * {{{
  * ./mill modules.examples.runMain com.worxbend.codeberg4s.examples.Authenticating
  * }}}
  *
  * ==Environment==
  *
  *   - `CODEBERG_TOKEN` — a personal access token, created at `https://codeberg.org/user/settings/applications`.
  *     '''Optional.''' When it is absent the program explains what it would have done and exits normally; it never
  *     throws for a missing variable, because "you have not set this up yet" is not a defect.
  *
  * ==What it shows==
  *
  *   - the three cases of [[com.worxbend.codeberg4s.auth.Auth]] — `Anonymous`, `Token` and `Basic`,
  *   - `ApiToken.from`, which returns `Either[ValidationError, ApiToken]` and so rejects a blank or
  *     control-character-bearing string '''before''' any request header is built,
  *   - handling the `Left` case explicitly rather than unwrapping it,
  *   - that a credential never renders itself: `toString` on a config, a token or a password is the mask `***`, so the
  *     lines this program prints are safe to paste into a bug report.
  *
  * Authentication is the only thing that changes between an anonymous client and an authenticated one. Everything else
  * — the base URI, the retry policy, the API surface — is identical.
  */
object Authenticating:

  /** The environment variable holding the personal access token. */
  val TokenVariable: String = "CODEBERG_TOKEN"

  private val AwaitLimit: FiniteDuration = 2.minutes

  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global

    showAnonymous()
    showBasic()
    showToken()

  /** No credentials at all: public endpoints only, and the instance's anonymous rate limit. */
  private def showAnonymous(): Unit =
    ExampleConsole.heading("Auth.Anonymous")
    ExampleConsole.line("  no credentials; public endpoints only")
    // Safe to print: CodebergConfig's toString cannot reveal a credential,
    // because the credential types inside Auth redact themselves.
    ExampleConsole.line(s"  config: ${CodebergConfig(Auth.Anonymous)}")

  /** HTTP basic credentials, for older self-hosted instances that still require them.
    *
    * `Password.from` deliberately does '''not''' trim: leading and trailing whitespace can be significant in a
    * password. It rejects an empty value and any control character, which cannot survive header encoding.
    */
  private def showBasic(): Unit =
    ExampleConsole.heading("Auth.Basic")

    Password.from("not-a-real-password") match
      case Left(problem) =>
        ExampleConsole.line(s"  invalid ${problem.field}: ${problem.message}")
      case Right(secret) =>
        val auth = Auth.Basic("example-user", secret)
        // Prints Basic(example-user,***) — the password masks itself, and so
        // does every case class or enum case that holds one.
        ExampleConsole.line(s"  $auth")
        ExampleConsole.line("  prefer a token wherever the instance allows one")

  /** A personal access token, sent as `Authorization: token <value>`. The preferred mechanism. */
  private def showToken()(using ExecutionContext): Unit =
    ExampleConsole.heading("Auth.Token")

    sys.env.get(TokenVariable) match
      case None =>
        // Degrade, do not throw. An unset variable is a setup step the reader
        // has not taken yet, not a failure of the program.
        ExampleConsole.line(s"  $TokenVariable is not set, so there is no authenticated call to make.")
        ExampleConsole.line("  Create a token at https://codeberg.org/user/settings/applications, then:")
        ExampleConsole.line(s"    export $TokenVariable=…")

      case Some(raw) =>
        ApiToken.from(raw) match
          case Left(problem) => reportRejection(problem)
          case Right(token)  => callAsSelf(token)

  /** What a rejected credential looks like.
    *
    * A [[com.worxbend.codeberg4s.ValidationError]] carries a stable `field` — here always `"apiToken"` — and a short
    * message. It never echoes the rejected input, so this line cannot leak a token that was almost valid.
    *
    * Note that no request was made: validation happens before a `CodebergConfig` exists, which is why this failure is a
    * bare `ValidationError` and not the [[com.worxbend.codeberg4s.CodebergError.Validation]] case that carries one onto
    * the same channel as remote failures.
    */
  private def reportRejection(problem: ValidationError): Unit =
    ExampleConsole.line(s"  the value of $TokenVariable was rejected before any request was built")
    ExampleConsole.line(s"  invalid ${problem.field}: ${problem.message}")

  /** Proves the token works by reading the account it belongs to — `GET /user`. */
  private def callAsSelf(token: ApiToken)(using ExecutionContext): Unit =
    // The only change from the anonymous config on the previous lines.
    val client: CodebergClient = CodebergClient(CodebergConfig(Auth.Token(token)))

    try
      val me: User = Await.result(client.users.current(), AwaitLimit)
      ExampleConsole.line(s"  authenticated as ${me.login} (${me.fullName.getOrElse("no display name")})")
      ExampleConsole.line(s"  token renders as $token, never as its material")
    finally client.close()
