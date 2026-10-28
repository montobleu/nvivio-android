package com.nvivio

import java.io.IOException
import kotlin.random.Random

/** Milliseconds for differences only (ages, time on a screen); never sent. */
internal fun interface Clock {
    fun now(): Long
}

/** The server's answer; for a setup error also its `error` and the `fix` it suggests. */
internal class Answer(val status: Int, val error: String? = null, val fix: String? = null)

/** Sends one request body; throws [IOException] when the network fails. */
internal fun interface Transport {
    @Throws(IOException::class)
    fun post(body: String): Answer
}

/** Runs a task later on the SDK's own thread; the function it returns cancels it. */
internal fun interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

internal interface Logger {
    val debug: Boolean

    /** Only when [debug] is on. */
    fun d(message: String)

    fun w(message: String)
}

/** What every request repeats: the app, the OS, the screen and the language. Nothing that tells one phone from another. */
internal class Device(
    val app: String,
    val version: String?,
    val os: String,
    val kind: String,
    val width: Int?,
    val lang: String?,
    /** "maker|model" (Build.MANUFACTURER, Build.MODEL); the server names it and never uses it to recognise anyone. */
    val model: String? = null,
)

/** The answers that mean the setup is wrong (unknown site, app not listed, invalid events): retrying never helps. */
internal val SETUP_ERRORS = setOf(400, 403, 404)

private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

/** A random code: the launch id (16) and screen view ids (8). Memory only. */
internal fun randomId(length: Int, random: Random = Random.Default): String =
    String(CharArray(length) { ID_CHARS[random.nextInt(ID_CHARS.length)] })

/**
 * The SDK's rules, without Android (PROTOCOL.md): screens, time on a screen, events, batching
 * and retries. Not thread-safe on purpose: every call comes from the SDK's one thread, and
 * [Scheduler] runs its timers there too. `at` is the [Clock]'s time of the app's call, taken
 * on the app's thread, so a request in flight on the SDK's thread never shifts it.
 */
