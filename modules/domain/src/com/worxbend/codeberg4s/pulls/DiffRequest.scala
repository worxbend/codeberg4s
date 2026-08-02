package com.worxbend.codeberg4s.pulls

/** What to ask `GET /repos/{owner}/{repo}/pulls/{index}.{diffType}` for.
  *
  * Two decisions travel together — which representation, and whether binary changes are included — so they are one
  * value rather than two parameters whose order a call site has to remember. The flag is a named builder for the reason
  * [[MergePullRequest]] gives: `download(pull, format, true)` makes a reader reconstruct what the `true` was.
  *
  * {{{
  * client.pulls.download(owner, name, number, DiffRequest.of(DiffFormat.Diff).includingBinary)
  * }}}
  *
  * @param format
  *   which document to fetch; see [[DiffFormat]]
  * @param includeBinary
  *   whether Forgejo should embed binary file changes. `false` unless [[includingBinary]] was called, because the
  *   embedded form multiplies the response size — a pull request that touches one image can turn a two-kilobyte diff
  *   into a megabyte one, and the library will not choose that on a caller's behalf. Set it when the result has to be
  *   applicable with `git apply`, which is the only thing it is for
  */
final case class DiffRequest(format: DiffFormat, includeBinary: Boolean):

  /** Asks for binary file changes to be embedded, which is what makes the result applicable with `git apply`. */
  def includingBinary: DiffRequest = copy(includeBinary = true)

object DiffRequest:

  /** A request for `format` with binary changes left out. */
  def of(format: DiffFormat): DiffRequest = DiffRequest(format = format, includeBinary = false)
