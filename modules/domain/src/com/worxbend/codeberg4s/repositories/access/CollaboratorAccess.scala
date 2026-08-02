package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.organizations.TeamPermission
import com.worxbend.codeberg4s.users.User

/** What one account may do with one repository — the answer of
  * `GET /repos/{owner}/{repo}/collaborators/{collaborator}/permission`.
  *
  * '''No golden fixture backs this model.''' `golden/MANIFEST.md` records that every capture was taken anonymously, and
  * every collaborator endpoint needs a token, so the fields below are the three properties of
  * `definitions.RepoCollaboratorPermission` in `spec/swagger.v1.json` read literally. `docs/HAZARDS.md` §1 is why they
  * are optional anyway: the spec declares no `required` list on any response model, so it is evidence of which keys may
  * appear and of nothing else. Should a capture ever contradict this model, the capture wins.
  *
  * ==The level is reported twice, on purpose==
  *
  * [[permission]] is the level parsed into [[com.worxbend.codeberg4s.organizations.TeamPermission]], and
  * [[rawPermission]] is the same field exactly as the instance spelled it. Everywhere else in this library an
  * unrecognised enum value is folded into `None` and forgotten, because losing one descriptive field costs a caller
  * nothing. Here it would cost them the answer: a caller who reads `permission = None` and concludes "no access" has
  * inverted the meaning of a level this library simply did not recognise. Keeping the raw string means a level from a
  * future Forgejo release is visible rather than silently absent.
  *
  * `permission` is not [[CollaboratorPermission]]: that type is the three-value set the '''write''' endpoint accepts,
  * and this field carries Forgejo's full five-value access vocabulary. See [[CollaboratorPermission]] for the whole
  * argument.
  *
  * @param user
  *   the account the permission describes. Required — a permission that names nobody describes nothing
  * @param permission
  *   the access level, absent when the instance sent nothing '''or''' sent a level this library does not recognise; use
  *   [[rawPermission]] to tell those two apart
  * @param rawPermission
  *   the `permission` field verbatim, absent only when the instance really sent nothing
  * @param roleName
  *   Forgejo's display name for the role, such as `Owner` or `Collaborator`. It is instance-configurable and localised,
  *   so it stays text rather than becoming an enum this library would have to keep in step with a deployment
  */
final case class CollaboratorAccess(
    user: User,
    permission: Option[TeamPermission],
    rawPermission: Option[String],
    roleName: Option[String],
)
