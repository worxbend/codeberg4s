package com.worxbend.codeberg4s.repositories

/** One entry in a repository's contents, with the fields its [[ContentKind]] actually populates.
  *
  * `docs/HAZARDS.md` §3 measured which fields depend on the entry's `type`: `content` and `encoding` arrive only for a
  * file, `target` only for a symlink, `submodule_git_url` only for a submodule. Modelling that as one case class with
  * four `Option`s would let a caller ask a directory for its symlink target and would let this library construct a
  * submodule carrying file contents. The enum makes both unrepresentable, which is the whole reason it exists.
  *
  * [[meta]] is the part every kind carries and is available without matching.
  */
enum ContentEntry:

  /** A blob.
    *
    * @param content
    *   the file's bytes. Present when the entry was fetched by its own path and '''absent when it was listed as part of
    *   a directory''' — `golden/repository/contents-dir-small.json` sends `content: null` for its `README.md` while
    *   `golden/repository/contents-file.json` sends the base64 payload for the same kind of entry. Absence here means
    *   "not sent", never "empty file"
    * @param downloadUrl
    *   where the raw bytes can be fetched, absent for an empty file
    */
  case File(meta: ContentMeta, content: Option[FileContent], downloadUrl: Option[String])

  /** A tree. Its children are reached by requesting [[ContentMeta.path]] as its own contents request. */
  case Directory(meta: ContentMeta)

  /** A symbolic link.
    *
    * @param target
    *   what the link points at, as stored in the repository. An arbitrary path that need not exist and need not stay
    *   inside the repository — resolve it with that in mind
    */
  case Symlink(meta: ContentMeta, target: Option[String])

  /** Another repository mounted at this path.
    *
    * @param gitUrl
    *   the submodule's clone URL as `.gitmodules` records it, absent when the repository does not record one
    */
  case Submodule(meta: ContentMeta, gitUrl: Option[String])

  /** The fields every entry carries, whatever its kind. */
  def meta: ContentMeta

  /** This entry's kind, without matching on the case. */
  def kind: ContentKind =
    this match
      case File(_, _, _)   => ContentKind.File
      case Directory(_)    => ContentKind.Directory
      case Symlink(_, _)   => ContentKind.Symlink
      case Submodule(_, _) => ContentKind.Submodule
