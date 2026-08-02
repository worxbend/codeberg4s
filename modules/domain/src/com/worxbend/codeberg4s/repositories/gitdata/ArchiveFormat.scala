package com.worxbend.codeberg4s.repositories.gitdata

/** The archive formats `GET /repos/{owner}/{repo}/archive/{archive}` can generate.
  *
  * The endpoint takes '''one''' path parameter that is a ref and a format glued together — `main.zip`, `v1.2.tar.gz` —
  * so the format is not a query parameter and not a header, and getting the suffix wrong is a `404` rather than a
  * different content type. This enum is what stops that suffix from being typed at a call site.
  *
  * The three cases are the three suffixes Forgejo's archive route recognises. An instance that adds a fourth would need
  * a case here; there is no free-text escape hatch, because a caller who could pass `"exe"` would get a `404` and no
  * indication why.
  */
enum ArchiveFormat:

  /** A zip archive of the ref's tree. */
  case Zip

  /** A gzipped tar archive of the ref's tree. Note the suffix has a dot in it, which is why [[suffix]] exists. */
  case TarGz

  /** A Git bundle — the repository's objects up to the ref, clonable offline. */
  case Bundle

object ArchiveFormat:

  extension (format: ArchiveFormat)

    /** The file extension Forgejo appends to the ref, without the leading dot.
      *
      * `tar.gz` contains a dot of its own, so this is a string rather than a single word: `main` plus [[TarGz]] is
      * `main.tar.gz`, three dot-separated parts.
      */
    def suffix: String =
      format match
        case Zip    => "zip"
        case TarGz  => "tar.gz"
        case Bundle => "bundle"
