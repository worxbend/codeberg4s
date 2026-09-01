package com.worxbend.codeberg4s.quota.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, JsonDecoder}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.quota.{
  AttachmentContainer,
  QuotaInfo,
  QuotaUsedArtifact,
  QuotaUsedAttachment,
  QuotaUsedPackage,
  QuotaUsedSize
}

import munit.FunSuite

/** The quota payloads on the wire — one suite, because `GET /orgs/{org}/quota` and `GET /user/quota` answer the same
  * four models and are read by the same DTOs.
  *
  * '''Every body below is derived from `spec/swagger.v1.json`, not captured.''' Forgejo ships quota disabled, every
  * quota route needs a token, and `modules/codec/test/resources/golden` was harvested anonymously — so no fixture for
  * any of this exists anywhere in the repository, and these payloads are the spec's `QuotaInfo`, `QuotaUsedArtifact`,
  * `QuotaUsedAttachment` and `QuotaUsedPackage` definitions written out by hand.
  *
  * Each field is exercised present, as JSON `null`, and absent, because
  * [[com.worxbend.codeberg4s.codec.WireConventions]] rule 2 requires the last two to be indistinguishable. For a quota
  * that matters more than usual: an absent size is "the instance does not track this", which a caller must be able to
  * tell from a tracked zero. The deepest part of the report — four levels of single-property objects — is flattened
  * into one breakdown, so most of the report half of this suite is about that flattening and about what happens when
  * the instance stops nesting halfway.
  */
