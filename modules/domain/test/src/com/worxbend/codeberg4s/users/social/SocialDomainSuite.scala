package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.repositories.RepoSlug

import munit.FunSuite

import java.time.Instant
import java.time.LocalDate

/** The identifiers, commands and vocabulary of the user social graph, keys and tokens.
  *
  * Nothing here touches a network or a codec. The subject is what a value of each type is allowed to be — which is
  * where this group's safety actually lives, since every path segment it interpolates and every scope it sends is one
  * of these.
  */
final class SocialDomainSuite extends FunSuite:

  // --- identifiers ----------------------------------------------------------

  test("every numeric identifier in this group rejects zero and anything below it"):
    assert(SshKeyId.from(0L).isLeft, "an SSH key id of 0 was accepted")
    assert(GpgKeyId.from(0L).isLeft, "a GPG key id of 0 was accepted")
    assert(AccessTokenId.from(-1L).isLeft, "a negative access token id was accepted")
    assert(BlockId.from(0L).isLeft, "a block id of 0 was accepted")

  test("a positive identifier survives as the number a path segment is rendered from"):
    assertEquals(orFail(SshKeyId.from(12L)).value, 12L)
    assertEquals(orFail(GpgKeyId.from(7L)).value, 7L)
    assertEquals(orFail(AccessTokenId.from(3L)).value, 3L)
    assertEquals(orFail(BlockId.from(99L)).value, 99L)

  test("a rejected identifier names the concept, not the endpoint"):
    assertEquals(fieldOf(SshKeyId.from(0L)), "sshKeyId")
    assertEquals(fieldOf(GpgKeyId.from(0L)), "gpgKeyId")
    assertEquals(fieldOf(AccessTokenId.from(0L)), "accessTokenId")
    assertEquals(fieldOf(BlockId.from(0L)), "blockId")

  // --- OpenPGP key ids ------------------------------------------------------

  test("an OpenPGP key id is trimmed, because it is pasted out of gpg output"):
    assertEquals(orFail(OpenPgpKeyId.from("  3AA5C34371567BD2  ")).value, "3AA5C34371567BD2")

  test("an OpenPGP key id refuses what cannot travel, and nothing else"):
    assert(OpenPgpKeyId.from("   ").isLeft, "a blank key id was accepted")
    assert(OpenPgpKeyId.from("ABC\nDEF").isLeft, "a control character was accepted")
    assert(OpenPgpKeyId.from("not-hexadecimal-at-all").isRight, "a non-hex spelling was rejected by the client")

  // --- the GPG verification handshake ---------------------------------------

  test("a verification token is trimmed, because the endpoint answers text/plain"):
    assertEquals(orFail(GpgKeyToken.from("d3adb33f\n")).value, "d3adb33f")

  test("a blank verification token is rejected — a blank challenge cannot be signed"):
    assert(GpgKeyToken.from("  ").isLeft, "a blank token was accepted")
    assertEquals(fieldOf(GpgKeyToken.from("")), "gpgKeyToken")

  test("a verification request can only be built from a token, which is what puts the handshake in order"):
    val token   = orFail(GpgKeyToken.from("d3adb33f"))
    val keyId   = orFail(OpenPgpKeyId.from("3AA5C34371567BD2"))
    val claim   = token.signedWith(keyId, orFail(ArmoredSignature.from(SocialDomainSuite.Signature)))
    val rebuilt = VerifyGpgKey(keyId, orFail(ArmoredSignature.from(SocialDomainSuite.Signature)))

    assertEquals(claim.keyId.value, "3AA5C34371567BD2")
    assertEquals(claim.armoredSignature.value, SocialDomainSuite.Signature)
    assertEquals(claim, rebuilt)

  test("an armored signature keeps its newlines, because they are part of what is verified"):
    assertEquals(orFail(ArmoredSignature.from(SocialDomainSuite.Signature)).value, SocialDomainSuite.Signature)
    assert(ArmoredSignature.from("").isLeft, "an empty signature was accepted")
    assert(ArmoredSignature.from(" \n ").isRight, "whitespace-only armor was rejected, but only empty is invalid")

  // --- key creation commands ------------------------------------------------

  test("an SSH key request trims both halves and refuses a blank one"):
    val command = orFail(CreateSshKey.of("  laptop  ", s"  ${SocialDomainSuite.KeyMaterial}  "))

    assertEquals(command.title, "laptop")
    assertEquals(command.key, SocialDomainSuite.KeyMaterial)
    assertEquals(command.isReadOnly, false)
    assertEquals(fieldOf(CreateSshKey.of("  ", SocialDomainSuite.KeyMaterial)), "title")
    assertEquals(fieldOf(CreateSshKey.of("laptop", "")), "key")

  test("an SSH key request states its push permission both ways"):
    val command = orFail(CreateSshKey.of("laptop", SocialDomainSuite.KeyMaterial))

    assertEquals(command.readOnly.isReadOnly, true)
    assertEquals(command.readOnly.readWrite.isReadOnly, false)

  test("a GPG key request is not trimmed, because armor is line-structured"):
    val command = orFail(CreateGpgKey.of(SocialDomainSuite.ArmoredKey))

    assertEquals(command.armoredPublicKey, SocialDomainSuite.ArmoredKey)
    assertEquals(command.armoredSignature, None)
    assertEquals(fieldOf(CreateGpgKey.of("")), "armoredPublicKey")

  test("a GPG key request can carry the proof of possession in the same call"):
    val signature = orFail(ArmoredSignature.from(SocialDomainSuite.Signature))
    val command   = orFail(CreateGpgKey.of(SocialDomainSuite.ArmoredKey)).provingPossession(signature)

    assertEquals(command.armoredSignature.map(_.value), Some(SocialDomainSuite.Signature))

  // --- token names and references -------------------------------------------

  test("a token name is a path segment, so a slash is refused before a request exists"):
    assertEquals(orFail(AccessTokenName.from("  ci  ")).value, "ci")
    assertEquals(fieldOf(AccessTokenName.from("../admin")), "accessTokenName")
    assert(AccessTokenName.from("ci/deploy").isLeft, "a slash was accepted into a path segment")
    assert(AccessTokenName.from("ci\tdeploy").isLeft, "a control character was accepted")
    assert(AccessTokenName.from(" ").isLeft, "a blank name was accepted")

  test("a bare dot segment is refused, since no slash rule would ever see it"):
    assert(AccessTokenName.from(".").isLeft, "'.' was accepted into a path segment")
    assert(AccessTokenName.from("..").isLeft, "'..' was accepted into a path segment")
    assertEquals(fieldOf(AccessTokenName.from("..")), "accessTokenName")
    assertEquals(orFail(AccessTokenName.from(".ci")).value, ".ci")

  test("a token reference renders whichever spelling it carries"):
    val byId   = AccessTokenRef.ById(orFail(AccessTokenId.from(42L)))
    val byName = AccessTokenRef.ByName(orFail(AccessTokenName.from("ci")))

    assertEquals(byId.pathSegment, "42")
    assertEquals(byName.pathSegment, "ci")

  // --- scopes ---------------------------------------------------------------

  test("every scope this release models round-trips through its wire spelling"):
    val modelled = TokenScope.All +: TokenCategory.values.toVector.flatMap(category =>
      Vector(TokenScope.Read(category), TokenScope.Write(category))
    )

    modelled.foreach(scope => assertEquals(TokenScope.parse(scope.wireValue), scope))

  test("the eight categories are exactly the ones the spec's example names"):
    assertEquals(
      TokenCategory.values.toVector.map(_.wireValue),
      Vector("activitypub", "issue", "misc", "notification", "organization", "package", "repository", "user"),
    )

  test("a scope this release does not model is carried verbatim rather than dropped"):
    assertEquals(TokenScope.parse("read:admin"), TokenScope.Other("read:admin"))
    assertEquals(TokenScope.parse("sudo"), TokenScope.Other("sudo"))
    assertEquals(TokenScope.parse("  write:everything  "), TokenScope.Other("write:everything"))
    assertEquals(TokenScope.Other("read:admin").wireValue, "read:admin")

  test("scope parsing tolerates casing, which nothing but the spec's example pins"):
    assertEquals(TokenScope.parse("ALL"), TokenScope.All)
    assertEquals(TokenScope.parse("Read:Repository"), TokenScope.Read(TokenCategory.Repository))

  // --- token creation -------------------------------------------------------

  test("a token request grants nothing and is confined to nothing until it is told otherwise"):
    val command = orFail(CreateAccessToken.named("ci"))

    assertEquals(command.name.value, "ci")
    assertEquals(command.scopes, Vector.empty[TokenScope])
    assertEquals(command.repositories, Vector.empty[RepoSlug])

  test("granting the same scope twice sends it once, and the order the caller wrote is kept"):
    val granted = orFail(CreateAccessToken.named("ci"))
      .granting(TokenScope.Read(TokenCategory.Repository), TokenScope.Write(TokenCategory.Issue))
      .granting(TokenScope.Read(TokenCategory.Repository))

    assertEquals(
      granted.scopes,
      Vector(TokenScope.Read(TokenCategory.Repository), TokenScope.Write(TokenCategory.Issue)),
    )

  test("confining to the same repository twice sends it once"):
    val confined = orFail(CreateAccessToken.named("ci")).limitedTo(slug, slug)

    assertEquals(confined.repositories, Vector(slug))

  // --- the credential -------------------------------------------------------

  test("a created token never renders its material, whatever holds it"):
    val token   = orFail(ApiToken.from("gto_realcredential"))
    val created = CreatedAccessToken(token, details)

    assert(!created.toString.contains("gto_realcredential"), s"the token reached toString: $created")
    assert(!s"$created".contains("gto_realcredential"), "the token reached string interpolation")
    assertEquals(created.token.reveal, "gto_realcredential")

  test("the listing model has nowhere to put a credential at all"):
    assertEquals(details.lastEight, Some("edential"))
    assertEquals(details.scopes, Vector(TokenScope.Read(TokenCategory.Repository)))

  // --- filters --------------------------------------------------------------

  test("an activity-feed filter distinguishes 'only mine' from 'anyone' from 'unstated'"):
    assertEquals(ActivityFeedQuery.Empty.onlyPerformedBy, None)
    assertEquals(ActivityFeedQuery.Empty.performedByTheAccount.onlyPerformedBy, Some(true))
    assertEquals(ActivityFeedQuery.Empty.performedByAnyone.onlyPerformedBy, Some(false))
    assertEquals(ActivityFeedQuery.Empty.on(LocalDate.of(2026, 8, 1)).date, Some(LocalDate.of(2026, 8, 1)))

  test("a tracked-time window is open at both ends until it is narrowed"):
    val moment = Instant.parse("2026-08-01T00:00:00Z")

    assertEquals(TrackedTimeWindow.Empty.since, None)
    assertEquals(TrackedTimeWindow.Empty.updatedSince(moment).since, Some(moment))
    assertEquals(TrackedTimeWindow.Empty.updatedBefore(moment).before, Some(moment))

  test("a remote follow target is trimmed and refuses what cannot travel"):
    assertEquals(
      orFail(RemoteFollowTarget.from(" https://social.example/users/x ")).value,
      "https://social.example/users/x",
    )
    assert(RemoteFollowTarget.from("").isLeft, "a blank target was accepted")
    assert(RemoteFollowTarget.from("https://x\ny").isLeft, "a control character was accepted")

  // --- fixtures -------------------------------------------------------------

  private def slug: RepoSlug =
    RepoSlug(orFail(Owner.from("forgejo")), orFail(RepoName.from("forgejo")))

  private def details: AccessToken =
    AccessToken(
      id           = orFail(AccessTokenId.from(42L)),
      name         = Some(orFail(AccessTokenName.from("ci"))),
      scopes       = Vector(TokenScope.Read(TokenCategory.Repository)),
      lastEight    = Some("edential"),
      repositories = Vector.empty,
      createdAt    = None,
    )

  private def fieldOf[A](result: Either[ValidationError, A]): String =
    result match
      case Left(error) => error.field
      case Right(_)    => fail("expected the value to be rejected")

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The multi-line and long fixtures, kept out of the tests so each reads as one behaviour. */
object SocialDomainSuite:

  private val KeyMaterial: String =
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA1B2C3D4E5F6G7H8I9J0KLMNOPQRSTUVWXYZabcd laptop"

  private val ArmoredKey: String =
    "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nmDMEY1234\n=abcd\n-----END PGP PUBLIC KEY BLOCK-----\n"

  private val Signature: String =
    "-----BEGIN PGP SIGNATURE-----\n\niHUEABYKAB0\n=wxyz\n-----END PGP SIGNATURE-----\n"
