package com.worxbend.codeberg4s.users.account.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.account.AttachmentContainer
import com.worxbend.codeberg4s.users.account.QuotaInfo
import com.worxbend.codeberg4s.users.account.QuotaUsedArtifact
import com.worxbend.codeberg4s.users.account.QuotaUsedAttachment
import com.worxbend.codeberg4s.users.account.QuotaUsedPackage
import com.worxbend.codeberg4s.users.account.QuotaUsedSize

import munit.FunSuite

/** Decoding the quota report and the three usage listings.
  *
  * '''Payloads written by hand from `spec/swagger.v1.json`, not captured'''; see [[QuotaInfoDto]]. The report is the
  * most deeply nested payload in this group — four levels of single-property objects — so most of this suite is about
  * the flattening, and about what happens when the instance stops nesting halfway.
  */
final class QuotaDtoSuite extends FunSuite:

  // --- the report -----------------------------------------------------------

  test("a full report flattens the used tree into one breakdown"):
    val used = report(QuotaDtoSuite.ReportBody).used

    assertEquals(used.publicRepositories, Some(1024L))
    assertEquals(used.privateRepositories, Some(512L))
    assertEquals(used.gitLfs, Some(4096L))
    assertEquals(used.artifacts, Some(64L))
    assertEquals(used.issueAttachments, Some(8L))
    assertEquals(used.releaseAttachments, Some(16L))
    assertEquals(used.packages, Some(2048L))

  test("the Git LFS total is read from the upper-case key Forgejo actually sends"):
    assertEquals(QuotaInfoDto.LfsKey, "LFS")
    assertEquals(report("""{"used":{"size":{"git":{"lfs":9}}}}""").used.gitLfs, None)

  test("a report whose nesting stops halfway keeps what it did carry"):
    val used = report("""{"used":{"size":{"repos":{"public":10}}}}""").used

    assertEquals(used.publicRepositories, Some(10L))
    assertEquals(used.artifacts, None)

  test("a report with no used object at all reports nothing, rather than reporting zero"):
    assertEquals(report("{}").used, QuotaUsedSize.Empty)

  test("groups and their rules are read in wire order"):
    val groups = report(QuotaDtoSuite.ReportBody).groups

    assertEquals(groups.flatMap(_.name), Vector("default", "extra"))
    assertEquals(groups.head.rules.flatMap(_.name), Vector("size-limit"))

  test("a rule's subjects are validated, and one that cannot be a subject is dropped rather than failing the rule"):
    val rules = report("""{"groups":[{"rules":[{"subjects":["size:all","   ","size:repos:public"]}]}]}""").rules

    assertEquals(rules.flatMap(_.subjects).map(_.value), Vector("size:all", "size:repos:public"))

  test("a negative limit survives decoding, because it is Forgejo's spelling of unlimited"):
    val rules = report("""{"groups":[{"rules":[{"limit":-1}]}]}""").rules

    assertEquals(rules.flatMap(_.limit), Vector(-1L))
    assertEquals(rules.map(_.isUnlimited), Vector(true))

  test("JSON null and an absent key decode identically for the report"):
    assertEquals(decodeReport(QuotaDtoSuite.NullReportBody), decodeReport("{}"))

  test("a report that decodes cannot fail to convert, because nothing in it addresses anything"):
    assertEquals(report("{}"), QuotaInfo(groups = Vector.empty, used = QuotaUsedSize.Empty))

  // --- artifacts ------------------------------------------------------------

  test("a quota artifact decodes field for field"):
    val artifact = one[QuotaUsedArtifactDto, QuotaUsedArtifact](QuotaDtoSuite.ArtifactBody)(_.toDomain)

    assertEquals(artifact.name, Some("coverage"))
    assertEquals(artifact.size, Some(2048L))
    assertEquals(artifact.htmlUrl, Some("https://forge.example/o/r/actions/runs/4711"))

  test("a quota artifact with nothing in it still decodes, because it carries no identifier to require"):
    assertEquals(
      one[QuotaUsedArtifactDto, QuotaUsedArtifact]("{}")(_.toDomain),
      QuotaUsedArtifact(name = None, size = None, htmlUrl = None),
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
    assertEquals(attachment.size, Some(4096L))
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
    assertEquals(
      one[QuotaUsedAttachmentDto, QuotaUsedAttachment]("""{"name":"a"}""")(_.toDomain).containedIn,
      None,
    )

  test("an attachment whose containing object names only one link still reports a container"):
    val attachment =
      one[QuotaUsedAttachmentDto, QuotaUsedAttachment]("""{"contained_in":{"html_url":"https://a"}}""")(_.toDomain)

    assertEquals(attachment.containedIn.flatMap(_.htmlUrl), Some("https://a"))
    assertEquals(attachment.containedIn.flatMap(_.apiUrl), None)

  test("JSON null and an absent key decode identically for a quota attachment"):
    assertEquals(
      decodeOne[QuotaUsedAttachmentDto]("""{"name":null,"size":null,"api_url":null,"contained_in":null}"""),
      decodeOne[QuotaUsedAttachmentDto]("{}"),
    )

  // --- packages -------------------------------------------------------------

  test("a quota package decodes field for field, type included"):
    val entry = one[QuotaUsedPackageDto, QuotaUsedPackage](QuotaDtoSuite.PackageBody)(_.toDomain)

    assertEquals(entry.name, Some("codeberg4s"))
    assertEquals(entry.version, Some("0.1.0"))
    assertEquals(entry.packageType, Some("maven"))
    assertEquals(entry.size, Some(65536L))
    assertEquals(entry.htmlUrl, Some("https://forge.example/o/-/packages/maven/codeberg4s/0.1.0"))

  test("a package registry this library has never heard of is kept, because the set is instance configuration"):
    assertEquals(
      one[QuotaUsedPackageDto, QuotaUsedPackage]("""{"type":"conda"}""")(_.toDomain).packageType,
      Some("conda"),
    )

  test("JSON null and an absent key decode identically for a quota package"):
    assertEquals(
      decodeOne[QuotaUsedPackageDto]("""{"name":null,"version":null,"type":null,"size":null,"html_url":null}"""),
      decodeOne[QuotaUsedPackageDto]("{}"),
    )

  test("a package array converts every element"):
    val dtos = decodeOne[Vector[QuotaUsedPackageDto]](s"[${QuotaDtoSuite.PackageBody}]")

    assertEquals(convert(QuotaUsedPackageDto.toDomainAll(JsonPath.Root, dtos)).flatMap(_.name), Vector("codeberg4s"))

  test("an attachment array converts every element"):
    val dtos = decodeOne[Vector[QuotaUsedAttachmentDto]](s"[${QuotaDtoSuite.AttachmentBody}]")

    assertEquals(convert(QuotaUsedAttachmentDto.toDomainAll(JsonPath.Root, dtos)).flatMap(_.name), Vector("crash.log"))

  // --- harness --------------------------------------------------------------

  private def decodeReport(body: String): QuotaInfoDto =
    decodeOne[QuotaInfoDto](body)

  private def report(body: String): QuotaInfo =
    convert(decodeReport(body).toDomain)

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
