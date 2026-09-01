package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Timestamps, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.{Reaction, ReactionContent}
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `Reaction` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' No golden fixture contains a reaction — the
  * harvest was anonymous and none of the captured issues or comments carried one. All three declared properties are
  * represented and all three are optional here, per `docs/HAZARDS.md` §1.
  *
  * `content` is decoded through [[com.worxbend.codeberg4s.issues.ReactionContent]], which is an '''open''' vocabulary:
  * the spec declares the field as a bare string with no `enum`, so a custom emoji an instance has been configured with
  * decodes exactly like `+1`.
  */
final case class ReactionDto(content: Option[String], user: Option[UserDto], createdAt: Option[String])
    extends WireModel[Reaction]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `content` is the one required field, and it goes through [[com.worxbend.codeberg4s.issues.ReactionContent.from]] —
    * a reaction that says nothing is not a reaction, and a blank one would be silently unusable as an argument to the
    * delete call. `user` is optional for the reason [[CommentDto]] gives; a failure inside it is reported at `$.user`.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Reaction] =
    for
      emoji   <- Wire.validated(at, "content", content)(ReactionContent.from)
      reactor <- Wire.nested(at, "user", user)(_.toDomainAt(_))
    yield Reaction(content = emoji, user = reactor, createdAt = Timestamps.parseOptional(createdAt))

object ReactionDto:

  /** Reads a `Reaction` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ReactionDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ReactionDto =
    ReactionDto(
      content   = fields.text("content"),
      user      = fields.nested("user").map(UserDto.fromFields),
      createdAt = fields.text("created_at"),
    )
