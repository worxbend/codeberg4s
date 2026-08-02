package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.repositories.hooks.wire.GitHookDto
import com.worxbend.codeberg4s.repositories.hooks.wire.IssueConfigDto
import com.worxbend.codeberg4s.repositories.hooks.wire.IssueConfigValidationDto
import com.worxbend.codeberg4s.repositories.hooks.wire.IssueTemplateDto
import com.worxbend.codeberg4s.repositories.hooks.wire.RepositoryFlagWire
import com.worxbend.codeberg4s.repositories.hooks.wire.WebhookDto
import com.worxbend.codeberg4s.repositories.hooks.wire.WikiCommitDto
import com.worxbend.codeberg4s.repositories.hooks.wire.WikiCommitListDto
import com.worxbend.codeberg4s.repositories.hooks.wire.WikiPageDto
import com.worxbend.codeberg4s.repositories.hooks.wire.WikiPageMetaDto

/** Every response shape the four API classes of this package can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call, exactly as
  * [[com.worxbend.codeberg4s.repositories.RepositoryDecoders]] does.
  *
  * ==Three body shapes==
  *
  * Most listings here are the bare JSON array the rest of the API returns. The wiki revision listing is not: it wraps
  * its results in `{"commits", "count"}` — see [[com.worxbend.codeberg4s.repositories.hooks.wire.WikiCommitListDto]] —
  * which is why its elements are reported at `$.commits[n]` and not at `$[n]`. And the flag listing is an array of bare
  * strings with no object anywhere, which is why it has a conversion rather than a DTO; see
  * [[com.worxbend.codeberg4s.repositories.hooks.wire.RepositoryFlagWire]].
  */
private[hooks] object RepositoryHookDecoders:

  /** Where the elements of a `{"commits", "count"}` envelope sit, so a bad element reports `$.commits[2].sha`. */
  private val RevisionsPath: JsonPath =
    JsonPath.Root.field(WikiCommitListDto.EntriesKey)

  /** One webhook object. */
  val webhook: Decode[Webhook] =
    WireDecode.of(Json.decoder[WebhookDto])(_.toDomain)

  /** A bare array of webhook objects, as the hook listing returns it. */
  val webhooks: Decode[Vector[Webhook]] =
    WireDecode.of(Json.decoder[Vector[WebhookDto]])(dtos => WebhookDto.toDomainAll(JsonPath.Root, dtos))

  /** One Git hook object. */
  val gitHook: Decode[GitHook] =
    WireDecode.of(Json.decoder[GitHookDto])(_.toDomain)

  /** A bare array of Git hook objects. */
  val gitHooks: Decode[Vector[GitHook]] =
    WireDecode.of(Json.decoder[Vector[GitHookDto]])(dtos => GitHookDto.toDomainAll(JsonPath.Root, dtos))

  /** A bare array of flag names, validated as path segments on the way into the domain. */
  val flags: Decode[Vector[RepositoryFlag]] =
    WireDecode.of(Json.decoder[Vector[String]])(values => RepositoryFlagWire.toDomainAll(JsonPath.Root, values))

  /** One wiki page, content included. */
  val wikiPage: Decode[WikiPage] =
    WireDecode.of(Json.decoder[WikiPageDto])(_.toDomain)

  /** A bare array of wiki page listing entries — metadata only, no content. */
  val wikiPages: Decode[Vector[WikiPageMeta]] =
    WireDecode.of(Json.decoder[Vector[WikiPageMetaDto]])(dtos => WikiPageMetaDto.toDomainAll(JsonPath.Root, dtos))

  /** The `{"commits", "count"}` envelope the revision listing returns, unwrapped to its revisions. */
  val wikiRevisions: Decode[Vector[WikiCommit]] =
    WireDecode.of(Json.decoder[WikiCommitListDto]): envelope =>
      WikiCommitDto.toDomainAll(RepositoryHookDecoders.RevisionsPath, envelope.entries)

  /** The repository's issue configuration. */
  val issueConfig: Decode[IssueConfig] =
    WireDecode.of(Json.decoder[IssueConfigDto])(_.toDomain)

  /** The verdict on the repository's issue configuration. */
  val issueConfigValidation: Decode[IssueConfigValidation] =
    WireDecode.of(Json.decoder[IssueConfigValidationDto])(_.toDomain)

  /** A bare array of issue template objects. */
  val issueTemplates: Decode[Vector[IssueTemplate]] =
    WireDecode.of(Json.decoder[Vector[IssueTemplateDto]])(dtos => IssueTemplateDto.toDomainAll(JsonPath.Root, dtos))
