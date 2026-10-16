package com.vayro.app.runtime

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives data-oriented FCM events for the online flavor.
 *
 * Remote backends should prefer data messages for events that need Mobile
 * Harness' centralized notification/deep-link policy. Notification messages
 * sent while the app is backgrounded may be rendered directly by the FCM SDK.
 */
class MobileHarnessFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val notification = remoteMessage.notification

        val title = data["title"]
            ?.takeIf { it.isNotBlank() }
            ?: notification?.title
            ?: "Conzyx"

        val body = data["body"]
            ?.takeIf { it.isNotBlank() }
            ?: notification?.body
            ?: return

        NotificationCoordinator.postRemote(
            context = this,
            title = title.take(MAX_TITLE_LENGTH),
            detail = body.take(MAX_BODY_LENGTH),
            messageId = remoteMessage.messageId,
            route = data["route"],
        )
    }

    // QA Medium: the FCM registration token used to be persisted here via
    // PushTokenStore, but nothing ever read it and no server ever received
    // it — the store is deleted, so there is nothing to save. (Push
    // delivery does not depend on local token persistence.)

    companion object {
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_BODY_LENGTH = 4_000
    }
}
