package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.gitdata.CommitInclude
import com.worxbend.codeberg4s.repositories.gitdata.CommitStatusQuery
import com.worxbend.codeberg4s.repositories.gitdata.RefName

/** The query strings the raw-git endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API class, exactly as
  * [[com.worxbend.codeberg4s.issues.wire.IssueQueries]] argues: `per_page`, `verification` and `basehead` are wire
  * spellings, and a wire spelling is written once. Parameter order is fixed so a recorded request is comparable between
  * runs.
  *
  * '''Only parameters the caller set are emitted''', with one deliberate exception: [[paging]] and [[treeWindow]]
  * always send both halves of the window. `golden/MANIFEST.md` records that a limit without a page is silently ignored
  * by Forgejo, which is how a client accidentally pulls an unbounded collection.
  */
private[codeberg4s] object GitDataQueries:

  /** The `page` and `limit` parameters, as almost every Forgejo listing spells them. */
  def paging(params: PageParams): List[(String, String)] =
    List("page" -> params.page.value.toString, "limit" -> params.size.value.toString)

  /** The window of `GET /repos/{owner}/{repo}/git/trees/{sha}`, which spells the size `per_page`.
    *
    * '''This endpoint is the odd one out.''' Every other paged route in the library takes `limit`; the tree route
    * declares `per_page` and ignores `limit` entirely, so sending the usual spelling here returns the instance's
    * default page size and no error. That single word is the reason this function exists rather than [[paging]] being
    * reused.
    *
    * @param recursive
    *   whether to descend into subtrees; emitted only when `true`, since `false` is the instance's own default
    */
  def treeWindow(params: PageParams, recursive: Boolean): List[(String, String)] =
    Option.when(recursive)("recursive" -> "true").toList ++
      List("page" -> params.page.value.toString, "per_page" -> params.size.value.toString)

  /** The `stat`, `verification` and `files` parameters of `GET /repos/{owner}/{repo}/git/commits/{sha}`.
    *
    * Each is emitted only when the caller took a position on it — see
    * [[com.worxbend.codeberg4s.repositories.gitdata.CommitInclude]] on why absent is not `false`.
    */
  def commitInclude(include: CommitInclude): List[(String, String)] =
    List(
      include.stat.map(wanted         => "stat" -> wanted.toString),
      include.verification.map(wanted => "verification" -> wanted.toString),
      include.files.map(wanted        => "files" -> wanted.toString),
    ).flatten

  /** The `verification` and `files` parameters of `GET /repos/{owner}/{repo}/git/notes/{sha}`.
    *
    * The notes route declares no `stat` parameter, so
    * [[com.worxbend.codeberg4s.repositories.gitdata.CommitInclude.stat]] is dropped rather than sent to a route that
    * does not know it.
    */
  def noteInclude(include: CommitInclude): List[(String, String)] =
    List(
      include.verification.map(wanted => "verification" -> wanted.toString),
      include.files.map(wanted        => "files" -> wanted.toString),
    ).flatten

  /** The `sort` and `state` filters of `GET /repos/{owner}/{repo}/commits/{ref}/statuses`. */
  def commitStatuses(query: CommitStatusQuery): List[(String, String)] =
    List(
      query.sort.map(ordering => "sort" -> ordering.wireValue),
      query.state.map(wanted  => "state" -> wanted.wireValue),
    ).flatten

  /** The optional `ref` parameter of `/raw`, `/media` and `/editorconfig`.
    *
    * A query parameter rather than a path segment, so the slashes in `refs/heads/main` are percent-encoded by the
    * transport and that is correct here — the opposite of what
    * [[com.worxbend.codeberg4s.repositories.gitdata.RefName.segments]] exists for. Absent means the repository's
    * default branch.
    */
  def atRef(ref: Option[RefName]): List[(String, String)] =
    ref.map(name => "ref" -> name.value).toList
