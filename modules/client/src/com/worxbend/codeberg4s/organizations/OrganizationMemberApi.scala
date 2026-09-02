package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, read}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.users.social.BlockedUser
import com.worxbend.codeberg4s.users.{User, Username}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod}

import scala.concurrent.Future

/** Who belongs to an organisation, which of them say so publicly, and who has been blocked from it.
  *
  * Reached as `client.organizations.members`. It is a group of its own rather than more methods on [[OrganizationApi]]
  * because that class had grown past what a reader can hold in their head; the endpoints, the models and the retry
  * decisions are unchanged by the move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[OrganizationMemberApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Membership read from both ends==
  *
  * [[members]], [[publicMembers]] and the membership checks start from the organisation and ask who is in it.
  * [[userOrganizations]], [[currentUserOrganizations]] and [[userPermissions]] start from a person and ask which
  * organisations they are in. Forgejo serves the second set under `/users/{username}/orgs` and `/user/orgs` rather
  * than under `/orgs`, which is why the paths in this group do not all share a prefix; the question they answer is the
  * same one from the other side.
  *
  * ==Public and private membership are different questions==
  *
  * Forgejo lets a member choose whether their membership is visible to people who are not in the organisation.
  * [[members]] needs a token that can see the organisation and answers with everybody; [[publicMembers]] answers with
  * the subset that has chosen to be visible, and answers it to anybody. A caller that treats the second as the whole
  * membership will silently under-report.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on
  * each method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the organisation or the account does not
  *     exist '''or''' is invisible to the credentials in use — Forgejo does not distinguish the two, on purpose —
  *     `401` when a token was required and none was sent, and `403` when the token lacks the scope or the account
  *     lacks the permission.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type, so a value that would forge a path or a query parameter is rejected by its own smart
  * constructor before a client is ever involved.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. The writes each name one organisation
  * and one account and set a flag rather than creating a row, so the state after N attempts is the state after one and
  * they use [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]]; each says so on its own method.
  */

