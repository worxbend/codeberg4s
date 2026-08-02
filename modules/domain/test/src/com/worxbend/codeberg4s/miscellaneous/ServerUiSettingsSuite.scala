package com.worxbend.codeberg4s.miscellaneous

import munit.FunSuite

/** [[ServerUiSettings.allows]]: the one question this model exists to answer, and the one answer that has to be
  * conservative.
  *
  * A caller reads this before offering a reaction, because a reaction the instance does not accept comes back as a
  * `422` from the reaction endpoints. The rule the type commits to is that an empty list means "the instance did not
  * say" rather than "everything is allowed" — silence is not permission, so an empty list allows nothing.
  */
final class ServerUiSettingsSuite extends FunSuite:

  test("a reaction the instance listed is allowed"):
    val settings = ui(Vector("+1", "-1", "heart"))

    assertEquals(settings.allows("+1"), true)
    assertEquals(settings.allows("heart"), true)

  test("a reaction the instance did not list is refused rather than passed through to a 422"):
    assertEquals(ui(Vector("+1", "heart")).allows("rocket"), false)

  test("an instance that said nothing allows nothing, because silence is not permission"):
    assertEquals(ui(Vector.empty).allows("+1"), false)
    assertEquals(ui(Vector.empty).allows(""), false)

  test("the match is exact, so a reaction is not allowed by a name that merely contains it"):
    val settings = ui(Vector("heart_eyes"))

    assertEquals(settings.allows("heart"), false)
    assertEquals(settings.allows("HEART_EYES"), false)
    assertEquals(settings.allows("heart_eyes"), true)

  test("a custom emoji the deployment defines is not thereby an allowed reaction"):
    val settings = ServerUiSettings(
      allowedReactions = Vector("+1"),
      customEmojis     = Vector("forgejo"),
      defaultTheme     = Some("forgejo-auto"),
    )

    assertEquals(settings.allows("forgejo"), false)
    assertEquals(settings.customEmojis, Vector("forgejo"))
    assertEquals(settings.defaultTheme, Some("forgejo-auto"))

  private def ui(allowedReactions: Vector[String]): ServerUiSettings =
    ServerUiSettings(allowedReactions = allowedReactions, customEmojis = Vector.empty, defaultTheme = None)
