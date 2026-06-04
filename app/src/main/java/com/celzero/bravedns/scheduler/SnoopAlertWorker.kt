/*
 * Copyright 2024 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.scheduler

import Logger
import Logger.LOG_TAG_SCHEDULER
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.celzero.bravedns.R
import com.celzero.bravedns.database.SnoopEventRepository
import com.celzero.bravedns.service.SnoopClassifier
import com.celzero.bravedns.ui.activity.SnoopActivity
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

// Fork (白い熊 考直) — Snooping panel: optional daily notification. Counts new
// high-severity SnoopEvents since the last run and posts a notification that opens
// the panel (replaces the dropped off-device email digest).
class SnoopAlertWorker(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val repository by inject<SnoopEventRepository>()

    companion object {
        private const val TAG = "SnoopAlertWorker"
        const val JOB_TAG = "SnoopAlertJob"
        private const val CHANNEL_ID = "snoop_alerts"
        private const val NOTIF_ID = 0x5A00 // 23040
        private const val PREFS = "snoop_prefs"
        private const val KEY_LAST_RUN = "lastRunTs"
        private val INTERVAL_HOURS = 24L

        fun schedule(context: Context) {
            val req =
                PeriodicWorkRequest.Builder(
                        SnoopAlertWorker::class.java,
                        INTERVAL_HOURS,
                        TimeUnit.HOURS
                    )
                    .addTag(JOB_TAG)
                    .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(JOB_TAG, ExistingPeriodicWorkPolicy.KEEP, req)
            Logger.i(LOG_TAG_SCHEDULER, "$TAG: scheduled daily snoop alert job")
        }
    }

    override suspend fun doWork(): Result {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val since = prefs.getLong(KEY_LAST_RUN, 0L)
            val now = System.currentTimeMillis()

            val newHigh = repository.countNewBySeverity(SnoopClassifier.SEV_HIGH, since)
            prefs.edit().putLong(KEY_LAST_RUN, now).apply()

            Logger.i(LOG_TAG_SCHEDULER, "$TAG: $newHigh new high-severity snoops since $since")
            if (newHigh > 0) {
                notify(newHigh)
            }
            return Result.success()
        } catch (e: Exception) {
            Logger.w(LOG_TAG_SCHEDULER, "$TAG: err ${e.message}")
            return Result.success() // never retry-spam for a notification job
        }
    }

    private fun notify(count: Int) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) {
            Logger.i(LOG_TAG_SCHEDULER, "$TAG: notifications disabled, skip")
            return
        }

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (isAtleastO()) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.snoop_notif_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            channel.description = context.getString(R.string.snoop_notif_channel_desc)
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, SnoopActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntent =
            Utilities.getActivityPendingIntent(
                context,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                mutable = false
            )

        val title = context.getString(R.string.snoop_notif_title)
        val text = context.getString(R.string.snoop_notif_text, count)
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

        try {
            nm.notify(NOTIF_ID, builder.build())
        } catch (e: SecurityException) {
            Logger.w(LOG_TAG_SCHEDULER, "$TAG: notify denied ${e.message}")
        }
    }
}
