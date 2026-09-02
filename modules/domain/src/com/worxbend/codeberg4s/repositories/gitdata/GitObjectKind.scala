package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.WireVocabulary

/** What kind of object a Git id points at: the four object types Git itself defines.
  *
  * Forgejo sends this as the `type` field of a [[GitReference]]'s target, of an [[AnnotatedTag]]'s target, and of every
  * entry of a tree listing. The set is closed by Git's own object model rather than by Forgejo, which is why the cases
  * are named rather than free text.
  *
  * [[parse]] is nevertheless lenient — see its own note. That is the opposite of
  * [[com.worxbend.codeberg4s.repositories.ContentKind]], and deliberately so: a content entry's `type` decides which
  * other fields of the payload mean anything, while an object's kind is purely descriptive and never changes how the
  * rest of the object is read.
  */
enum GitObjectKind(val wireName: String) extends WireVocabulary:

  /** A file's contents. */
  case Blob extends GitObjectKind("blob")

  /** A directory. */
  case Tree extends GitObjectKind("tree")

  /** A commit. Also what a tree entry reports for a submodule, since a gitlink stores a commit id. */
  case Commit extends GitObjectKind("commit")

  /** An annotated tag object, as opposed to a lightweight tag, which is a ref pointing straight at a commit. */
  case Tag extends GitObjectKind("tag")

object GitObjectKind:

  /** Parses Forgejo's spelling of an object `type`.
    *
    * Answers `None` for anything unrecognised rather than failing the decode. A kind this library does not know costs
    * the caller one descriptive field; failing would cost them the whole tree page it arrived in.
    */
  def parse(value: String): Option[GitObjectKind] =
    WireVocabulary.parse(values, value)
