package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.repositories.Commit

/** A Git note attached to a commit — `GET /repos/{owner}/{repo}/git/notes/{sha}`.
  *
  * Notes live in `refs/notes/commits` and are metadata bolted onto a commit '''after''' it was written: rewriting a
  * note does not change the commit's id, which is the whole point of the mechanism and also why a note is not part of
  * [[com.worxbend.codeberg4s.repositories.CommitDetails]].
  *
  * @param message
  *   the note's text. Optional because every field on this wire model is; a note whose message the instance omitted is
  *   indistinguishable from one whose message is empty
  * @param commit
  *   the commit the note is attached to, as the endpoint echoes it back. Absent when the instance sent no `commit`
  */
final case class GitNote(message: Option[String], commit: Option[Commit])
