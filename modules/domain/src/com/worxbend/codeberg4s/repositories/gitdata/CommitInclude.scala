package com.worxbend.codeberg4s.repositories.gitdata

/** Which optional parts of a commit the instance should compute — the `stat`, `verification` and `files` parameters.
  *
  * Every one of them defaults to `true` server-side, and every one costs the instance work: `files` and `stat` both
  * walk the diff against the parent, which on a merge of a large branch is the difference between a fast response and a
  * slow one. Turning them off is the documented speed-up, so it is worth being able to say so.
  *
  * ==Absent is not false==
  *
  * Each member is an `Option[Boolean]` with three meanings, not two: absent omits the parameter and takes the
  * instance's default, `Some(true)` asks for the part explicitly, and `Some(false)` declines it. Omitting is what
  * [[CommitInclude.Default]] does, and it is the only value that survives a change to Forgejo's own defaults.
  *
  * ==Where each member applies==
  *
  * `GET /git/commits/{sha}` declares all three. `GET /git/notes/{sha}` declares only `verification` and `files` — it
  * has no `stat` parameter — so a request built for the notes endpoint drops [[stat]] rather than sending a parameter
  * the route does not know.
  *
  * @param stat
  *   whether to include the commit's line counts
  * @param verification
  *   whether to include the signature verdict
  * @param files
  *   whether to include the list of affected files
  */
final case class CommitInclude(stat: Option[Boolean], verification: Option[Boolean], files: Option[Boolean]):

  /** This selection with an explicit answer for `stat`. */
  def withStat(wanted: Boolean): CommitInclude = copy(stat = Some(wanted))

  /** This selection with an explicit answer for `verification`. */
  def withVerification(wanted: Boolean): CommitInclude = copy(verification = Some(wanted))

  /** This selection with an explicit answer for `files`. */
  def withFiles(wanted: Boolean): CommitInclude = copy(files = Some(wanted))

object CommitInclude:

  /** Say nothing and take the instance's defaults, which are `true` for all three. */
  val Default: CommitInclude = CommitInclude(None, None, None)

  /** Decline all three: the cheapest commit read the endpoint offers. */
  val Minimal: CommitInclude = CommitInclude(Some(false), Some(false), Some(false))
