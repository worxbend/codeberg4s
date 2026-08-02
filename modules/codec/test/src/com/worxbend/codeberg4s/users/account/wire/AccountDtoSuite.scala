package com.worxbend.codeberg4s.users.account.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.account.ClientSecret
import com.worxbend.codeberg4s.users.account.Email
import com.worxbend.codeberg4s.users.account.OAuth2Application
import com.worxbend.codeberg4s.users.account.UserSettings

import munit.FunSuite

/** Decoding the three object shapes that are new to the account group: an application, an address, and the settings.
  *
  * '''Payloads written by hand from `spec/swagger.v1.json`, not captured.''' Every endpoint in this group requires a
  * token and the golden harvest was anonymous, so there is no fixture to check these against; see
  * [[OAuth2ApplicationDto]].
  *
  * Each model is exercised three ways, which is the rule `docs/HAZARDS.md` §1 forces: a full payload, a payload whose
  * every field is JSON `null`, and a payload whose every optional key is absent. The second and third must decode
  * identically, because on this API they mean the same thing.
  */
final class AccountDtoSuite extends FunSuite:

  // --- OAuth2 applications --------------------------------------------------

  test("a full application decodes field for field"):
    val dto = decodeApplication(AccountDtoSuite.ApplicationBody)

    assertEquals(dto.id, Some(7L))
    assertEquals(dto.name, Some("deploy-bot"))
    assertEquals(dto.clientId, Some("2fd3a1c0-1111-2222-3333-444455556666"))
    assertEquals(dto.clientSecret, Some("gto_51f0c9a2"))
    assertEquals(dto.redirectUris, Vector("https://ci.example/oauth/callback"))
    assertEquals(dto.confidentialClient, Some(true))
    assertEquals(dto.created, Some("2026-07-30T19:14:15+02:00"))

  test("a creation response carries the client secret as a masked credential"):
    val app = application(AccountDtoSuite.ApplicationBody)

    assertEquals(app.clientSecret.map(_.reveal), Some("gto_51f0c9a2"))
    assertEquals(app.clientSecret.map(_.toString), Some(ClientSecret.Redacted))
    assertEquals(app.carriesSecret, true)

  test("a read response carries no secret, which is the API's behaviour and not a gap in the payload"):
    assertEquals(application("""{"id":7,"name":"deploy-bot"}""").carriesSecret, false)

  test("a blank client secret is absence rather than a failure, because a read may echo an empty key"):
    assertEquals(application("""{"id":7,"client_secret":"   "}""").carriesSecret, false)

  test("an application without an id cannot be converted, because nothing else addresses it"):
    assertEquals(applicationFailure("""{"name":"nameless"}"""), Some("$.id"))

  test("an application whose id is not a positive row id is refused at the id's own path"):
    assertEquals(applicationFailure("""{"id":0}"""), Some("$.id"))

  test("a confidential_client the instance omitted reads as a public client"):
    assertEquals(application("""{"id":7}""").isConfidentialClient, false)

  test("the zero-time sentinel on created is folded into absence"):
    assertEquals(application("""{"id":7,"created":"0001-01-01T00:00:00Z"}""").createdAt, None)

  test("JSON null and an absent key decode identically for every application field"):
    assertEquals(decodeApplication(AccountDtoSuite.NullApplicationBody), decodeApplication("""{"id":7}"""))

  test("a null redirect_uris array becomes an empty vector rather than aborting the read"):
    assertEquals(decodeApplication("""{"id":7,"redirect_uris":null}""").redirectUris, Vector.empty[String])

  test("a bad element of an application array reports its own position"):
    val dtos = decodeAll[OAuth2ApplicationDto]("""[{"id":1},{"name":"no id"}]""")

    assertEquals(
      OAuth2ApplicationDto.toDomainAll(JsonPath.Root, dtos).swap.toOption.map(_.path.render),
      Some("$[1].id"),
    )

  // --- email addresses ------------------------------------------------------

  test("a full address decodes field for field"):
    val dto = decodeEmail(AccountDtoSuite.EmailBody)

    assertEquals(dto.email, Some("maintainer@example.org"))
    assertEquals(dto.primary, Some(true))
    assertEquals(dto.verified, Some(true))
    assertEquals(dto.userId, Some(31L))
    assertEquals(dto.username, Some("maintainer"))

  test("an address converts into the identifier both mutating endpoints name"):
    assertEquals(email(AccountDtoSuite.EmailBody).address.value, "maintainer@example.org")

  test("flags the instance omitted read as false, because that is what every caller would have supplied"):
    val decoded = email("""{"email":"maintainer@example.org"}""")

    assertEquals(decoded.isPrimary, false)
    assertEquals(decoded.isVerified, false)

  test("an entry without an address cannot be converted, because the address is the identifier"):
    assertEquals(emailFailure("""{"primary":true}"""), Some("$.email"))

  test("an address the instance sends that is not address-shaped fails at its own path"):
    assertEquals(emailFailure("""{"email":"not-an-address"}"""), Some("$.email"))

  test("JSON null and an absent key decode identically for every address field"):
    val body = """{"email":"maintainer@example.org"}"""

    assertEquals(decodeEmail(AccountDtoSuite.NullEmailBody), decodeEmail(body))

  test("a bad element of an address array reports its own position, rather than being dropped"):
    val dtos = decodeAll[EmailDto]("""[{"email":"a@b.example"},{"primary":true}]""")

    assertEquals(EmailDto.toDomainAll(JsonPath.Root, dtos).swap.toOption.map(_.path.render), Some("$[1].email"))

  // --- settings -------------------------------------------------------------

  test("full settings decode field for field"):
    val decoded = settings(AccountDtoSuite.SettingsBody)

    assertEquals(decoded.fullName, Some("A Maintainer"))
    assertEquals(decoded.website, Some("https://example.org"))
    assertEquals(decoded.location, Some("Somewhere"))
    assertEquals(decoded.description, Some("writes things"))
    assertEquals(decoded.pronouns, Some("they/them"))
    assertEquals(decoded.language, Some("en-US"))
    assertEquals(decoded.theme, Some("forgejo-dark"))
    assertEquals(decoded.diffViewStyle, Some("unified"))
    assertEquals(decoded.hidesEmail, true)
    assertEquals(decoded.hidesActivity, false)
    assertEquals(decoded.hidesPronouns, true)
    assertEquals(decoded.showsRepoUnitHints, true)

  test("Forgejo's empty string for unset text is folded into absence"):
    val decoded = settings("""{"location":"","website":"","description":"","language":""}""")

    assertEquals(decoded.location, None)
    assertEquals(decoded.website, None)
    assertEquals(decoded.description, None)
    assertEquals(decoded.language, None)

  test("settings can never fail to convert, because nothing in them addresses anything"):
    assertEquals(settings("{}"), AccountDtoSuite.EmptySettings)

  test("JSON null and an absent key decode identically for every settings field"):
    assertEquals(decodeSettings(AccountDtoSuite.NullSettingsBody), decodeSettings("{}"))

  // --- harness --------------------------------------------------------------

  private def decodeApplication(body: String): OAuth2ApplicationDto =
    decodeOne[OAuth2ApplicationDto](body)

  private def application(body: String): OAuth2Application =
    convert(decodeApplication(body).toDomain)

  private def applicationFailure(body: String): Option[String] =
    decodeApplication(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeEmail(body: String): EmailDto =
    decodeOne[EmailDto](body)

  private def email(body: String): Email =
    convert(decodeEmail(body).toDomain)

  private def emailFailure(body: String): Option[String] =
    decodeEmail(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeSettings(body: String): UserSettingsDto =
    decodeOne[UserSettingsDto](body)

  private def settings(body: String): UserSettings =
    convert(decodeSettings(body).toDomain)

  private def decodeOne[A: upickle.default.Reader](body: String): A =
    Json.decode[A](body) match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the payload did not decode: ${failure.message}")

  private def decodeAll[A: upickle.default.Reader](body: String): Vector[A] =
    decodeOne[Vector[A]](body)

  private def convert[A](result: Either[DecodeFailure, A]): A =
    result match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

/** The payloads this suite decodes, kept out of the test bodies so each test reads as one behaviour.
  *
  * All of them are hand-written from `spec/swagger.v1.json`; no endpoint in this group has a golden capture.
  */
object AccountDtoSuite:

  private val ApplicationBody: String =
    """{"id": 7, "name": "deploy-bot", "client_id": "2fd3a1c0-1111-2222-3333-444455556666",
      | "client_secret": "gto_51f0c9a2", "redirect_uris": ["https://ci.example/oauth/callback"],
      | "confidential_client": true, "created": "2026-07-30T19:14:15+02:00"}""".stripMargin

  private val NullApplicationBody: String =
    """{"id": 7, "name": null, "client_id": null, "client_secret": null, "redirect_uris": null,
      | "confidential_client": null, "created": null}""".stripMargin

  private val EmailBody: String =
    """{"email": "maintainer@example.org", "primary": true, "verified": true, "user_id": 31,
      | "username": "maintainer"}""".stripMargin

  private val NullEmailBody: String =
    """{"email": "maintainer@example.org", "primary": null, "verified": null, "user_id": null,
      | "username": null}""".stripMargin

  private val SettingsBody: String =
    """{"full_name": "A Maintainer", "website": "https://example.org", "location": "Somewhere",
      | "description": "writes things", "pronouns": "they/them", "language": "en-US", "theme": "forgejo-dark",
      | "diff_view_style": "unified", "hide_email": true, "hide_activity": false, "hide_pronouns": true,
      | "enable_repo_unit_hints": true}""".stripMargin

  private val NullSettingsBody: String =
    """{"full_name": null, "website": null, "location": null, "description": null, "pronouns": null,
      | "language": null, "theme": null, "diff_view_style": null, "hide_email": null, "hide_activity": null,
      | "hide_pronouns": null, "enable_repo_unit_hints": null}""".stripMargin

  /** What `{}` has to decode to: nothing stated, and every flag off. */
  private val EmptySettings: UserSettings =
    UserSettings(
      fullName           = None,
      website            = None,
      location           = None,
      description        = None,
      pronouns           = None,
      language           = None,
      theme              = None,
      diffViewStyle      = None,
      hidesEmail         = false,
      hidesActivity      = false,
      hidesPronouns      = false,
      showsRepoUnitHints = false,
    )
