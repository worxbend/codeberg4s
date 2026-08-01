package com.worxbend.codeberg4s.repositories

/** What a commit did to one file.
  *
  * These are the values Forgejo derives from Git's own status letters. `golden/repository/commits-list.json` only ever
  * shows `modified`, so the remaining cases are read from Forgejo's source rather than measured — which is precisely
  * why [[CommitFileStatus.parse]] answers `None` instead of failing on a value it does not know.
  */
enum CommitFileStatus:

  /** The file did not exist before this commit. */
  case Added

  /** The file existed and its contents changed. */
  case Modified

  /** The file existed and no longer does. */
  case Removed

  /** The file moved, with its contents substantially intact. */
  case Renamed

  /** The file was created from another file that still exists. */
  case Copied

  /** The file's mode or type changed rather than its contents. */
  case Changed

  /** The file is listed but untouched — Git reports this for some merge and rename detections. */
  case Unchanged

object CommitFileStatus:

  /** Parses Forgejo's lowercase spelling.
    *
    * Answers `None` for anything unrecognised rather than failing, for the same reason
    * [[com.worxbend.codeberg4s.users.UserVisibility.parse]] does: a status this library has not seen must not cost the
    * caller the whole commit. Matching is case-insensitive because nothing guarantees the casing but observation.
    */
  def parse(value: String): Option[CommitFileStatus] =
    value.trim.toLowerCase match
      case "added"     => Some(Added)
      case "modified"  => Some(Modified)
      case "removed"   => Some(Removed)
      case "renamed"   => Some(Renamed)
      case "copied"    => Some(Copied)
      case "changed"   => Some(Changed)
      case "unchanged" => Some(Unchanged)
      case _           => None

  extension (status: CommitFileStatus)

    /** The lowercase spelling Forgejo uses on the wire. */
    def wireName: String =
      status match
        case Added     => "added"
        case Modified  => "modified"
        case Removed   => "removed"
        case Renamed   => "renamed"
        case Copied    => "copied"
        case Changed   => "changed"
        case Unchanged => "unchanged"
