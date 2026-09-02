package com.worxbend.codeberg4s.miscellaneous

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.miscellaneous.wire.{
  GitignoreTemplateDto,
  LicenseTemplateDto,
  LicenseTemplateSummaryDto,
  NodeInfoDto,
  ServerApiSettingsDto,
  ServerAttachmentSettingsDto,
  ServerRepositorySettingsDto,
  ServerUiSettingsDto,
  TemplateLabelDto,
  TemplateNamesDto
}
import com.worxbend.codeberg4s.repositories.actions.ActionRun
import com.worxbend.codeberg4s.repositories.actions.wire.ActionRunDto

/** Every response shape [[MiscellaneousApi]] can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call: a decoder is a function
  * from a body to a value, and allocating a new one per request would be waste with no upside.
  *
  * '''Four of these parse no JSON.''' [[signingKey]], [[sshSigningKey]] and [[markdown]] read a `text/plain` or
  * `text/html` body through [[PlainText]], which parses nothing, and [[templateNames]] reads a bare array of strings.
  * See [[MiscellaneousApi]] for which endpoint answers what.
  */
private[miscellaneous] object MiscellaneousDecoders:

  /** The instance's API limits and defaults. */
  val apiSettings: Decode[ServerApiSettings] =
    WireDecode.single(Json.decoder[ServerApiSettingsDto])(_.toDomain)

  /** What the instance allows repositories to do. */
  val repositorySettings: Decode[ServerRepositorySettings] =
    WireDecode.single(Json.decoder[ServerRepositorySettingsDto])(_.toDomain)

  /** The instance's attachment limits and allowed types. */
  val attachmentSettings: Decode[ServerAttachmentSettings] =
    WireDecode.single(Json.decoder[ServerAttachmentSettingsDto])(_.toDomain)

  /** The armored OpenPGP block, absent when the instance signs nothing. */
  val signingKey: Decode[Option[SigningKey]] =
    PlainText.decodedAs(SigningKey.from)

  /** A rendered HTML fragment, read verbatim from a `text/html` body. */
  val markdown: Decode[RenderedMarkdown] =
    PlainText.decodedAs(RenderedMarkdown.apply)

  /** The instance's web-UI defaults. */
  val uiSettings: Decode[ServerUiSettings] =
    WireDecode.single(Json.decoder[ServerUiSettingsDto])(_.toDomain)

  /** The OpenSSH authorized-key line, absent when the instance signs nothing. */
  val sshSigningKey: Decode[Option[SshSigningKey]] =
    PlainText.decodedAs(SshSigningKey.from)

  /** Shared by both catalogues whose body is a bare array of strings; see
    * [[com.worxbend.codeberg4s.miscellaneous.wire.TemplateNamesDto]].
    */
  val templateNames: Decode[Vector[TemplateName]] =
    WireDecode.single(Json.decoder[Vector[String]])(TemplateNamesDto.toDomainAll(JsonPath.Root, _))

  /** One gitignore template, with its contents. */
  val gitignoreTemplate: Decode[GitignoreTemplate] =
    WireDecode.single(Json.decoder[GitignoreTemplateDto])(_.toDomain)

  /** A bare array of label-template entries. */
  val templateLabels: Decode[Vector[TemplateLabel]] =
    WireDecode.vector(Json.decoder[Vector[TemplateLabelDto]])

  /** A bare array of license summaries — the catalogue, without the license texts. */
  val licenseTemplates: Decode[Vector[LicenseTemplateSummary]] =
    WireDecode.vector(Json.decoder[Vector[LicenseTemplateSummaryDto]])

  /** One license template, with its full text. */
  val licenseTemplate: Decode[LicenseTemplate] =
    WireDecode.single(Json.decoder[LicenseTemplateDto])(_.toDomain)

  /** The NodeInfo document the instance publishes to the fediverse. */
  val nodeInfo: Decode[NodeInfo] =
    WireDecode.single(Json.decoder[NodeInfoDto])(_.toDomain)

  /** The run model the repository Actions group owns, reused verbatim: `GET /actions/run` answers the same `ActionRun`
    * object, so it is read by the same DTO rather than by a second copy of it.
    */
  val actionsRun: Decode[ActionRun] =
    WireDecode.single(Json.decoder[ActionRunDto])(_.toDomain)
