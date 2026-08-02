package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.repositories.FileContent

import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64

/** Building the base64 payload the wiki endpoints consume.
  *
  * `CreateWikiPageOptions.content_base64` is documented as "content must be base64 encoded", so a caller holding
  * ordinary text has to encode it. These two helpers are that encoding, written once so the charset decision is made
  * once: text is encoded as UTF-8, which is what Git stores and what
  * [[com.worxbend.codeberg4s.repositories.FileContent.text]] assumes on the way back.
  *
  * The result is a [[com.worxbend.codeberg4s.repositories.FileContent]] and not a new type, because this library
  * already has one representation of base64 content and a second would be one too many — [[WikiPage.content]] hands
  * back the same type, so a page can be read, edited and written without ever changing representation.
  *
  * The encoder is the '''basic''' one, which emits no line breaks. MIME base64 with line breaks decodes fine on the way
  * back — [[com.worxbend.codeberg4s.repositories.FileContent.decoded]] uses a MIME decoder — but a payload without them
  * is what Forgejo's own clients send, and unbroken output keeps a rendered request body reproducible in a test.
  */
object WikiContent:

  /** The base64 of `text`, encoded as UTF-8. */
  def ofText(text: String): FileContent =
    ofBytes(text.getBytes(StandardCharsets.UTF_8))

  /** The base64 of `bytes`, for a page whose content is not text this library should guess the charset of. */
  def ofBytes(bytes: Array[Byte]): FileContent =
    FileContent.Base64(JavaBase64.getEncoder.encodeToString(bytes))
