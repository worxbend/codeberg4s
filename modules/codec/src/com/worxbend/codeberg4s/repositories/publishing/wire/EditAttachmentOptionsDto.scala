package com.worxbend.codeberg4s.repositories.publishing.wire

import com.worxbend.codeberg4s.repositories.publishing.EditAsset

/** Forgejo's `EditAttachmentOptions` request model — the body of
  * `PATCH /repos/{owner}/{repo}/releases/{id}/assets/{attachment_id}`.
  *
  * An object rather than a case class, for the reason [[CreateReleaseOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of this request exists.
  *
  * '''Only what the caller set is emitted.''' `browser_download_url` in particular must not appear unasked: the spec
  * notes it may only be set on an attachment of the external kind, so sending it by default would turn every rename of
  * an ordinary uploaded attachment into a rejected request.
  */
private[codeberg4s] object EditAttachmentOptionsDto:

  /** Renders `command` as the JSON body to `PATCH`. */
  def render(command: EditAsset): String =
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: EditAsset): List[(String, ujson.Value)] =
    List(
      command.name.map(fileName          => "name" -> ujson.Str(fileName)),
      command.browserDownloadUrl.map(url => "browser_download_url" -> ujson.Str(url)),
    ).flatten
