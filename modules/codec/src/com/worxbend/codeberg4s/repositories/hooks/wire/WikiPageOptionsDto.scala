package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.repositories.FileContent
import com.worxbend.codeberg4s.repositories.hooks.CreateWikiPage
import com.worxbend.codeberg4s.repositories.hooks.EditWikiPage

/** Forgejo's `CreateWikiPageOptions` request model — the body of both `POST /repos/{owner}/{repo}/wiki/new` and
  * `PATCH /repos/{owner}/{repo}/wiki/page/{pageName}`.
  *
  * One object for two commands because the API has one model for two routes, and rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once. The two renderings
  * differ in exactly one way, and it is the way that matters: on a create, `title` names the page being created and is
  * always sent; on an edit, `title` is a '''rename''' and is sent only when the caller asked for one, because the spec
  * documents an empty title as "leave unchanged" and sending `""` to mean that would be indistinguishable from a caller
  * who meant to send a title and built an empty one.
  *
  * `content_base64` is always sent by both, because `CreateWikiPageOptions` offers no way to say "keep the content".
  * The value is taken verbatim from a [[com.worxbend.codeberg4s.repositories.FileContent.Base64]]; the commands refuse
  * anything else at construction, so nothing that is not base64 can reach a key named `content_base64`.
  */
private[codeberg4s] object WikiPageOptionsDto:

  /** The wire key a page's title is sent under. */
  val TitleKey: String = "title"

  /** The wire key a page's base64 content is sent under. */
  val ContentKey: String = "content_base64"

  /** The wire key the commit message is sent under. */
  val MessageKey: String = "message"

  /** Renders `command` as the JSON body to `POST`. `title` and `content_base64` are always emitted. */
  def renderCreate(command: CreateWikiPage): String =
    val fields = List(
      Some(TitleKey   -> ujson.Str(command.title.value)),
      Some(ContentKey -> ujson.Str(payload(command.content))),
      command.message.map(text => MessageKey -> ujson.Str(text)),
    ).flatten

    ujson.write(ujson.Obj.from(fields))

  /** Renders `command` as the JSON body to `PATCH`. `title` is emitted only when the edit renames the page. */
  def renderEdit(command: EditWikiPage): String =
    val fields = List(
      command.renamedTo.map(title => TitleKey -> ujson.Str(title.value)),
      Some(ContentKey -> ujson.Str(payload(command.content))),
      command.message.map(text => MessageKey -> ujson.Str(text)),
    ).flatten

    ujson.write(ujson.Obj.from(fields))

  /** The base64 payload of `content`.
    *
    * The [[com.worxbend.codeberg4s.repositories.FileContent.Opaque]] branch is unreachable from the public API —
    * [[com.worxbend.codeberg4s.repositories.hooks.CreateWikiPage.of]] and
    * [[com.worxbend.codeberg4s.repositories.hooks.EditWikiPage.of]] reject it — and is written out rather than left to
    * a partial match so that a future command type cannot introduce the hole silently. Its `raw` is what the instance
    * sent for that content, which is the closest thing to a correct value that exists.
    */
  private def payload(content: FileContent): String =
    content match
      case FileContent.Base64(raw)    => raw
      case FileContent.Opaque(_, raw) => raw