final class OrganizationMemberApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: OrganizationMemberApi.Attempt = OrganizationMemberApi.Attempt(this)

  /** Lists an organisation's members — `GET /orgs/{org}/members`.
    *
    * This is the full membership, including members who chose to keep their membership concealed; [[publicMembers]] is
    * the subset that is public. Reading it needs credentials that may see the organisation's membership.
    *
    * '''`401` is the ordinary anonymous outcome''', not an exceptional one: `golden/MANIFEST.md` records that this path
    * answers `401 token is required` to an anonymous caller on codeberg.org, with a body identical to
    * `golden/error/401-token-required.json`, even though `docs/API_INVENTORY.md` marks it "optional" — a column derived
    * from path shape, for the reason `docs/HAZARDS.md` §2 gives.
    *
    * '''Failures.''' The group contract above, with [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] meaning an
    * element carried no `id` or `login`.
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many members it may hold
    */
  def members(org: OrgName, params: PageParams): Future[Page[User]] =
    pipeline.callPage(OrganizationMemberApi.membersRequest(org, params), params)(using OrganizationDecoders.users)

  /** Lists the members who have made their membership public — `GET /orgs/{org}/public_members`.
    *
    * A member is concealed by default on Forgejo and appears here only after publicising their membership, so this
    * listing is normally much shorter than [[members]] and is never longer.
    *
    * '''Not probed.''' No golden fixture exists for this path: the anonymous capture session recorded `401` for
    * [[members]] and did not separately probe this one. Treat a `401` as ordinary here too until an instance proves
    * otherwise.
    *
    * '''Failures.''' As [[members]].
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many members it may hold
    */
  def publicMembers(org: OrgName, params: PageParams): Future[Page[User]] =
    pipeline.callPage(OrganizationMemberApi.publicMembersRequest(org, params), params)(using OrganizationDecoders.users)

  /** Asks whether an account is a member — `GET /orgs/{org}/members/{username}`.
    *
    * '''This endpoint answers with a status, not a body.''' `204` means member, `404` means not a member, and there is
    * no payload either way. So this is one of two operations in this class that read a `404` as an answer rather than
    * as a failure — it becomes `false` on the success channel, and every other status keeps travelling on the error
    * channel exactly as it would elsewhere. [[com.worxbend.codeberg4s.pulls.PullRequestApi.isMerged]] is the pattern.
    *
    * '''The `404` is genuinely ambiguous, and the ambiguity is Forgejo's.''' The same status answers "not a member",
    * "no such organisation" and "no such account". A caller who needs to tell them apart reads [[get]], which
    * distinguishes the middle one.
    *
    * '''There is a `303` in the spec, and it is not a failure either.''' Forgejo redirects a caller who may not see the
    * full membership to `/orgs/{org}/public_members/{username}`, which then answers the narrower question. The
    * transport follows redirects, so what arrives here is the redirected `204` or `404` — meaning a `true` from this
    * method can mean "a public member" rather than "a member", when the credentials could not see more.
    * [[isPublicMember]] asks the narrow question deliberately.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]] like any other `GET`. A `404`
    * is not a retryable status, so the `false` answer is never reached by way of an exhausted retry budget.
    *
    * '''Failures.''' The group contract above, minus `404` and minus the decoding case — nothing is parsed. `401` and
    * `403` still arrive as [[com.worxbend.codeberg4s.CodebergError.Api]], so an unreadable organisation does not
    * masquerade as a non-member.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account handle
    */
  def isMember(org: OrgName, username: Username): Future[Boolean] =
    OrganizationMemberApi.probe(pipeline, OrganizationMemberApi.isMemberRequest(org, username))

  /** Asks whether an account is a '''public''' member — `GET /orgs/{org}/public_members/{username}`.
    *
    * The same status-as-answer contract as [[isMember]]: `204` is `true`, `404` is `false`, everything else fails.
    * Unlike [[isMember]] there is no redirect to reason about, and the question is narrower — a member who has
    * concealed their membership answers `false` here and `true` there.
    *
    * '''Failures.''' As [[isMember]].
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account handle
    */
  def isPublicMember(org: OrgName, username: Username): Future[Boolean] =
    OrganizationMemberApi.probe(pipeline, OrganizationMemberApi.isPublicMemberRequest(org, username))

  /** Removes an account from the organisation — `DELETE /orgs/{org}/members/{username}`.
    *
    * '''This removes them from every team as well''', because team membership is a subset of organisation membership.
    * Removing someone from one team is [[teamAdmin]]'s `removeMember`.
    *
    * '''Never retried''', for the class-level reason: the request names the organisation by a handle [[rename]] can
    * move, and what it does is remove a person's access. A repeat after a lost success could evict an account from an
    * organisation nobody asked about.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account to remove
    */
  def removeMember(org: OrgName, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationMemberApi.removeMemberRequest(org, username), RetryEligibility.Never)

  /** Makes a membership public — `PUT /orgs/{org}/public_members/{username}`.
    *
    * The account then appears in [[publicMembers]] and on the organisation's page. Forgejo lets a member publicise
    * their own membership and an owner publicise anyone's.
    *
    * '''Never retried''', for the class-level reason. Setting a flag twice is setting it once, so the '''repetition'''
    * is harmless; what is not is the handle, which [[rename]] can move between the lost success and the retry — and
    * this call publishes a fact about a person's affiliation, which is not something to publish about the wrong
    * organisation.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above. `403` is what Forgejo answers for a caller publicising somebody else's
    * membership without the standing to.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account whose membership becomes public
    */
  def publicizeMember(org: OrgName, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationMemberApi.publicizeMemberRequest(org, username), RetryEligibility.Never)

  /** Conceals a membership — `DELETE /orgs/{org}/public_members/{username}`.
    *
    * The account stays a member and stops appearing in [[publicMembers]]. This is '''not''' [[removeMember]]: nothing
    * about the account's access changes.
    *
    * '''Never retried''', for [[publicizeMember]]'s reason.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account whose membership is concealed
    */
  def concealMember(org: OrgName, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationMemberApi.concealMemberRequest(org, username), RetryEligibility.Never)

  // --- blocks ---------------------------------------------------------------

  /** Lists the accounts an organisation has blocked — `GET /orgs/{org}/list_blocked`.
    *
    * '''The entries do not name the accounts.''' Forgejo's `BlockedUser` model declares `block_id` and `created_at` and
    * nothing else; see [[BlockedUser]] for why that is reported as-is rather than patched over with a guessed field.
    *
    * '''Failures.''' The group contract above. The spec declares only a `200` for this operation, which means nothing:
    * `docs/HAZARDS.md` §2 measured the spec's error coverage to be decorative, and a `404` for an organisation that
    * does not exist is what any of these paths does.
    *
    * @param org
    *   the organisation handle
    * @param params
    *   the page to fetch and how many entries it may hold
    */
  def blockedUsers(org: OrgName, params: PageParams): Future[Page[BlockedUser]] =
    pipeline.callPage(OrganizationMemberApi.blockedUsersRequest(org, params), params)(using OrganizationDecoders.blockedUsers)

  /** Blocks an account — `PUT /orgs/{org}/block/{username}`.
    *
    * A blocked account cannot follow the organisation, open issues on its repositories or interact with it. Forgejo
    * removes them from the organisation as a side effect if they were a member, which is why this is not simply a flag.
    *
    * '''Never retried''', for the class-level reason, and the side effect above is why it matters here: the handle can
    * move between a lost success and a retry, and the retry would then evict somebody from a different organisation.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above. `422` is what Forgejo answers for an account it will not let the
    * organisation block — an owner, or one already blocked.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account to block
    */
  def blockUser(org: OrgName, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationMemberApi.blockUserRequest(org, username), RetryEligibility.Never)

  /** Lifts a block — `PUT /orgs/{org}/unblock/{username}`.
    *
    * '''A `PUT`, not a `DELETE`, and that is the API's own shape.''' The block is lifted by naming the account at a
    * second path rather than by deleting the record [[blockedUsers]] reports — which is why [[BlockId]] is never handed
    * to anything.
    *
    * '''Never retried''', for [[blockUser]]'s reason.
    *
    * '''Answers `204`''', and does not restore a membership the block removed.
    *
    * '''Failures.''' The group contract above. `422` is what an account that is not blocked produces.
    *
    * @param org
    *   the organisation handle
    * @param username
    *   the account to unblock
    */
  def unblockUser(org: OrgName, username: Username): Future[Unit] =
    pipeline.callUnit(OrganizationMemberApi.unblockUserRequest(org, username), RetryEligibility.Never)

  // --- teams ----------------------------------------------------------------

  /** Lists the organisations an account belongs to — `GET /users/{username}/orgs`.
    *
    * '''`401` is the ordinary anonymous outcome.''' `golden/error/401-user-orgs.json` is a verbatim capture of
    * `GET /users/earl-warren/orgs?page=1&limit=3` answering `401 token is required` with no credentials, even though
    * `docs/API_INVENTORY.md` marks the operation "optional". That column is derived from path shape, not from the
    * specification — `docs/HAZARDS.md` §2 measured that the spec declares `security` globally with zero per-operation
    * overrides and so carries no per-endpoint authentication information at all. The manifest records the same
    * behaviour for `/users/{u}/followers` and `/users/{u}/starred`.
    *
    * The argument is a [[com.worxbend.codeberg4s.users.Username]] and not an [[OrgName]]: this operation names a
    * '''person''' and asks what they belong to.
    *
    * '''Failures.''' The group contract above, with `404` for an account that does not exist and
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when an element carried no `id` or no usable `name`.
    *
    * @param username
    *   the account handle as it appears in a Codeberg URL
    * @param params
    *   the page to fetch and how many organisations it may hold
    */
  def userOrganizations(username: Username, params: PageParams): Future[Page[Organization]] =
    pipeline.callPage(OrganizationMemberApi.userOrganizationsRequest(username, params), params)(using
      OrganizationDecoders.organizations)

  /** Lists the organisations the '''authenticated''' account belongs to — `GET /user/orgs`.
    *
    * The counterpart to [[userOrganizations]] that names nobody: the token decides whose organisations these are, which
    * is why the path is `/user/orgs` — singular `user` — and takes no handle. It is the only operation in this group
    * whose result depends on the credentials rather than on an argument, and the only one that is useless without a
    * token: the spec declares `401` for it explicitly, which given how little the spec says about authentication
    * anywhere is worth noticing.
    *
    * It also sees more than [[userOrganizations]] would for the same account: concealed memberships are included,
    * because the caller is the member.
    *
    * '''Failures.''' The group contract above, with `401` the expected outcome without a token.
    *
    * @param params
    *   the page to fetch and how many organisations it may hold
    */
  def currentUserOrganizations(params: PageParams): Future[Page[Organization]] =
    pipeline.callPage(OrganizationMemberApi.currentUserOrganizationsRequest(params), params)(using
      OrganizationDecoders.organizations)

  /** Reads what one account may do in one organisation — `GET /users/{username}/orgs/{org}/permissions`.
    *
    * '''The effective answer, not a role''': Forgejo folds ownership, team membership and site administration into five
    * booleans and reports the result, with no field saying which of them granted what. See [[OrganizationPermissions]].
    *
    * '''Absent flags read as `false`''', which is the safe direction for a permission — see
    * [[com.worxbend.codeberg4s.organizations.wire.OrganizationPermissionsDto]].
    *
    * '''Failures.''' The group contract above. `403` is what Forgejo answers a caller asking about somebody else
    * without the standing to; `404` covers both the account and the organisation, and does not say which.
    *
    * @param username
    *   the account being asked about
    * @param org
    *   the organisation the question is about
    */
  def userPermissions(username: Username, org: OrgName): Future[OrganizationPermissions] =
    pipeline.call(OrganizationMemberApi.userPermissionsRequest(username, org), RetryEligibility.IdempotentOnly)(using
      OrganizationDecoders.permissions)

