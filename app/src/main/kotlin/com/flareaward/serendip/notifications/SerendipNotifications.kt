package com.flareaward.serendip.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.flareaward.serendip.R
import com.flareaward.serendip.domain.model.PauseReason
import com.flareaward.serendip.presentation.MainActivity
import com.flareaward.serendip.service.AutoCaptureService
import com.flareaward.serendip.service.ServiceCommandReceiver
import com.flareaward.serendip.system.AppLog
import com.flareaward.serendip.system.PermissionChecker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Text shown in the persistent foreground-service notification. */
data class ServiceNotificationModel(
    val photosToday: Int,
    val dailyLimit: Int,
    /** Epoch millis of the next planned photo, `null` when nothing more is planned today. */
    val nextCaptureAt: Long?,
    val paused: PauseReason?,
)

/**
 * All notifications of the app. Three channels:
 *  - service: the mandatory, silent foreground-service notification;
 *  - photos: "a photo was taken" (only after a real, verified success, and only if enabled);
 *  - attention: the app needs the user (reboot, revoked permission, blocked camera).
 */
class SerendipNotifications(
    private val context: Context,
    private val permissions: PermissionChecker,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_service_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PHOTOS,
                context.getString(R.string.channel_photos_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_photos_description)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ATTENTION,
                context.getString(R.string.channel_attention_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_attention_description)
            },
        )
    }

    // ------------------------------------------------------------------ foreground service

    fun buildServiceNotification(model: ServiceNotificationModel): Notification {
        val title = context.getString(R.string.notification_service_title)
        val text = when {
            model.paused != null -> context.getString(R.string.notification_service_paused)
            model.photosToday >= model.dailyLimit ->
                context.getString(R.string.notification_service_done, model.photosToday, model.dailyLimit)
            model.nextCaptureAt != null ->
                context.getString(
                    R.string.notification_service_progress,
                    model.photosToday,
                    model.dailyLimit,
                    timeFormatter.format(Instant.ofEpochMilli(model.nextCaptureAt).atZone(ZoneId.systemDefault())),
                )
            else -> context.getString(R.string.notification_service_waiting, model.photosToday, model.dailyLimit)
        }
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .addAction(0, context.getString(R.string.notification_action_turn_off), turnOffIntent())
            .build()
    }

    fun updateServiceNotification(model: ServiceNotificationModel) {
        notifySafely(ID_SERVICE, buildServiceNotification(model))
    }

    // ------------------------------------------------------------------ photos

    /** Posts the "photo taken" notification. Call only after the image has been verified and recorded. */
    suspend fun showPhotoSaved(eventId: Long, photoUri: Uri, takenAt: Long) {
        if (!permissions.notificationsAllowed()) return
        val time = timeFormatter.format(Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()))
        val builder = NotificationCompat.Builder(context, CHANNEL_PHOTOS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_photo_title))
            .setContentText(context.getString(R.string.notification_photo_text, time))
            .setAutoCancel(true)
            .setWhen(takenAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(GROUP_PHOTOS)
            .setContentIntent(openPhotoIntent(eventId))
        loadThumbnail(photoUri)?.let { bitmap ->
            builder.setLargeIcon(bitmap)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(context.getString(R.string.notification_photo_text, time)),
            )
        }
        notifySafely(eventId.toInt(), builder.build(), TAG_PHOTO)
    }

    // ------------------------------------------------------------------ attention

    /** Tells the user why automatic photos stopped and offers the one-tap way back. */
    fun showAttention(reason: PauseReason) {
        val (title, text) = attentionTexts(reason)
        val builder = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent())
        if (reason != PauseReason.CAMERA_PERMISSION_MISSING && reason != PauseReason.NO_BACK_CAMERA) {
            builder.addAction(0, context.getString(R.string.notification_action_resume), resumeIntent())
        }
        notifySafely(ID_ATTENTION, builder.build())
    }

    fun cancelAttention() {
        manager.cancel(ID_ATTENTION)
    }

    fun attentionTexts(reason: PauseReason): Pair<String, String> {
        val title = context.getString(R.string.notification_attention_title)
        val text = when (reason) {
            PauseReason.REBOOT -> context.getString(R.string.pause_reason_reboot)
            PauseReason.APP_UPDATED -> context.getString(R.string.pause_reason_app_updated)
            PauseReason.CAMERA_PERMISSION_MISSING -> context.getString(R.string.pause_reason_camera_permission)
            PauseReason.CAMERA_ACCESS_BLOCKED -> context.getString(R.string.pause_reason_camera_blocked)
            PauseReason.SERVICE_NOT_ALLOWED -> context.getString(R.string.pause_reason_service_not_allowed)
            PauseReason.NO_BACK_CAMERA -> context.getString(R.string.pause_reason_no_camera)
            PauseReason.SERVICE_NOT_RUNNING -> context.getString(R.string.pause_reason_service_not_running)
        }
        return title to text
    }

    // ------------------------------------------------------------------ intents

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        RC_OPEN_APP,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openPhotoIntent(eventId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        RC_OPEN_PHOTO_BASE + (eventId % 10_000).toInt(),
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_PHOTO)
            .putExtra(MainActivity.EXTRA_EVENT_ID, eventId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * "Resume" starts the camera foreground service through a notification
     * action — one of the states Android explicitly allows a while-in-use
     * (camera) foreground service to be started from.
     */
    private fun resumeIntent(): PendingIntent = PendingIntent.getForegroundService(
        context,
        RC_RESUME,
        AutoCaptureService.intent(context, AutoCaptureService.ACTION_RESUME_FROM_NOTIFICATION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun turnOffIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        RC_TURN_OFF,
        Intent(context, ServiceCommandReceiver::class.java).setAction(ServiceCommandReceiver.ACTION_TURN_OFF),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ------------------------------------------------------------------ helpers

    private fun notifySafely(id: Int, notification: Notification, tag: String? = null) {
        try {
            if (tag != null) manager.notify(tag, id, notification) else manager.notify(id, notification)
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call — nothing to do.
            AppLog.w("Notification $id could not be posted", error)
        }
    }

    private suspend fun loadThumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val largest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (largest / sample > THUMBNAIL_MAX_PX) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (error: Exception) {
            AppLog.w("Thumbnail for notification failed", error)
            null
        }
    }

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_PHOTOS = "photos"
        const val CHANNEL_ATTENTION = "attention"

        const val ID_SERVICE = 1
        const val ID_ATTENTION = 2
        private const val TAG_PHOTO = "photo"
        private const val GROUP_PHOTOS = "com.flareaward.serendip.PHOTOS"

        private const val RC_OPEN_APP = 100
        private const val RC_RESUME = 101
        private const val RC_TURN_OFF = 102
        private const val RC_OPEN_PHOTO_BASE = 10_000

        private const val THUMBNAIL_MAX_PX = 1280
    }
}
