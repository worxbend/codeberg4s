package com.worxbend.codeberg4s.miscellaneous

/** Which repository features `GET /settings/repository` says the instance has switched off.
  *
  * Every flag is phrased as the wire phrases it — `mirrorsDisabled`, not `mirrorsEnabled`. Inverting the sense would
  * read better in a condition and would make this model impossible to line up against the payload during a bug report,
  * which is the wrong trade for a client library: `golden/misc/settings-repository.json` has seven keys and this type
  * has the same seven, spelled the same way.
  *
  * Use it to hide an action before a caller attempts it. A disabled feature is not reported as a capability check by
  * the endpoint that implements it — Forgejo answers a `404` or a `403` there — so asking here first is the only way to
  * tell a user "this instance does not do forks" rather than "something went wrong".
  *
  * @param mirrorsDisabled
  *   pull and push mirrors cannot be created
  * @param httpGitDisabled
  *   the instance serves Git over SSH only
  * @param migrationsDisabled
  *   repositories cannot be migrated in from another forge
  * @param starsDisabled
  *   the star feature is switched off, so star counts are meaningless
  * @param forksDisabled
  *   repositories cannot be forked
  * @param timeTrackingDisabled
  *   issue time tracking is switched off
  * @param lfsDisabled
  *   Git LFS is not served
  */
final case class ServerRepositorySettings private[codeberg4s] (
    mirrorsDisabled: Boolean,
    httpGitDisabled: Boolean,
    migrationsDisabled: Boolean,
    starsDisabled: Boolean,
    forksDisabled: Boolean,
    timeTrackingDisabled: Boolean,
    lfsDisabled: Boolean,
)
