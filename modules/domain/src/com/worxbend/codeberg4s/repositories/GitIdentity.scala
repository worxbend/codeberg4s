package com.worxbend.codeberg4s.repositories

import java.time.Instant

/** Who authored or committed a change, as Git itself records it.
  *
  * This is deliberately '''not''' a [[com.worxbend.codeberg4s.users.User]]: the name and address in a Git object are
  * free text written by whoever ran `git commit`, and they need not correspond to any account on the instance. Forgejo
  * reports both — the account when it can match one, this identity always — and a commit from an unregistered
  * contributor has only the latter.
  *
  * Every field is optional because Forgejo spells the same idea two ways depending on the endpoint: the identity inside
  * a branch's commit carries `username` and no `date`, while the identity inside a commit's `commit` object carries
  * `date` and no `username`. Both are captured in `golden/repository/branches-list.json` and
  * `golden/repository/commits-list.json` respectively. One model covers both rather than two models differing by one
  * field.
  *
  * @param name
  *   the display name from the Git object, for example `Renovate Bot`
  * @param email
  *   the address from the Git object; it is public in the repository's history, not a private detail this library
  *   discovered
  * @param username
  *   the instance account Forgejo matched the address to, when it matched one and the endpoint reports it
  * @param date
  *   when the authorship or the commit was recorded, when the endpoint reports it
  */
final case class GitIdentity private[codeberg4s] (
    name: Option[String],
    email: Option[String],
    username: Option[String],
    date: Option[Instant],
)
