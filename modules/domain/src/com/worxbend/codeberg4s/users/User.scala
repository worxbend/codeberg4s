package com.worxbend.codeberg4s.users

import java.time.Instant

/** An account on a Codeberg or Forgejo instance.
  *
  * `User` is the most reused model in the API: `docs/LEDGER.md` records it as embedded in repositories, issues, pull
  * requests, comments, releases, organisations and notifications. It is therefore defined once and widened rather than
  * forked — Forgejo sometimes returns a reduced object with only `id`, `login` and `avatar_url`, and that is the same
  * type with fewer fields present, not a different one.
  *
  * Organisations are users too, on the wire: `golden/user/user-single-org-shaped.json` is `GET /users/forgejo`, and the
  * body is field-for-field a `User`. The distinction lives in the endpoint, not in the payload.
  *
  * Two fields the API sends are deliberately absent here. `username` duplicates `login` on all 102 observed user
  * objects and is not even in the spec; `login_name` and `source_id` describe the instance's authentication source and
  * are `""`/`0` for every account a client can read. Their DTO keeps them, so nothing is lost, but they are not domain
  * concepts.
  *
  * @param id
  *   the instance-local numeric identifier
  * @param login
  *   the handle in URLs — the value that becomes a [[com.worxbend.codeberg4s.Owner]]
  * @param fullName
  *   the display name, absent when the account left it blank
  * @param email
  *   frequently the instance's `@noreply` placeholder rather than a reachable address
  * @param createdAt
  *   when the account was created
  * @param lastLoginAt
  *   absent unless the caller is an administrator; Forgejo reports the zero-time sentinel otherwise
  */
final case class User private[codeberg4s] (
    id: Long,
    login: String,
    fullName: Option[String],
    email: Option[String],
    avatarUrl: Option[String],
    htmlUrl: Option[String],
    language: Option[String],
    location: Option[String],
    pronouns: Option[String],
    website: Option[String],
    description: Option[String],
    visibility: Option[UserVisibility],
    isAdmin: Boolean,
    isActive: Boolean,
    isRestricted: Boolean,
    isProhibitedFromLogin: Boolean,
    followersCount: Long,
    followingCount: Long,
    starredRepositoriesCount: Long,
    createdAt: Option[Instant],
    lastLoginAt: Option[Instant],
)
