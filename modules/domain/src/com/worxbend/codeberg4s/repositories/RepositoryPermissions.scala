package com.worxbend.codeberg4s.repositories

/** What the authenticated caller may do with a repository.
  *
  * Forgejo answers this from the perspective of whoever made the request, so the same repository yields different
  * permissions to different tokens, and an anonymous call to a public repository yields `pull` only — exactly what
  * `golden/repository/repo-single.json` shows.
  *
  * @param admin
  *   may change settings, collaborators and branch protection
  * @param push
  *   may write to branches
  * @param pull
  *   may read; `true` for any repository a caller can see at all
  */
final case class RepositoryPermissions(admin: Boolean, push: Boolean, pull: Boolean)
