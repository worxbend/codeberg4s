package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.repositories.RepoSlug
import com.worxbend.codeberg4s.users.social.ActivityFeedQuery
import com.worxbend.codeberg4s.users.social.ArmoredSignature
import com.worxbend.codeberg4s.users.social.CreateAccessToken
import com.worxbend.codeberg4s.users.social.CreateGpgKey
import com.worxbend.codeberg4s.users.social.CreateSshKey
import com.worxbend.codeberg4s.users.social.GpgKeyToken
import com.worxbend.codeberg4s.users.social.OpenPgpKeyId
import com.worxbend.codeberg4s.users.social.RemoteFollowTarget
import com.worxbend.codeberg4s.users.social.TokenCategory
import com.worxbend.codeberg4s.users.social.TokenScope
import com.worxbend.codeberg4s.users.social.TrackedTimeWindow

import munit.FunSuite

import java.time.Instant
import java.time.LocalDate

/** What this group '''sends''': the five request bodies and the query strings.
  *
  * Every shape is asserted directly rather than through a stub backend, which is the point of keeping the renderers in
  * the codec module. All of them are hand-authored from `spec/swagger.v1.json`; none of these endpoints has a golden
  * capture, because every one of them authenticates.
  */
final class SocialRequestSuite extends FunSuite:

  // --- key bodies -----------------------------------------------------------

  test("an SSH key body sends all three keys, read_only included"):
    val command = orFail(CreateSshKey.of("laptop", "ssh-ed25519 AAAA comment"))

    assertEquals(
      CreateKeyOptionDto.render(command),
      """{"title":"laptop","key":"ssh-ed25519 AAAA comment","read_only":false}""",
    )
    assertEquals(
      CreateKeyOptionDto.render(command.readOnly),
      """{"title":"laptop","key":"ssh-ed25519 AAAA comment","read_only":true}""",
    )

  test("a GPG key body omits the signature when the caller is not proving possession"):
    val command = orFail(CreateGpgKey.of("BLOCK"))

    assertEquals(CreateGpgKeyOptionDto.render(command), """{"armored_public_key":"BLOCK"}""")

  test("a GPG key body carries the signature when it has one, escaped rather than injected"):
    val signature = orFail(ArmoredSignature.from("LINE1\nLINE2"))
    val command   = orFail(CreateGpgKey.of("BLOCK")).provingPossession(signature)

    assertEquals(
      CreateGpgKeyOptionDto.render(command),
      """{"armored_public_key":"BLOCK","armored_signature":"LINE1\nLINE2"}""",
    )

  test("a verification body names the OpenPGP key and the signature over the token"):
    val token = orFail(GpgKeyToken.from("d3adb33f"))
    val claim = token.signedWith(orFail(OpenPgpKeyId.from("3AA5C34371567BD2")), orFail(ArmoredSignature.from("SIG")))

    assertEquals(
      VerifyGpgKeyOptionDto.render(claim),
      """{"key_id":"3AA5C34371567BD2","armored_signature":"SIG"}""",
    )

  test("a multi-line signature survives the JSON boundary intact"):
    val armored = "-----BEGIN PGP SIGNATURE-----\n\niHUE\n=ab\"cd\\\n-----END PGP SIGNATURE-----\n"
    val token   = orFail(GpgKeyToken.from("d3adb33f"))
    val claim   = token.signedWith(orFail(OpenPgpKeyId.from("AB")), orFail(ArmoredSignature.from(armored)))
    val body    = VerifyGpgKeyOptionDto.render(claim)

    assertEquals(Json.parse(body).toOption.flatMap(_.field("armored_signature")).flatMap(_.strOpt), Some(armored))

  // --- token bodies ---------------------------------------------------------

  test("a token body sends only the name when nothing else was asked for"):
    assertEquals(CreateAccessTokenOptionDto.render(orFail(CreateAccessToken.named("ci"))), """{"name":"ci"}""")

  test("a token body sends scopes in the order they were granted"):
    val command = orFail(CreateAccessToken.named("ci"))
      .granting(TokenScope.Read(TokenCategory.Repository), TokenScope.All)

    assertEquals(
      CreateAccessTokenOptionDto.render(command),
      """{"name":"ci","scopes":["read:repository","all"]}""",
    )

  test("a repository restriction is sent as Forgejo's {owner, name} target, not as a full name"):
    val command = orFail(CreateAccessToken.named("ci")).limitedTo(slug)

    assertEquals(
      CreateAccessTokenOptionDto.render(command),
      """{"name":"ci","repositories":[{"owner":"forgejo","name":"forgejo"}]}""",
    )

  test("a scope this release does not model round-trips into the body verbatim"):
    val command = orFail(CreateAccessToken.named("ci")).granting(TokenScope.Other("read:admin"))

    assertEquals(CreateAccessTokenOptionDto.render(command), """{"name":"ci","scopes":["read:admin"]}""")

  test("a remote follow body carries the target and nothing else"):
    val target = orFail(RemoteFollowTarget.from("https://social.example/users/x"))

    assertEquals(RemoteFollowOptionDto.render(target), """{"target":"https://social.example/users/x"}""")

  // --- queries --------------------------------------------------------------

  test("an unset activity filter sends nothing at all"):
    assertEquals(SocialQueries.activityFeeds(ActivityFeedQuery.Empty), Nil)

  test("an activity filter sends the hyphenated key the spec declares, and an ISO date"):
    val query = ActivityFeedQuery.Empty.performedByTheAccount.on(LocalDate.of(2026, 8, 1))

    assertEquals(
      SocialQueries.activityFeeds(query),
      List("only-performed-by" -> "true", "date" -> "2026-08-01"),
    )

  test("stating that anyone's actions are wanted is not the same request as saying nothing"):
    assertEquals(
      SocialQueries.activityFeeds(ActivityFeedQuery.Empty.performedByAnyone),
      List("only-performed-by" -> "false"),
    )

  test("an unset tracked-time window sends nothing at all"):
    assertEquals(SocialQueries.trackedTimes(TrackedTimeWindow.Empty), Nil)

  test("a tracked-time window renders RFC-3339 with second precision, which Go parses"):
    val window = TrackedTimeWindow.Empty
      .updatedSince(Instant.parse("2026-08-01T10:11:12.345Z"))
      .updatedBefore(Instant.parse("2026-08-02T00:00:00Z"))

    assertEquals(
      SocialQueries.trackedTimes(window),
      List("since" -> "2026-08-01T10:11:12Z", "before" -> "2026-08-02T00:00:00Z"),
    )

  // --- harness --------------------------------------------------------------

  private def slug: RepoSlug =
    RepoSlug(orFail(Owner.from("forgejo")), orFail(RepoName.from("forgejo")))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
