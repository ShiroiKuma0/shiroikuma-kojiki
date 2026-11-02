/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.customui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Fork (白い熊 考直): finds screens that outlive themselves. Diagnostics → "Screen leaks", off by
 * default; off, a destroyed activity costs one boolean check.
 *
 * 白い熊 応用管理's process monitor showed this process holding 15 Activity objects and ~2000 views
 * with no screen open. The app is not debuggable, so `am dumpheap` is refused, and a VPN process is
 * never backgrounded, so the system never forces a GC either. This watches from the inside instead:
 * every destroyed activity is kept as a [WeakReference]; [CHECK_DELAY_MS] later the heap is collected
 * and whatever is still reachable is a leak, written to the LEAKS trace (see [KojikiDiag]). It found
 * CustomUi's layout-listener map pinning every screen ever opened (fixed 2026-11-02).
 *
 * With "Heap dump on next leak" on as well, the first leak found also writes [KojikiDiag.HPROF] next
 * to the trace and turns that switch back off, so it happens once per request.
 */
object KojikiLeakWatch {

    private const val CHECK_DELAY_MS = 15_000L

    private class Gone(val ref: WeakReference<Activity>, val name: String, val at: Long)

    private val gone = mutableListOf<Gone>() // main thread only
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private var app: Application? = null

    fun install(app: Application) {
        this.app = app
        KojikiDiag.addListener { g, value ->
            if (g == KojikiDiag.Group.LEAKS && !value) main.post { gone.clear(); main.removeCallbacks(check) }
        }
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityDestroyed(activity: Activity) {
                    if (!KojikiDiag.isOn(KojikiDiag.Group.LEAKS)) return
                    gone.add(Gone(WeakReference(activity), activity.javaClass.name, System.currentTimeMillis()))
                    main.removeCallbacks(check)
                    main.postDelayed(check, CHECK_DELAY_MS)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            })
    }

    private val check = Runnable {
        io.execute {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Runtime.getRuntime().gc()
            main.post { report() }
        }
    }

    private fun report() {
        gone.removeAll { it.ref.get() == null }
        val now = System.currentTimeMillis()
        val leaked = gone.filter { now - it.at >= CHECK_DELAY_MS }
        if (leaked.isEmpty()) return
        val lines = StringBuilder()
        lines.append(leaked.size).append(" destroyed, still alive:")
        leaked.groupBy { it.name }.forEach { (name, list) ->
            lines.append("\n    ").append(list.size).append(" × ").append(name)
                .append(" (oldest destroyed ").append(stamp.format(Date(list.minOf { it.at }))).append(")")
        }
        KojikiDiag.log(KojikiDiag.Group.LEAKS, lines.toString())
        if (KojikiDiag.isOn(KojikiDiag.Group.HEAP_DUMP)) {
            val a = app ?: return
            KojikiDiag.set(a, KojikiDiag.Group.HEAP_DUMP, false)
            val out = KojikiDiag.directory()?.let { File(it, KojikiDiag.HPROF) } ?: return
            // queued behind the report line, on the trace thread, so the two land in order
            KojikiDiag.onIo {
                out.parentFile?.mkdirs()
                Debug.dumpHprofData(out.absolutePath)
                KojikiDiag.log(KojikiDiag.Group.LEAKS, "    heap dumped to ${out.name}")
            }
        }
    }
}