/** The requests this group issues, its operation ids, and its typed rail. */
object OrganizationMemberApi:

  /** The stable operation id of [[OrganizationMemberApi.members]]. */
  val MembersOperation: String = "orgs.members.list"

  /** The stable operation id of [[OrganizationMemberApi.isMember]]. */
  val IsMemberOperation: String = "orgs.members.check"

  /** The stable operation id of [[OrganizationMemberApi.removeMember]]. */
  val RemoveMemberOperation: String = "orgs.members.remove"

  /** The stable operation id of [[OrganizationMemberApi.publicMembers]]. */
  val PublicMembersOperation: String = "orgs.publicMembers.list"

  /** The stable operation id of [[OrganizationMemberApi.isPublicMember]]. */
  val IsPublicMemberOperation: String = "orgs.publicMembers.check"

  /** The stable operation id of [[OrganizationMemberApi.publicizeMember]]. */
  val PublicizeMemberOperation: String = "orgs.publicMembers.add"

  /** The stable operation id of [[OrganizationMemberApi.concealMember]]. */
  val ConcealMemberOperation: String = "orgs.publicMembers.remove"

  /** The stable operation id of [[OrganizationMemberApi.blockedUsers]]. */
  val BlockedUsersOperation: String = "orgs.blocks.list"

  /** The stable operation id of [[OrganizationMemberApi.blockUser]]. */
  val BlockUserOperation: String = "orgs.blocks.add"

  /** The stable operation id of [[OrganizationMemberApi.unblockUser]]. */
  val UnblockUserOperation: String = "orgs.blocks.remove"

  /** The stable operation id of [[OrganizationMemberApi.userOrganizations]]. */
  val UserOrganizationsOperation: String = "orgs.userOrgs.list"

  /** The stable operation id of [[OrganizationMemberApi.currentUserOrganizations]]. */
  val CurrentUserOrganizationsOperation: String = "orgs.currentUserOrgs.list"

  /** The stable operation id of [[OrganizationMemberApi.userPermissions]]. */
  val UserPermissionsOperation: String = "orgs.userPermissions.get"

  /** The typed rail of [[OrganizationMemberApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as
    * a value.
    *
    * Obtained as `client.organizations.members.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: OrganizationMemberApi)(using exec: Exec[Future]):

    /** [[OrganizationMemberApi.members]] with its failure as a value. */
    def members(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.members(org, params))

    /** [[OrganizationMemberApi.publicMembers]] with its failure as a value. */
    def publicMembers(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.publicMembers(org, params))

    /** [[OrganizationMemberApi.isMember]] with its failure as a value. The `404` that means "not a member" is still a
      * `Right(false)` here, not a `Left`.
      */
    def isMember(org: OrgName, username: Username): Future[Either[CodebergError, Boolean]] =
      exec.attempt(rail.isMember(org, username))

    /** [[OrganizationMemberApi.isPublicMember]] with its failure as a value, on [[isMember]]'s terms. */
    def isPublicMember(org: OrgName, username: Username): Future[Either[CodebergError, Boolean]] =
      exec.attempt(rail.isPublicMember(org, username))

    /** [[OrganizationMemberApi.removeMember]] with its failure as a value. */
    def removeMember(org: OrgName, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.removeMember(org, username))

    /** [[OrganizationMemberApi.publicizeMember]] with its failure as a value. */
    def publicizeMember(org: OrgName, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.publicizeMember(org, username))

    /** [[OrganizationMemberApi.concealMember]] with its failure as a value. */
    def concealMember(org: OrgName, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.concealMember(org, username))

    /** [[OrganizationMemberApi.blockedUsers]] with its failure as a value. */
    def blockedUsers(org: OrgName, params: PageParams): Future[Either[CodebergError, Page[BlockedUser]]] =
      exec.attempt(rail.blockedUsers(org, params))

    /** [[OrganizationMemberApi.blockUser]] with its failure as a value. */
    def blockUser(org: OrgName, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.blockUser(org, username))

    /** [[OrganizationMemberApi.unblockUser]] with its failure as a value. */
    def unblockUser(org: OrgName, username: Username): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.unblockUser(org, username))

    /** [[OrganizationMemberApi.userOrganizations]] with its failure as a value. */
    def userOrganizations(
        username: Username,
        params: PageParams,
    ): Future[Either[CodebergError, Page[Organization]]] =
      exec.attempt(rail.userOrganizations(username, params))

    /** [[OrganizationMemberApi.currentUserOrganizations]] with its failure as a value. */
    def currentUserOrganizations(params: PageParams): Future[Either[CodebergError, Page[Organization]]] =
      exec.attempt(rail.currentUserOrganizations(params))

    /** [[OrganizationMemberApi.userPermissions]] with its failure as a value. */
    def userPermissions(
        username: Username,
        org: OrgName,
    ): Future[Either[CodebergError, OrganizationPermissions]] =
      exec.attempt(rail.userPermissions(username, org))

  /** The status Forgejo answers when the subject of a membership probe is not a member.
    *
    * A `404` from those two endpoints is an answer rather than a failure, and they are the only place in this group
    * that reads a status that way. Named rather than written inline so the pattern below says why it exists;
    * [[com.worxbend.codeberg4s.pulls.PullRequestApi]] does the same for its merge probe.
    */
  private val NotAMember: Int = 404

  /** Runs a status-only membership probe: `204` is `true`, `404` is `false`, everything else fails.
    *
    * Written once because [[OrganizationMemberApi.isMember]] and [[OrganizationMemberApi.isPublicMember]] are the
    * same shape, and a second copy that quietly widened the accepted status would be indistinguishable from one that
    * did not.
    */
  private def probe(pipeline: ApiPipeline[Future], request: CodebergRequest)(using
      exec: Exec[Future]): Future[Boolean] =
    val asked = pipeline.callUnit(request, RetryEligibility.IdempotentOnly)

    exec.flatMap(exec.attempt(asked)):
      case Right(_)                                     => exec.pure(true)
      case Left(CodebergError.Api(_, NotAMember, _, _)) => exec.pure(false)
      case Left(error)                                  => exec.raise(error)

  private def membersRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(MembersOperation, membersPath(org), PagingQuery.window(params))

  private def isMemberRequest(org: OrgName, username: Username): CodebergRequest =
    read(IsMemberOperation, memberPath(org, username), Nil)

  private def removeMemberRequest(org: OrgName, username: Username): CodebergRequest =
    bodiless(RemoveMemberOperation, HttpMethod.Delete, memberPath(org, username))

  private def publicMembersRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(PublicMembersOperation, publicMembersPath(org), PagingQuery.window(params))

  private def isPublicMemberRequest(org: OrgName, username: Username): CodebergRequest =
    read(IsPublicMemberOperation, publicMemberPath(org, username), Nil)

  private def publicizeMemberRequest(org: OrgName, username: Username): CodebergRequest =
    bodiless(PublicizeMemberOperation, HttpMethod.Put, publicMemberPath(org, username))

  private def concealMemberRequest(org: OrgName, username: Username): CodebergRequest =
    bodiless(ConcealMemberOperation, HttpMethod.Delete, publicMemberPath(org, username))

  private def blockedUsersRequest(org: OrgName, params: PageParams): CodebergRequest =
    read(
      BlockedUsersOperation,
      OrganizationRequests.organizationPath(org) :+ "list_blocked",
      PagingQuery.window(params),
    )

  private def blockUserRequest(org: OrgName, username: Username): CodebergRequest =
    bodiless(
      BlockUserOperation,
      HttpMethod.Put,
      OrganizationRequests.organizationPath(org) ++ List("block", username.value),
    )

  private def unblockUserRequest(org: OrgName, username: Username): CodebergRequest =
    bodiless(
      UnblockUserOperation,
      HttpMethod.Put,
      OrganizationRequests.organizationPath(org) ++ List("unblock", username.value),
    )

  private def userOrganizationsRequest(username: Username, params: PageParams): CodebergRequest =
    read(
      UserOrganizationsOperation,
      OrganizationRequests.userPath(username) :+ OrganizationRequests.OrgsSegment,
      PagingQuery.window(params),
    )

  private def currentUserOrganizationsRequest(params: PageParams): CodebergRequest =
    read(
      CurrentUserOrganizationsOperation,
      List("user", OrganizationRequests.OrgsSegment),
      PagingQuery.window(params),
    )

  private def userPermissionsRequest(username: Username, org: OrgName): CodebergRequest =
    read(
      UserPermissionsOperation,
      OrganizationRequests.userPath(username) ++ List(OrganizationRequests.OrgsSegment, org.value, "permissions"),
      Nil,
    )

  private def membersPath(org: OrgName): List[String] =
    OrganizationRequests.organizationPath(org) :+ "members"

  private def memberPath(org: OrgName, username: Username): List[String] =
    membersPath(org) :+ username.value

  private def publicMembersPath(org: OrgName): List[String] =
    OrganizationRequests.organizationPath(org) :+ "public_members"

  private def publicMemberPath(org: OrgName, username: Username): List[String] =
    publicMembersPath(org) :+ username.value
