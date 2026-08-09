package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.users.UserVisibility

import java.time.Instant

/** An organisation on a Codeberg or Forgejo instance.
  *
  * Curated, not generated (ADR-0001), and owned by this group per `docs/LEDGER.md`. Forgejo's `Organization` has twelve
  * keys and eleven of them survive here; the twelfth, `username`, the spec itself marks `deprecated` and it duplicates
  * `name` on every organisation in `golden/organization/org-single.json` and `org-list.json`. It is kept on
  * [[com.worxbend.codeberg4s.organizations.wire.OrganizationDto]] so a captured payload can still be diffed against the
  * DTO field for field.
  *
  * ==An organisation is also a user, on some endpoints==
  *
  * `GET /users/forgejo` answers with a `User` body for the same account this model describes —
  * `golden/user/user-single-org-shaped.json` is that capture — and `GET /orgs/forgejo` answers with this shape. The two
  * are different projections of one account, not different accounts, and neither payload carries a field saying which
  * it is. The distinction lives in the endpoint. Practically: read [[com.worxbend.codeberg4s.users.User]] when the
  * caller only needs identity and avatar, and read this when it needs [[visibility]] or [[repoAdminChangeTeamAccess]],
  * which the user projection does not carry.
  *
  * ==Visibility is Forgejo's one `VisibleType`, not an organisation-specific set==
  *
  * [[visibility]] is [[com.worxbend.codeberg4s.users.UserVisibility]] rather than a second three-case enum spelled for
  * organisations. Forgejo has a single Go `VisibleType` with exactly `public`, `limited` and `private`, and both
  * `User.visibility` and `Organization.visibility` render it. `docs/LEDGER.md` makes the first wave that needs a shared
  * model its owner and calls a forked copy a review-blocking defect, so this group imports wave 1's enum instead of
  * cloning it. The type's name says "user" because a user is where it was first needed, not because it is narrower than
  * the wire type.
  *
  * @param id
  *   the instance-wide row identifier. Almost never what a caller wants: every organisation endpoint addresses the
  *   organisation by [[name]]
  * @param name
  *   the handle in URLs, and the argument every operation in this group takes; see [[OrgName]]
  * @param fullName
  *   the display name, absent when the organisation left it blank — Forgejo sends `""`, not `null`, and the two are
  *   folded together before this model is built
  * @param email
  *   the contact address the organisation published, absent on every organisation in the golden captures
  * @param visibility
  *   how much of the organisation the instance shows to whom, absent when Forgejo sent a value this library does not
  *   recognise. See the note above on why the type is named for users
  * @param repoAdminChangeTeamAccess
  *   whether a repository administrator may change which teams reach that repository; `false` when the instance did not
  *   say. Both golden captures report `true`
  * @param createdAt
  *   when the organisation was created
  */
final case class Organization private[codeberg4s] (
    id: Long,
    name: OrgName,
    fullName: Option[String],
    email: Option[String],
    avatarUrl: Option[String],
    description: Option[String],
    website: Option[String],
    location: Option[String],
    visibility: Option[UserVisibility],
    repoAdminChangeTeamAccess: Boolean,
    createdAt: Option[Instant],
)
