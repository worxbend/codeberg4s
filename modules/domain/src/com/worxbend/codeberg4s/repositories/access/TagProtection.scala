package com.worxbend.codeberg4s.repositories.access

import java.time.Instant

/** One tag protection rule — who may create or delete the tags whose names match [[namePattern]].
  *
  * '''No golden fixture backs this model.''' `golden/MANIFEST.md` records that every capture was taken anonymously and
  * `GET /repos/{owner}/{repo}/tag_protections` requires a token, so the six fields below are exactly the properties of
  * `definitions.TagProtection` in `spec/swagger.v1.json`. Per `docs/HAZARDS.md` §1 the spec asserts nothing about
  * optionality, so every field is treated as absent-able and the conversion supplies the reading below.
  *
  * ==A tag protection is a whitelist, not a switch==
  *
  * There is no `enabled` flag and no equivalent of a branch rule's `enable_push_whitelist`: the rule exists, and the
  * two whitelists say who is exempt from it. An empty whitelist therefore means "nobody may touch these tags", which is
  * the opposite of what an empty whitelist means on a branch rule whose whitelist switch is off. Reading one of these
  * as though it were the other is the mistake this note exists to prevent.
  *
  * @param id
  *   the identifier the by-id endpoints take. Required, because a rule that cannot be addressed cannot be edited or
  *   removed
  * @param namePattern
  *   the glob matched against tag names — `v*`, `v1.*`, `*`. A plain `String` and not a [[TagNamePattern]], because a
  *   pattern the instance holds must not vanish from a listing for its spelling; [[TagNamePattern.from]] is how a
  *   caller turns one back into something they can send
  * @param whitelistUsernames
  *   the accounts exempt from the rule; empty when the payload carried none, which grants nobody an exemption
  * @param whitelistTeams
  *   the teams exempt from the rule, on the same reading
  */
final case class TagProtection private[codeberg4s] (
    id: TagProtectionId,
    namePattern: String,
    whitelistUsernames: Vector[String],
    whitelistTeams: Vector[String],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
)
