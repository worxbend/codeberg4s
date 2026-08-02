package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.issues.EditAttachment

/** Forgejo's `EditAttachmentOptions` request model — the body of `PATCH …/issues/{index}/assets/{attachment_id}` and of
  * the matching comment route.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of this request exists. The publishing group renders the same Forgejo
  * model for release assets in its own package; the two are not shared, because `docs/LEDGER.md` puts the two
  * attachment concepts in different groups and a shared renderer would make either group's next field the other's
  * problem.
  *
  * '''`browser_download_url` must not appear unasked.''' The spec notes it may only be set on an attachment of the
  * external kind, so emitting it by default would turn every rename of an ordinary uploaded attachment into a rejected
  * request.
  */
private[codeberg4s] object EditAttachmentOptionDto:

  /** Renders `command` as the JSON body to `PATCH`; `{}` when it changes nothing. */
  def render(command: EditAttachment): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: EditAttachment): List[(String, JsonValue)] =
    List(
      command.name.map(fileName          => "name" -> JsonValue.Str(fileName)),
      command.browserDownloadUrl.map(url => "browser_download_url" -> JsonValue.Str(url)),
    ).flatten
