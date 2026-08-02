package com.worxbend.codeberg4s.users.account

import java.util.Locale

/** Which hash a new repository's Git objects are named by — the `object_format_name` of `CreateRepoOption`.
  *
  * '''The spec enumerates this one.''' `spec/swagger.v1.json` declares `enum: [sha1, sha256]`, which is where the two
  * cases come from verbatim. It is therefore a closed enum with no `Other` case, unlike
  * [[com.worxbend.codeberg4s.repositories.hooks.HookType]] whose response counterpart is an open string: this value is
  * only ever '''sent''', never read back through this group, so there is no unrecognised value to keep.
  *
  * '''It cannot be changed later.''' The object format is fixed when the repository is created, and an instance that
  * was not built with SHA-256 support rejects that choice with a `422`.
  */
enum ObjectFormat:

  /** Git's traditional 40-hex-character object names — `sha1`. Forgejo's default. */
  case Sha1

  /** 64-hex-character object names — `sha256`. Only on an instance whose Git supports it. */
  case Sha256

object ObjectFormat:

  /** Reads Forgejo's lowercase spelling.
    *
    * Answers `None` for anything outside the enumerated set. Unlike the open enums elsewhere in this library there is
    * no `Other` case to fall back to, so an unrecognised value is absence — see the enum note for why that costs
    * nothing here.
    */
  def parse(value: String): Option[ObjectFormat] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "sha1"   => Some(Sha1)
      case "sha256" => Some(Sha256)
      case _        => None

  extension (format: ObjectFormat)

    /** The lowercase spelling the request body carries. */
    def wireValue: String =
      format match
        case Sha1   => "sha1"
        case Sha256 => "sha256"

/** Whose signatures a new repository trusts — the `trust_model` of `CreateRepoOption`.
  *
  * '''The spec enumerates this one too''': `enum: [default, collaborator, committer, collaboratorcommitter]`. It
  * decides which GPG signatures Forgejo marks as verified in the commit list, and it is a repository setting rather
  * than a security boundary — an unverified badge is not a rejected push.
  *
  * A closed enum, for the reason [[ObjectFormat]] gives: this library only ever sends the value.
  */
enum TrustModel:

  /** Whatever the instance is configured to use — `default`. */
  case Default

  /** A signature is trusted when its key belongs to a collaborator on the repository — `collaborator`. */
  case Collaborator

  /** A signature is trusted when its key belongs to the commit's own committer — `committer`. */
  case Committer

  /** Both of the above must hold — `collaboratorcommitter`. */
  case CollaboratorCommitter

object TrustModel:

  /** Reads Forgejo's lowercase spelling; `None` for anything outside the enumerated set. */
  def parse(value: String): Option[TrustModel] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "default"               => Some(Default)
      case "collaborator"          => Some(Collaborator)
      case "committer"             => Some(Committer)
      case "collaboratorcommitter" => Some(CollaboratorCommitter)
      case _                       => None

  extension (model: TrustModel)

    /** The lowercase spelling the request body carries. */
    def wireValue: String =
      model match
        case Default               => "default"
        case Collaborator          => "collaborator"
        case Committer             => "committer"
        case CollaboratorCommitter => "collaboratorcommitter"
