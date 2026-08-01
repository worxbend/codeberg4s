package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.JsonFields

/** Forgejo's `InternalTracker` model — the `internal_tracker` object embedded in a repository.
  *
  * Absent entirely on repositories that use an external issue tracker, which is why every field is optional and why the
  * embedding field is itself an `Option`. `golden/repository/repo-single.json` carries it; one of the thirteen
  * repository objects across the golden fixtures does not.
  *
  * It has no domain counterpart: these are issue-tracker settings, and this library reads repositories rather than
  * configuring them. The DTO exists so the payload can be decoded without loss and so the eventual settings API has
  * something to build on.
  *
  * @param enableTimeTracker
  *   the `enable_time_tracker` key
  * @param allowOnlyContributorsToTrackTime
  *   the `allow_only_contributors_to_track_time` key
  * @param enableIssueDependencies
  *   the `enable_issue_dependencies` key
  */
final case class InternalTrackerDto(
    enableTimeTracker: Option[Boolean],
    allowOnlyContributorsToTrackTime: Option[Boolean],
    enableIssueDependencies: Option[Boolean],
)

object InternalTrackerDto:

  /** Reads an `internal_tracker` object. */
  given upickle.default.Reader[InternalTrackerDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the repository DTO that embeds this one. */
  def fromFields(fields: JsonFields): InternalTrackerDto =
    InternalTrackerDto(
      enableTimeTracker                = fields.boolean("enable_time_tracker"),
      allowOnlyContributorsToTrackTime = fields.boolean("allow_only_contributors_to_track_time"),
      enableIssueDependencies          = fields.boolean("enable_issue_dependencies"),
    )
