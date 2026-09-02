package com.worxbend.codeberg4s.miscellaneous

import com.worxbend.codeberg4s.core.CodebergRequest.{read, text, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.miscellaneous.wire.{MarkdownOptionDto, MarkupOptionDto}
import com.worxbend.codeberg4s.repositories.actions.ActionRun
import com.worxbend.codeberg4s.{CodebergError, HttpMethod}

import scala.concurrent.Future

/** The instance-level endpoints: what this deployment is configured to do, what it renders, what templates it ships,
  * and how it identifies itself to the rest of the fediverse.
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
  * '''Five of these do not answer JSON.''' [[signingKey]] returns an armored OpenPGP block, [[sshSigningKey]] returns
  * an OpenSSH authorized-key line, and the three renderers — [[renderMarkdown]], [[renderMarkdownRaw]] and
  * [[renderMarkup]] — return an HTML fragment, all with `Content-Type: text/plain` or `text/html`. They are decoded
  * through [[PlainText]], which parses nothing.
  *
  * ==The template catalogues==
  *
  * [[gitignoreTemplates]], [[labelTemplates]] and [[licenseTemplates]] list what the '''distribution''' ships, and the
  * three by-name reads fetch one entry each. They are the same shape three times over, so they take one
  * [[TemplateName]] rather than three near-identical types; see that type for what it accepts and why it is wider than
  * every other path type in the library.
  *
  * '''None of the six is paged.''' `spec/swagger.v1.json` declares neither `page` nor `limit` for any of them, so the
  * whole catalogue arrives at once and each returns a `Vector` rather than a [[com.worxbend.codeberg4s.paging.Page]] —
  * a page describing a window nobody asked for would be a lie about what was requested. `golden/MANIFEST.md` records
  * the cost of that for `GET /licenses`: roughly 80 KB, in one response, with no way to ask for less.
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
      MiscellaneousDecoders.apiSettings)

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
      MiscellaneousDecoders.repositorySettings)

  /** Reads the instance's attachment limits — `GET /settings/attachment`.
    *
    * '''Failures.''' As [[repositorySettings]]: no field is required, so a JSON object always decodes. `GET` is safe,
    * so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def attachmentSettings(): Future[ServerAttachmentSettings] =
    pipeline.call(MiscellaneousApi.AttachmentSettingsRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousDecoders.attachmentSettings)

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
      MiscellaneousDecoders.signingKey)

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
      MiscellaneousDecoders.markdown)

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
      MiscellaneousDecoders.markdown)

  /** Reads the instance's web-interface settings — `GET /settings/ui`.
    *
    * The one member of the `/settings` family that changes nothing about what the API will accept. Its use is agreeing
    * with the web UI: [[ServerUiSettings.allowedReactions]] is what the reaction endpoints will take, and offering an
    * emoji that is not on that list earns a `422` from them.
    *
    * '''Failures.''' As [[repositorySettings]]: no field is required, so a JSON object always decodes and this
    * operation cannot produce [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] on one. `GET` is safe, so the
    * call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def uiSettings(): Future[ServerUiSettings] =
    pipeline.call(MiscellaneousApi.UiSettingsRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousDecoders.uiSettings)

  /** Reads the instance's default SSH signing key — `GET /signing-key.ssh`.
    *
    * The SSH counterpart of [[signingKey]]: an OpenSSH authorized-key line as `text/plain`, handed back verbatim
    * through [[PlainText]] for an SSH library to parse. A deployment signs with one scheme or the other, so a caller
    * who does not know which asks both and takes whichever answers.
    *
    * '''`None` is a success, not a failure''', exactly as for [[signingKey]] — an instance that signs nothing answers
    * `200` with an empty body. See [[SshSigningKey.from]].
    *
    * '''Unlike [[signingKey]], this endpoint declares a `404`''', which arrives as
    * [[com.worxbend.codeberg4s.CodebergError.Api]] and not as `None`. So "there is no SSH signing key" reaches a caller
    * in two different shapes depending on how the instance chose to say it, and only the `200` one is a success.
    *
    * '''Failures.''' As [[signingKey]], plus the `404` above. It never produces
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]], because nothing is parsed. `GET` is safe, so the call is
    * retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def sshSigningKey(): Future[Option[SshSigningKey]] =
    pipeline.call(MiscellaneousApi.SshSigningKeyRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousDecoders.sshSigningKey)

  /** Lists the `.gitignore` templates the instance ships — `GET /gitignore/templates`.
    *
    * The body is a bare array of names — 297 of them on `golden/misc/gitignore-templates.json` — and each is returned
    * as a [[TemplateName]] so it can be handed straight to [[gitignoreTemplate]]. Not paged; see the class note.
    *
    * '''Failures.''' The returned `Future` fails with [[com.worxbend.codeberg4s.CodebergException]] carrying
    * [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance,
    * [[com.worxbend.codeberg4s.CodebergError.Api]] for a non-2xx status — this endpoint takes no argument to get wrong
    * and needs no credentials, so in practice only a deployment that does not serve it produces one —
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when the body is not an array of usable names, at `$[n]`
    * for the offending element, and [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure
    * outlived the policy. `GET` is safe, so the call is retried under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def gitignoreTemplates(): Future[Vector[TemplateName]] =
    pipeline.call(MiscellaneousApi.GitignoreTemplatesRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousDecoders.templateNames)

  /** Reads one `.gitignore` template, contents and all — `GET /gitignore/templates/{name}`.
    *
    * '''Failures.''' As [[gitignoreTemplates]], with [[com.worxbend.codeberg4s.CodebergError.Api]] status `404` for a
    * template the instance does not ship — the one failure this call can produce that the listing cannot — and
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$.source` when the payload carries no contents. `GET`
    * is safe, so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    *
    * @param name
    *   a name from [[gitignoreTemplates]]
    */
  def gitignoreTemplate(name: TemplateName): Future[GitignoreTemplate] =
    pipeline.call(MiscellaneousApi.gitignoreTemplateRequest(name), RetryEligibility.IdempotentOnly)(using
      MiscellaneousDecoders.gitignoreTemplate)

  /** Lists the label templates the instance ships — `GET /label/templates`.
    *
    * The names of the '''sets''' — `Default`, `Advanced` — not the labels in them; [[labelTemplate]] reads one set.
    * Same bare-array shape as [[gitignoreTemplates]] and not paged, for the same reason.
    *
    * '''Failures.''' As [[gitignoreTemplates]].
    */
  def labelTemplates(): Future[Vector[TemplateName]] =
    pipeline.call(MiscellaneousApi.LabelTemplatesRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousDecoders.templateNames)

  /** Reads every label in one template — `GET /label/templates/{name}`.
    *
    * '''The body is an array, not an object.''' A label template is a set of labels, so this returns all of them; the
    * elements are [[TemplateLabel]] and not [[com.worxbend.codeberg4s.issues.Label]], because a template label has
    * never been created anywhere and therefore has no identifier — see [[TemplateLabel]].
    *
    * '''Failures.''' As [[gitignoreTemplate]], with [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] reported
    * at `$[n].name` for an element that names no label. A colour the instance spelled unrecognisably is '''not''' a
    * failure; it arrives as `None`.
    *
    * @param name
    *   a name from [[labelTemplates]]
    */
  def labelTemplate(name: TemplateName): Future[Vector[TemplateLabel]] =
    pipeline.call(MiscellaneousApi.labelTemplateRequest(name), RetryEligibility.IdempotentOnly)(using
      MiscellaneousDecoders.templateLabels)

  /** Lists the license templates the instance ships — `GET /licenses`.
    *
    * '''Names and URLs only, and it is still large.''' `golden/MANIFEST.md` measured roughly 80 KB for this response
    * with no `limit` support, which is why the entries carry no license text: [[licenseTemplate]] fetches one body at a
    * time. Not paged; see the class note.
    *
    * '''Failures.''' As [[gitignoreTemplates]], with [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] reported
    * at `$[n].name` for an entry that names nothing.
    */
  def licenseTemplates(): Future[Vector[LicenseTemplateSummary]] =
    pipeline.call(MiscellaneousApi.LicenseTemplatesRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousDecoders.licenseTemplates)

  /** Reads one license template, text and all — `GET /licenses/{name}`.
    *
    * '''Placeholders are left alone.''' [[LicenseTemplate.body]] is the file Forgejo would copy into a repository, with
    * `[year]` and `[fullname]` exactly as they are; substituting them is the caller's job.
    *
    * '''Failures.''' As [[gitignoreTemplate]], with [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at
    * `$.body` when the payload carries no license text.
    *
    * @param name
    *   a [[LicenseTemplateSummary.name]] from [[licenseTemplates]]
    */
  def licenseTemplate(name: TemplateName): Future[LicenseTemplate] =
    pipeline.call(MiscellaneousApi.licenseTemplateRequest(name), RetryEligibility.IdempotentOnly)(using
      MiscellaneousDecoders.licenseTemplate)

  /** Renders a markup document of any supported language as HTML — `POST /markup`.
    *
    * The generalisation of [[renderMarkdown]]: the same rendering pipeline, but the language is chosen by
    * [[MarkupRenderRequest.mode]] rather than assumed. [[MarkupMode.File]] makes the instance pick the renderer from
    * [[MarkupRenderRequest.filePath]], which is how an Org-mode or AsciiDoc document is rendered the way the web UI
    * would render it. Prefer [[renderMarkdown]] when the document is markdown; it is the narrower request.
    *
    * '''Retried, and for exactly the reason [[renderMarkdown]] is.''' Rendering has no effect on the instance — nothing
    * is created, nothing is stored, and the same input always produces the same output — so repeating it after a `503`
    * or a dropped connection costs a little CPU and cannot duplicate anything. It therefore runs under
    * [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]] rather than the
    * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]] every other mutating method uses. These three renderers
    * are the only `POST`s in the library that are retried.
    *
    * '''The result is a [[RenderedMarkdown]]''', which is the type the markdown renderers return, because the two are
    * the same thing: an HTML fragment the instance produced and sanitised, carrying the trust boundary that type
    * documents. A second one-field type spelled `RenderedMarkup` would differ from it in the name only.
    *
    * '''Failures.''' As [[renderMarkdown]]. A `422` is what a [[MarkupMode.File]] request with no
    * [[MarkupRenderRequest.filePath]] earns, and what an extension the instance has no renderer for earns.
    *
    * @param request
    *   the document and how to render it; [[MarkupRenderRequest.of]] and [[MarkupRenderRequest.ofFile]] build the two
    *   common cases
    */
  def renderMarkup(request: MarkupRenderRequest): Future[RenderedMarkdown] =
    pipeline.call(MiscellaneousApi.markupRequest(request), RetryEligibility.AlwaysRetry)(using
      MiscellaneousDecoders.markdown)

  /** Reads the instance's NodeInfo 2.1 document — `GET /nodeinfo`.
    *
    * The federation metadata every fediverse server publishes: which software, which protocols, how many accounts. A
    * caller deciding whether an instance can be federated with reads this, not [[com.worxbend.codeberg4s.VersionApi]] —
    * the version endpoint says which Forgejo, this says whether it federates at all.
    *
    * '''Expect this to be missing.''' `golden/MANIFEST.md` records `GET /nodeinfo` answering `404` on codeberg.org,
    * with a '''plain-text''' body rather than the usual JSON error shape. That `404` arrives as
    * [[com.worxbend.codeberg4s.CodebergError.Api]] with an [[com.worxbend.codeberg4s.ApiErrorBody.Empty]] payload,
    * because the body could not be parsed as one — the status is never masked by an unreadable body.
    *
    * '''Failures.''' As [[gitignoreTemplates]], with [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at
    * `$.version`, `$.software` or `$.software.name` when the document identifies neither its schema nor the software it
    * describes.
    */
  def nodeInfo(): Future[NodeInfo] =
    pipeline.call(MiscellaneousApi.NodeInfoRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousDecoders.nodeInfo)

  /** Reads the workflow run the calling token belongs to — `GET /actions/run`.
    *
    * The one endpoint in the library meant to be called '''from inside a running workflow''': it takes no argument
    * because the credential is the argument. Forgejo resolves the automatic Actions token to the job that holds it and
    * answers with that job's run, which is how a step finds out what started it without being told.
    *
    * ==The authentication scheme is a known risk, and this library cannot remove it==
    *
    * The spec's own description says the automatic Actions token "must be used as the authentication mechanism
    * (`Authorization: Bearer ${{ forgejo.token }}`); other types of tokens cannot be used", and that the job must still
    * be running for the request to succeed. This library sends [[com.worxbend.codeberg4s.auth.Auth.Token]] as
    * `Authorization: token <value>`, which is the one scheme it produces — [[com.worxbend.codeberg4s.auth.Auth]] has no
    * `Bearer` case. So a deployment that accepts only the `Bearer` spelling answers `401` here and there is nothing a
    * caller can do about it from this API. Adding a `Bearer` scheme is a change to `Auth` and to the transport adapter,
    * neither of which this group owns.
    *
    * '''The result is the same [[com.worxbend.codeberg4s.repositories.actions.ActionRun]]''' the repository Actions
    * group returns, decoded by the same model. One run object, one type.
    *
    * '''Failures.''' As [[gitignoreTemplates]], with [[com.worxbend.codeberg4s.CodebergError.Api]] status `401` for the
    * scheme mismatch above or a token that is not an Actions token, `404` for a job that has already finished, and
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$.id` when the payload carries no run identifier.
    * `GET` is safe, so the call is retried under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
    */
  def actionsRun(): Future[ActionRun] =
    pipeline.call(MiscellaneousApi.ActionsRunRequest, RetryEligibility.IdempotentOnly)(using
      MiscellaneousDecoders.actionsRun)

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

  /** The stable operation id of [[MiscellaneousApi.uiSettings]]. */
  val UiSettingsOperation: String = "settings.ui"

  /** The stable operation id of [[MiscellaneousApi.sshSigningKey]]. */
  val SshSigningKeyOperation: String = "misc.sshSigningKey"

  /** The stable operation id of [[MiscellaneousApi.gitignoreTemplates]]. */
  val GitignoreTemplatesOperation: String = "misc.gitignoreTemplates.list"

  /** The stable operation id of the single-template read on [[MiscellaneousApi.gitignoreTemplate]]. */
  val GitignoreTemplateOperation: String = "misc.gitignoreTemplates.get"

  /** The stable operation id of [[MiscellaneousApi.labelTemplates]]. */
  val LabelTemplatesOperation: String = "misc.labelTemplates.list"

  /** The stable operation id of the single-template read on [[MiscellaneousApi.labelTemplate]]. */
  val LabelTemplateOperation: String = "misc.labelTemplates.get"

  /** The stable operation id of [[MiscellaneousApi.licenseTemplates]]. */
  val LicenseTemplatesOperation: String = "misc.licenses.list"

  /** The stable operation id of the single-template read on [[MiscellaneousApi.licenseTemplate]]. */
  val LicenseTemplateOperation: String = "misc.licenses.get"

  /** The stable operation id of [[MiscellaneousApi.renderMarkup]]. */
  val RenderMarkupOperation: String = "misc.renderMarkup"

  /** The stable operation id of [[MiscellaneousApi.nodeInfo]]. */
  val NodeInfoOperation: String = "misc.nodeInfo"

  /** The stable operation id of [[MiscellaneousApi.actionsRun]]. */
  val ActionsRunOperation: String = "misc.actionsRun"

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

    /** [[MiscellaneousApi.uiSettings]] with its failure as a value. */
    def uiSettings(): Future[Either[CodebergError, ServerUiSettings]] =
      exec.attempt(rail.uiSettings())

    /** [[MiscellaneousApi.sshSigningKey]] with its failure as a value. `Right(None)` still means the instance signs
      * nothing with SSH.
      */
    def sshSigningKey(): Future[Either[CodebergError, Option[SshSigningKey]]] =
      exec.attempt(rail.sshSigningKey())

    /** [[MiscellaneousApi.gitignoreTemplates]] with its failure as a value. */
    def gitignoreTemplates(): Future[Either[CodebergError, Vector[TemplateName]]] =
      exec.attempt(rail.gitignoreTemplates())

    /** The single-template read on [[MiscellaneousApi.gitignoreTemplate]], with its failure as a value. */
    def gitignoreTemplate(name: TemplateName): Future[Either[CodebergError, GitignoreTemplate]] =
      exec.attempt(rail.gitignoreTemplate(name))

    /** [[MiscellaneousApi.labelTemplates]] with its failure as a value. */
    def labelTemplates(): Future[Either[CodebergError, Vector[TemplateName]]] =
      exec.attempt(rail.labelTemplates())

    /** The single-template read on [[MiscellaneousApi.labelTemplate]], with its failure as a value. */
    def labelTemplate(name: TemplateName): Future[Either[CodebergError, Vector[TemplateLabel]]] =
      exec.attempt(rail.labelTemplate(name))

    /** [[MiscellaneousApi.licenseTemplates]] with its failure as a value. */
    def licenseTemplates(): Future[Either[CodebergError, Vector[LicenseTemplateSummary]]] =
      exec.attempt(rail.licenseTemplates())

    /** The single-template read on [[MiscellaneousApi.licenseTemplate]], with its failure as a value. */
    def licenseTemplate(name: TemplateName): Future[Either[CodebergError, LicenseTemplate]] =
      exec.attempt(rail.licenseTemplate(name))

    /** [[MiscellaneousApi.renderMarkup]] with its failure as a value. */
    def renderMarkup(request: MarkupRenderRequest): Future[Either[CodebergError, RenderedMarkdown]] =
      exec.attempt(rail.renderMarkup(request))

    /** [[MiscellaneousApi.nodeInfo]] with its failure as a value. */
    def nodeInfo(): Future[Either[CodebergError, NodeInfo]] =
      exec.attempt(rail.nodeInfo())

    /** [[MiscellaneousApi.actionsRun]] with its failure as a value. */
    def actionsRun(): Future[Either[CodebergError, ActionRun]] =
      exec.attempt(rail.actionsRun())

  /** The `Content-Type` `POST /markdown/raw` consumes: its body is the markdown source itself, not a JSON document. */
  private val PlainTextUtf8: String = "text/plain; charset=utf-8"

  private val ApiSettingsRequest: CodebergRequest =
    settingsRequest(ApiSettingsOperation, "api")

  private val RepositorySettingsRequest: CodebergRequest =
    settingsRequest(RepositorySettingsOperation, "repository")

  private val AttachmentSettingsRequest: CodebergRequest =
    settingsRequest(AttachmentSettingsOperation, "attachment")

  /** The path of the gitignore catalogue, written once because both its operations start from it. */
  private val GitignoreTemplatesPath: List[String] = List("gitignore", "templates")

  /** The path of the label-template catalogue; note that the first segment is singular, `label`, and that the by-name
    * form hangs off the same two segments.
    */
  private val LabelTemplatesPath: List[String] = List("label", "templates")

  /** The path of the license catalogue. One segment, unlike the other two catalogues. */
  private val LicensesPath: List[String] = List("licenses")

  private val UiSettingsRequest: CodebergRequest =
    settingsRequest(UiSettingsOperation, "ui")

  private val SigningKeyRequest: CodebergRequest =
    read(SigningKeyOperation, List("signing-key.gpg"), Nil)

  private val SshSigningKeyRequest: CodebergRequest =
    read(SshSigningKeyOperation, List("signing-key.ssh"), Nil)

  private val GitignoreTemplatesRequest: CodebergRequest =
    read(GitignoreTemplatesOperation, GitignoreTemplatesPath, Nil)

  private val LabelTemplatesRequest: CodebergRequest =
    read(LabelTemplatesOperation, LabelTemplatesPath, Nil)

  private val LicenseTemplatesRequest: CodebergRequest =
    read(LicenseTemplatesOperation, LicensesPath, Nil)

  private val NodeInfoRequest: CodebergRequest =
    read(NodeInfoOperation, List("nodeinfo"), Nil)

  private val ActionsRunRequest: CodebergRequest =
    read(ActionsRunOperation, List("actions", "run"), Nil)

  private def settingsRequest(operation: String, area: String): CodebergRequest =
    read(operation, List("settings", area), Nil)

  private def gitignoreTemplateRequest(name: TemplateName): CodebergRequest =
    read(GitignoreTemplateOperation, GitignoreTemplatesPath :+ name.value, Nil)

  private def labelTemplateRequest(name: TemplateName): CodebergRequest =
    read(LabelTemplateOperation, LabelTemplatesPath :+ name.value, Nil)

  private def licenseTemplateRequest(name: TemplateName): CodebergRequest =
    read(LicenseTemplateOperation, LicensesPath :+ name.value, Nil)

  private def markupRequest(request: MarkupRenderRequest): CodebergRequest =
    write(RenderMarkupOperation, HttpMethod.Post, List("markup"), MarkupOptionDto.fromDomain(request).toJson)

  private def markdownRequest(request: MarkdownRenderRequest): CodebergRequest =
    write(RenderMarkdownOperation, HttpMethod.Post, List("markdown"), MarkdownOptionDto.fromDomain(request).toJson)

  /** The one request in this group whose body is not JSON: the markdown source is sent verbatim as plain text. */
  private def markdownRawRequest(markdown: String): CodebergRequest =
    text(RenderMarkdownRawOperation, HttpMethod.Post, List("markdown", "raw"), markdown, PlainTextUtf8)
