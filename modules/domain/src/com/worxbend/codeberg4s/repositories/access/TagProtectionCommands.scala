package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.users.Username

/** What `POST /repos/{owner}/{repo}/tag_protections` is told.
  *
  * {{{
  * CreateTagProtection.matching(pattern).exemptingTeams(Vector("release-managers"))
  * }}}
  *
  * '''An omitted whitelist is an empty whitelist here, not a default.''' `CreateTagProtectionOption` has three
  * properties and Forgejo has nothing to fall back on for the two lists, so a command that names neither creates a rule
  * nobody is exempt from. That is a legitimate and common intent — "nobody touches `v*`" — which is why the lists are
  * plain vectors rather than `Option`s: there is no third state to express.
  *
  * @param namePattern
  *   the glob to match tag names with, sent as `name_pattern`
  * @param whitelistUsernames
  *   the accounts to exempt. Typed, because a blank entry would be a whitelist line that exempts nobody while looking
  *   like it exempts someone — see [[BranchProtectionSettings]] for the same argument at more length
  * @param whitelistTeams
  *   the teams to exempt, as plain names; a team name is scoped to an organisation and this library cannot check one
  */
final case class CreateTagProtection(
    namePattern: TagNamePattern,
    whitelistUsernames: Vector[Username],
    whitelistTeams: Vector[String],
):

  /** Replaces the accounts exempt from the rule. */
  def exempting(usernames: Vector[Username]): CreateTagProtection = copy(whitelistUsernames = usernames)

  /** Replaces the teams exempt from the rule. */
  def exemptingTeams(teams: Vector[String]): CreateTagProtection = copy(whitelistTeams = teams)

object CreateTagProtection:

  /** Starts a command from the pattern, exempting nobody.
    *
    * Total rather than validated: [[TagNamePattern]] has already rejected everything this could reject.
    */
  def matching(namePattern: TagNamePattern): CreateTagProtection =
    CreateTagProtection(namePattern = namePattern, whitelistUsernames = Vector.empty, whitelistTeams = Vector.empty)

/** What `PATCH /repos/{owner}/{repo}/tag_protections/{id}` is told.
  *
  * `EditTagProtectionOption` declares the same three properties as `CreateTagProtectionOption`, but this is a `PATCH`
  * and the endpoint is addressed by id, so every one of them is optional here: an edit that states only a new pattern
  * must not empty the whitelists as a side effect. That is the same rule [[EditBranchProtection]] follows, and for the
  * same reason.
  *
  * @param namePattern
  *   the new glob, absent to leave the rule matching what it already matches
  * @param whitelistUsernames
  *   the replacement exemption list, absent to leave it alone. An empty vector is '''not''' absence: it clears the list
  * @param whitelistTeams
  *   the replacement team exemption list, on the same reading
  */
final case class EditTagProtection(
    namePattern: Option[TagNamePattern],
    whitelistUsernames: Option[Vector[Username]],
    whitelistTeams: Option[Vector[String]],
):

  /** States a new glob for the rule to match. */
  def matching(pattern: TagNamePattern): EditTagProtection = copy(namePattern = Some(pattern))

  /** Replaces the accounts exempt from the rule; an empty vector clears the exemptions. */
  def exempting(usernames: Vector[Username]): EditTagProtection = copy(whitelistUsernames = Some(usernames))

  /** Replaces the teams exempt from the rule; an empty vector clears the exemptions. */
  def exemptingTeams(teams: Vector[String]): EditTagProtection = copy(whitelistTeams = Some(teams))

object EditTagProtection:

  /** An edit that changes nothing, to be built on. Renders to `{}`, which Forgejo accepts and then does nothing. */
  val Nothing: EditTagProtection = EditTagProtection(None, None, None)
