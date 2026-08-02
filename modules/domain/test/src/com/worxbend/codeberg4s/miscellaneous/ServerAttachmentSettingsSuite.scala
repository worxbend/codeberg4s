package com.worxbend.codeberg4s.miscellaneous

import munit.FunSuite

/** [[ServerAttachmentSettings.acceptsAnyType]] is read before offering an upload, so a wrong answer either hides an
  * upload the instance would have accepted or offers one it will reject with a `422`. The wildcard is one exact string,
  * the one `golden/misc/settings-attachment.json` reports in its `allowed_types`, and a subtype wildcard is not it.
  */
final class ServerAttachmentSettingsSuite extends FunSuite:

  test("the wildcard is a star, a slash and a star, which is what Codeberg reports"):
    assertEquals(ServerAttachmentSettings.AnyType, "*/*")

  test("an instance whose allowed types carry the wildcard accepts anything"):
    assertEquals(settings(Vector(ServerAttachmentSettings.AnyType)).acceptsAnyType, true)

  test("the wildcard is recognised alongside other entries, since it survives the split as one element"):
    assertEquals(settings(Vector("image/png", ServerAttachmentSettings.AnyType)).acceptsAnyType, true)

  test("an explicit type list does not accept anything"):
    assertEquals(settings(Vector("image/png", "application/pdf")).acceptsAnyType, false)

  test("a subtype wildcard is not the any-type wildcard, so image/* does not mean anything goes"):
    assertEquals(settings(Vector("image/*")).acceptsAnyType, false)

  test("an instance that reports no allowed types at all does not accept anything"):
    assertEquals(settings(Vector.empty).acceptsAnyType, false)

  test("acceptsAnyType is about the type list, not about whether attachments are enabled"):
    val disabled = ServerAttachmentSettings(
      enabled      = false,
      allowedTypes = Vector(ServerAttachmentSettings.AnyType),
      maxSizeMib   = None,
      maxFiles     = None,
    )

    assertEquals(disabled.acceptsAnyType, true)
    assertEquals(disabled.enabled, false)

  test("the size limit is in mebibytes, the unit Forgejo configures it in"):
    val codeberg = ServerAttachmentSettings(
      enabled      = true,
      allowedTypes = Vector(ServerAttachmentSettings.AnyType),
      maxSizeMib   = Some(100L),
      maxFiles     = Some(20L),
    )

    assertEquals(codeberg.maxSizeMib, Some(100L))
    assertEquals(codeberg.maxFiles, Some(20L))

  private def settings(allowedTypes: Vector[String]): ServerAttachmentSettings =
    ServerAttachmentSettings(enabled = true, allowedTypes = allowedTypes, maxSizeMib = None, maxFiles = None)
