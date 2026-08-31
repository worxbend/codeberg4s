package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}

/** Forgejo's `NoteOptions` request model — the body of `POST /repos/{owner}/{repo}/git/notes/{sha}`.
  *
  * An object rather than a case class with a `Writer`, for the reason
  * [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]] gives: a request body is only ever written, so the
  * interesting decision is which keys appear, and putting that in the rendering keeps it in one visible place.
  *
  * The model has exactly one property and it is always emitted, including when it is empty. An empty note is a
  * meaningful instruction — it is how a caller blanks a note's text without deleting the note — so the "omit what the
  * caller did not set" rule that governs the other request DTOs has nothing to omit here.
  */
private[codeberg4s] object NoteOptionsDto:

  /** Renders `message` as the JSON body to `POST`. */
  def render(message: String): String =
    Json.render(JsonValue.Obj("message" -> JsonValue.Str(message)))
