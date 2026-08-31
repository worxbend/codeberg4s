package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.hooks.IssueConfig
import com.worxbend.codeberg4s.repositories.hooks.IssueConfigValidation
import com.worxbend.codeberg4s.repositories.hooks.IssueContactLink

/** Forgejo's `IssueConfigContactLink` model — one alternative to opening an issue.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[WebhookDto]].
  */
final case class IssueContactLinkDto(
    name: Option[String],
    url: Option[String],
    about: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `name` and `url` are both required, because a contact link is a label and a destination and neither half is
    * optional in any rendering of one: a link with no label cannot be shown, and a link with no destination goes
    * nowhere. `about` is the explanatory sentence and is genuinely optional.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, IssueContactLink] =
    for
      label       <- Wire.required(at, "name", name)
      destination <- Wire.required(at, "url", url)
    yield IssueContactLink(name = label, url = destination, about = about)

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, IssueContactLink] =
    toDomainAt(JsonPath.Root)

object IssueContactLinkDto:

  /** Reads an `IssueConfigContactLink` object. Absent and `null` are the same thing; see [[JsonFields]]. */
  given JsonDecoder[IssueContactLinkDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the config that embeds these. */
  def fromFields(fields: JsonFields): IssueContactLinkDto =
    IssueContactLinkDto(
      name  = fields.text("name"),
      url   = fields.text("url"),
      about = fields.text("about"),
    )

/** Forgejo's `IssueConfig` model — the repository's `ISSUE_TEMPLATE` config file, parsed.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[WebhookDto]].
  */
final case class IssueConfigDto(
    blankIssuesEnabled: Option[Boolean],
    contactLinks: Vector[IssueContactLinkDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Nothing at this level is required — a repository with no config at all answers with an object that says nothing —
    * but a contact link that is present and incomplete fails the whole conversion at its own index, so a caller never
    * receives a link with a missing half.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, IssueConfig] =
    ArrayElements
      .convert(at.field(IssueConfigDto.ContactLinksKey), contactLinks)((dto, path) => dto.toDomainAt(path))
      .map(links => IssueConfig(blankIssuesEnabled = blankIssuesEnabled, contactLinks = links))

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, IssueConfig] =
    toDomainAt(JsonPath.Root)

object IssueConfigDto:

  /** The wire key the contact links sit under. */
  val ContactLinksKey: String = "contact_links"

  /** Reads an `IssueConfig` object. A missing or `null` `contact_links` key is an empty list, not a failure. */
  given JsonDecoder[IssueConfigDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): IssueConfigDto =
    IssueConfigDto(
      blankIssuesEnabled = fields.boolean("blank_issues_enabled"),
      contactLinks       = fields.nestedAll(ContactLinksKey).map(IssueContactLinkDto.fromFields),
    )

/** Forgejo's `IssueConfigValidation` model — the verdict on a repository's issue config.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[WebhookDto]].
  */
final case class IssueConfigValidationDto(
    valid: Option[Boolean],
    message: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `valid` is required, and deliberately so: reading an absent verdict as `false` would report every payload this
    * library failed to understand as an invalid config, which is a much worse answer than saying the payload did not
    * decode. See [[com.worxbend.codeberg4s.repositories.hooks.IssueConfigValidation]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, IssueConfigValidation] =
    Wire
      .required(at, "valid", valid)
      .map(verdict => IssueConfigValidation(isValid = verdict, message = message))

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, IssueConfigValidation] =
    toDomainAt(JsonPath.Root)

object IssueConfigValidationDto:

  /** Reads an `IssueConfigValidation` object. Absent and `null` are the same thing; see [[JsonFields]]. */
  given JsonDecoder[IssueConfigValidationDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): IssueConfigValidationDto =
    IssueConfigValidationDto(
      valid   = fields.boolean("valid"),
      message = fields.text("message"),
    )
