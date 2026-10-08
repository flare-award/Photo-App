package com.flareaward.serendip.domain.model

/** What Android currently allows the app to do in the background. */
enum class BackgroundRestriction {
    /** Battery optimisation is off for the app ("Unrestricted"). */
    UNRESTRICTED,

    /** Default state: Doze / App Standby may delay or deny background work. */
    OPTIMIZED,

    /** The user chose "Restricted": background work is actively prevented. */
    RESTRICTED,
}

/** Snapshot of permissions and system switches that affect automatic photos. */
data class SystemStatus(
    val cameraPermissionGranted: Boolean,
    /** POST_NOTIFICATIONS granted (Android 13+) or notifications enabled (older). */
    val notificationsAllowed: Boolean,
    /** Whether the runtime permission dialog for notifications exists on this device (Android 13+). */
    val notificationPermissionRequired: Boolean,
    val backgroundRestriction: BackgroundRestriction,
    val hasBackCamera: Boolean,
) {
    companion object {
        val UNKNOWN = SystemStatus(
            cameraPermissionGranted = false,
            notificationsAllowed = false,
            notificationPermissionRequired = true,
            backgroundRestriction = BackgroundRestriction.OPTIMIZED,
            hasBackCamera = true,
        )
    }
}
