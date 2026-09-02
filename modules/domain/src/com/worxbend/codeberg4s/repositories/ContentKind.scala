package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.WireVocabulary

/** What a repository entry is, from the `type` field Forgejo puts on every content object.
  *
  * This is the '''second''' discriminator on the contents endpoint and it is independent of the first. The first is the
  * JSON kind of the whole response — object for one entry, array for a directory listing — and it is modelled by
  * [[RepositoryContent]]. This one is the per-entry `type`, and it decides which fields of that entry are populated:
  * `content` and `encoding` for a file, `target` for a symlink, `submodule_git_url` for a submodule. `docs/HAZARDS.md`
  * §3 measured both, and [[ContentEntry]] is the enum that keeps the second one honest.
  */
enum ContentKind(val wireName: String) extends WireVocabulary:

  /** A blob. Its bytes are reported when the entry was fetched by its own path, and not when it was listed. */
  case File extends ContentKind("file")

  /** A tree — the entry has children, which are reached by requesting its own path. */
  case Directory extends ContentKind("dir")

  /** A symbolic link. What it points at is its `target`, which is a path and not necessarily a valid one. */
  case Symlink extends ContentKind("symlink")

  /** A gitlink: another repository mounted at this path. */
  case Submodule extends ContentKind("submodule")

object ContentKind:

  /** Parses Forgejo's spelling of the `type` field.
    *
    * Note `dir`, not `directory` — the wire is abbreviated and the domain is not. Answers `None` for anything
    * unrecognised, which the DTO turns into a decoding failure: unlike a descriptive field, the kind decides which
    * other fields mean anything, so guessing it would produce a plausible and wrong entry.
    */
  def parse(value: String): Option[ContentKind] =
    WireVocabulary.parse(values, value)
