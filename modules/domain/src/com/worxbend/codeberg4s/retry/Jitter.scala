package com.worxbend.codeberg4s.retry

/** How much randomness to add to a computed backoff delay.
  *
  * Without jitter every client that saw the same `429` retries at the same instant, and the instance gets a second
  * thundering herd one backoff later.
  *
  * Note that `Jitter.None` shadows `scala.None` when the enum's cases are imported unqualified; refer to it as
  * `Jitter.None`.
  */
enum Jitter:

  /** Use the computed delay exactly. Deterministic, and the right choice in tests. */
  case None

  /** Sleep for a uniformly random duration in `[0, computed delay]`, as in AWS's "full jitter" backoff. */
  case Full
