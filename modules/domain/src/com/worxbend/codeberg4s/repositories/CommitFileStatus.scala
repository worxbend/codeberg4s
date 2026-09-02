package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.WireVocabulary

/** What a commit did to one file.
  *
  * These are the values Forgejo derives from Git's own status letters. `golden/repository/commits-list.json` only ever
  * shows `modified`, so the remaining cases are read from Forgejo's source rather than measured — which is precisely
  * why [[CommitFileStatus.parse]] answers `None` instead of failing on a value it does not know.
  */
enum CommitFileStatus(val wireName: String) extends WireVocabulary:

  /** The file did not exist before this commit. */
  case Added extends CommitFileStatus("added")

  /** The file existed and its contents changed. */
  case Modified extends CommitFileStatus("modified")

  /** The file existed and no longer does. */
  case Removed extends CommitFileStatus("removed")

  /** The file moved, with its contents substantially intact. */
  case Renamed extends CommitFileStatus("renamed")

  /** The file was created from another file that still exists. */
  case Copied extends CommitFileStatus("copied")

  /** The file's mode or type changed rather than its contents. */
  case Changed extends CommitFileStatus("changed")

  /** The file is listed but untouched — Git reports this for some merge and rename detections. */
  case Unchanged extends CommitFileStatus("unchanged")

object CommitFileStatus:

  /** Parses Forgejo's lowercase spelling.
    *
    * Answers `None` for anything unrecognised rather than failing, for the same reason
    * [[com.worxbend.codeberg4s.users.UserVisibility.parse]] does: a status this library has not seen must not cost the
    * caller the whole commit. Matching is case-insensitive because nothing guarantees the casing but observation.
    */
  def parse(value: String): Option[CommitFileStatus] =
    WireVocabulary.parse(values, value)