final class QuotaDtoSuite extends FunSuite:

  // --- the report -----------------------------------------------------------

  test("a full report flattens the whole used tree into one breakdown"):
    val used = report(QuotaDtoSuite.ReportBody).used

    assertEquals(used.publicRepositories, Some(1024L))
    assertEquals(used.privateRepositories, Some(512L))
    assertEquals(used.gitLfs, Some(4096L))
    assertEquals(used.artifacts, Some(64L))
    assertEquals(used.issueAttachments, Some(8L))
    assertEquals(used.releaseAttachments, Some(16L))
    assertEquals(used.packages, Some(2048L))

  test("LFS is read under its upper-case key, which is the one key in the payload that is not snake_case"):
    assertEquals(QuotaInfoDto.LfsKey, "LFS")
    assertEquals(report("""{"used":{"size":{"git":{"LFS":7}}}}""").used.gitLfs, Some(7L))
    assertEquals(report("""{"used":{"size":{"git":{"lfs":7}}}}""").used.gitLfs, None)

  test("packages usage is read out of the nested all property, not off the packages object itself"):
    assertEquals(report("""{"used":{"size":{"assets":{"packages":{"all":9}}}}}""").used.packages, Some(9L))

  test("a report whose nesting stops halfway keeps what it did carry"):
    val used = report("""{"used":{"size":{"repos":{"public":10}}}}""").used

    assertEquals(used.publicRepositories, Some(10L))
    assertEquals(used.artifacts, None)

  test("a report with no used object at all reports nothing, rather than reporting zero"):
    val info = report("{}")

    assertEquals(info.groups, Vector.empty)
    assertEquals(info.used, QuotaUsedSize.Empty)

  test("a used branch sent as JSON null decodes identically to an absent one"):
    assertEquals(report("""{"used":null}"""), report("{}"))

  test("a size sent as JSON null decodes identically to an absent one"):
    assertEquals(
      report("""{"used":{"size":{"repos":{"public":null,"private":3}}}}"""),
      report("""{"used":{"size":{"repos":{"private":3}}}}"""),
    )

  test("a reported zero is a measurement and an absent heading is not, which a caller must be able to tell apart"):
    val used = report("""{"used":{"size":{"repos":{"public":0}}}}""").used

    assertEquals(used.publicRepositories, Some(0L))
    assertEquals(used.privateRepositories, None)

  test("JSON null and an absent key decode identically for the whole report"):
    assertEquals(decodeOne[QuotaInfoDto](QuotaDtoSuite.NullReportBody), decodeOne[QuotaInfoDto]("{}"))

  test("groups and their rules are read in wire order"):
    val groups = report(QuotaDtoSuite.ReportBody).groups

    assertEquals(groups.flatMap(_.name), Vector("default", "extra"))
    assertEquals(groups.head.rules.flatMap(_.name), Vector("size-limit"))

  test("a rule carries its subjects verbatim, so one this library cannot parse is still reported"):
    val rules = report("""{"groups":[{"rules":[{"subjects":["size:all","   ","size:repos:public"]}]}]}""").rules

    assertEquals(rules.flatMap(_.subjects), Vector("size:all", "   ", "size:repos:public"))

  test("a negative limit is carried as Forgejo sent it, because that is how it spells 'unlimited'"):
    val rules = report("""{"groups":[{"name":"g","rules":[{"limit":-1}]}]}""").rules

    assertEquals(rules.flatMap(_.limit), Vector(-1L))
    assertEquals(rules.map(_.isUnlimited), Vector(true))

  test("a rule name absent because the caller is not an administrator is None, not a failure"):
    val rules = report("""{"groups":[{"rules":[{"limit":5,"subjects":["size:all"]}]}]}""").rules

    assertEquals(rules.map(_.name), Vector(None))

  test("groups sent as JSON null are an empty vector, never a crash"):
    assertEquals(report("""{"groups":null}""").groups, Vector.empty)

  test("a report that decodes cannot fail to convert, because nothing in it addresses anything"):
    assertEquals(report("{}"), QuotaInfo(groups = Vector.empty, used = QuotaUsedSize.Empty))

  test("a quota body that is not JSON is a decoding failure at the root, never an escaping exception"):
    Json.decode[QuotaInfoDto]("not json at all") match
      case Left(failure) => assertEquals(failure.path.render, "$")
      case Right(value)  => fail(s"expected a decoding failure, got $value")

  test("a quota body that is the JSON literal null is rejected rather than becoming a null reference"):
    Json.decode[QuotaInfoDto]("null") match
      case Left(failure) => assertEquals(failure.path.render, "$")
      case Right(value)  => fail(s"expected a decoding failure, got $value")

  // --- artifacts ------------------------------------------------------------

  test("a quota artifact decodes its three keys"):
    val artifact = one[QuotaUsedArtifactDto, QuotaUsedArtifact](QuotaDtoSuite.ArtifactBody)(_.toDomain)

    assertEquals(artifact.name, Some("coverage"))
    assertEquals(artifact.sizeBytes, Some(2048L))
    assertEquals(artifact.htmlUrl, Some("https://forge.example/o/r/actions/runs/4711"))

  test("a quota artifact with nothing in it still decodes, because it carries no identifier to require"):
    assertEquals(
      one[QuotaUsedArtifactDto, QuotaUsedArtifact]("{}")(_.toDomain),
      QuotaUsedArtifact(name = None, sizeBytes = None, htmlUrl = None),
    )

  test("JSON null and an absent key decode identically for a quota artifact"):
    assertEquals(
      decodeOne[QuotaUsedArtifactDto]("""{"name":null,"size":null,"html_url":null}"""),
      decodeOne[QuotaUsedArtifactDto]("{}"),
    )

  test("an artifact array converts every element"):
    val dtos = decodeOne[Vector[QuotaUsedArtifactDto]](s"[${QuotaDtoSuite.ArtifactBody}, {}]")

    assertEquals(convert(QuotaUsedArtifactDto.toDomainAll(JsonPath.Root, dtos)).length, 2)

  // --- attachments ----------------------------------------------------------

  test("a quota attachment lifts its containing object's two links into one value"):
    val attachment = one[QuotaUsedAttachmentDto, QuotaUsedAttachment](QuotaDtoSuite.AttachmentBody)(_.toDomain)

    assertEquals(attachment.name, Some("crash.log"))
    assertEquals(attachment.sizeBytes, Some(4096L))
    assertEquals(attachment.apiUrl, Some("https://forge.example/api/v1/attachments/9"))
    assertEquals(
      attachment.containedIn,
      Some(
        AttachmentContainer(
          apiUrl  = Some("https://forge.example/api/v1/repos/o/r/issues/3"),
          htmlUrl = Some("https://forge.example/o/r/issues/3"),
        )
      ),
    )

  test("an attachment with no containing object reports none, rather than an object with two absent links"):
    assertEquals(one[QuotaUsedAttachmentDto, QuotaUsedAttachment]("""{"name":"a"}""")(_.toDomain).containedIn, None)

  test("an attachment whose containing object names only one link still reports a container"):
    val attachment =
      one[QuotaUsedAttachmentDto, QuotaUsedAttachment]("""{"contained_in":{"html_url":"https://a"}}""")(_.toDomain)

    assertEquals(attachment.containedIn.flatMap(_.htmlUrl), Some("https://a"))
    assertEquals(attachment.containedIn.flatMap(_.apiUrl), None)

  test("an attachment whose contained_in is JSON null decodes identically to one without the key"):
    assertEquals(
      decodeOne[QuotaUsedAttachmentDto]("""{"name":"a","contained_in":null}"""),
      decodeOne[QuotaUsedAttachmentDto]("""{"name":"a"}"""),
    )

  test("JSON null and an absent key decode identically for a quota attachment"):
    assertEquals(
      decodeOne[QuotaUsedAttachmentDto]("""{"name":null,"size":null,"api_url":null,"contained_in":null}"""),
      decodeOne[QuotaUsedAttachmentDto]("{}"),
    )

  test("an attachment array converts every element"):
    val dtos = decodeOne[Vector[QuotaUsedAttachmentDto]](s"[${QuotaDtoSuite.AttachmentBody}]")

    assertEquals(convert(QuotaUsedAttachmentDto.toDomainAll(JsonPath.Root, dtos)).flatMap(_.name), Vector("crash.log"))

  // --- packages -------------------------------------------------------------

  test("a package entry reads type into packageType, because type is a Scala keyword"):
    val entry = one[QuotaUsedPackageDto, QuotaUsedPackage](QuotaDtoSuite.PackageBody)(_.toDomain)

    assertEquals(entry.name, Some("codeberg4s"))
    assertEquals(entry.version, Some("0.1.0"))
    assertEquals(entry.packageType, Some("maven"))
    assertEquals(entry.sizeBytes, Some(65536L))
    assertEquals(entry.htmlUrl, Some("https://forge.example/o/-/packages/maven/codeberg4s/0.1.0"))

  test("a package registry this library has never heard of survives, because the spec enumerates none"):
    val entry = one[QuotaUsedPackageDto, QuotaUsedPackage]("""{"type":"conda"}""")(_.toDomain)

    assertEquals(entry.packageType, Some("conda"))

  test("JSON null and an absent key decode identically for a quota package"):
    assertEquals(
      decodeOne[QuotaUsedPackageDto]("""{"name":null,"version":null,"type":null,"size":null,"html_url":null}"""),
      decodeOne[QuotaUsedPackageDto]("{}"),
    )

  test("a package array converts every element"):
    val dtos = decodeOne[Vector[QuotaUsedPackageDto]](s"[${QuotaDtoSuite.PackageBody}]")

    assertEquals(convert(QuotaUsedPackageDto.toDomainAll(JsonPath.Root, dtos)).flatMap(_.name), Vector("codeberg4s"))

  test("an empty usage listing is an empty vector for all three shapes"):
    assertEquals(convert(QuotaUsedArtifactDto.toDomainAll(JsonPath.Root, decodeOne("[]"))), Vector.empty)
    assertEquals(convert(QuotaUsedAttachmentDto.toDomainAll(JsonPath.Root, decodeOne("[]"))), Vector.empty)
    assertEquals(convert(QuotaUsedPackageDto.toDomainAll(JsonPath.Root, decodeOne("[]"))), Vector.empty)

  // --- harness --------------------------------------------------------------

  private def report(body: String): QuotaInfo =
    convert(decodeOne[QuotaInfoDto](body).toDomain)

  private def one[D: JsonDecoder, A](body: String)(toDomain: D => Either[DecodeFailure, A]): A =
    convert(toDomain(decodeOne[D](body)))

  private def decodeOne[A: JsonDecoder](body: String): A =
    Json.decode[A](body) match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the payload did not decode: ${failure.message}")

  private def convert[A](result: Either[DecodeFailure, A]): A =
    result match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

