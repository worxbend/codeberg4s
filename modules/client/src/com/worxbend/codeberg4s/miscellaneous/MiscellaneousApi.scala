package com.worxbend.codeberg4s.miscellaneous

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.miscellaneous.wire.MarkdownOptionDto
import com.worxbend.codeberg4s.miscellaneous.wire.ServerApiSettingsDto
import com.worxbend.codeberg4s.miscellaneous.wire.ServerAttachmentSettingsDto
import com.worxbend.codeberg4s.miscellaneous.wire.ServerRepositorySettingsDto

import scala.concurrent.Future

/** The instance-level endpoints: what this deployment is configured to do, and its markdown renderer.
  *
  * Reached as `client.misc`. Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[MiscellaneousApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * Nothing here takes a repository or a user, which is what makes the group unusual: every operation describes the
  * '''instance''', so the answers are the same for every caller and cheap to cache for the lifetime of a client.
  * `GET /version` is the fourth member of this family and lives on [[com.worxbend.codeberg4s.VersionApi]], which
  * predates this class.
  *
  * '''Two of these do not answer JSON.''' [[signingKey]] returns an armored OpenPGP block and the two markdown
  * renderers return an HTML fragment, all with `Content-Type: text/plain` or `text/html`. They are decoded through
  * [[PlainText]], which parses nothing.
  */
final class MiscellaneousApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: MiscellaneousApi.Attempt = MiscellaneousApi.Attempt(this)

  /** Reads the instance's API limits — `GET /settings/api`.
    *
    * Worth calling once per client against a self-hosted instance. `max_response_items` is the ceiling Forgejo silently
    * applies to the `limit` query parameter while the `Link` header goes on echoing the limit that was asked for, which
    * is why nothing in this library infers "last page" from how many items arrived — see `docs/HAZARDS.md` §5 and
    * [[ServerApiSettings]]. Codeberg reports `50`, which is what [[com.worxbend.codeberg4s.paging.PageSize.Max]]
    * enforces; a self-hosted instance may report something else.
    *
    * '''Failures.''' The returned `Future` fails with [[com.worxbend.codeberg4s.CodebergException]] carrying
    * [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance,
    * [[com.worxbend.codeberg4s.CodebergError.Api]] for a non-2xx status — `404` from a deployment that does not serve
    * the settings endpoints at all, and nothing else in practice, since the endpoint takes no argument to get wrong and
    * needs no credentials — [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when the payload carries neither
    * `max_response_items` nor `default_paging_num`, and [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when
    * a retryable failure outlived the policy. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def apiSettings(): Future[ServerApiSettings] =
    pipeline.call(MiscellaneousApi.ApiSettingsRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousApi.ApiSettingsDecoder)

  /** Reads which repository features the instance has switched off — `GET /settings/repository`.
    *
    * Use it to hide an action rather than to let a caller discover the restriction as a `403` halfway through a
    * workflow.
    *
    * '''Failures.''' As [[apiSettings]], except that this operation cannot produce
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] on a JSON object: every flag defaults to "not disabled"
    * when the instance omits it, so only a body that is not a JSON object at all fails to decode. `GET` is safe, so the
    * call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def repositorySettings(): Future[ServerRepositorySettings] =
    pipeline.call(MiscellaneousApi.RepositorySettingsRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousApi.RepositorySettingsDecoder)

  /** Reads the instance's attachment limits — `GET /settings/attachment`.
    *
    * '''Failures.''' As [[repositorySettings]]: no field is required, so a JSON object always decodes. `GET` is safe,
    * so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def attachmentSettings(): Future[ServerAttachmentSettings] =
    pipeline.call(MiscellaneousApi.AttachmentSettingsRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousApi.AttachmentSettingsDecoder)

  /** Reads the instance's default signing key — `GET /signing-key.gpg`.
    *
    * The response is an armored OpenPGP block as `text/plain`, not JSON, and it is handed back verbatim through
    * [[PlainText]] for a cryptography library to parse.
    *
    * '''`None` is a success, not a failure.''' An instance that signs nothing answers `200` with an empty body rather
    * than `404`, so absence arrives on the success channel — see [[SigningKey.from]].
    *
    * '''Failures.''' The returned `Future` fails with [[com.worxbend.codeberg4s.CodebergException]] carrying
    * [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance,
    * [[com.worxbend.codeberg4s.CodebergError.Api]] for a non-2xx status, and
    * [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy. It never
    * produces [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]], because nothing is parsed. `GET` is safe, so
    * the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def signingKey(): Future[Option[SigningKey]] =
    pipeline.call(MiscellaneousApi.SigningKeyRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousApi.SigningKeyDecoder)

  /** Renders a markdown document the way the instance would render it — `POST /markdown`.
    *
    * The point of asking the server rather than rendering locally is fidelity: `#123`, `@someone` and the emoji set are
    * resolved by the same code that renders the web UI, against the repository named by
    * [[MarkdownRenderRequest.context]].
    *
    * '''This POST is retried, and it is the only one in the library that is.''' Rendering has no effect on the instance
    * — nothing is created, nothing is stored, and the same input always produces the same output — so repeating it
    * after a `503` or a dropped connection costs a little CPU and cannot duplicate anything. It therefore runs under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]] rather than the
    * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]] every other mutating method uses. The same reasoning
    * applies to [[renderMarkdownRaw]] and to nothing else.
    *
    * '''Failures.''' The returned `Future` fails with [[com.worxbend.codeberg4s.CodebergException]] carrying
    * [[com.worxbend.codeberg4s.CodebergError.Api]] with status `422` when the instance rejects the body — an
    * unrecognised `Mode` is the realistic cause — `401` when the deployment requires a token for rendering, which
    * Codeberg does even though the spec marks the endpoint anonymous, and `403` when the token lacks the scope.
    * [[com.worxbend.codeberg4s.CodebergError.Transport]] means nothing reached the instance and
    * [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] means a retryable failure outlived the policy. It never
    * produces [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]], because the HTML is not parsed.
    *
    * @param request
    *   the document and the rendering options; [[MarkdownRenderRequest.of]] builds the standalone case
    */
  def renderMarkdown(request: MarkdownRenderRequest): Future[RenderedMarkdown] =
    pipeline.call(MiscellaneousApi.markdownRequest(request), RetryEligibility.AlwaysRetry)(using
      MiscellaneousApi.MarkdownDecoder)

  /** Renders a markdown document with no options at all — `POST /markdown/raw`.
    *
    * The body is the markdown itself as `text/plain`, and the instance renders it the way [[MarkdownRenderRequest.of]]
    * would: plain markdown, no repository context, nothing resolved. Prefer [[renderMarkdown]] whenever the document
    * belongs to a repository; this exists because it is one request shorter and because the endpoint exists.
    *
    * '''Retried like [[renderMarkdown]]''', and for the same reason: rendering is a pure function of its input, so
    * repeating it is free of consequence. See the note there.
    *
    * '''Failures.''' As [[renderMarkdown]].
    *
    * @param markdown
    *   the markdown source, sent verbatim as the request body
    */
  def renderMarkdownRaw(markdown: String): Future[RenderedMarkdown] =
    pipeline.call(MiscellaneousApi.markdownRawRequest(markdown), RetryEligibility.AlwaysRetry)(using
      MiscellaneousApi.MarkdownDecoder)

