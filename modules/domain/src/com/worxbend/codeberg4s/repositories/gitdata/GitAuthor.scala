package com.worxbend.codeberg4s.repositories.gitdata

/** A name and an address to write into a commit — Forgejo's `Identity`, in request position.
  *
  * Distinct from [[com.worxbend.codeberg4s.repositories.GitIdentity]] on purpose. That model is what a '''response'''
  * carries: it also has a `username`, which is the instance's account match, and a `date`, and neither is something a
  * caller supplies. This one is the two fields a request may set, so a caller cannot ask for an account match by
  * accident.
  *
  * Neither field is validated. Both are free text on their way into a JSON body — never into a URI path — so there is
  * nothing to forge, and Git itself accepts any bytes in an identity. Forgejo fills in whichever half is missing from
  * the other, and falls back to the authenticated account when both are.
  *
  * @param name
  *   the human name to record
  * @param email
  *   the address to record
  */
final case class GitAuthor(name: String, email: String)
