package com.worxbend.codeberg4s.repositories.actions

/** Which runners a repository's runner listing should include.
  *
  * The wire spelling is a bare `visible` boolean, which is precisely the shape `SCALA_CODE_STYLE.md` says not to put in
  * front of a caller: `listRunners(owner, name, true, page)` says nothing at the call site, and the parameter's meaning
  * — `true` widens the listing rather than narrowing it — is the opposite of what "visible" reads like at a glance.
  *
  * The parameter is always sent, rather than being left off to get the instance's default, so that a listing's contents
  * are a property of the request and not of the Forgejo version answering it.
  */
enum RunnerVisibility:

  /** Only runners registered against this repository. */
  case OwnedOnly

  /** Every runner the repository can dispatch to, including those inherited from its owner and from the instance. */
  case AllVisible

object RunnerVisibility:

  extension (visibility: RunnerVisibility)

    /** The `visible` query parameter's value. */
    def wireValue: String =
      visibility match
        case OwnedOnly  => "false"
        case AllVisible => "true"
