package com.worxbend.codeberg4s.repositories.actions

import java.util.Locale

/** Whether a registered runner is reachable, and whether it is busy.
  *
  * A closed set, and one of the few Forgejo enumerates outright: the `ActionRunner` model in `spec/swagger.v1.json`
  * declares `enum: [offline, idle, active]` on `status`. It is therefore an enum here rather than a `String`, so a
  * caller filtering for available capacity cannot mistype `"idle"` and silently match nothing.
  */
enum RunnerStatus:

  /** The runner has not contacted the instance recently enough to be considered available. */
  case Offline

  /** Connected and waiting for work. */
  case Idle

  /** Connected and currently executing a task. */
  case Active

  /** Whether the instance can dispatch work to this runner — that is, anything but [[RunnerStatus.Offline]]. */
  def isConnected: Boolean =
    this match
      case Offline => false
      case Idle | Active => true

object RunnerStatus:

  /** Parses Forgejo's lowercase spelling.
    *
    * Answers `None` for an unrecognised value rather than failing: a status a later release adds must not cost the
    * caller the whole runner. Matching is case-insensitive and trims.
    */
  def parse(value: String): Option[RunnerStatus] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "offline" => Some(Offline)
      case "idle"    => Some(Idle)
      case "active"  => Some(Active)
      case _         => None

  extension (status: RunnerStatus)

    /** The lowercase spelling Forgejo uses on the wire. */
    def wireValue: String =
      status match
        case Offline => "offline"
        case Idle    => "idle"
        case Active  => "active"
