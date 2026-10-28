package com.nvivio

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.net.MalformedURLException
import java.net.URL
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Nvivio for Android apps: screen views, time on a screen and events, without identifiers and
 * without storing anything on the device, so there is nothing to ask consent for. Every call is
 * cheap and safe from any thread; the work and the network happen on the SDK's own thread.
 *
 * ```
 * Nvivio.start(this, "NV-6T78AE8")                 // in Application.onCreate
 * Nvivio.screen("Checkout")
 * Nvivio.event("Purchase", mapOf("plan" to "pro"))
 * ```
 */
public object Nvivio {
    private const val TAG = "Nvivio"

    /** Time since boot, deep sleep included: ages and durations survive a change of the clock. Never sent. */
    private val clock = Clock { SystemClock.elapsedRealtime() }

    @Volatile
    private var enabled = true

    @Volatile
    private var executor: ScheduledThreadPoolExecutor? = null

    /** Only touched on the executor's thread. */
    private var tracker: Tracker? = null

    /** Logs every request and answer with android.util.Log, tag "Nvivio". */
    @JvmStatic
    @Volatile
    public var debug: Boolean = false

    /**
     * False drops what waits and sends nothing until it is true again. The SDK does not remember
     * it: keep the user's choice in your app's own settings and set it on every launch.
     */
    @JvmStatic
    public var isEnabled: Boolean
        get() = enabled
        set(value) {
            enabled = value
            if (executor != null) post { it.setEnabled(value) }
        }

    /**
     * Starts measuring. Call it once, in Application.onCreate: it has to see the first activity
     * start to know when the app goes to the background.
     *
     * @param site the site's code from Nvivio's settings, such as "NV-6T78AE8".
     * @param endpoint where the events go; replaceable for tests and local development.
     * @param trackActivities names screens after activities ("CheckoutActivity" is "Checkout").
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    public fun start(context: Context, site: String, endpoint: String = "https://nviv.io/a", trackActivities: Boolean = false) {
        if (executor != null) {
            Log.w(TAG, "start: already started")
            return
        }
        val url = try {
            URL(endpoint)
        } catch (e: MalformedURLException) {
            Log.w(TAG, "start: \"$endpoint\" is not a URL")
            return
        }
        val app = context.applicationContext
        val thread = ScheduledThreadPoolExecutor(1) { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, TAG).apply { isDaemon = true }
        }
        thread.removeOnCancelPolicy = true
        val scheduler = Scheduler { delayMs, task ->
            val future = thread.schedule(safely(task), delayMs, TimeUnit.MILLISECONDS)
            return@Scheduler { future.cancel(false) }
        }
        // Reading the package info is a call to the system: on the SDK's thread, not the app's.
        thread.execute(safely {
            tracker = Tracker(site.trim(), device(app), randomId(16), clock, HttpTransport(url), scheduler, AndroidLogger, enabled = enabled)
        })
        executor = thread
        val application = app as? Application
        if (application == null) {
            Log.w(TAG, "start: no Application, so the SDK does not see the app go to the background")
        } else {
            application.registerActivityLifecycleCallbacks(Lifecycle(trackActivities))
        }
    }

    /** A screen view ("Checkout", "Settings/Privacy"); the screen that is already current sends nothing. */
    @JvmStatic
    public fun screen(name: String) {
        if (!enabled) return
        val at = clock.now()
        post { it.screen(name, at) }
    }

    /**
     * A custom event on the current screen. Names: letters, digits, spaces and `_ . : -`, at most
     * 64. Up to 20 properties; values a string (200 characters), number or boolean.
     */
    @JvmStatic
    @JvmOverloads
    public fun event(name: String, props: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        val at = clock.now()
        // A copy: the app may change its map before the SDK's thread reads it.
        val copy = props.toMap()
        post { it.event(name, copy, at) }
    }

    /** The deep link that opened the app: its utm_* go with the next screen view. */
    @JvmStatic
    public fun open(uri: Uri) {
        if (!enabled) return
        val utm = utmOf(uri.toString())
        post { it.open(utm) }
    }

    private fun foreground() {
        val at = clock.now()
        post { it.foreground(at) }
    }

    private fun background() {
        val at = clock.now()
        post { it.background(at) }
    }

    private fun post(task: (Tracker) -> Unit) {
        val thread = executor
        if (thread == null) {
            if (debug) Log.d(TAG, "left out: call start() first")
            return
        }
        thread.execute(safely { tracker?.let(task) })
    }

    /** An error in the SDK is logged, never thrown at the app. */
    private fun safely(task: () -> Unit) = Runnable {
        try {
            task()
        } catch (e: Exception) {
            Log.w(TAG, "internal error", e)
        }
    }

    /** The screen's configuration, the OS version and the locale: nothing beyond what the protocol allows. */
    private fun device(context: Context): Device {
        // The shorter side in dp, the same in either orientation.
        val sw = context.resources.configuration.smallestScreenWidthDp
        val locale = if (Build.VERSION.SDK_INT >= 24) LocaleList.getDefault()[0] else Locale.getDefault()
        return Device(
            app = context.packageName,
            version = versionName(context)?.take(32),
            os = "Android ${Build.VERSION.RELEASE}",
            kind = if (sw >= 600) "tablet" else "phone",
            width = sw.takeIf { it > 0 },
            lang = locale.toLanguageTag().takeIf { it != "und" },
            // Public build constants, to show which phones people use (sdks/PROTOCOL.md).
            model = "${Build.MANUFACTURER}|${Build.MODEL}".take(60),
        )
    }

    private fun versionName(context: Context): String? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private object AndroidLogger : Logger {
        override val debug: Boolean get() = Nvivio.debug

        override fun d(message: String) {
            if (debug) Log.d(TAG, message)
        }

        override fun w(message: String) {
            Log.w(TAG, message)
        }
    }

    /**
     * In the foreground while an activity is started; when none is, the app went to the
     * background, unless the last one only restarts for a configuration change (a rotation).
     */
    private class Lifecycle(private val nameScreens: Boolean) : Application.ActivityLifecycleCallbacks {
        /** Main thread only. */
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) foreground()
        }

        override fun onActivityStopped(activity: Activity) {
            if (started == 0) return
            if (--started == 0 && !activity.isChangingConfigurations) background()
        }

        /** Resumed rather than started: back from a translucent activity, the one below was never stopped. */
        override fun onActivityResumed(activity: Activity) {
            if (!nameScreens) return
            val name = activity.javaClass.simpleName
            screen(name.removeSuffix("Activity").ifEmpty { name })
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
