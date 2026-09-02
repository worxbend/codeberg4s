package com.worxbend.codeberg4s.notifications

import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.notifications.wire.{NotificationCountDto, NotificationThreadDto}

/** Every response shape [[NotificationApi]] can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call: a decoder is a function
  * from a body to a value, and allocating a new one per request would be waste with no upside.
  *
  * These three shapes are the spec's word rather than a capture's: see [[NotificationApi]] for why this one group
  * could not be built against a real response body.
  */
private[notifications] object NotificationDecoders:

  /** One notification thread, as `GET /notifications/threads/{id}` returns it. */
  val thread: Decode[NotificationThread] =
    WireDecode.single(Json.decoder[NotificationThreadDto])(_.toDomain)

  /** A bare array of notification threads, as both listings return them. */
  val threads: Decode[Vector[NotificationThread]] =
    WireDecode.vector(Json.decoder[Vector[NotificationThreadDto]])

  /** The unread counter `GET /notifications/new` answers. */
  val unreadCount: Decode[UnreadCount] =
    WireDecode.single(Json.decoder[NotificationCountDto])(_.toDomain)
