package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.actions.{DispatchedWorkflowRun, RunId}

/** Forgejo's `DispatchWorkflowRun` model — what a dispatch reports when it was asked to report anything.
  *
  * '''Derived from the spec, not from a capture'''; see [[ActionArtifactDto]].
  *
  * The endpoint answers `204` with an empty body unless the request set `return_run_info`, so this DTO describes only
  * half of the possible outcomes. The other half — no body at all — is handled by the decoder in
  * [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionDecoders]], which is where "empty means the caller
  * did not ask" is turned into a `None` rather than into a decoding failure.
  */
final case class DispatchedWorkflowRunDto(
    id: Option[Long],
    runNumber: Option[Long],
    jobs: Vector[String],
):

  /** Converts to the domain.
    *
    * '''Nothing is required, so this cannot fail.''' Every other model in this group insists on an identifier, because
    * every other model describes something that can be addressed again. This one describes an acknowledgement: the
    * dispatch either happened or the call failed, and an acknowledgement missing its run id is still an
    * acknowledgement. Failing here would turn a workflow that was successfully started into an error the caller would
    * reasonably retry.
    *
    * An `id` that is not a positive identifier is therefore dropped rather than reported. The `Either` is kept for the
    * benefit of [[com.worxbend.codeberg4s.client.WireDecode]], which composes readers with conversions of this shape;
    * there is no `toDomainAt` because nothing embeds this model, so no nested path could ever be reported.
    */
  def toDomain: Either[DecodeFailure, DispatchedWorkflowRun] =
    Right(
      DispatchedWorkflowRun(
        id        = id.flatMap(value => RunId.from(value).toOption),
        runNumber = runNumber,
        jobs      = jobs,
      )
    )

object DispatchedWorkflowRunDto:

  /** Reads a `DispatchWorkflowRun` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[DispatchedWorkflowRunDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): DispatchedWorkflowRunDto =
    DispatchedWorkflowRunDto(
      id        = fields.number("id"),
      runNumber = fields.number("run_number"),
      jobs      = ActionWire.strings(fields, "jobs"),
    )
