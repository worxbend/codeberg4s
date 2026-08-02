package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.organizations.QuotaArtifact
import com.worxbend.codeberg4s.organizations.QuotaAttachment
import com.worxbend.codeberg4s.organizations.QuotaAttachmentContext
import com.worxbend.codeberg4s.organizations.QuotaInfo
import com.worxbend.codeberg4s.organizations.QuotaPackage
import com.worxbend.codeberg4s.organizations.QuotaUsage

import munit.FunSuite

/** The quota payloads on the wire.
  *
  * '''Every body below is derived from `spec/swagger.v1.json`, not captured.''' Forgejo ships quota disabled, every
  * quota route needs a token, and `modules/codec/test/resources/golden` was harvested anonymously — so no fixture for
  * any of this exists anywhere in the repository, and these payloads are the spec's `QuotaInfo`, `QuotaUsedArtifact`,
  * `QuotaUsedAttachment` and `QuotaUsedPackage` definitions written out by hand.
  *
  * Each field is exercised present, as JSON `null`, and absent, because
  * [[com.worxbend.codeberg4s.codec.WireConventions]] rule 2 requires the last two to be indistinguishable. For a quota
  * that matters more than usual: an absent size is "the instance does not track this", which a caller must be able to
  * tell from a tracked zero.
  */
