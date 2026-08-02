package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.FileContent

/** What `POST /repos/{owner}/{repo}/wiki/new` is told.
  *
  * {{{
  * CreateWikiPage.ofText(name, "# Deployment\n\nRun `make deploy`.").withMessage("document the deploy")
  * }}}
  *
  * `CreateWikiPageOptions` is shared by the create and the edit route in `spec/swagger.v1.json`, but the two do not
  * mean the same thing by it — a create must name the page it is creating, while an edit's `title` is documented as
  * "leave empty to keep unchanged" and is therefore a rename. Modelling them as one type would make `title` optional on
  * a route that cannot work without it, so there are two: this one and [[EditWikiPage]]. They share a renderer, which
  * is where the wire spellings live exactly once.
  *
  * @param title
  *   the page to create
  * @param content
  *   the page body, base64-encoded — build it with [[WikiContent]], or reuse a
  *   [[com.worxbend.codeberg4s.repositories.FileContent]] read back from another page
  * @param message
  *   the commit message for the revision this creates, absent to let Forgejo write its own
  */
final case class CreateWikiPage(
    title: WikiPageName,
    content: FileContent,
    message: Option[String],
):

  /** Records `text` as the commit message of the revision this creates. */
  def withMessage(text: String): CreateWikiPage =
    copy(message = Some(text))

object CreateWikiPage:

  /** The command that creates `title` with `text` as its content, encoded as UTF-8 base64 by [[WikiContent.ofText]].
    *
    * Total: text always encodes.
    */
  def ofText(title: WikiPageName, text: String): CreateWikiPage =
    CreateWikiPage(title = title, content = WikiContent.ofText(text), message = None)

  /** The command that creates `title` with already-encoded content.
    *
    * '''Rejects content that is not base64.''' A [[com.worxbend.codeberg4s.repositories.FileContent.Opaque]] is content
    * whose encoding this library does not know, and sending it under a key named `content_base64` would store rubbish
    * in the caller's wiki under a name that claims otherwise. Failing here is the only honest option; re-encode the
    * bytes with [[WikiContent.ofBytes]] if you have them.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"content"` field
    */
  def of(title: WikiPageName, content: FileContent): Either[ValidationError, CreateWikiPage] =
    WikiPageContent.base64(content).map(encoded => CreateWikiPage(title = title, content = encoded, message = None))

/** What `PATCH /repos/{owner}/{repo}/wiki/page/{pageName}` is told.
  *
  * The same `CreateWikiPageOptions` body as [[CreateWikiPage]], read the way the edit route reads it: the page being
  * edited is named by the request path, and the body's `title` is a '''rename'''. Leaving [[renamedTo]] absent keeps
  * the page where it is.
  *
  * A rename is also why [[RepositoryWikiApi.editPage]] is never retried — see that method for the full argument, which
  * has a second half about revisions.
  *
  * @param renamedTo
  *   the title to move the page to, absent to leave it alone
  * @param content
  *   the page body, base64-encoded. Always sent: `CreateWikiPageOptions` has no way to say "keep the content and change
  *   only the title", so an edit that means to rename still has to state the content it is keeping
  * @param message
  *   the commit message for the revision this creates, absent to let Forgejo write its own
  */
final case class EditWikiPage(
    renamedTo: Option[WikiPageName],
    content: FileContent,
    message: Option[String],
):

  /** Moves the page to `title` as part of this edit; see the class note on what that costs. */
  def movedTo(title: WikiPageName): EditWikiPage =
    copy(renamedTo = Some(title))

  /** Records `text` as the commit message of the revision this creates. */
  def withMessage(text: String): EditWikiPage =
    copy(message = Some(text))

object EditWikiPage:

  /** The command that replaces the page's content with `text`, encoded as UTF-8 base64. Total. */
  def ofText(text: String): EditWikiPage =
    EditWikiPage(renamedTo = None, content = WikiContent.ofText(text), message = None)

  /** The command that replaces the page's content with already-encoded content.
    *
    * Rejects content that is not base64, for the reason [[CreateWikiPage.of]] gives.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"content"` field
    */
  def of(content: FileContent): Either[ValidationError, EditWikiPage] =
    WikiPageContent.base64(content).map(encoded => EditWikiPage(renamedTo = None, content = encoded, message = None))

/** The one check both wiki commands make on the content they are handed. */
private object WikiPageContent:

  /** Accepts content only if it is base64, so nothing is ever sent under `content_base64` that is not. */
  def base64(content: FileContent): Either[ValidationError, FileContent] =
    content match
      case FileContent.Base64(_)    => Right(content)
      case FileContent.Opaque(_, _) =>
        Left(ValidationError("content", "must be base64 encoded — build it with WikiContent"))