/** The requests this group issues, its operation ids, and its typed rail. */
object MiscellaneousApi:

  /** The stable operation id [[MiscellaneousApi.apiSettings]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]]. Safe to alert on.
    */
  val ApiSettingsOperation: String = "settings.api"

  /** The stable operation id of [[MiscellaneousApi.repositorySettings]]. */
  val RepositorySettingsOperation: String = "settings.repository"

  /** The stable operation id of [[MiscellaneousApi.attachmentSettings]]. */
  val AttachmentSettingsOperation: String = "settings.attachment"

  /** The stable operation id of [[MiscellaneousApi.signingKey]]. */
  val SigningKeyOperation: String = "misc.signingKey"

  /** The stable operation id of [[MiscellaneousApi.renderMarkdown]]. */
  val RenderMarkdownOperation: String = "misc.renderMarkdown"

  /** The stable operation id of [[MiscellaneousApi.renderMarkdownRaw]]. */
  val RenderMarkdownRawOperation: String = "misc.renderMarkdownRaw"

  /** The typed rail of [[MiscellaneousApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.misc.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: MiscellaneousApi)(using exec: Exec[Future]):

    /** [[MiscellaneousApi.apiSettings]] with its failure as a value. The returned `Future` never fails with a
      * [[com.worxbend.codeberg4s.CodebergException]].
      */
    def apiSettings(): Future[Either[CodebergError, ServerApiSettings]] =
      exec.attempt(rail.apiSettings())

    /** [[MiscellaneousApi.repositorySettings]] with its failure as a value. */
    def repositorySettings(): Future[Either[CodebergError, ServerRepositorySettings]] =
      exec.attempt(rail.repositorySettings())

    /** [[MiscellaneousApi.attachmentSettings]] with its failure as a value. */
    def attachmentSettings(): Future[Either[CodebergError, ServerAttachmentSettings]] =
      exec.attempt(rail.attachmentSettings())

    /** [[MiscellaneousApi.signingKey]] with its failure as a value. `Right(None)` still means the instance signs
      * nothing.
      */
    def signingKey(): Future[Either[CodebergError, Option[SigningKey]]] =
      exec.attempt(rail.signingKey())

    /** [[MiscellaneousApi.renderMarkdown]] with its failure as a value. */
    def renderMarkdown(request: MarkdownRenderRequest): Future[Either[CodebergError, RenderedMarkdown]] =
      exec.attempt(rail.renderMarkdown(request))

    /** [[MiscellaneousApi.renderMarkdownRaw]] with its failure as a value. */
    def renderMarkdownRaw(markdown: String): Future[Either[CodebergError, RenderedMarkdown]] =
      exec.attempt(rail.renderMarkdownRaw(markdown))

  /** The `Content-Type` `POST /markdown/raw` consumes.
    *
    * Sent as an explicit header because [[com.worxbend.codeberg4s.core.RequestBody]] models only a JSON payload and an
    * empty one, and this endpoint takes neither: its body is the markdown source as plain text. The transport applies a
    * request's own headers after the body's content type, replacing it, so this is what reaches the wire — the
    * `MiscellaneousApiSuite` case "renderMarkdownRaw sends the markdown as a plain-text body" pins that down rather
    * than trusting it.
    */
  private val PlainTextUtf8: String = "text/plain; charset=utf-8"

  private val ContentTypeHeader: String = "Content-Type"

  private val ApiSettingsRequest: CodebergRequest =
    settingsRequest(ApiSettingsOperation, "api")

  private val RepositorySettingsRequest: CodebergRequest =
    settingsRequest(RepositorySettingsOperation, "repository")

  private val AttachmentSettingsRequest: CodebergRequest =
    settingsRequest(AttachmentSettingsOperation, "attachment")

  private val SigningKeyRequest: CodebergRequest =
    CodebergRequest(
      operation = SigningKeyOperation,
      method    = HttpMethod.Get,
      path      = List("signing-key.gpg"),
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  private val ApiSettingsDecoder: Decode[ServerApiSettings] =
    WireDecode.of(Json.decoder[ServerApiSettingsDto])(_.toDomain)

  private val RepositorySettingsDecoder: Decode[ServerRepositorySettings] =
    WireDecode.of(Json.decoder[ServerRepositorySettingsDto])(_.toDomain)

  private val AttachmentSettingsDecoder: Decode[ServerAttachmentSettings] =
    WireDecode.of(Json.decoder[ServerAttachmentSettingsDto])(_.toDomain)

  private val SigningKeyDecoder: Decode[Option[SigningKey]] =
    PlainText.decodedAs(SigningKey.from)

  private val MarkdownDecoder: Decode[RenderedMarkdown] =
    PlainText.decodedAs(RenderedMarkdown.apply)

  private def settingsRequest(operation: String, area: String): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Get,
      path      = List("settings", area),
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  private def markdownRequest(request: MarkdownRenderRequest): CodebergRequest =
    CodebergRequest(
      operation = RenderMarkdownOperation,
      method    = HttpMethod.Post,
      path      = List("markdown"),
      query     = Nil,
      headers   = Nil,
      body      = Some(RequestBody.Json(MarkdownOptionDto.fromDomain(request).toJson)),
    )

  /** The one request in the library whose body is not JSON.
    *
    * [[com.worxbend.codeberg4s.core.RequestBody.Json]] is used as the carrier because core models no other non-empty
    * body, and the `Content-Type` header above corrects what that would otherwise put on the wire. A `RequestBody.Text`
    * case in core would remove the workaround; adding one means changing the transport's match as well, which is not
    * this group's to change.
    */
  private def markdownRawRequest(markdown: String): CodebergRequest =
    CodebergRequest(
      operation = RenderMarkdownRawOperation,
      method    = HttpMethod.Post,
      path      = List("markdown", "raw"),
      query     = Nil,
      headers   = List(ContentTypeHeader -> PlainTextUtf8),
      body      = Some(RequestBody.Json(markdown)),
    )
