package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.ValidationError

/** What `POST /repos/{owner}/{repo}/actions/workflows/{workflowfilename}/dispatches` is told.
  *
  * {{{
  * DispatchWorkflow
  *   .on("refs/heads/main")
  *   .map(_.withInput("environment", "staging").returningRunInfo)
  * }}}
  *
  * ==Inputs are ordered==
  *
  * [[inputs]] is a `Vector` of pairs rather than a `Map`, so the rendered body is byte-for-byte reproducible between
  * runs and can be asserted on directly. Forgejo does not care about the order; a test that compares request bodies
  * does. A key set twice is sent twice, exactly as the caller built it — this type does not silently de-duplicate,
  * because deciding which of two values wins is not a decision a client library should make.
  *
  * @param ref
  *   the Git reference to run against. Forgejo accepts both the short form (`main`) and the full one
  *   (`refs/heads/main`); the full form is unambiguous when a branch and a tag share a name
  * @param inputs
  *   the `workflow_dispatch` inputs the workflow file declares, in the order they will be sent
  * @param returnRunInfo
  *   whether to ask for a description of the run that was started. When `false` — the default and the API's own default
  *   — the instance answers `204` with nothing, and
  *   [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi.dispatchWorkflow]] yields `None`
  */
final case class DispatchWorkflow(
    ref: String,
    inputs: Vector[(String, String)],
    returnRunInfo: Boolean,
):

  /** Appends one workflow input. Repeating a key appends a second entry; see the class note. */
  def withInput(name: String, value: String): DispatchWorkflow =
    copy(inputs = inputs.appended(name -> value))

  /** Asks the instance to describe the run it started, which turns the `204` into a `201` with a body. */
  def returningRunInfo: DispatchWorkflow = copy(returnRunInfo = true)

object DispatchWorkflow:

  /** Starts a command from the one field Forgejo requires.
    *
    * Trims the reference and rejects a blank one. It is not otherwise validated: the value travels in a JSON body
    * rather than in a path, so it cannot forge a request, and a reference this library refused would be a workflow the
    * caller could not run.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"ref"` field
    */
  def on(ref: String): Either[ValidationError, DispatchWorkflow] =
    val trimmed = ref.trim

    if trimmed.isEmpty then Left(ValidationError("ref", "must not be blank"))
    else Right(DispatchWorkflow(ref = trimmed, inputs = Vector.empty, returnRunInfo = false))
