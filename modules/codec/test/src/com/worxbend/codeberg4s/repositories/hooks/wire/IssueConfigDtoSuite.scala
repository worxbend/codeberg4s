package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.repositories.hooks.IssueConfig
import com.worxbend.codeberg4s.repositories.hooks.IssueConfigValidation
import com.worxbend.codeberg4s.repositories.hooks.IssueFormFieldType
import com.worxbend.codeberg4s.repositories.hooks.IssueTemplate

import munit.FunSuite

/** Decoding what a repository tells a contributor about opening an issue: its config, the verdict on that config, and
  * its templates.
  *
  * '''Payloads written by hand from `spec/swagger.v1.json`, not captured'''; see [[WebhookDto]].
  */
final class IssueConfigDtoSuite extends FunSuite:

  // --- IssueConfig ----------------------------------------------------------

  test("an issue config decodes with its flag and its contact links, in file order"):
    val config = domainConfig(IssueConfigDtoSuite.Config)

    assertEquals(config.blankIssuesEnabled, Some(false))
    assertEquals(config.contactLinks.map(_.name), Vector("Security", "Chat"))
    assertEquals(config.contactLinks.headOption.flatMap(_.about), Some("Report a vulnerability privately"))

  test("a repository with no config file still decodes, because nothing here is required"):
    assertEquals(domainConfig("""{}""").contactLinks, Vector.empty)
    assertEquals(domainConfig("""{}""").blankIssuesEnabled, None)

  test("contact links that arrive as JSON null are an empty list, not a failure"):
    assertEquals(domainConfig("""{"contact_links":null}""").contactLinks, Vector.empty)

  test("a contact link missing its label fails the whole config at its own index"):
    assertEquals(
      configFailure("""{"contact_links":[{"name":"Security","url":"https://s"},{"url":"https://c"}]}"""),
      Some("$.contact_links[1].name"),
    )

  test("a contact link missing its destination fails the whole config at its own index"):
    assertEquals(configFailure("""{"contact_links":[{"name":"Chat"}]}"""), Some("$.contact_links[0].url"))

  test("JSON null and an absent key decode identically for every optional issue config field"):
    Vector("blank_issues_enabled", "contact_links").foreach: key =>
      assertEquals(
        decodeConfig(s"""{"$key":null}"""),
        decodeConfig("""{}"""),
        s"'$key' distinguished null from absent",
      )

  test("JSON null and an absent key decode identically for a contact link's optional field"):
    assertEquals(
      decodeConfig("""{"contact_links":[{"name":"C","url":"u","about":null}]}"""),
      decodeConfig("""{"contact_links":[{"name":"C","url":"u"}]}"""),
    )

  // --- IssueConfigValidation ------------------------------------------------

  test("an invalid config is a successful decode carrying the reason, not a failure"):
    val verdict = domainValidation("""{"valid":false,"message":"yaml: line 3: mapping values are not allowed"}""")

    assertEquals(verdict.isValid, false)
    assertEquals(verdict.message, Some("yaml: line 3: mapping values are not allowed"))

  test("a valid config carries no message"):
    assertEquals(domainValidation("""{"valid":true}""").message, None)

  test("a verdict with no verdict cannot be converted, rather than silently reading as invalid"):
    assertEquals(validationFailure("""{"message":"something"}"""), Some("$.valid"))

  test("JSON null and an absent key decode identically for the verdict's optional field"):
    assertEquals(
      decodeValidation("""{"valid":true,"message":null}"""),
      decodeValidation("""{"valid":true}"""),
    )

  // --- IssueTemplate --------------------------------------------------------

  test("a Markdown template decodes with its file name, its body and its labels"):
    val template = domainTemplate(IssueConfigDtoSuite.MarkdownTemplate)

    assertEquals(template.fileName, "bug.md")
    assertEquals(template.name, Some("Bug report"))
    assertEquals(template.content, Some("What happened?\n"))
    assertEquals(template.labels, Vector("bug", "needs-triage"))
    assertEquals(template.ref, Some("refs/heads/main"))
    assertEquals(template.isForm, false)

  test("a form template decodes its fields, in file order"):
    val template = domainTemplate(IssueConfigDtoSuite.FormTemplate)

    assertEquals(template.isForm, true)
    assertEquals(template.fields.map(_.id), Vector(Some("summary"), Some("version")))
    assertEquals(template.fields.headOption.flatMap(_.fieldType), Some(IssueFormFieldType.Input))

  test("a form field's string attribute is unwrapped, and a structured one keeps its JSON"):
    val field = domainTemplate(IssueConfigDtoSuite.FormTemplate).fields.lift(1)

    assertEquals(field.flatMap(_.attributes.get("label")), Some("Version"))
    assertEquals(field.flatMap(_.attributes.get("options")), Some("""["v1","v2"]"""))
    assertEquals(field.flatMap(_.validations.get("required")), Some("true"))
    assertEquals(field.map(_.visible), Some(Vector("form")))

  test("a template without a file name cannot be converted, because nothing else identifies it"):
    assertEquals(templateFailure("""{"name":"Bug report"}"""), Some("$.file_name"))

  test("a template whose body is JSON null is a Markdown template, not a failure"):
    assertEquals(domainTemplate("""{"file_name":"bug.md","body":null}""").fields, Vector.empty)

  test("JSON null and an absent key decode identically for every optional template field"):
    IssueConfigDtoSuite.TemplateOptionalKeys.foreach: key =>
      assertEquals(
        decodeTemplate(s"""{"file_name":"bug.md","$key":null}"""),
        decodeTemplate("""{"file_name":"bug.md"}"""),
        s"'$key' distinguished null from absent",
      )

  test("JSON null and an absent key decode identically for every optional form field key"):
    Vector("id", "type", "attributes", "validations", "visible").foreach: key =>
      assertEquals(
        decodeTemplate(s"""{"file_name":"f","body":[{"$key":null}]}"""),
        decodeTemplate("""{"file_name":"f","body":[{}]}"""),
        s"'$key' distinguished null from absent",
      )

  test("a bad element of a template array reports its own position"):
    val dtos = Json.decode[Vector[IssueTemplateDto]]("""[{"file_name":"a.md"},{"name":"b"}]""") match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the array did not decode: ${failure.message}")

    assertEquals(
      IssueTemplateDto.toDomainAll(JsonPath.Root, dtos).swap.toOption.map(_.path.render),
      Some("$[1].file_name"),
    )

  private def decodeConfig(body: String): IssueConfigDto =
    Json.decode[IssueConfigDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domainConfig(body: String): IssueConfig =
    decodeConfig(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def configFailure(body: String): Option[String] =
    decodeConfig(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeValidation(body: String): IssueConfigValidationDto =
    Json.decode[IssueConfigValidationDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domainValidation(body: String): IssueConfigValidation =
    decodeValidation(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def validationFailure(body: String): Option[String] =
    decodeValidation(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeTemplate(body: String): IssueTemplateDto =
    Json.decode[IssueTemplateDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domainTemplate(body: String): IssueTemplate =
    decodeTemplate(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def templateFailure(body: String): Option[String] =
    decodeTemplate(body).toDomain.swap.toOption.map(_.path.render)

/** The payloads this suite decodes, kept out of the test bodies so each test reads as one behaviour. */
object IssueConfigDtoSuite:

  private val Config: String =
    """{
      |  "blank_issues_enabled": false,
      |  "contact_links": [
      |    {"name": "Security", "url": "https://example.org/security", "about": "Report a vulnerability privately"},
      |    {"name": "Chat", "url": "https://example.org/chat", "about": "Ask a question"}
      |  ]
      |}""".stripMargin

  private val MarkdownTemplate: String =
    """{
      |  "file_name": "bug.md",
      |  "name": "Bug report",
      |  "about": "Something is broken",
      |  "title": "[bug] ",
      |  "content": "What happened?\n",
      |  "labels": ["bug", "needs-triage"],
      |  "ref": "refs/heads/main"
      |}""".stripMargin

  private val FormTemplate: String =
    """{
      |  "file_name": "bug.yaml",
      |  "name": "Bug report",
      |  "body": [
      |    {"id": "summary", "type": "input", "attributes": {"label": "Summary"}},
      |    {
      |      "id": "version",
      |      "type": "dropdown",
      |      "attributes": {"label": "Version", "options": ["v1", "v2"]},
      |      "validations": {"required": true},
      |      "visible": ["form"]
      |    }
      |  ]
      |}""".stripMargin

  /** Every wire key of `IssueTemplate` this DTO reads, apart from the one required field. */
  private val TemplateOptionalKeys: Vector[String] =
    Vector("name", "about", "title", "content", "labels", "ref", "body")
