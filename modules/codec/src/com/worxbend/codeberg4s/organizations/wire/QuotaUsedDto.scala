package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.organizations.QuotaArtifact
import com.worxbend.codeberg4s.organizations.QuotaAttachment
import com.worxbend.codeberg4s.organizations.QuotaAttachmentContext
import com.worxbend.codeberg4s.organizations.QuotaPackage
import com.worxbend.codeberg4s.repositories.wire.Elements

/** Forgejo's `QuotaUsedArtifact`, `QuotaUsedAttachment` and `QuotaUsedPackage` models — the elements of the three quota
  * usage listings.
  *
  * '''Derived from `spec/swagger.v1.json`, not from captured responses''' — see
  * [[com.worxbend.codeberg4s.organizations.QuotaInfo]] for why no quota fixture exists.
  *
  * ==None of the three can fail to convert, so none of them has a `toDomainAt`==
  *
  * Every property of all three models is optional and none of them addresses the thing being described: there is no id,
  * and the URLs are links rather than handles — see [[com.worxbend.codeberg4s.organizations.QuotaArtifact]]. A required
  * field here would be one this library invented.
  *
  * Every other DTO in this package offers `toDomainAt(at: JsonPath)` alongside `toDomain`, so that a failure inside an
  * array element is reported at `$[2].id` rather than at `$`. These three do not, because there is no failure to
  * position: an overload taking a path it could never use would be a parameter the compiler rejects under
  * `-Wunused:all` and a promise the model cannot keep. Their `toDomainAll` therefore discards the index it is handed.
  *
  * ==`size` is bytes and `type` is a keyword==
  *
  * `size` is `format: int64` on all three and is carried as a `Long` without scaling. `QuotaUsedPackage.type` becomes
  * `packageType` in Scala, because `type` is a keyword; the wire spelling stays `type` and is written exactly once, in
  * [[QuotaPackageDto.fromFields]].
  */
final case class QuotaArtifactDto(name: Option[String], size: Option[Long], htmlUrl: Option[String]):

  /** Converts to the domain. Total; see the object note on why there is no path-carrying overload. */
  def toDomain: Either[DecodeFailure, QuotaArtifact] =
    Right(QuotaArtifact(name = name, sizeBytes = size, htmlUrl = htmlUrl))

object QuotaArtifactDto:

  /** Reads a `QuotaUsedArtifact` object. Absent and `null` are the same thing for every field. */
  given upickle.default.Reader[QuotaArtifactDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): QuotaArtifactDto =
    QuotaArtifactDto(name = fields.text("name"), size = fields.number("size"), htmlUrl = fields.text("html_url"))

  /** Converts a decoded array. The element path is discarded; see the object note. */
  def toDomainAll(base: JsonPath, dtos: Vector[QuotaArtifactDto]): Either[DecodeFailure, Vector[QuotaArtifact]] =
    Elements.convert(base, dtos)((dto, _) => dto.toDomain)

/** The `contained_in` object of `QuotaUsedAttachment` — an inline object in the spec with no definition of its own. */
final case class QuotaAttachmentContextDto(apiUrl: Option[String], htmlUrl: Option[String]):

  /** Converts to the domain. Total. */
  def toDomain: QuotaAttachmentContext =
    QuotaAttachmentContext(apiUrl = apiUrl, htmlUrl = htmlUrl)

object QuotaAttachmentContextDto:

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): QuotaAttachmentContextDto =
    QuotaAttachmentContextDto(apiUrl = fields.text("api_url"), htmlUrl = fields.text("html_url"))

/** Forgejo's `QuotaUsedAttachment` model, field for field. */
final case class QuotaAttachmentDto(
    name: Option[String],
    size: Option[Long],
    apiUrl: Option[String],
    containedIn: Option[QuotaAttachmentContextDto],
):

  /** Converts to the domain. Total; see the object note on [[QuotaArtifactDto]]. */
  def toDomain: Either[DecodeFailure, QuotaAttachment] =
    Right(
      QuotaAttachment(
        name        = name,
        sizeBytes   = size,
        apiUrl      = apiUrl,
        containedIn = containedIn.map(_.toDomain),
      )
    )

object QuotaAttachmentDto:

  /** Reads a `QuotaUsedAttachment` object. */
  given upickle.default.Reader[QuotaAttachmentDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): QuotaAttachmentDto =
    QuotaAttachmentDto(
      name        = fields.text("name"),
      size        = fields.number("size"),
      apiUrl      = fields.text("api_url"),
      containedIn = fields.nested("contained_in").map(QuotaAttachmentContextDto.fromFields),
    )

  /** Converts a decoded array. The element path is discarded; see the object note. */
  def toDomainAll(base: JsonPath, dtos: Vector[QuotaAttachmentDto]): Either[DecodeFailure, Vector[QuotaAttachment]] =
    Elements.convert(base, dtos)((dto, _) => dto.toDomain)

/** Forgejo's `QuotaUsedPackage` model, field for field. */
final case class QuotaPackageDto(
    name: Option[String],
    version: Option[String],
    packageType: Option[String],
    size: Option[Long],
    htmlUrl: Option[String],
):

  /** Converts to the domain. Total; see the object note on [[QuotaArtifactDto]]. */
  def toDomain: Either[DecodeFailure, QuotaPackage] =
    Right(
      QuotaPackage(
        name        = name,
        version     = version,
        packageType = packageType,
        sizeBytes   = size,
        htmlUrl     = htmlUrl,
      )
    )

object QuotaPackageDto:

  /** Reads a `QuotaUsedPackage` object. */
  given upickle.default.Reader[QuotaPackageDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. The wire spelling `type` is written here and nowhere else. */
  def fromFields(fields: JsonFields): QuotaPackageDto =
    QuotaPackageDto(
      name        = fields.text("name"),
      version     = fields.text("version"),
      packageType = fields.text("type"),
      size        = fields.number("size"),
      htmlUrl     = fields.text("html_url"),
    )

  /** Converts a decoded array. The element path is discarded; see the object note. */
  def toDomainAll(base: JsonPath, dtos: Vector[QuotaPackageDto]): Either[DecodeFailure, Vector[QuotaPackage]] =
    Elements.convert(base, dtos)((dto, _) => dto.toDomain)
