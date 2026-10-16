# Mobile Harness Extended FCM Remote Events

Mobile Harness Extended uses Firebase Cloud Messaging only as a remote transport. Local Android/Linux runtime notifications remain on the Android notification path and do not depend on FCM.

## Online flavor

The `online` flavor includes Firebase Messaging and registers `MobileHarnessFirebaseMessagingService`.

The service accepts data-oriented events with this contract:

- `title` — notification title.
- `body` — notification body.
- `route` — optional in-app route/context string carried into the launch intent.
- `type` — optional event type for future routing/analytics.
- `messageId` — supplied by FCM when available; it is used to derive a stable notification id.

For events that require Mobile Harness Extended to own presentation, send a data message rather than depending on an FCM notification payload. Background notification payloads can be rendered directly by the FCM SDK.

Example data payload:

```json
{
  "data": {
    "title": "Agent session completed",
    "body": "The background coding task finished in mobile-harness.",
    "route": "project/mobile-harness/session/current",
    "type": "agent_task_completed"
  }
}
```

The app posts the resulting notification through `NotificationCoordinator`, so channel setup, icon policy, tap behavior, and notification permissions remain centralized.

## Token lifecycle

FCM registration tokens are persisted in app-private `SharedPreferences` through `PushTokenStore`. The token is not an OAuth credential and is not logged or embedded into source code.

A future authenticated backend may read the token and register it against the signed-in Mobile Harness Extended account. Until that backend exists, the client intentionally does not transmit the token anywhere.

## Offline flavor

The `offline` flavor does not include the Firebase Messaging dependency or the messaging service. Its local runtime remains fully independent of FCM.

## Credential boundary

Do not place Firebase service-account private keys, FCM server credentials, or Antigravity OAuth/session credentials in the Android application. Server-side FCM HTTP v1 authentication belongs on a trusted backend.
