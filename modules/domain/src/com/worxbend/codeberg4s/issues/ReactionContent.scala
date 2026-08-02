package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

/** What a [[Reaction]] says — `+1`, `heart`, or a custom emoji shortcode the instance has been configured with.
  *
  * ==Why this is not a closed enum==
  *
  * It was checked rather than assumed. In `spec/swagger.v1.json` both `Reaction.content` and
  * `EditReactionOption.content` are declared as a bare `{"type": "string"}` with '''no''' `enum` and no `pattern` —
  * unlike, say, `Attachment.type`, which the same document does constrain and which [[AttachmentKind]] therefore models
  * as a closed set with an escape hatch. Forgejo additionally lets an instance administrator configure which reactions
  * the web UI offers, and the API does not republish that list. Modelling this as a sealed set of six emoji would make
  * every instance with a custom reaction undecodable, so it is an opaque string with the validation a query-free
  * path-free wire value actually needs.
  *
  * The consequence a caller has to know: '''nothing here checks that the instance accepts the reaction.''' A content
  * the instance does not offer comes back as a `403` or a `404` from
  * [[com.worxbend.codeberg4s.issues.IssueReactionApi]], not as a [[ValidationError]] here.
  *
  * ==Error contract==
  *
  * Construction produces [[ValidationError]] on the `"reaction"` field and nothing else; it performs no I/O.
  */
opaque type ReactionContent = String

object ReactionContent:

  /** The reaction Forgejo's own UI spells `+1`. Offered as a constant because it is the one every caller writes. */
  val ThumbsUp: ReactionContent = "+1"

  /** The reaction Forgejo's own UI spells `-1`. */
  val ThumbsDown: ReactionContent = "-1"

  /** Parses a reaction.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value and a value containing a control character. Nothing
    * else is checked, for the reason the type note gives — the vocabulary belongs to the instance, not to this library.
    *
    * @return
    *   the trimmed reaction, or a [[ValidationError]] on the `"reaction"` field
    */
  def from(value: String): Either[ValidationError, ReactionContent] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(ValidationError("reaction", "must not be blank"))
    else if trimmed.exists(_.isControl) then Left(ValidationError("reaction", "must not contain a control character"))
    else Right(trimmed)

  extension (content: ReactionContent)

    /** The reaction as a string, ready to be sent as `EditReactionOption.content`. */
    def value: String = content