internal class Tracker(
    private val site: String,
    private val device: Device,
    private val launch: String,
    private val clock: Clock,
    private val transport: Transport,
    private val scheduler: Scheduler,
    private val log: Logger,
    private val random: Random = Random.Default,
    private var enabled: Boolean = true,
) {
    private class Event(
        val name: String,
        val at: Long,
        val screen: String?,
        val view: String,
        val props: Map<String, Any>? = null,
        val seconds: Long? = null,
        val utm: Map<String, String>? = null,
    )

    private val queue = ArrayDeque<Event>()
    private var screen: String? = null
    private var view = ""

    /** Time on the current screen counts from here: when it was shown or the app came back. Null in the background. */
    private var since: Long? = null

    /** Until an activity says otherwise: an app calls screen() when it shows something. */
    private var foreground = true
    private var utm: Map<String, String>? = null
    private var failures = 0
    private var retryAt = 0L
    private var cancelTimer: (() -> Unit)? = null
    private var warned = false

    fun screen(name: String, at: Long) {
        if (!enabled) return
        val u = name.trim().take(MAX_SCREEN)
        if (u.isEmpty()) {
            log.d("screen: the name is empty")
            return
        }
        // A redraw or onResume twice: the screen that is already current sends nothing.
        if (u == screen) return
        end(at)
        screen = u
        view = randomId(8, random)
        since = if (foreground) at else null
        add(Event(PV, at, u, view, utm = utm))
        utm = null
    }

    fun event(name: String, props: Map<String, Any?>, at: Long) {
        if (!enabled) return
        val n = name.trim()
        if (!NAME.matches(n) || n in RESERVED) {
            log.d("event \"$name\" left out: letters, digits, spaces and _ . : - (at most 64); pv, en, ln, pageview and engagement are reserved")
            return
        }
        val u = screen
        if (u == null) {
            log.d("event \"$n\" left out: an event belongs to a screen; call screen() first")
            return
        }
        add(Event(n, at, u, view, props = clean(props)))
    }

    /** The utm_* of the deep link that opened the app, for the next screen view only. */
    fun open(utm: Map<String, String>) {
        if (enabled) this.utm = utm.ifEmpty { null }
    }

    fun foreground(at: Long) {
        if (foreground) return
        foreground = true
        if (screen != null) since = at
    }

    /** Time on the current screen so far, then everything goes at once: the process may be frozen or ended soon. */
    fun background(at: Long) {
        if (!foreground) return
        foreground = false
        end(at)
        since = null
        pump(all = true)
    }

    /** Off drops what waits and forgets the screen: nothing of before goes out after it is switched back on. */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) return
        queue.clear()
        cancelTimer?.invoke()
        cancelTimer = null
        screen = null
        since = null
        utm = null
        failures = 0
        retryAt = 0
    }

    /** `en` for the current screen: whole seconds in the foreground, when it is at least one. */
    private fun end(at: Long) {
        val from = since ?: return
        val seconds = (at - from) / 1000
        if (seconds >= 1) add(Event(EN, at, null, view, seconds = seconds))
    }

    private fun add(e: Event) {
        queue.addLast(e)
        if (queue.size > MAX_QUEUE) {
            queue.removeFirst()
            log.d("more than $MAX_QUEUE events waiting: the oldest is left out")
        }
        pump(all = false)
    }

    /**
     * Sends what is due, oldest first and up to 20 per request, then plans the next send: when
     * 20 wait, 10 seconds after the first one waits, after the backoff when the last send
     * failed, or at once for [all] (the app went to the background).
     */
    private fun pump(all: Boolean) {
        cancelTimer?.invoke()
        cancelTimer = null
        var atOnce = all
        while (true) {
            val t = clock.now()
            queue.removeAll { age(it, t) > MAX_AGE_S }
            if (queue.isEmpty()) return
            val due = if (atOnce) t else maxOf(retryAt, if (queue.size >= BATCH) t else queue.first().at + DELAY_MS)
            if (due > t) {
                cancelTimer = scheduler.schedule(due - t) {
                    cancelTimer = null
                    pump(all = false)
                }
                return
            }
            if (!send(t)) atOnce = false
        }
    }

    /** One request with the oldest events; false when they stay to be tried again. */
    private fun send(now: Long): Boolean {
        val batch = queue.take(BATCH)
        val body = Json.encode(payload(batch, now))
        log.d("POST $body")
        val answer = try {
            transport.post(body)
        } catch (e: IOException) {
            return retry(now, "network error: ${e.message}")
        }
        val status = answer.status
        // 429, 5xx or anything unexpected (a proxy in the way): the events stay for the next try.
        if (status !in 200..299 && status !in SETUP_ERRORS) return retry(now, "$status")
        repeat(batch.size) { queue.removeFirst() }
        failures = 0
        retryAt = 0
        if (status in SETUP_ERRORS) {
            // Once per launch, also without debug: otherwise a wrong site or app id goes unnoticed.
            if (log.debug || !warned) log.w("$status: ${answer.error ?: "setup error"} ${answer.fix.orEmpty()}".trim())
            warned = true
        } else {
            log.d("answer $status")
        }
        return true
    }

    private fun retry(now: Long, why: String): Boolean {
        failures++
        val wait = BACKOFF_S[minOf(failures, BACKOFF_S.size) - 1]
        retryAt = now + wait * 1000
        log.d("$why: ${queue.size} events wait, next try in $wait s")
        return false
    }

    private fun payload(batch: List<Event>, now: Long) = linkedMapOf(
        "s" to site,
        "a" to device.app,
        "v" to device.version,
        "o" to device.os,
        "m" to device.kind,
        "w" to device.width,
        "l" to device.lang,
        "h" to device.model,
        "k" to launch,
        "e" to batch.map {
            linkedMapOf(
                "n" to it.name,
                "u" to it.screen,
                "i" to it.view,
                "q" to it.utm,
                "p" to it.props,
                "d" to it.seconds,
                "g" to age(it, now),
            )
        },
    )

    /** Whole seconds since the event; computed when the request leaves, so a retried event keeps its real age. */
    private fun age(e: Event, now: Long) = maxOf(0L, (now - e.at) / 1000)

    /** The properties the server keeps: keys [A-Za-z0-9_.-]{1,40}; strings (200 characters), finite numbers, booleans; 20 at most. */
    private fun clean(props: Map<String, Any?>): Map<String, Any>? {
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in props) {
            val value: Any? = when (v) {
                is String -> v.take(200)
                is Boolean -> v
                is Double -> v.takeIf { it.isFinite() }
                is Float -> v.takeIf { it.isFinite() }
                is Number -> v
                else -> null
            }
            if (value == null || !KEY.matches(k) || out.size == MAX_PROPS) {
                log.d("property \"$k\" left out: up to $MAX_PROPS, keys [A-Za-z0-9_.-], values a string, number or boolean")
                continue
            }
            out[k] = value
        }
        return out.ifEmpty { null }
    }

    private companion object {
        const val PV = "pv"
        const val EN = "en"
        val RESERVED = setOf(PV, EN, "pageview", "engagement", "ln")
        val NAME = Regex("[A-Za-z0-9_.: -]{1,64}")
        val KEY = Regex("[A-Za-z0-9_.-]{1,40}")
        const val MAX_SCREEN = 300
        const val MAX_PROPS = 20
        const val BATCH = 20
        const val DELAY_MS = 10_000L
        const val MAX_AGE_S = 3600L
        const val MAX_QUEUE = 200
        val BACKOFF_S = longArrayOf(10, 30, 60, 120, 240, 300)
    }
}