final class QuotaDtoSuite extends FunSuite:

  private val FullQuota: String =
    """{
      |  "groups": [
      |    {
      |      "name": "default",
      |      "rules": [
      |        {"name": "repo-size", "limit": 1073741824, "subjects": ["size:repos:all", "size:git:lfs"]}
      |      ]
      |    }
      |  ],
      |  "used": {
      |    "size": {
      |      "repos": {"public": 1024, "private": 2048},
      |      "assets": {
      |        "artifacts": 4096,
      |        "attachments": {"issues": 8192, "releases": 16384},
      |        "packages": {"all": 32768}
      |      },
      |      "git": {"LFS": 65536}
      |    }
      |  }
      |}""".stripMargin

  // --- QuotaInfo ------------------------------------------------------------

  test("a full quota payload decodes every size in the tree"):
    val info = domainQuota(FullQuota)

    assertEquals(info.used.size.repositories.publicBytes, Some(1024L))
    assertEquals(info.used.size.repositories.privateBytes, Some(2048L))
    assertEquals(info.used.size.assets.artifactBytes, Some(4096L))
    assertEquals(info.used.size.assets.attachments.issueBytes, Some(8192L))
    assertEquals(info.used.size.assets.attachments.releaseBytes, Some(16384L))
    assertEquals(info.used.size.assets.packageBytes, Some(32768L))
    assertEquals(info.used.size.git.lfsBytes, Some(65536L))

  test("LFS is read under its upper-case key, which is the one key in the payload that is not snake_case"):
    assertEquals(domainQuota("""{"used":{"size":{"git":{"LFS":7}}}}""").used.size.git.lfsBytes, Some(7L))
    assertEquals(domainQuota("""{"used":{"size":{"git":{"lfs":7}}}}""").used.size.git.lfsBytes, None)

  test("packages usage is read out of the nested all property, not off the packages object itself"):
    assertEquals(
      domainQuota("""{"used":{"size":{"assets":{"packages":{"all":9}}}}}""").used.size.assets.packageBytes,
      Some(9L),
    )

  test("a quota group carries its rules, with limits and subjects verbatim"):
    val group = domainQuota(FullQuota).groups.headOption match
      case Some(value) => value
      case None        => fail("the payload carried no group")

    assertEquals(group.name, Some("default"))
    assertEquals(group.rules.map(_.name), Vector(Some("repo-size")))
    assertEquals(group.rules.map(_.limit), Vector(Some(1073741824L)))
    assertEquals(group.rules.flatMap(_.subjects), Vector("size:repos:all", "size:git:lfs"))

  test("a negative limit is carried as Forgejo sent it, because that is how it spells 'unlimited'"):
    val info = domainQuota("""{"groups":[{"name":"g","rules":[{"limit":-1}]}]}""")

    assertEquals(info.groups.flatMap(_.rules).map(_.limit), Vector(Some(-1L)))

  test("a rule name absent because the caller is not an administrator is None, not a failure"):
    val info = domainQuota("""{"groups":[{"rules":[{"limit":5,"subjects":["size:all"]}]}]}""")

    assertEquals(info.groups.flatMap(_.rules).map(_.name), Vector(None))

  test("an empty quota payload is an empty tree, not a failure"):
    val info = domainQuota("{}")

    assertEquals(info.groups, Vector.empty)
    assertEquals(info.used, QuotaUsage.Empty)

  test("a used branch sent as JSON null decodes identically to an absent one"):
    assertEquals(domainQuota("""{"used":null}"""), domainQuota("{}"))

  test("a size sent as JSON null decodes identically to an absent one"):
    assertEquals(
      domainQuota("""{"used":{"size":{"repos":{"public":null,"private":3}}}}"""),
      domainQuota("""{"used":{"size":{"repos":{"private":3}}}}"""),
    )

  test("a missing size branch is absence rather than zero, which a caller must be able to tell apart"):
    val info = domainQuota("""{"used":{"size":{"repos":{"public":0}}}}""")

    assertEquals(info.used.size.repositories.publicBytes, Some(0L))
    assertEquals(info.used.size.repositories.privateBytes, None)

  test("groups sent as JSON null are an empty vector, never a crash"):
    assertEquals(domainQuota("""{"groups":null}""").groups, Vector.empty)

  test("a quota body that is not JSON is a decoding failure at the root, never an escaping exception"):
    Json.decode[QuotaInfoDto]("not json at all") match
      case Left(failure) => assertEquals(failure.path.render, "$")
      case Right(value)  => fail(s"expected a decoding failure, got $value")

  test("a quota body that is the JSON literal null is rejected rather than becoming a null reference"):
    Json.decode[QuotaInfoDto]("null") match
      case Left(failure) => assertEquals(failure.path.render, "$")
      case Right(value)  => fail(s"expected a decoding failure, got $value")

  // --- the three usage listings ---------------------------------------------

  test("an artifact entry decodes its three keys"):
    val entries = artifacts("""[{"name":"build-log","size":512,"html_url":"https://forge.example/run/1"}]""")

    assertEquals(entries, Vector(QuotaArtifact(Some("build-log"), Some(512L), Some("https://forge.example/run/1"))))

  test("an artifact entry whose keys are JSON null decodes identically to one with none of them"):
    assertEquals(artifacts("""[{"name":null,"size":null,"html_url":null}]"""), artifacts("[{}]"))

  test("an artifact entry with nothing at all is still an entry, because none of its fields is required"):
    assertEquals(artifacts("[{}]"), Vector(QuotaArtifact(None, None, None)))

  test("an attachment entry decodes its container context"):
    val body = """[{"name":"crash.log","size":64,"api_url":"https://forge.example/api/a/1",""" +
      """"contained_in":{"api_url":"https://forge.example/api/i/2","html_url":"https://forge.example/i/2"}}]"""

    val entry = attachments(body).headOption match
      case Some(value) => value
      case None        => fail("the payload carried no attachment")

    assertEquals(entry.name, Some("crash.log"))
    assertEquals(entry.sizeBytes, Some(64L))
    assertEquals(entry.apiUrl, Some("https://forge.example/api/a/1"))
    assertEquals(
      entry.containedIn,
      Some(QuotaAttachmentContext(Some("https://forge.example/api/i/2"), Some("https://forge.example/i/2"))),
    )

  test("an attachment whose contained_in is JSON null decodes identically to one without the key"):
    assertEquals(attachments("""[{"name":"a","contained_in":null}]"""), attachments("""[{"name":"a"}]"""))

  test("a package entry reads type into packageType, because type is a Scala keyword"):
    val entry = packages("""[{"name":"codeberg4s","version":"0.1.0","type":"maven","size":128}]""").headOption match
      case Some(value) => value
      case None        => fail("the payload carried no package")

    assertEquals(entry.name, Some("codeberg4s"))
    assertEquals(entry.version, Some("0.1.0"))
    assertEquals(entry.packageType, Some("maven"))
    assertEquals(entry.sizeBytes, Some(128L))
    assertEquals(entry.htmlUrl, None)

  test("a package registry this library has never heard of survives, because the spec enumerates none"):
    assertEquals(
      packages("""[{"type":"some-future-registry"}]""").map(_.packageType),
      Vector(Some("some-future-registry")),
    )

  test("an empty usage listing is an empty vector for all three shapes"):
    assertEquals(artifacts("[]"), Vector.empty)
    assertEquals(attachments("[]"), Vector.empty)
    assertEquals(packages("[]"), Vector.empty)

  // --- helpers --------------------------------------------------------------

  private def domainQuota(body: String): QuotaInfo =
    Json.decode[QuotaInfoDto](body).flatMap(_.toDomain) match
      case Right(info)   => info
      case Left(failure) => fail(s"$body did not decode: ${failure.path.render} ${failure.message}")

  private def artifacts(body: String): Vector[QuotaArtifact] =
    Json
      .decode[Vector[QuotaArtifactDto]](body)
      .flatMap(dtos => QuotaArtifactDto.toDomainAll(JsonPath.Root, dtos)) match
      case Right(entries) => entries
      case Left(failure)  => fail(s"$body did not decode: ${failure.path.render} ${failure.message}")

  private def attachments(body: String): Vector[QuotaAttachment] =
    Json
      .decode[Vector[QuotaAttachmentDto]](body)
      .flatMap(dtos => QuotaAttachmentDto.toDomainAll(JsonPath.Root, dtos)) match
      case Right(entries) => entries
      case Left(failure)  => fail(s"$body did not decode: ${failure.path.render} ${failure.message}")

  private def packages(body: String): Vector[QuotaPackage] =
    Json
      .decode[Vector[QuotaPackageDto]](body)
      .flatMap(dtos => QuotaPackageDto.toDomainAll(JsonPath.Root, dtos)) match
      case Right(entries) => entries
      case Left(failure)  => fail(s"$body did not decode: ${failure.path.render} ${failure.message}")
