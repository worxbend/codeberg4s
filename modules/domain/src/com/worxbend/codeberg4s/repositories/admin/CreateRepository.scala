package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.BranchName

/** Everything `POST /user/repos` may be told, as one value.
  *
  * Derived from `CreateRepoOption` in `spec/swagger.v1.json`, which declares `name` required and twelve optional
  * properties. No golden capture of this request exists.
  *
  * '''The new repository belongs to the authenticated account.''' There is no owner field: the token decides. Creating
  * a repository under an organisation is a different endpoint, and creating one from a template is
  * `RepositoryPublishingApi.generate`.
  *
  * ==An empty repository is the default, and it is usually not what a caller wants==
  *
  * Without [[initialised]] Forgejo creates a repository with no commits and no default branch, which cannot be cloned
  * usefully and which several other endpoints — the contents writes in this very group — then answer `404` for.
  * [[initialised]] is the flag that makes Forgejo write an initial commit, and [[readme]], [[gitignores]] and
  * [[license]] only do anything alongside it.
  *
  * @param name
  *   what the repository will be called
  * @param description
  *   its description
  * @param isPrivate
  *   whether it is private. Forgejo may refuse a public repository on an instance configured to forbid them
  * @param autoInit
  *   write an initial commit; see the note above
  * @param defaultBranch
  *   the name of the branch the initial commit lands on. Absent leaves the instance's default
  * @param readme
  *   the name of the README template to use, as the instance's `/repo/create` page lists them. Only with [[autoInit]]
  * @param gitignores
  *   the `.gitignore` templates to combine, comma-separated as Forgejo expects. Only with [[autoInit]]
  * @param license
  *   the licence template to use. Only with [[autoInit]]
  * @param issueLabels
  *   the name of the label set to install
  * @param isTemplate
  *   whether the repository may itself be used as a template
  * @param objectFormat
  *   the Git hash algorithm. Fixed at creation and never changeable — see [[ObjectFormat]]
  * @param trustModel
  *   how commit signatures are judged — see [[TrustModel]]
  */
final case class CreateRepository(
    name: RepoName,
    description: Option[String],
    isPrivate: Boolean,
    autoInit: Boolean,
    defaultBranch: Option[BranchName],
    readme: Option[String],
    gitignores: Option[String],
    license: Option[String],
    issueLabels: Option[String],
    isTemplate: Boolean,
    objectFormat: Option[ObjectFormat],
    trustModel: Option[TrustModel],
):

  /** Describes the repository. */
  def describedAs(text: String): CreateRepository = copy(description = Some(text))

  /** Creates the repository private. */
  def asPrivate: CreateRepository = copy(isPrivate = true)

  /** Writes an initial commit, so the repository is clonable and writable. See the type note. */
  def initialised: CreateRepository = copy(autoInit = true)

  /** Names the branch the initial commit lands on. */
  def defaultingTo(branch: BranchName): CreateRepository = copy(defaultBranch = Some(branch))

  /** Uses the named README template. Only takes effect alongside [[initialised]]. */
  def withReadme(template: String): CreateRepository = copy(readme = Some(template))

  /** Uses the named `.gitignore` templates, comma-separated. Only takes effect alongside [[initialised]]. */
  def withGitignores(templates: String): CreateRepository = copy(gitignores = Some(templates))

  /** Uses the named licence template. Only takes effect alongside [[initialised]]. */
  def withLicense(template: String): CreateRepository = copy(license = Some(template))

  /** Installs the named issue-label set. */
  def withIssueLabels(labelSet: String): CreateRepository = copy(issueLabels = Some(labelSet))

  /** Marks the repository as a template others may generate from. */
  def asTemplate: CreateRepository = copy(isTemplate = true)

  /** Chooses the Git hash algorithm. Cannot be changed afterwards — see [[ObjectFormat]]. */
  def using(format: ObjectFormat): CreateRepository = copy(objectFormat = Some(format))

  /** Chooses how commit signatures are judged. */
  def trusting(model: TrustModel): CreateRepository = copy(trustModel = Some(model))

object CreateRepository:

  /** Starts a command from the one thing Forgejo insists on.
    *
    * Cannot fail: the argument is an already-validated type, so there is nothing left for this constructor to check.
    */
  def named(name: RepoName): CreateRepository =
    CreateRepository(
      name          = name,
      description   = None,
      isPrivate     = false,
      autoInit      = false,
      defaultBranch = None,
      readme        = None,
      gitignores    = None,
      license       = None,
      issueLabels   = None,
      isTemplate    = false,
      objectFormat  = None,
      trustModel    = None,
    )
