package com.worxbend.codeberg4s.codec

import munit.Assertions

import java.nio.charset.StandardCharsets

/** Reads a captured response body from `modules/codec/test/resources/golden`.
  *
  * Those files are verbatim, unmodified bodies from the live Codeberg instance, and their manifest states the rule this
  * whole module is built on: they outrank `spec/swagger.v1.json` on every disagreement. A test that fails against a
  * fixture is a decoder bug, never a reason to edit the fixture.
  */
trait GoldenFixtures:
  self: Assertions =>

  /** Loads a fixture by its path below `golden/`, for example `"user/user-single.json"`.
    *
    * Fails the test rather than returning an empty body when the resource is missing: a silently absent fixture would
    * turn every assertion below it into a vacuous truth.
    */
  def golden(path: String): String =
    val resource = s"/golden/$path"
    Option(getClass.getResourceAsStream(resource)) match
      case Some(stream) =>
        try String(stream.readAllBytes(), StandardCharsets.UTF_8)
        finally stream.close()
      case None         =>
        fail(s"missing golden fixture: $resource")
