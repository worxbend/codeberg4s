package com.worxbend.codeberg4s.organizations

/** What a team may do — with the organisation's repositories, or with one unit of them.
  *
  * This is the one closed set the pinned spec actually declares: `definitions.Team.permission` carries an explicit
  * `enum` of `none`, `read`, `write`, `admin` and `owner`, and the values of `Team.units_map` are drawn from the same
  * set. Everywhere else in this API a "type" field is an undeclared string, so this is worth taking at face value.
  *
  * The levels are ordered as Forgejo orders them, from no access to full ownership, and [[rank]] exposes that order so
  * a caller can ask "is this at least write?" without writing a five-armed match. The order is a fact about Forgejo's
  * `AccessMode`, not an interpretation.
  */
enum TeamPermission:

  /** No access at all. Forgejo spells this `none` on the wire.
    *
    * It is '''not''' called `None` here: inside this enum's scope that name would shadow `scala.None`, and the very
    * first thing the companion below does is return an `Option`.
    */
  case NoAccess

  /** May read — clone, browse, and see the unit's contents. */
  case Read

  /** May read and write — push, and open or edit the unit's contents. */
  case Write

  /** May administer the repositories the team reaches, short of owning the organisation. */
  case Admin

  /** Owns the organisation: every permission, including managing teams and deleting the organisation. */
  case Owner

object TeamPermission:

  /** Parses Forgejo's lowercase spelling.
    *
    * Answers `None` for anything unrecognised rather than failing, for the reason
    * [[com.worxbend.codeberg4s.users.UserVisibility.parse]] gives: a future Forgejo release adding a sixth level must
    * not make an otherwise perfectly good team object undecodable. Matching is case-insensitive because the spec
    * declares the spelling and no capture proves it.
    */
  def parse(value: String): Option[TeamPermission] =
    value.trim.toLowerCase match
      case "none"  => Some(NoAccess)
      case "read"  => Some(Read)
      case "write" => Some(Write)
      case "admin" => Some(Admin)
      case "owner" => Some(Owner)
      case _       => None

  extension (permission: TeamPermission)

    /** The lowercase spelling Forgejo uses on the wire. */
    def wireName: String =
      permission match
        case NoAccess => "none"
        case Read     => "read"
        case Write    => "write"
        case Admin    => "admin"
        case Owner    => "owner"

    /** Where this level sits in Forgejo's ordering, `0` for [[NoAccess]] and `4` for [[Owner]].
      *
      * Comparing ranks is how "at least write" is asked. Do not persist the number: it describes an ordering, not an
      * identifier, and Forgejo inserting a level would renumber it.
      */
    def rank: Int =
      permission match
        case NoAccess => 0
        case Read     => 1
        case Write    => 2
        case Admin    => 3
        case Owner    => 4

    /** Whether this level grants everything `other` grants. */
    def allows(other: TeamPermission): Boolean =
      permission.rank >= other.rank
