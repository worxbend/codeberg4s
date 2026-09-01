package com.worxbend.codeberg4s.quota.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.quota.{AttachmentContainer, QuotaUsedArtifact, QuotaUsedAttachment, QuotaUsedPackage}

/** Forgejo's `QuotaUsedArtifact` model — one artifact counting towards a quota.
  *
  * '''Derived from `spec/swagger.v1.json`, not from captured responses''' — see
  * [[com.worxbend.codeberg4s.quota.QuotaInfo]] for why no quota fixture exists. The organisation and account listings
  * answer the same three models, so they are read here once for both.
  *
  * ==Nothing in these three listings is required, and that is a deliberate reading==
  *
  * None of the quota usage models carries an identifier: they answer "what is taking up space", not "which object is
  * this". There is therefore no field whose absence would leave a value nobody can use, so every conversion in this
  * file succeeds and a sparse element arrives as a sparse element. The alternative — requiring `name`, say — would fail
  * a whole page of a diagnostic listing over one entry the instance described poorly, which is the wrong trade for a
  * report a caller is reading to find out why they are over quota.
  *
  * That is also why these models have no `toDomainAt`: a path exists to say where a conversion failed, and none of
  * these can. The array conversions still go through [[com.worxbend.codeberg4s.codec.ArrayElements.convert]] so that
  * the day one of these fields becomes load-bearing, the position reporting is already in place.
  *
  * ==`size` is bytes and `type` is a keyword==
  *
  * `size` is `format: int64` on all three and is carried as a `Long` without scaling. `QuotaUsedPackage.type` becomes
  * `packageType` in Scala, because `type` is a keyword; the wire spelling stays `type` and is written exactly once, in
  * [[QuotaUsedPackageDto.fromFields]].
  */
final case class QuotaUsedArtifactDto(name: Option[String], size: Option[Long], htmlUrl: Option[String]):

  /** Converts to the domain. Cannot fail; see the class note. */
  def toDomain: Either[DecodeFailure, QuotaUsedArtifact] =
    Right(QuotaUsedArtifact(name = name, sizeBytes = size, htmlUrl = htmlUrl))

object QuotaUsedArtifactDto:

  /** Reads a `QuotaUsedArtifact` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[QuotaUsedArtifactDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): QuotaUsedArtifactDto =
    QuotaUsedArtifactDto(name = fields.text("name"), size = fields.number("size"), htmlUrl = fields.text("html_url"))

  /** Converts a decoded array, ready to report the position of an element that ever starts being able to fail. */
  def toDomainAll(
      base: JsonPath,
      dtos: Vector[QuotaUsedArtifactDto],
  ): Either[DecodeFailure, Vector[QuotaUsedArtifact]] =
    ArrayElements.convert(base, dtos)((dto, _) => dto.toDomain)

/** Forgejo's `QuotaUsedAttachment` model — one attachment counting towards a quota.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[QuotaInfoDto]], and see
  * [[QuotaUsedArtifactDto]] for why nothing here is required.
  *
  * `contained_in` is declared inline in the spec rather than as a named definition, so there is no DTO for it: its two
  * links are lifted to the top level of this DTO and folded back into
  * [[com.worxbend.codeberg4s.quota.AttachmentContainer]] on the way into the domain. The container is reported as
  * absent when it carried neither link, so a caller holding one is holding at least one URL.
  */
final case class QuotaUsedAttachmentDto(
    name: Option[String],
    size: Option[Long],
    apiUrl: Option[String],
    containedInApiUrl: Option[String],
    containedInHtmlUrl: Option[String],
):

  /** Converts to the domain. Cannot fail; see [[QuotaUsedArtifactDto]]. */
  def toDomain: Either[DecodeFailure, QuotaUsedAttachment] =
    Right(
      QuotaUsedAttachment(
        name        = name,
        sizeBytes   = size,
        apiUrl      = apiUrl,
        containedIn = container,
      )
    )

  /** The containing object, absent when the payload named neither of its two links. */
  private def container: Option[AttachmentContainer] =
    Option.when(containedInApiUrl.isDefined || containedInHtmlUrl.isDefined)(
      AttachmentContainer(apiUrl = containedInApiUrl, htmlUrl = containedInHtmlUrl)
    )

object QuotaUsedAttachmentDto:

  /** The wire key of the object describing where an attachment hangs. */
  val ContainedInKey: String = "contained_in"

  /** Reads a `QuotaUsedAttachment` object. */
  given JsonDecoder[QuotaUsedAttachmentDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, lifting the two `contained_in` links to the top level. */
  def fromFields(fields: JsonFields): QuotaUsedAttachmentDto =
    val container = fields.nested(ContainedInKey).getOrElse(JsonFields.Empty)

    QuotaUsedAttachmentDto(
      name               = fields.text("name"),
      size               = fields.number("size"),
      apiUrl             = fields.text("api_url"),
      containedInApiUrl  = container.text("api_url"),
      containedInHtmlUrl = container.text("html_url"),
    )

  /** Converts a decoded array; see [[QuotaUsedArtifactDto.toDomainAll]]. */
  def toDomainAll(
      base: JsonPath,
      dtos: Vector[QuotaUsedAttachmentDto],
  ): Either[DecodeFailure, Vector[QuotaUsedAttachment]] =
    ArrayElements.convert(base, dtos)((dto, _) => dto.toDomain)

/** Forgejo's `QuotaUsedPackage` model — one package version counting towards a quota.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[QuotaInfoDto]], and see
  * [[QuotaUsedArtifactDto]] for why nothing here is required.
  */
final case class QuotaUsedPackageDto(
    name: Option[String],
    version: Option[String],
    packageType: Option[String],
    size: Option[Long],
    htmlUrl: Option[String],
):

  /** Converts to the domain. Cannot fail; see [[QuotaUsedArtifactDto]]. */
  def toDomain: Either[DecodeFailure, QuotaUsedPackage] =
    Right(
      QuotaUsedPackage(
        name        = name,
        version     = version,
        packageType = packageType,
        sizeBytes   = size,
        htmlUrl     = htmlUrl,
      )
    )

object QuotaUsedPackageDto:

  /** Reads a `QuotaUsedPackage` object. */
  given JsonDecoder[QuotaUsedPackageDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. The wire spelling `type` is written here and nowhere else. */
  def fromFields(fields: JsonFields): QuotaUsedPackageDto =
    QuotaUsedPackageDto(
      name        = fields.text("name"),
      version     = fields.text("version"),
      packageType = fields.text("type"),
      size        = fields.number("size"),
      htmlUrl     = fields.text("html_url"),
    )

  /** Converts a decoded array; see [[QuotaUsedArtifactDto.toDomainAll]]. */
  def toDomainAll(
      base: JsonPath,
      dtos: Vector[QuotaUsedPackageDto],
  ): Either[DecodeFailure, Vector[QuotaUsedPackage]] =
    ArrayElements.convert(base, dtos)((dto, _) => dto.toDomain)
