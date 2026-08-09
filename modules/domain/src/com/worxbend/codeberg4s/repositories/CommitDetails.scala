package com.worxbend.codeberg4s.repositories

/** The Git object behind a [[Commit]] — what `git cat-file commit` would print, in JSON.
  *
  * Forgejo nests this under the `commit` key of every commit it returns, which is why the enclosing model is called
  * [[Commit]] and this one is called its details. The distinction is real rather than cosmetic: everything here comes
  * from the repository's history, while [[Commit.author]] and [[Commit.committer]] come from the instance's account
  * database and exist only when it could match an address to an account.
  *
  * @param message
  *   the full commit message, subject and body
  * @param url
  *   the API URL of the commit object
  * @param author
  *   who wrote the change, as Git records it
  * @param committer
  *   who applied it, as Git records it
  * @param tree
  *   the tree this commit points at
  * @param verification
  *   the instance's signature verdict, when it reports one
  */
final case class CommitDetails private[codeberg4s] (
    message: Option[String],
    url: Option[String],
    author: Option[GitIdentity],
    committer: Option[GitIdentity],
    tree: Option[CommitRef],
    verification: Option[CommitVerification],
)
