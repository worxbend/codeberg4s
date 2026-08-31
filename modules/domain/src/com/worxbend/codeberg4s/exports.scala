package com.worxbend.codeberg4s

/* Root-level re-exports of the everyday surface.
 *
 * A caller writing the quick-start flow — build an `Auth`, construct a client, page through a
 * listing — should not need to know which sub-package each of those types lives in. These exports
 * make `import com.worxbend.codeberg4s.*` sufficient for that flow.
 *
 * The list is deliberately short and curated: only types that appear in almost every program are
 * re-exported, and never a whole package. Everything else keeps a single canonical import from its
 * own sub-package, so a name in an error message or a stack trace still points at one place.
 */

export com.worxbend.codeberg4s.auth.Auth
export com.worxbend.codeberg4s.paging.Page
export com.worxbend.codeberg4s.paging.PageParams
export com.worxbend.codeberg4s.paging.PageSize
