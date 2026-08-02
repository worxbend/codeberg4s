package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.repositories.ReleaseId

import munit.FunSuite

/** [[AssetId]] exists so that an attachment id and a release id cannot be swapped at a call site. */
final class AssetIdSuite extends FunSuite:

  test("the first attachment id of the golden release capture is accepted"):
    assertEquals(accepted(1730449L).value, 1730449L)

  test("zero is rejected, because Forgejo row ids start at one"):
    AssetId.from(0L) match
      case Left(error)  => assertEquals((error.field, error.message), ("assetId", "must be at least 1"))
      case Right(value) => fail(s"expected 0 to be rejected, got ${value.value}")

  test("a negative id is rejected"):
    assert(AssetId.from(-1L).isLeft)

  test("an asset id and a release id can hold the same number and still be different types"):
    val asset   = accepted(1730449L)
    val release = ReleaseId.from(1730449L) match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.message}")

    // The compiler is what rejects `getAsset(owner, name, asset, release)`; all
    // this can observe is that the two carry the same number and are still not
    // interchangeable, which is exactly why both types exist.
    assertEquals(asset.value, release.value)

  private def accepted(value: Long): AssetId =
    AssetId.from(value) match
      case Right(id)   => id
      case Left(error) => fail(s"expected $value to be accepted, got ${error.message}")
