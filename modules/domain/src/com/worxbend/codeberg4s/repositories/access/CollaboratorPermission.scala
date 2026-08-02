package com.worxbend.codeberg4s.repositories.access

/** The access level a collaborator is granted — the `permission` of `AddCollaboratorOption`.
  *
  * ==Why this is not `organizations.TeamPermission`==
  *
  * `docs/LEDGER.md` treats an unexamined fork as a defect, so the question was examined rather than assumed. The answer
  * is that the two are '''not''' the same closed set:
  *
  *   - `definitions.Team.permission` declares `enum: [none, read, write, admin, owner]`, and
  *     [[com.worxbend.codeberg4s.organizations.TeamPermission]] is exactly those five.
  *   - `definitions.AddCollaboratorOption.permission` declares `enum: [read, write, admin]`, and nothing else.
  *
  * Reusing the five-level type on this endpoint would let `addCollaborator(…, TeamPermission.Owner)` compile — a value
  * Forgejo's own enum does not accept and which arrives as a `422` after a round trip. Worse, `TeamPermission.NoAccess`
  * would compile too, and a caller could reasonably read "set this collaborator to none" as "revoke", when the endpoint
  * that revokes is `DELETE`. On the surface where a wrong value is a wrong access grant, a type that cannot express the
  * wrong value is worth more than the two lines it costs. This is a deliberate fork of a '''different''' closed set,
  * not a copy of the same one.
  *
  * The read side made the opposite call: `definitions.RepoCollaboratorPermission.permission` declares '''no''' enum at
  * all, and Forgejo renders its internal access mode there — the same five-value vocabulary `TeamPermission` names. So
  * [[CollaboratorAccess.permission]] reuses `TeamPermission` rather than forking a third type. The naming is awkward,
  * because that type lives under `organizations` and describes a vocabulary that is not team-specific; renaming it to
  * something like `AccessLevel` in a shared package is the right fix and is not this group's to make.
  */
enum CollaboratorPermission:

  /** May clone and browse the repository, and open issues and pull requests against it. */
  case Read

  /** May read, and push to branches that no protection rule forbids. */
  case Write

  /** May read, write, and change the repository's settings — including everything in this group. */
  case Admin

object CollaboratorPermission:

  /** Parses Forgejo's lowercase spelling.
    *
    * Answers `None` for anything outside the three declared values rather than failing. Matching is case-insensitive
    * because the spec declares the spelling and no capture proves it.
    *
    * This exists for symmetry and for tests; nothing in the library parses a collaborator permission off the wire,
    * because no response model carries this narrow enum — see the type's own note on the read side.
    */
  def parse(value: String): Option[CollaboratorPermission] =
    value.trim.toLowerCase match
      case "read"  => Some(Read)
      case "write" => Some(Write)
      case "admin" => Some(Admin)
      case _       => None

  extension (permission: CollaboratorPermission)

    /** The lowercase spelling Forgejo declares, and the exact string sent as `AddCollaboratorOption.permission`. */
    def wireName: String =
      permission match
        case Read  => "read"
        case Write => "write"
        case Admin => "admin"
