package com.glass.auto;

import android.service.notification.NotificationListenerService;

/**
 * Stub NotificationListenerService required to access MediaSessionManager.getActiveSessions().
 * The system grants this app's ComponentName access to active media sessions once the user
 * enables "Notification access" for Glass Auto in Settings → Apps → Special access.
 *
 * On a car box you can grant it via adb without user interaction:
 *   adb shell cmd notification allow_listener com.glass.auto/.MediaListenerService
 */
public class MediaListenerService extends NotificationListenerService {
    // No overrides needed — the ComponentName is all MediaSessionManager requires.
}
