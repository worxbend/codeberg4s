package com.worxbend.codeberg4s.repositories.admin

import java.util.Locale

/** The hash algorithm a repository's Git objects are named with — `object_format_name` on `CreateRepoOption`.
  *
  * '''Chosen once, at creation, and never afterwards.''' There is no endpoint that converts a repository between the
  * two, which is why this appears on [[CreateRepository]] and not on [[EditRepository]]. Picking SHA-256 makes the
  * repository unreadable to Git clients older than 2.29 and to any forge that has not implemented it.
  *
  * `spec/swagger.v1.json` declares `enum: [sha1, sha256]`, so this really is a closed set rather than this library
  * narrowing a free-text field.
  */
enum ObjectFormat:

  /** Git's historical object format, and Forgejo's default when the caller names none. */
  case Sha1

  /** Git's SHA-256 object format. See the type note for the compatibility cost. */
  case Sha256

object ObjectFormat:

  /** Parses Forgejo's lowercase spelling, case-insensitively and trimming. Answers `None` for anything else. */
  def parse(value: String): Option[ObjectFormat] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "sha1"   => Some(Sha1)
      case "sha256" => Some(Sha256)
      case _        => None

  extension (format: ObjectFormat)

    /** The spelling Forgejo expects in `object_format_name`. */
    def wireValue: String =
      format match
        case Sha1   => "sha1"
        case Sha256 => "sha256"

/** How much a commit signature is trusted for — `trust_model` on `CreateRepoOption`.
  *
  * Forgejo decides whether to render a commit as verified by comparing the signing key against the model chosen here.
  * `spec/swagger.v1.json` declares `enum: [default, collaborator, committer, collaboratorcommitter]`.
  */
enum TrustModel:

  /** Whatever the instance is configured to use. */
  case Default

  /** A signature counts when the key belongs to a collaborator on the repository. */
  case Collaborator

  /** A signature counts when the key belongs to the account named as the committer. */
  case Committer

  /** Both of the above must hold. */
  case CollaboratorCommitter

object TrustModel:

  /** Parses Forgejo's lowercase spelling, case-insensitively and trimming. Answers `None` for anything else. */
  def parse(value: String): Option[TrustModel] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "default"               => Some(Default)
      case "collaborator"          => Some(Collaborator)
      case "committer"             => Some(Committer)
      case "collaboratorcommitter" => Some(CollaboratorCommitter)
      case _                       => None

  extension (model: TrustModel)

    /** The spelling Forgejo expects in `trust_model`. Note that the last case is one unhyphenated word. */
    def wireValue: String =
      model match
        case Default               => "default"
        case Collaborator          => "collaborator"
        case Committer             => "committer"
        case CollaboratorCommitter => "collaboratorcommitter"

/** Which kind of forge `POST /repos/migrate` is being pointed at — `service` on `MigrateRepoOptions`.
  *
  * The value decides how much Forgejo can bring across. [[MigrationService.Git]] copies commits and nothing else,
  * because a bare Git remote has nothing else to offer; every other case unlocks the issue, label, milestone, release
  * and pull-request flags on [[MigrateRepository]] by telling Forgejo which API to talk to.
  *
  * `spec/swagger.v1.json` declares the ten cases below as an `enum`.
  */
enum MigrationService:

  /** A plain Git remote. Commits only — see the type note. */
  case Git

  /** GitHub, or GitHub Enterprise. */
  case GitHub

  /** Gitea. */
  case Gitea

  /** GitLab, self-hosted or not. */
  case GitLab

  /** Gogs. */
  case Gogs

  /** OneDev. */
  case OneDev

  /** GitBucket. */
  case GitBucket

  /** Codebase. */
  case Codebase

  /** Forgejo. */
  case Forgejo

  /** Pagure. */
  case Pagure

object MigrationService:

  /** Parses Forgejo's lowercase spelling, case-insensitively and trimming. Answers `None` for anything else. */
  def parse(value: String): Option[MigrationService] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "git"       => Some(Git)
      case "github"    => Some(GitHub)
      case "gitea"     => Some(Gitea)
      case "gitlab"    => Some(GitLab)
      case "gogs"      => Some(Gogs)
      case "onedev"    => Some(OneDev)
      case "gitbucket" => Some(GitBucket)
      case "codebase"  => Some(Codebase)
      case "forgejo"   => Some(Forgejo)
      case "pagure"    => Some(Pagure)
      case _           => None

  extension (service: MigrationService)

    /** The spelling Forgejo expects in `service`. */
    def wireValue: String =
      service match
        case Git       => "git"
        case GitHub    => "github"
        case Gitea     => "gitea"
        case GitLab    => "gitlab"
        case Gogs      => "gogs"
        case OneDev    => "onedev"
        case GitBucket => "gitbucket"
        case Codebase  => "codebase"
        case Forgejo   => "forgejo"
        case Pagure    => "pagure"
