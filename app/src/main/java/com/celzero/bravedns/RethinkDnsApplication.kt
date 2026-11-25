/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_SCHEDULER
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.celzero.bravedns.customui.KojikiDiag
import com.celzero.bravedns.customui.KojikiLeakWatch
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.scheduler.ScheduleManager
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.util.GlobalExceptionHandler
import com.celzero.bravedns.util.GoReportingHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class RethinkDnsApplication : Application() {
    companion object {
        var DEBUG: Boolean = false
    }

    /**
     * Fork (白い熊 考直): once, put the IP version back on IPv4 (白い熊, 2026-11-22).
     *
     * Upstream's "Fixed" network policy set "IPv4 & IPv6" silently and locked the row, so the
     * tunnel carried IPv6 on a phone whose networks gain nothing from it (home WiFi is v4-only,
     * the WireGuard tunnels carry no v6, the browser used v4 for every connection) — and every
     * fault of 2026-11-11 and 2026-11-21/22 lived on that IPv6 path. The policy no longer touches
     * the IP version (TunnelSettingsActivity); this undoes what it had already stored. A choice
     * made after this build is left alone.
     */
    private fun kojikiIpv4Once() {
        try {
            val prefs = getSharedPreferences("kojiki_migrations", MODE_PRIVATE)
            if (prefs.getBoolean("ipv4_once_034", false)) return
            prefs.edit().putBoolean("ipv4_once_034", true).apply()
            val ps = org.koin.java.KoinJavaComponent.get<com.celzero.bravedns.service.PersistentState>(
                com.celzero.bravedns.service.PersistentState::class.java)
            val was = ps.internetProtocolType
            if (was != com.celzero.bravedns.util.InternetProtocol.IPv4.id) {
                ps.internetProtocolType = com.celzero.bravedns.util.InternetProtocol.IPv4.id
                com.celzero.bravedns.service.KojikiNetDiag.onEvent("ip version: was $was, set to IPv4 (one-time, 0.5.8+034)")
            }
        } catch (_: Exception) {
            // never in the way of the app starting
        }
    }

    override fun onCreate() {
        super.onCreate()
        DEBUG =
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE ==
                    ApplicationInfo.FLAG_DEBUGGABLE

        // Fork (白い熊 考直): Diagnostics switches (all off by default) and the diagnostics they gate.
        KojikiDiag.init(this)
        KojikiLeakWatch.install(this)
        com.celzero.bravedns.service.KojikiNetDiag.init()
        com.celzero.bravedns.service.KojikiNetDiag.onEvent("process start (${com.celzero.bravedns.service.KojikiBootStart.sinceBoot()})")
        // Fork (白い熊 考直): the per-app Roaming control watches the underlying network from here.
        com.celzero.bravedns.service.KojikiRoaming.init(this)

        startKoin {
            if (DEBUG) androidLogger()
            androidContext(this@RethinkDnsApplication)
            koin.loadModules(AppModules)
        }

        // Fork (白い熊 考直): lay the colour roles over the resources — under the Custom theme only,
        // and before the first activity resolves a colour.
        com.celzero.bravedns.customui.KojikiPalette.install(this) {
            com.celzero.bravedns.util.Themes.isCustomTheme(
                org.koin.java.KoinJavaComponent.get<com.celzero.bravedns.service.PersistentState>(
                    com.celzero.bravedns.service.PersistentState::class.java).theme)
        }

        // Fork (白い熊 考直): bring the VPN back when it was wanted and is missing (reboot above all).
        com.celzero.bravedns.service.KojikiBootStart.onProcessStart(this)

        // Fork (白い熊 考直): Diagnostics → Engine log (needs Koin for the stored log level).
        com.celzero.bravedns.service.KojikiEngineDiag.init()
        // Fork (白い熊 考直): IPv4 only is the fork's IP version — see kojikiIpv4Once.
        kojikiIpv4Once()

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Initialize global exception handler
        GlobalExceptionHandler.initialize(this)
        // firebase error reporting is play-flavor only; initialized in
        // RethinkDnsApplicationPlay. website/fdroid variants use stubs.
        GoReportingHandler.initialize(appScope, this)

        turnOnStrictMode()

        appScope.launch {
            scheduleJobs()
        }
    }

    private suspend fun scheduleJobs() {
        Logger.d(LOG_TAG_SCHEDULER, "Schedule job")
        get<WorkScheduler>().scheduleAppExitInfoCollectionJob()
        // database refresh to keep app data up to date
        get<ScheduleManager>().scheduleDatabaseRefreshJob()
        get<WorkScheduler>().scheduleDataUsageJob()
        get<WorkScheduler>().schedulePurgeConnectionsLog()
        get<WorkScheduler>().schedulePurgeConsoleLogs()
    }

    private fun turnOnStrictMode() {
        if (!DEBUG) return
        // Uncomment the code below to enable the StrictModes.
        // To test the apps disk read/writes, network usages.
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .permitDiskReads()
                .permitDiskWrites()
                .permitNetwork()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build()
        )
    }
}
