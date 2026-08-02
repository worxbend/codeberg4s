package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.RepoName

/** What `POST /user/repos` is told.
  *
  * {{{
  * CreateRepository.named(name).describedAs("nothing yet").keptPrivate.initialised.licensedAs("MIT")
  * }}}
  *
  * ==Why this lives beside the account and not beside repositories==
  *
  * `POST /user/repos` creates a repository '''owned by whoever the credentials are''' — there is no owner in the path,
  * and that is the whole difference between it and the admin and organisation creation routes. It is an account
  * operation that happens to produce a repository, which is why the command sits in this package while the value it
  * produces is the shared [[com.worxbend.codeberg4s.repositories.Repository]].
  *
  * ==What Forgejo requires, and what it merely accepts==
  *
  * `CreateRepoOption` is one of the request models that does declare a `required` list, and it names exactly `name`.
  * [[CreateRepository.named]] takes that one value; everything else is a builder, and a field left alone is not
  * rendered at all, so the instance's own default applies. The default of [[isPrivate]] and of the two other flags is
  * `false`, which is the model's zero value and therefore what Forgejo would have used anyway — they are sent
  * regardless, so that what a created repository looks like is a property of the request rather than of the Forgejo
  * version answering it.
  *
  * ==The three template names are strings on purpose==
  *
  * [[gitignores]], [[license]] and [[issueLabels]] name templates the '''instance''' ships: `GET /gitignore/templates`,
  * `GET /licenses` and the label-set files a deployment was configured with. The sets are deployment data, not part of
  * the API contract, so an enum here would be a list this library invented and a template an instance added would be
  * one a caller could not ask for. A name the instance does not know arrives as a `422`.
  *
  * @param name
  *   the repository's name, already validated as a path segment by [[com.worxbend.codeberg4s.repositories.RepoName]]
  * @param description
  *   the one-line description, absent to leave it empty
  * @param isPrivate
  *   whether the repository is private. Always sent
  * @param isTemplate
  *   whether the repository may be used as a template for others. Always sent
  * @param autoInit
  *   whether Forgejo should make an initial commit. Always sent — and note that [[defaultBranch]], [[readme]],
  *   [[gitignores]] and [[license]] only take effect when it is `true`, because they describe what that first commit
  *   contains
  * @param defaultBranch
  *   the branch the initial commit lands on, absent to take the instance's default
  * @param gitignores
  *   the `.gitignore` templates to seed, comma-separated as the API expects — see the class note
  * @param license
  *   the license template to seed
  * @param readme
  *   the README template to seed. The API's own default is `Default`, which is a template name and not the word "none";
  *   leaving this absent seeds that one
  * @param issueLabels
  *   the label set to install
  * @param objectFormat
  *   the Git object hash, absent to take the instance's default; see [[ObjectFormat]]
  * @param trustModel
  *   the signature trust model, absent to take the instance's default; see [[TrustModel]]
  */
final case class CreateRepository(
    name: RepoName,
    description: Option[String],
    isPrivate: Boolean,
    isTemplate: Boolean,
    autoInit: Boolean,
    defaultBranch: Option[BranchName],
    gitignores: Option[String],
    license: Option[String],
    readme: Option[String],
    issueLabels: Option[String],
    objectFormat: Option[ObjectFormat],
    trustModel: Option[TrustModel],
):

  /** Sets the one-line description. */
  def describedAs(text: String): CreateRepository = copy(description = Some(text))

  /** Creates the repository private. */
  def keptPrivate: CreateRepository = copy(isPrivate = true)

  /** Creates the repository public, which is this type's default. */
  def keptPublic: CreateRepository = copy(isPrivate = false)

  /** Marks the repository as usable as a template for others. */
  def asTemplate: CreateRepository = copy(isTemplate = true)

  /** Asks Forgejo to make an initial commit; see the class note on what that enables. */
  def initialised: CreateRepository = copy(autoInit = true)

  /** Lands the initial commit on `branch` rather than on the instance's default branch. */
  def onDefaultBranch(branch: BranchName): CreateRepository = copy(defaultBranch = Some(branch))

  /** Seeds the named `.gitignore` templates, comma-separated as the API expects. */
  def ignoring(templates: String): CreateRepository = copy(gitignores = Some(templates))

  /** Seeds the named license template. */
  def licensedAs(template: String): CreateRepository = copy(license = Some(template))

  /** Seeds the named README template. */
  def withReadme(template: String): CreateRepository = copy(readme = Some(template))

  /** Installs the named issue label set. */
  def withIssueLabels(labelSet: String): CreateRepository = copy(issueLabels = Some(labelSet))

  /** Creates the repository with `format` as its Git object hash; it cannot be changed afterwards. */
  def usingObjectFormat(format: ObjectFormat): CreateRepository = copy(objectFormat = Some(format))

  /** Creates the repository with `model` as its signature trust model. */
  def trusting(model: TrustModel): CreateRepository = copy(trustModel = Some(model))

object CreateRepository:

  /** Starts a command from the one field Forgejo requires.
    *
    * Total rather than validated: [[com.worxbend.codeberg4s.repositories.RepoName]] has already refused everything that
    * cannot be a path segment, and whether the name is free is the instance's judgement — it arrives as a `409`, which
    * is the one status this endpoint declares that no other creation route in the library does.
    */
  def named(name: RepoName): CreateRepository =
    CreateRepository(
      name          = name,
      description   = None,
      isPrivate     = false,
      isTemplate    = false,
      autoInit      = false,
      defaultBranch = None,
      gitignores    = None,
      license       = None,
      readme        = None,
      issueLabels   = None,
      objectFormat  = None,
      trustModel    = None,
    )
