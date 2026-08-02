package com.worxbend.codeberg4s

import munit.FunSuite

/** [[TransportCause.describe]] is what a human reads when a request never produced a response, and its six arms are six
  * near-identical one-liners — exactly the shape a copy-paste gets wrong. Each is asserted whole, so an arm that named
  * the wrong failure or dropped the detail fails here.
  */
final class TransportCauseSuite extends FunSuite:

  private val Detail: String = "api.codeberg.org:443"

  test("a connection failure says so and keeps the detail"):
    assertEquals(TransportCause.ConnectionFailed(Detail).describe, s"connection failed ($Detail)")

  test("a timeout says so and keeps the detail"):
    assertEquals(TransportCause.Timeout(Detail).describe, s"timed out ($Detail)")

  test("a TLS failure says TLS, not connection failed"):
    assertEquals(TransportCause.Tls(Detail).describe, s"TLS failure ($Detail)")

  test("a name resolution failure says so, and is not folded into a connection failure"):
    assertEquals(TransportCause.Dns(Detail).describe, s"name resolution failed ($Detail)")

  test("an interruption says so, and is not reported as a timeout"):
    assertEquals(TransportCause.Interrupted(Detail).describe, s"interrupted ($Detail)")

  test("an unclassified failure says it is unclassified rather than guessing"):
    assertEquals(TransportCause.Unknown(Detail).describe, s"unclassified transport failure ($Detail)")

  test("no two causes describe themselves identically, so the text distinguishes them as well as the case does"):
    val descriptions = causes.map(_.describe)

    assertEquals(descriptions.distinct.length, descriptions.length)

  test("every description embeds the detail it was given, because that is where the diagnostic lives"):
    causes.foreach(cause => assert(cause.describe.contains(Detail), s"${cause.describe} dropped the detail"))

  private def causes: List[TransportCause] =
    List(
      TransportCause.ConnectionFailed(Detail),
      TransportCause.Timeout(Detail),
      TransportCause.Tls(Detail),
      TransportCause.Dns(Detail),
      TransportCause.Interrupted(Detail),
      TransportCause.Unknown(Detail),
    )
