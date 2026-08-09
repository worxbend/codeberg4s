package com.worxbend.codeberg4s.repositories.actions

import java.time.Instant

/** A file bundle a workflow run uploaded, as the artifact endpoints report it.
  *
  * '''Derived from `spec/swagger.v1.json`'s `ActionArtifact` definition, not from a captured response.''' No golden
  * fixture exists for this group — the harvest that produced `modules/codec/test/resources/golden` was anonymous, and
  * every Actions endpoint requires a token — so the field set here is the spec's and the nullability is the
  * conservative reading `docs/HAZARDS.md` §1 mandates: everything the domain can live without is optional.
  *
  * ==Downloading==
  *
  * `GET /repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip` is implemented, on
  * `com.worxbend.codeberg4s.repositories.actions.ActionDownloadApi` — reached as `client.repos.actions.downloads`. It
  * holds the whole archive in memory, because nothing in this library streams, so [[archiveDownloadUrl]] remains the
  * route for an archive too large to want in the heap: hand it to an HTTP client that can stream bytes. Note that the
  * URL is authenticated exactly like the API is, so the caller's own client must send the same credentials.
  *
  * @param id
  *   the identifier the artifact endpoints address this artifact by
  * @param name
  *   the name the workflow uploaded it under; not unique within a run, and not unique within a repository
  * @param sizeInBytes
  *   the total size of the archive, absent when the instance did not report it
  * @param isExpired
  *   whether the artifact has passed its retention window. An expired artifact is still listed and still readable as
  *   metadata; only its bytes are gone
  * @param archiveDownloadUrl
  *   where the ZIP lives — see the downloading note above
  * @param runId
  *   the run that produced it
  * @param expiresAt
  *   when the bytes are removed, absent when the instance did not report a retention window
  */
final case class ActionArtifact private[codeberg4s] (
    id: ArtifactId,
    name: Option[String],
    sizeInBytes: Option[Long],
    isExpired: Boolean,
    archiveDownloadUrl: Option[String],
    runId: Option[RunId],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
    expiresAt: Option[Instant],
)
