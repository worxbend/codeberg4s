package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.repositories.FileContent

/** One wiki page with its content, as `GET /repos/{owner}/{repo}/wiki/page/{pageName}` returns it.
  *
  * '''Derived from `spec/swagger.v1.json`'s `WikiPage` definition, not from a captured response'''; see [[Webhook]] for
  * why no fixture exists.
  *
  * ==Content is base64, and is modelled as such==
  *
  * The wire key is `content_base64` and the spec annotates it `Page content, base64 encoded`, so it becomes a
  * [[com.worxbend.codeberg4s.repositories.FileContent]] — the same type `GET /repos/{owner}/{repo}/contents/{filepath}`
  * produces, with the same [[com.worxbend.codeberg4s.repositories.FileContent.text]] accessor. There is deliberately no
  * second representation of base64 in this library, and the bytes are decoded only when a caller asks, because a page
  * nobody reads should not pay for a decode.
  *
  * '''[[sidebar]] and [[footer]] are not.''' The spec declares both as bare strings and says nothing about their
  * encoding, so they are handed back exactly as the instance sent them rather than being guessed at. If your instance
  * base64-encodes them too, decode them yourself; this library will not assert an encoding the spec does not state.
  *
  * @param title
  *   the page's title, which is required: a page that cannot be named cannot be fetched again or edited
  * @param content
  *   the page body. Absent when the payload carried no `content_base64` — a genuinely empty page arrives as an empty
  *   base64 string, which is `Some` of empty content and not `None`
  * @param sidebar
  *   the wiki's `_Sidebar` page as this response reported it, verbatim; see the note above
  * @param footer
  *   the wiki's `_Footer` page, on the same terms as [[sidebar]]
  * @param htmlUrl
  *   where a human reads this page
  * @param subUrl
  *   the page's path within the wiki, which is Forgejo's normalised spelling of [[title]] and may differ from the name
  *   the caller asked for
  * @param lastCommit
  *   the revision this content came from
  * @param commitCount
  *   how many revisions the page has. Absent when the instance did not say; `0` is a value the instance can genuinely
  *   send and is preserved
  */
final case class WikiPage private[codeberg4s] (
    title: String,
    content: Option[FileContent],
    sidebar: Option[String],
    footer: Option[String],
    htmlUrl: Option[String],
    subUrl: Option[String],
    lastCommit: Option[WikiCommit],
    commitCount: Option[Long],
)

/** One entry of `GET /repos/{owner}/{repo}/wiki/pages` — a wiki page without its content.
  *
  * Forgejo's `WikiPageMetaData` is `WikiPage` minus the body, the sidebar, the footer and the commit count, and the
  * listing endpoint returns these rather than whole pages so that listing a wiki does not transfer every page's text.
  * [[RepositoryWikiApi.page]] is how a caller then reads one.
  *
  * '''Derived from the spec, not from a captured response'''; see [[Webhook]].
  *
  * @param title
  *   the page's title, required for the reason [[WikiPage.title]] gives
  * @param htmlUrl
  *   where a human reads the page
  * @param subUrl
  *   the page's path within the wiki, which is Forgejo's normalised spelling of [[title]]
  * @param lastCommit
  *   the page's most recent revision
  */
final case class WikiPageMeta private[codeberg4s] (
    title: String,
    htmlUrl: Option[String],
    subUrl: Option[String],
    lastCommit: Option[WikiCommit],
)
