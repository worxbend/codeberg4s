package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.NodeInfo
import com.worxbend.codeberg4s.miscellaneous.NodeInfoServices
import com.worxbend.codeberg4s.miscellaneous.NodeInfoUsers

import munit.FunSuite

/** `GET /nodeinfo`, the one model in this module whose keys are camelCase because the schema is not Forgejo's own.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' `golden/MANIFEST.md` records that the
  * endpoint answered `404` with a plain-text body on codeberg.org, so every payload here was written from the
  * `NodeInfo` definition and its four nested models.
  */
final class NodeInfoDtoSuite extends FunSuite:

  test("a full document decodes through every nested model"):
    domain(NodeInfoDtoSuite.FullBody) match
      case Right(info)   =>
        assertEquals(info.version, "2.1")
        assertEquals(info.software.name, "forgejo")
        assertEquals(info.software.version, Some("12.0.0"))
        assertEquals(info.protocols, Vector("activitypub"))
        assertEquals(info.services, Some(NodeInfoServices(Vector.empty, Vector.empty)))
        assertEquals(info.usage.flatMap(_.users), Some(NodeInfoUsers(Some(1234L), Some(56L), Some(7L))))
        assertEquals(info.usage.flatMap(_.localPosts), Some(89L))
        assertEquals(info.hasOpenRegistrations, true)
      case Left(failure) => fail(s"did not convert: ${failure.path.render} ${failure.message}")

  test("the camelCase keys are read as camelCase, which snake_case spellings would not be"):
    domain("""{"version":"2.1","software":{"name":"forgejo"},"open_registrations":true,"local_posts":9}""") match
      case Right(info)   =>
        assertEquals(info.hasOpenRegistrations, false, "open_registrations is not the key this schema uses")
        assertEquals(info.usage, None)
      case Left(failure) => fail(s"did not convert: ${failure.path.render} ${failure.message}")

  test("an absent key and an explicit null decode identically"):
    assertEquals(
      Json.decode[NodeInfoDto]("""{}"""),
      Json.decode[NodeInfoDto](
        """{"version":null,"software":null,"protocols":null,"services":null,"usage":null,
          |"openRegistrations":null,"metadata":null}""".stripMargin.replace("\n", "")
      ),
    )

  test("a null protocols array is an empty vector, never a crash"):
    assertEquals(
      Json.decode[NodeInfoDto]("""{"protocols":null}""").map(_.protocols),
      Right(Vector.empty[String]),
    )

  test("a document naming no schema version fails at $.version"):
    domain("""{"software":{"name":"forgejo"}}""") match
      case Left(failure) => assertEquals(failure.path.render, "$.version")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a document naming no software fails at $.software"):
    domain("""{"version":"2.1"}""") match
      case Left(failure) => assertEquals(failure.path.render, "$.software")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("software that will not identify itself fails at $.software.name, one level down"):
    domain("""{"version":"2.1","software":{"version":"12.0.0"}}""") match
      case Left(failure) => assertEquals(failure.path.render, "$.software.name")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a deployment that publishes no usage counts still converts"):
    domain("""{"version":"2.1","software":{"name":"forgejo"},"usage":{"users":{}}}""") match
      case Right(info)   => assertEquals(info.usage.flatMap(_.users), Some(NodeInfoUsers(None, None, None)))
      case Left(failure) => fail(s"did not convert: ${failure.path.render} ${failure.message}")

  test("an absent openRegistrations is read as closed, not as open"):
    domain("""{"version":"2.1","software":{"name":"forgejo"}}""") match
      case Right(info)   => assertEquals(info.hasOpenRegistrations, false)
      case Left(failure) => fail(s"did not convert: ${failure.path.render} ${failure.message}")

  test("the free-form metadata object is read and discarded rather than breaking the decode"):
    domain("""{"version":"2.1","software":{"name":"forgejo"},"metadata":{"nodeName":"x","nested":{"a":1}}}""") match
      case Right(info)   => assertEquals(info.version, "2.1")
      case Left(failure) => fail(s"did not convert: ${failure.path.render} ${failure.message}")

  test("a body that is not JSON is a DecodeFailure, not an exception"):
    assert(Json.decode[NodeInfoDto]("404 page not found").isLeft)

  private def domain(body: String): Either[DecodeFailure, NodeInfo] =
    Json.decode[NodeInfoDto](body).flatMap(_.toDomain)

object NodeInfoDtoSuite:

  private val FullBody: String =
    """{"version":"2.1","software":{"name":"forgejo","version":"12.0.0",
      |"repository":"https://codeberg.org/forgejo/forgejo","homepage":"https://forgejo.org"},
      |"protocols":["activitypub"],"services":{"inbound":[],"outbound":[]},"openRegistrations":true,
      |"usage":{"users":{"total":1234,"activeHalfyear":56,"activeMonth":7},"localPosts":89,"localComments":10},
      |"metadata":{}}""".stripMargin.replace("\n", "")