/** The payloads this suite decodes, kept out of the test bodies so each test reads as one behaviour.
  *
  * All of them are hand-written from `spec/swagger.v1.json`; no quota endpoint has a golden capture.
  */
object QuotaDtoSuite:

  private val ReportBody: String =
    """{"groups": [{"name": "default", "rules": [{"name": "size-limit", "limit": 1048576,
      | "subjects": ["size:all"]}]}, {"name": "extra", "rules": []}],
      | "used": {"size": {"repos": {"public": 1024, "private": 512}, "git": {"LFS": 4096},
      | "assets": {"artifacts": 64, "attachments": {"issues": 8, "releases": 16}, "packages": {"all": 2048}}}}}"""
      .stripMargin

  private val NullReportBody: String = """{"groups": null, "used": null}"""

  private val ArtifactBody: String =
    """{"name": "coverage", "size": 2048, "html_url": "https://forge.example/o/r/actions/runs/4711"}"""

  private val AttachmentBody: String =
    """{"name": "crash.log", "size": 4096, "api_url": "https://forge.example/api/v1/attachments/9",
      | "contained_in": {"api_url": "https://forge.example/api/v1/repos/o/r/issues/3",
      | "html_url": "https://forge.example/o/r/issues/3"}}""".stripMargin

  private val PackageBody: String =
    """{"name": "codeberg4s", "version": "0.1.0", "type": "maven", "size": 65536,
      | "html_url": "https://forge.example/o/-/packages/maven/codeberg4s/0.1.0"}""".stripMargin
