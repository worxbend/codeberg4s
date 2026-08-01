package com.worxbend.codeberg4s.notifications

import munit.FunSuite

/** [[NotificationSubjectType]]: the discriminator no machine-readable part of the spec enumerates.
  *
  * The behaviour worth pinning is the leniency. `NotifySubjectType` is declared as a bare string, the four known values
  * come from prose, and nobody has seen a live payload — so an unrecognised value has to survive as data rather than
  * fail a page, and the capitalisation has to be treated as unconfirmed.
  */
final class NotificationSubjectTypeSuite extends FunSuite:

  test("the four documented values parse, in the capitalisation the spec's prose uses"):
    assertEquals(NotificationSubjectType.from("Issue"), NotificationSubjectType.Issue)
    assertEquals(NotificationSubjectType.from("Pull"), NotificationSubjectType.Pull)
    assertEquals(NotificationSubjectType.from("Commit"), NotificationSubjectType.Commit)
    assertEquals(NotificationSubjectType.from("Repository"), NotificationSubjectType.Repository)

  test("matching ignores case, because the filter spells the same concepts in lowercase"):
    assertEquals(NotificationSubjectType.from("issue"), NotificationSubjectType.Issue)
    assertEquals(NotificationSubjectType.from("PULL"), NotificationSubjectType.Pull)

  test("surrounding whitespace does not change what a value means"):
    assertEquals(NotificationSubjectType.from("  Commit  "), NotificationSubjectType.Commit)

  test("an unrecognised value is kept verbatim rather than failing the notification"):
    assertEquals(NotificationSubjectType.from("Discussion"), NotificationSubjectType.Other("Discussion"))

  test("an unrecognised value keeps its own capitalisation, since it is the instance's word and not ours"):
    assertEquals(NotificationSubjectType.from(" discussion "), NotificationSubjectType.Other("discussion"))

  test("wireValue round-trips every case, Other included"):
    assertEquals(NotificationSubjectType.Issue.wireValue, "Issue")
    assertEquals(NotificationSubjectType.Pull.wireValue, "Pull")
    assertEquals(NotificationSubjectType.Commit.wireValue, "Commit")
    assertEquals(NotificationSubjectType.Repository.wireValue, "Repository")
    assertEquals(NotificationSubjectType.Other("Discussion").wireValue, "Discussion")

  test("the subject filter is a different, closed vocabulary and spells its values in lowercase"):
    assertEquals(NotificationSubjectFilter.Issue.wireValue, "issue")
    assertEquals(NotificationSubjectFilter.Pull.wireValue, "pull")
    assertEquals(NotificationSubjectFilter.Repository.wireValue, "repository")

  test("the filter has no commit case, which is the API asymmetry and not an omission here"):
    assertEquals(NotificationSubjectFilter.values.length, 3)

  test("the status vocabulary is the three values the spec names in prose"):
    assertEquals(NotificationStatus.Unread.wireValue, "unread")
    assertEquals(NotificationStatus.Read.wireValue, "read")
    assertEquals(NotificationStatus.Pinned.wireValue, "pinned")
