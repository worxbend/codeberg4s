package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.ValidationError

/** What `POST /repos/{owner}/{repo}/actions/runners` is told.
  *
  * {{{
  * RegisterRunner.named("build-box-3").map(_.describedAs("hetzner cx42").ephemeral)
  * }}}
  *
  * @param name
  *   the operator's label for the machine. Required by the spec, and explicitly '''not''' unique — registering the same
  *   name twice produces two runners, which is one reason the call is never retried
  * @param isEphemeral
  *   whether the runner should de-register itself after a single job. The safer choice for a runner that executes code
  *   from pull requests, since nothing survives between jobs
  */
final case class RegisterRunner(
    name: String,
    description: Option[String],
    isEphemeral: Boolean,
):

  /** Sets the free-text description shown beside the runner. */
  def describedAs(text: String): RegisterRunner = copy(description = Some(text))

  /** Registers the runner as ephemeral; see [[isEphemeral]]. */
  def ephemeral: RegisterRunner = copy(isEphemeral = true)

object RegisterRunner:

  /** Starts a command from the one field Forgejo requires.
    *
    * Trims the name and rejects a blank one: the instance would answer `400`, and finding that out after a round trip
    * is strictly worse than finding it out here.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"runnerName"` field
    */
  def named(name: String): Either[ValidationError, RegisterRunner] =
    val trimmed = name.trim

    if trimmed.isEmpty then Left(ValidationError("runnerName", "must not be blank"))
    else Right(RegisterRunner(name = trimmed, description = None, isEphemeral = false))
