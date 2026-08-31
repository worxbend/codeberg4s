package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.PathSegment
import com.worxbend.codeberg4s.SegmentLiteral
import com.worxbend.codeberg4s.ValidationError

/** Validation shared by every identifier in this group that Forgejo expresses as a positive integer.
  *
  * [[RepositoryId]], [[TopicId]] and [[ActivityId]] are all `int64` on the wire, and the first ends up interpolated
  * into a request path. A number cannot forge a path, so the point here is confusion rather than escaping: a
  * repository's `id`, a topic's `id` and an activity entry's `id` are all `Long`, all plausible values for one another,
  * and two of them can be read off the same response.
  *
  * This duplicates `com.worxbend.codeberg4s.repositories.actions.ActionIds`, `com.worxbend.codeberg4s.issues.NumericId`
  * and `com.worxbend.codeberg4s.pulls.PullIds`, each of which is private to its own group and therefore unreachable
  * from here. `docs/LEDGER.md` already lists that kind of helper under "helpers awaiting promotion"; the right fix is
  * one shared validator in the domain module root, not a widened internal.
  */
private[admin] object AdminIds:

  private val MinValue: Long = 1L

  /** Accepts `value` only if it is a positive identifier.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    */
  def from(field: String, value: Long): Either[ValidationError, Long] =
    if value < MinValue then Left(ValidationError(field, s"must be at least $MinValue")) else Right(value)

/** The instance-wide identifier of a repository — the `{id}` of `GET /repositories/{id}`.
  *
  * The one way to address a repository that survives a rename or a transfer. `owner/name` does not: renaming a
  * repository or moving it to another owner changes both halves of the slug while this stays put, which is exactly why
  * Forgejo offers the endpoint at all.
  */
opaque type RepositoryId = Long

object RepositoryId:

  /** Parses a repository identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"repositoryId"` field
    */
  def from(value: Long): Either[ValidationError, RepositoryId] =
    AdminIds.from("repositoryId", value)

  extension (id: RepositoryId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id

/** The instance-wide identifier of a topic, as `GET /topics/search` reports it.
  *
  * No endpoint in this library takes one in a path; it exists so two topics with the same display name on different
  * instances stay distinguishable, and so a caller can key a cache on something stable.
  */
opaque type TopicId = Long

object TopicId:

  /** Parses a topic identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"topicId"` field
    */
  def from(value: Long): Either[ValidationError, TopicId] =
    AdminIds.from("topicId", value)

  extension (id: TopicId)

    /** The identifier as a `Long`. */
    def value: Long = id

/** The identifier of one entry in a repository's activity feed. */
opaque type ActivityId = Long

object ActivityId:

  /** Parses an activity identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"activityId"` field
    */
  def from(value: Long): Either[ValidationError, ActivityId] =
    AdminIds.from("activityId", value)

  extension (id: ActivityId)

    /** The identifier as a `Long`. */
    def value: Long = id

/** The remote name of a push mirror — the `{name}` of `/repos/{owner}/{repo}/push_mirrors/{name}`.
  *
  * Forgejo generates this itself when a mirror is created, as a random `remote_` handle, and it is the only way to
  * address the mirror afterwards. It is '''not''' the mirror's remote address and not the mirrored repository's name;
  * [[PushMirror.remoteName]] is where a caller gets one.
  *
  * Validated as a single URI path segment, so it cannot forge a path — see
  * [[com.worxbend.codeberg4s.repositories.PathSegment]].
  */
opaque type MirrorName = String

object MirrorName:

  /** Parses a push-mirror remote name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..`.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"mirrorName"` field
    */
  def from(value: String): Either[ValidationError, MirrorName] =
    PathSegment.from("mirrorName", value)

  /** Builds a mirror name from a string literal, checked while the code compiles.
    *
    * `MirrorName("...")` '''is''' the mirror name, with no `Either` to unwrap: a literal is either valid or it is not,
    * and an invalid one is a compile error pointing at the literal itself. The rules are [[from]]'s, minus the trim —
    * surrounding whitespace is refused rather than removed. See [[com.worxbend.codeberg4s.SegmentLiteral]], and use
    * [[from]] for a value known only at run time.
    */
  inline def apply[V <: String & Singleton](inline value: V): MirrorName =
    SegmentLiteral.plain("mirrorName", value)

  extension (name: MirrorName)

    /** The name as a string, ready to be used as one path segment. */
    def value: String = name
