package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.BranchName

import java.util.Base64 as JavaBase64

/** Everything `POST /repos/{owner}/{repo}/branches` may be told, as one value.
  *
  * Derived from `CreateBranchRepoOption` in `spec/swagger.v1.json`, which declares `new_branch_name` required. No
  * golden capture of this request exists.
  *
  * ==Only one of the two "from" fields is modelled==
  *
  * The wire model has both `old_branch_name` and `old_ref_name`, and the spec marks the first `Deprecated: true`. Only
  * the second is offered here, as [[startingAt]]: it accepts a branch, a tag or a commit id, so it is strictly more
  * capable, and carrying a deprecated alias would mean this library had to decide what happens when a caller sets both.
  *
  * Setting neither is valid and asks Forgejo to branch from the repository's default branch.
  *
  * @param newBranchName
  *   the branch to create
  * @param fromRef
  *   the branch, tag or commit id to create it at. Absent means the repository's default branch
  */
final case class CreateBranch(newBranchName: BranchName, fromRef: Option[String]):

  /** Creates the branch at `ref`, which may name a branch, a tag or a commit. */
  def startingAt(ref: String): CreateBranch = copy(fromRef = Some(ref))

object CreateBranch:

  /** Starts a command that branches from the repository's default branch.
    *
    * Cannot fail: the argument is an already-validated type.
    */
  def named(newBranchName: BranchName): CreateBranch =
    CreateBranch(newBranchName = newBranchName, fromRef = None)

/** Everything `PATCH /repos/{owner}/{repo}/branches/{branch}` may be told, as one value.
  *
  * Derived from `UpdateBranchRepoOption` in `spec/swagger.v1.json`, which declares one required property, `name`. No
  * golden capture of this request exists.
  *
  * '''This endpoint only renames.''' Despite the "update a branch" summary, `name` is the sole property the wire model
  * has: there is nothing else about a branch this call can change. Moving a branch to a different commit is a Git push
  * or `RepositoryGitApi`'s reference endpoints, not this.
  *
  * Renaming the default branch is the one case that has consequences elsewhere — Forgejo moves the default with it, and
  * every open pull request targeting the old name is retargeted.
  *
  * @param newName
  *   what the branch will be called
  */
final case class RenameBranch(newName: BranchName)

/** An avatar image, on its way to `POST /repos/{owner}/{repo}/avatar`.
  *
  * ==This endpoint is a JSON body, not a multipart upload==
  *
  * `spec/swagger.v1.json` declares the body as `UpdateRepoAvatarOption`, an object with one `image` property described
  * as "image must be base64 encoded", consumed as `application/json`. It is '''not''' `multipart/form-data` — release
  * assets are, and reaching for [[com.worxbend.codeberg4s.core.RequestBody.Multipart]] here would produce a request
  * Forgejo rejects. The distinction is easy to get wrong from memory, which is why it is written down here and asserted
  * in the API suite.
  *
  * The value is therefore Base64 text and this type carries exactly that. [[AvatarImage.ofBytes]] does the encoding for
  * a caller who has the file's bytes; [[AvatarImage.ofBase64]] takes text a caller already encoded.
  *
  * Forgejo decides for itself which image formats and which sizes it accepts, and rejects the rest with a `422`. This
  * type therefore validates only that there is something to send.
  */
final case class AvatarImage private (base64: String)

object AvatarImage:

  /** Wraps Base64 text the caller already produced.
    *
    * The text is not decoded to check that it is valid Base64: a caller who encoded it themselves knows the encoding
    * they used, and a round trip through a decoder here would cost the bytes twice to catch a mistake Forgejo reports
    * anyway. Only a blank value is rejected.
    *
    * @return
    *   the image, or a [[com.worxbend.codeberg4s.ValidationError]] on the `"avatarImage"` field
    */
  def ofBase64(value: String): Either[ValidationError, AvatarImage] =
    if value.trim.isEmpty then Left(ValidationError("avatarImage", "must not be blank"))
    else Right(AvatarImage(value.trim))

  /** Encodes `bytes` as Base64 and wraps the result.
    *
    * Cannot fail for a non-empty array; an empty one is rejected, because an avatar of no bytes is a caller mistake
    * rather than a request. The encoding is the MIME-free, unpadded-line variant `java.util.Base64.getEncoder`
    * produces, which is what Forgejo's Go decoder expects.
    *
    * @return
    *   the image, or a [[com.worxbend.codeberg4s.ValidationError]] on the `"avatarImage"` field
    */
  def ofBytes(bytes: Array[Byte]): Either[ValidationError, AvatarImage] =
    if bytes.isEmpty then Left(ValidationError("avatarImage", "must not be empty"))
    else Right(AvatarImage(JavaBase64.getEncoder.encodeToString(bytes)))
