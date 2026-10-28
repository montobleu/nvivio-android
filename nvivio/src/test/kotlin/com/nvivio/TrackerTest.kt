package com.nvivio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class TrackerTest {
    private val t0 = 5_000_000L
    private var now = t0
    private var debug = false

    private class Timer(val at: Long, val task: () -> Unit)

    private class Request(val at: Long, val json: JSONObject) {
        val events: List<JSONObject> = json.getJSONArray("e").objects()
    }

    private val timers = mutableListOf<Timer>()
    private val requests = mutableListOf<Request>()
    private val warnings = mutableListOf<String>()

    /** What the server answers next, in order; 204 when none is lined up. */
    private val answers = ArrayDeque<Answer>()

    private val tracker = Tracker(
        site = "NV-6T78AE8",
        device = Device("com.acme.shop", "2.3.1", "Android 16", "phone", 412, "nl-BE", "Google|Pixel 8"),
        launch = "q7m2x9c4h1v8b3n5",
        clock = { now },
        transport = { body ->
            requests += Request(now, JSONObject(body))
            val answer = answers.removeFirstOrNull() ?: Answer(204)
            if (answer === OFFLINE) throw IOException("offline")
            answer
        },
        scheduler = { delayMs, task ->
            val timer = Timer(now + delayMs, task)
            timers += timer
            val cancel: () -> Unit = { timers.remove(timer) }
            cancel
        },
        log = object : Logger {
            override val debug get() = this@TrackerTest.debug
            override fun d(message: String) = Unit
            override fun w(message: String) {
                warnings += message
            }
        },
        random = Random(7),
    )

    private fun screen(name: String) = tracker.screen(name, now)

    private fun event(name: String, props: Map<String, Any?> = emptyMap()) = tracker.event(name, props, now)

    private fun background() = tracker.background(now)

    private fun foreground() = tracker.foreground(now)

    /** Moves the clock on, running the timers that come due on the way, in order. */
    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val timer = timers.filter { it.at <= end }.minByOrNull { it.at } ?: break
            timers.remove(timer)
            now = timer.at
            timer.task()
        }
        now = end
    }

    private val sent get() = requests.flatMap { it.events }

    /** "pv Home", "en 5", "Purchase Home": what an event is, in short. */
    private fun brief(events: List<JSONObject> = sent) = events.map { e ->
        listOfNotNull(e.getString("n"), e.optString("u").ifEmpty { null }, if (e.has("d")) e.getInt("d").toString() else null).joinToString(" ")
    }

    @Test
    fun aRequestCarriesEveryField() {
        tracker.open(mapOf("utm_source" to "newsletter"))
        screen("Checkout")
        advance(3_000)
        event("Purchase", mapOf("plan" to "pro", "qty" to 2, "gift" to true, "price" to 9.5))
        advance(1_000)
        background()

        val json = requests.single().json
        assertEquals(setOf("s", "a", "v", "o", "m", "w", "l", "h", "k", "e"), json.keys().asSequence().toSet())
        assertEquals("NV-6T78AE8", json.getString("s"))
        assertEquals("com.acme.shop", json.getString("a"))
        assertEquals("2.3.1", json.getString("v"))
        assertEquals("Android 16", json.getString("o"))
        assertEquals("phone", json.getString("m"))
        assertEquals(412, json.getInt("w"))
        assertEquals("nl-BE", json.getString("l"))
        assertEquals("Google|Pixel 8", json.getString("h"))
        assertEquals("q7m2x9c4h1v8b3n5", json.getString("k"))

        val (pv, purchase, en) = requests.single().events
        assertEquals(setOf("n", "u", "i", "q", "g"), pv.keys().asSequence().toSet())
        assertEquals("pv", pv.getString("n"))
        assertEquals("Checkout", pv.getString("u"))
        assertTrue(Regex("[a-z0-9]{8}").matches(pv.getString("i")))
        assertEquals("newsletter", pv.getJSONObject("q").getString("utm_source"))
        assertEquals(4, pv.getInt("g"))

        assertEquals(setOf("n", "u", "i", "p", "g"), purchase.keys().asSequence().toSet())
        assertEquals("Purchase", purchase.getString("n"))
        assertEquals("Checkout", purchase.getString("u"))
        assertEquals(pv.getString("i"), purchase.getString("i"))
        val p = purchase.getJSONObject("p")
        assertEquals("pro", p.getString("plan"))
        assertEquals(2, p.getInt("qty"))
        assertEquals(true, p.getBoolean("gift"))
        assertEquals(9.5, p.getDouble("price"), 0.0)
        assertEquals(1, purchase.getInt("g"))

        assertEquals(setOf("n", "i", "d", "g"), en.keys().asSequence().toSet())
        assertEquals("en", en.getString("n"))
        assertEquals(pv.getString("i"), en.getString("i"))
        assertEquals(4, en.getInt("d"))
        assertEquals(0, en.getInt("g"))
    }

    @Test
    fun twentyGoAtOnceFewerTenSecondsAfterTheFirstWaits() {
        screen("Home")
        repeat(18) { event("Tap", mapOf("n" to it)) }
        advance(9_999)
        assertEquals(0, requests.size)
        advance(1)
        assertEquals(19, requests.single().events.size)
        assertEquals(10, requests.single().events.first().getInt("g"))

        repeat(25) { event("Tap", mapOf("n" to it)) }
        assertEquals(2, requests.size)
        assertEquals((0 until 20).toList(), requests[1].events.map { it.getJSONObject("p").getInt("n") })
        advance(9_999)
        assertEquals(2, requests.size)
        advance(1)
        assertEquals((20 until 25).toList(), requests[2].events.map { it.getJSONObject("p").getInt("n") })
    }

    @Test
    fun enGoesBeforeTheNextScreenView() {
        screen("Home")
        advance(5_400)
        screen("Products")
        advance(10_000)

        assertEquals(listOf("pv Home", "en 5", "pv Products"), brief())
        val (home, en, products) = sent
        assertEquals(home.getString("i"), en.getString("i"))
        assertTrue(home.getString("i") != products.getString("i"))
    }

    @Test
    fun noEnUnderOneSecond() {
        screen("Home")
        advance(999)
        screen("Products")
        advance(500)
        background()

        assertEquals(listOf("pv Home", "pv Products"), brief())
    }

    @Test
    fun theBackgroundSendsEnAndFlushesAtOnceAndItsTimeDoesNotCount() {
        screen("Home")
        advance(5_000)
        background()
        assertEquals(listOf("pv Home", "en 5"), brief(requests.single().events))

        advance(100_000)
        foreground()
        advance(3_000)
        screen("Products")
        advance(10_000)
        assertEquals(listOf("en 3", "pv Products"), brief(requests[1].events))
        assertEquals(sent[0].getString("i"), requests[1].events[0].getString("i"))
    }

    @Test
    fun theCurrentScreenSendsNoSecondScreenView() {
        screen("Home")
        screen("Home")
        screen(" Home ")
        background()
        foreground()
        screen("Home")
        advance(10_000)

        assertEquals(listOf("pv Home"), brief())
    }

    @Test
    fun utmGoesWithTheNextScreenViewOnly() {
        tracker.open(mapOf("utm_source" to "newsletter", "utm_campaign" to "spring"))
        screen("Home")
        screen("Products")
        advance(10_000)

        val (home, products) = sent
        assertEquals("spring", home.getJSONObject("q").getString("utm_campaign"))
        assertFalse(products.has("q"))
    }

    @Test
    fun answer204IsDone() {
        screen("Home")
        advance(10_000)
        advance(3_600_000)

        assertEquals(1, requests.size)
        assertTrue(timers.isEmpty())
    }

    @Test
    fun aSetupErrorDropsTheBatchIsNeverRetriedAndIsLoggedOnce() {
        for (status in listOf(400, 403, 404)) {
            answers += Answer(status, "App \"com.acme.shop\" hoort niet bij deze site.", "Voeg de app toe.")
            screen("Screen $status")
            advance(10_000)
        }
        advance(3_600_000)

        assertEquals(3, requests.size)
        assertEquals(listOf("400: App \"com.acme.shop\" hoort niet bij deze site. Voeg de app toe."), warnings)
    }

    @Test
    fun withDebugEverySetupErrorIsLogged() {
        debug = true
        answers += Answer(403)
        answers += Answer(403)
        screen("Home")
        advance(10_000)
        screen("Products")
        advance(10_000)

        assertEquals(2, warnings.size)
    }

    @Test
    fun networkErrors429And5xxKeepTheEventsAndBackOff() {
        answers += listOf(OFFLINE, Answer(429), Answer(503), Answer(500), OFFLINE, Answer(502), Answer(504))
        screen("Home")
        advance(3_000_000)

        val tries = requests.map { (it.at - t0) / 1000 }
        assertEquals(listOf(10L, 20, 50, 110, 230, 470, 770, 1070), tries)
        // The same screen view every time, its age taken when the request leaves.
        assertEquals(tries, requests.map { it.events.single().getLong("g") })
        assertTrue(timers.isEmpty())
    }

    @Test
    fun theBackgroundSendsAtOnceAlsoWhileBackingOff() {
        answers += OFFLINE
        screen("Home")
        advance(12_000)
        background()

        assertEquals(listOf(10L, 12), requests.map { (it.at - t0) / 1000 })
    }

    @Test
    fun eventsOlderThanAnHourAreLeftOut() {
        answers += List(16) { OFFLINE }
        screen("Home")
        advance(3_600_000)
        assertEquals(16, requests.size)
        assertTrue(requests.all { it.events.single().getInt("g") <= 3600 })

        event("Purchase")
        advance(200_000)
        // The next try, at 3770 s: the screen view is too old by then, the event is not.
        assertEquals(listOf("Purchase Home"), brief(requests.last().events))
        assertEquals(170, requests.last().events.single().getInt("g"))
    }

    @Test
    fun atMost200EventsWaitTheOldestGoFirst() {
        answers += OFFLINE
        screen("Home")
        repeat(250) { event("Tap", mapOf("n" to it)) }
        advance(10_000)

        assertEquals(1 + 10, requests.size)
        assertEquals((50 until 250).toList(), requests.drop(1).flatMap { it.events }.map { it.getJSONObject("p").getInt("n") })
    }

    @Test
    fun disabledDropsWhatWaitsAndSendsNothing() {
        screen("Home")
        event("Tap")
        tracker.setEnabled(false)
        screen("Products")
        event("Tap")
        tracker.open(mapOf("utm_source" to "newsletter"))
        advance(5_000)
        background()
        foreground()
        advance(3_600_000)
        assertEquals(0, requests.size)
        assertTrue(timers.isEmpty())

        // Back on, nothing of before: the screen was forgotten, so it is a new screen view.
        tracker.setEnabled(true)
        screen("Products")
        advance(10_000)
        assertEquals(listOf("pv Products"), brief())
        assertFalse(sent.single().has("q"))
    }

    @Test
    fun anEventNeedsAScreenAndAValidName() {
        event("Early")
        screen("Home")
        for (name in listOf("pv", "en", "ln", "pageview", "engagement", "Käse", "a/b", "x".repeat(65), " ")) event(name)
        event("Sign up: step-1.ok")
        advance(10_000)

        assertEquals(listOf("pv Home", "Sign up: step-1.ok Home"), brief())
    }

    @Test
    fun propertiesAreCleaned() {
        screen("Home")
        val props = linkedMapOf<String, Any?>(
            "plan" to "pro",
            "bad key" to 1,
            "nan" to Double.NaN,
            "object" to Any(),
            "none" to null,
            "long" to "x".repeat(250),
        )
        for (i in 1..25) props["k$i"] = i
        event("Tap", props)
        advance(10_000)

        val p = sent[1].getJSONObject("p")
        assertEquals((listOf("plan", "long") + (1..18).map { "k$it" }).toSet(), p.keys().asSequence().toSet())
        assertEquals(200, p.getString("long").length)
    }

    @Test
    fun idsAreRandomLowercaseLettersAndDigits() {
        assertTrue(Regex("[a-z0-9]{16}").matches(randomId(16)))
        screen("Home")
        screen("Products")
        advance(10_000)
        val ids = sent.map { it.getString("i") }
        assertTrue(ids.all { Regex("[a-z0-9]{8}").matches(it) })
        assertEquals(2, ids.toSet().size)
    }

    private companion object {
        /** Lined up as an answer: the transport throws an IOException instead. */
        val OFFLINE = Answer(-1)

        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    }
}
