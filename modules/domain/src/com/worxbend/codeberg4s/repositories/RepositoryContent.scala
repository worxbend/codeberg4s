package com.worxbend.codeberg4s.repositories

/** What `GET /repos/{owner}/{repo}/contents/{filepath}` returned: one entry, or a directory's worth of them.
  *
  * '''This endpoint is an untagged union and the specification denies it.''' `docs/HAZARDS.md` §3 has the measurement:
  * the spec declares the `200` response as `#/definitions/ContentsResponse`, a single object, and the union appears
  * only in the English summary. Live, `contents/README.md` answers a JSON object and `contents/models` answers a JSON
  * array of the same element shape. A generated client believes the spec, decodes the array as an object, and fails on
  * every directory. Both arms are captured — `golden/repository/contents-file.json` and
  * `golden/repository/contents-dir.json` — and both are decoded here, by inspecting the JSON kind of the top-level
  * value and nothing else.
  *
  * The caller therefore branches once, on a real ADT, instead of guessing:
  *
  * {{{
  * client.repos.getContents(owner, name, path).map:
  *   case RepositoryContent.File(entry)         => entry.meta.size
  *   case RepositoryContent.Directory(entries)  => entries.size
  * }}}
  */
enum RepositoryContent:

  /** The path addressed a single entry, and the response was a JSON object.
    *
    * Named for the overwhelmingly common case, but it carries a whole [[ContentEntry]] because the object arm is also
    * what a symlink and a submodule come back as. Match on `entry` when that distinction matters.
    */
  case File(entry: ContentEntry)

  /** The path addressed a directory, and the response was a JSON array.
    *
    * @param entries
    *   the directory's immediate children, in the order the instance listed them. Not paginated: this endpoint takes no
    *   `page` or `limit`, so a directory with ten thousand files arrives in one response
    */
  case Directory(entries: Vector[ContentEntry])
