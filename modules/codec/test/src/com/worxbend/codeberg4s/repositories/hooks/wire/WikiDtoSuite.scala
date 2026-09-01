package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.repositories.hooks.WikiCommit
import com.worxbend.codeberg4s.repositories.hooks.WikiPage
import com.worxbend.codeberg4s.repositories.hooks.WikiPageMeta

import munit.FunSuite

import java.time.Instant

/** Decoding the wiki models: a page with its base64 content, a listing entry without it, and a revision.
  *
  * '''Payloads written by hand from `spec/swagger.v1.json`, not captured'''; see [[WebhookDto]].
  */
final class WikiDtoSuite extends FunSuite:

  // --- WikiPage -------------------------------------------------------------

  test("a wiki page decodes with its title, its decoded content and its revision"):
    val page = domainPage(WikiDtoSuite.Full)

    assertEquals(page.title, "Getting Started")
    assertEquals(page.content.flatMap(_.text), Some("# Getting Started\n"))
    assertEquals(page.subUrl, Some("Getting-Started"))
    assertEquals(page.htmlUrl, Some("https://forge.example/o/r/wiki/Getting-Started"))
    assertEquals(page.commitCount, Some(3L))
    assertEquals(page.lastCommit.map(_.sha.value), Some("d0c4f1e2a3b4c5d6e7f8091a2b3c4d5e6f708192"))

  test("the sidebar and the footer are handed back verbatim, because the spec never says they are encoded"):
    val page = domainPage("""{"title":"Home","sidebar":"* [Home](Home)","footer":"CC-BY"}""")

    assertEquals(page.sidebar, Some("* [Home](Home)"))
    assertEquals(page.footer, Some("CC-BY"))

  test("a page whose content is empty base64 has content, which is not the same as having none"):
    assertEquals(domainPage("""{"title":"Home","content_base64":""}""").content.flatMap(_.text), Some(""))

  test("a page with no content_base64 key has no content at all"):
    assertEquals(domainPage("""{"title":"Home"}""").content, None)

  test("a wiki page without a title cannot be converted, because nothing else addresses it"):
    assertEquals(pageFailure("""{"content_base64":"aGk="}"""), Some("$.title"))

  test("a last_commit that is present and unusable fails the page at its own path"):
    assertEquals(pageFailure("""{"title":"Home","last_commit":{"message":"x"}}"""), Some("$.last_commit.sha"))

  test("JSON null and an absent key decode identically for every optional wiki page field"):
    WikiDtoSuite.PageOptionalKeys.foreach: key =>
      assertEquals(
        decodePage(s"""{"title":"Home","$key":null}"""),
        decodePage("""{"title":"Home"}"""),
        s"'$key' distinguished null from absent",
      )

  // --- WikiPageMetaData -----------------------------------------------------

  test("a listing entry decodes with its title and its revision, and has nowhere to put content"):
    val entry = domainMeta(
      """{"title":"Home","sub_url":"Home","html_url":"https://forge.example/o/r/wiki/Home",
        | "last_commit":{"sha":"abcd1234"}}""".stripMargin
    )

    assertEquals(entry.title, "Home")
    assertEquals(entry.subUrl, Some("Home"))
    assertEquals(entry.lastCommit.map(_.sha.value), Some("abcd1234"))

  test("a listing entry without a title cannot be converted"):
    assertEquals(metaFailure("""{"sub_url":"Home"}"""), Some("$.title"))

  test("JSON null and an absent key decode identically for every optional listing entry field"):
    Vector("html_url", "sub_url", "last_commit").foreach: key =>
      assertEquals(
        decodeMeta(s"""{"title":"Home","$key":null}"""),
        decodeMeta("""{"title":"Home"}"""),
        s"'$key' distinguished null from absent",
      )

  test("a bad element of a page listing reports its own position"):
    val dtos = Json.decode[Vector[WikiPageMetaDto]]("""[{"title":"Home"},{"sub_url":"x"}]""") match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the array did not decode: ${failure.message}")

    assertEquals(
      WireModel.all(JsonPath.Root, dtos).swap.toOption.map(_.path.render),
      Some("$[1].title"),
    )

  // --- WikiCommit -----------------------------------------------------------

  test("a revision decodes with its object id, its identities and its message"):
    val commit = domainCommit(WikiDtoSuite.Commit)

    assertEquals(commit.sha.value, "d0c4f1e2a3b4c5d6e7f8091a2b3c4d5e6f708192")
    assertEquals(commit.author.flatMap(_.name), Some("Ada"))
    assertEquals(commit.author.flatMap(_.date), Some(Instant.parse("2026-07-30T19:14:15Z")))
    assertEquals(commit.committer.flatMap(_.email), Some("ada@example.org"))
    assertEquals(commit.message, Some("document the deploy"))

  test("the committer arrives under the API's misspelled key, and only under it"):
    val underMisspelling = domainCommit("""{"sha":"abcd1234","commiter":{"name":"Ada"}}""")
    val underCorrection  = domainCommit("""{"sha":"abcd1234","committer":{"name":"Ada"}}""")

    assertEquals(underMisspelling.committer.flatMap(_.name), Some("Ada"))
    assertEquals(underCorrection.committer, None)
    assertEquals(WikiCommitDto.CommitterKey, "commiter")

  test("a revision without an object id cannot be converted"):
    assertEquals(commitFailure("""{"message":"x"}"""), Some("$.sha"))

  test("a revision whose object id is not one cannot be converted"):
    assertEquals(commitFailure("""{"sha":"not a sha"}"""), Some("$.sha"))

  test("JSON null and an absent key decode identically for every optional revision field"):
    Vector("author", "commiter", "message").foreach: key =>
      assertEquals(
        decodeCommit(s"""{"sha":"abcd1234","$key":null}"""),
        decodeCommit("""{"sha":"abcd1234"}"""),
        s"'$key' distinguished null from absent",
      )

  // --- WikiCommitList -------------------------------------------------------

  test("the revision envelope unwraps to its commits and reports the body's own count"):
    val envelope = decodeEnvelope("""{"commits":[{"sha":"abcd1234"}],"count":17}""")

    assertEquals(envelope.count, Some(17L))
    assertEquals(envelope.entries.size, 1)

  test("an envelope whose commits key is null or absent is an empty listing, not a failure"):
    assertEquals(decodeEnvelope("""{"commits":null}""").entries, Vector.empty[WikiCommitDto])
    assertEquals(decodeEnvelope("""{}"""), decodeEnvelope("""{"commits":null,"count":null}"""))

  test("a bad revision inside the envelope reports its position under the commits key"):
    val envelope = decodeEnvelope("""{"commits":[{"sha":"abcd1234"},{"message":"x"}]}""")
    val path     = JsonPath.Root.field(WikiCommitListDto.EntriesKey)

    assertEquals(
      WireModel.all(path, envelope.entries).swap.toOption.map(_.path.render),
      Some("$.commits[1].sha"),
    )

  private def decodePage(body: String): WikiPageDto =
    Json.decode[WikiPageDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domainPage(body: String): WikiPage =
    decodePage(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def pageFailure(body: String): Option[String] =
    decodePage(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeMeta(body: String): WikiPageMetaDto =
    Json.decode[WikiPageMetaDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domainMeta(body: String): WikiPageMeta =
    decodeMeta(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def metaFailure(body: String): Option[String] =
    decodeMeta(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeCommit(body: String): WikiCommitDto =
    Json.decode[WikiCommitDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domainCommit(body: String): WikiCommit =
    decodeCommit(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def commitFailure(body: String): Option[String] =
    decodeCommit(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeEnvelope(body: String): WikiCommitListDto =
    Json.decode[WikiCommitListDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

/** The payloads this suite decodes, kept out of the test bodies so each test reads as one behaviour. */
object WikiDtoSuite:

  /** `# Getting Started\n`, base64-encoded. */
  private val ContentBase64: String = "IyBHZXR0aW5nIFN0YXJ0ZWQK"

  private val Commit: String =
    """{
      |  "sha": "d0c4f1e2a3b4c5d6e7f8091a2b3c4d5e6f708192",
      |  "author": {"name": "Ada", "email": "ada@example.org", "date": "2026-07-30T21:14:15+02:00"},
      |  "commiter": {"name": "Ada", "email": "ada@example.org", "date": "2026-07-30T21:14:15+02:00"},
      |  "message": "document the deploy"
      |}""".stripMargin

  /** Every property `spec/swagger.v1.json` declares on `WikiPage`. */
  private val Full: String =
    s"""{
       |  "title": "Getting Started",
       |  "content_base64": "$ContentBase64",
       |  "sidebar": "",
       |  "footer": "",
       |  "html_url": "https://forge.example/o/r/wiki/Getting-Started",
       |  "sub_url": "Getting-Started",
       |  "last_commit": $Commit,
       |  "commit_count": 3
       |}""".stripMargin

  /** Every wire key of `WikiPage` this DTO reads, apart from the one required field. */
  private val PageOptionalKeys: Vector[String] =
    Vector("content_base64", "sidebar", "footer", "html_url", "sub_url", "last_commit", "commit_count")
