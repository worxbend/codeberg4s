package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.repositories.admin.AvatarImage
import com.worxbend.codeberg4s.repositories.admin.CreateBranch
import com.worxbend.codeberg4s.repositories.admin.RenameBranch

/** Forgejo's `CreateBranchRepoOption` and `UpdateBranchRepoOption` request models — the bodies of
  * `POST /repos/{owner}/{repo}/branches` and `PATCH /repos/{owner}/{repo}/branches/{branch}`.
  *
  * One object for two models because both are a branch name and nothing else, and rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once. Note that the two
  * spell the name differently — `new_branch_name` on the create and `name` on the update — which is exactly the sort of
  * detail that goes wrong when it is written in two files.
  *
  * `old_branch_name` is '''not''' emitted. The spec marks it `Deprecated: true` and `old_ref_name` supersedes it, so
  * only the latter is offered; see [[com.worxbend.codeberg4s.repositories.admin.CreateBranch.startingAt]].
  */
private[codeberg4s] object BranchOptionDto:

  /** The wire key the create model names the new branch under. */
  val NewBranchNameKey: String = "new_branch_name"

  /** The wire key the create model names the starting point under. */
  val OldRefNameKey: String = "old_ref_name"

  /** The wire key the update model names the new branch under. Deliberately different from [[NewBranchNameKey]]. */
  val NameKey: String = "name"

  /** Renders `command` as the JSON body to `POST`.
    *
    * `old_ref_name` is emitted only when the command names a starting point; absent asks Forgejo to branch from the
    * repository's default branch, and sending `""` would be a request to branch from a ref called nothing.
    */
  def renderCreate(command: CreateBranch): String =
    ujson.write(
      ujson.Obj.from(
        List(
          Some(NewBranchNameKey -> ujson.Str(command.newBranchName.value)),
          command.fromRef.map(ref => OldRefNameKey -> ujson.Str(ref)),
        ).flatten
      )
    )

  /** Renders `command` as the JSON body to `PATCH`. `name` is the model's only property and is always emitted. */
  def renderRename(command: RenameBranch): String =
    ujson.write(ujson.Obj(NameKey -> ujson.Str(command.newName.value)))

/** Forgejo's `UpdateRepoAvatarOption` request model — the body of `POST /repos/{owner}/{repo}/avatar`.
  *
  * ==A JSON body, not a multipart upload==
  *
  * `spec/swagger.v1.json` declares this operation as `consumes: application/json` with a one-property object whose
  * `image` is described as "image must be base64 encoded". Release assets are the multipart endpoint; this one is not,
  * and reaching for [[com.worxbend.codeberg4s.core.RequestBody.Multipart]] here produces a request Forgejo rejects.
  * That is why [[com.worxbend.codeberg4s.repositories.admin.AvatarImage]] carries Base64 text rather than bytes.
  *
  * `image` is the model's only property and is always emitted.
  */
private[codeberg4s] object AvatarOptionDto:

  /** The wire key the Base64 image travels under. */
  val ImageKey: String = "image"

  /** Renders `image` as the JSON body to `POST`. */
  def render(image: AvatarImage): String =
    ujson.write(ujson.Obj(ImageKey -> ujson.Str(image.base64)))
