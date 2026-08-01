package com.worxbend.codeberg4s

/** The version string a Forgejo or Codeberg instance reports from `GET /version`.
  *
  * Deliberately '''not''' parsed into major/minor/patch. The value captured from the live instance in
  * `golden/version/version.json` is `16.0.0-dev-668-1bdb1938+gitea-1.22.0`: a Forgejo release train, a development
  * distance, a Git hash and a Gitea compatibility claim, in one field with no documented grammar. Any structure this
  * library imposed on it would be a guess, and a guess that silently mis-orders two builds is worse than no ordering at
  * all.
  *
  * Compare instances for equality, log the value, show it in a bug report — but do not branch on it ordinally.
  * Capability detection belongs on the endpoint that has the capability.
  *
  * @param raw
  *   the string exactly as the instance reported it, never blank
  */
final case class ServerVersion(raw: String)
