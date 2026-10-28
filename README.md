# Nvivio for Android

Cookieless EU analytics for your own Android app: screen views, the time on each screen and
custom events, without identifiers and without storing anything on the device, so the app needs
no consent prompt for it where your website needs no cookie banner. Android 6.0 (API 23) and later, Kotlin or Java, no dependencies besides
the Kotlin standard library, and only the `INTERNET` permission.

## Install

From [JitPack](https://jitpack.io/#montobleu/nvivio-android): add the repository once, in
`settings.gradle.kts`,

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

and the library to your app module:

```kotlin
dependencies {
    implementation("com.github.montobleu:nvivio-android:1.0.0")
}
```

A Java-only app also adds `org.jetbrains.kotlin:kotlin-stdlib`. R8 needs no extra rules.

In Nvivio, list the app's package name under **Settings › Parts › + App** of its site; requests
from an app the site does not list are refused (403).

## Start

Once, in your `Application`, so the SDK sees the first activity start:

```kotlin
class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Nvivio.start(this, "NV-6T78AE8")
    }
}
```

Options: `endpoint = "https://nviv.io/a"` (replaceable for tests and local development) and
`trackActivities = true` (every activity is a screen, named after its class: `CheckoutActivity`
is `Checkout`).

## Screens

```kotlin
Nvivio.screen("Checkout")    // in onResume, or LaunchedEffect(Unit) in Compose
```

Showing the screen that is already current sends nothing. The time on a screen counts only while
the app is in the foreground; it goes out before the next screen view and when the app goes to
the background. Use names, not data: an order number in a screen name makes every order a screen.

## Events

```kotlin
Nvivio.event("Purchase", mapOf("plan" to "pro", "qty" to 2, "gift" to true))
```

An event belongs to the current screen, so it is left out before the first `screen()`. Names:
letters, digits, spaces and `_ . : -`, at most 64 characters (`pv`, `en`, `ln`, `pageview` and
`engagement` are reserved). Up to 20 properties, keys `[A-Za-z0-9_.-]{1,40}`, values a string (at
most 200 characters), number or boolean.

From Java:

```java
Nvivio.start(this, "NV-6T78AE8");
Nvivio.screen("Checkout");
Nvivio.event("Purchase", Collections.singletonMap("plan", "pro"));
```

## Deep links

Pass the link that opened the app; its `utm_source`, `utm_medium`, `utm_campaign`, `utm_term`
and `utm_content` go with the next screen view:

```kotlin
intent.data?.let(Nvivio::open)    // in onCreate, and in onNewIntent for an activity that is reused
```

## Opting out

`Nvivio.isEnabled = false` drops what waits and sends nothing until it is `true` again. The SDK
stores nothing, so it does not remember this: keep the user's choice in your own settings and set
it on every start.

```kotlin
Nvivio.isEnabled = prefs.getBoolean("analytics", true)
```

## Debugging

`Nvivio.debug = true` logs every request and answer to Logcat, tag `Nvivio`. A setup error (400
invalid events, 403 app not listed, 404 unknown site) is logged once per launch also without it.

## What it sends

`POST https://nviv.io/a` with JSON and the user agent `Nvivio-Android/1.0.0`, in batches of at
most 20 events: when 20 wait, 10 seconds after the first one waits, and at once when the app
goes to the background. Every request repeats the package name, `versionName`, `Android 16`,
phone or tablet (`smallestScreenWidthDp` ≥ 600), the screen's shorter side in dp, the first
language, the device's maker and model (`Build.MANUFACTURER` and `Build.MODEL`) and a launch id:
16 random characters, made when the process starts and kept in memory only. Each event has its name, the screen, a random screen view id, properties, the seconds on
the screen, its age in seconds and the deep link's utm parameters.

Never: the advertising ID, Android ID, App Set ID, serial numbers, push tokens or user IDs. The
model is there to show which phones people use (a model shows once it has five visitors) and is
never part of how Nvivio counts visitors. Nothing is stored: no files, no SharedPreferences, no HTTP cache. At most 200 events
wait in memory, for at most an hour; after a network error, 429 or 5xx they are tried again (10 s,
30 s, 60 s… at most 5 minutes apart), after a 400, 403 or 404 they are dropped. The protocol is
in [`PROTOCOL.md`](PROTOCOL.md).

## Google Play Data safety

App activity › App interactions, Location › Approximate location and App info and performance ›
Diagnostics (the app's version, the Android version and the device model): collected for
Analytics, not shared, encrypted in transit; optional if the app offers an opt-out.

## Development

```sh
./gradlew :nvivio:testDebugUnitTest    # JVM unit tests of the SDK's rules
./gradlew :example:installDebug        # the example app, on an emulator
adb shell am start -a android.intent.action.VIEW -d "nvivio-example://products?utm_source=newsletter"
```

The example (`com.nvivio.example.android`, three screens and a Purchase event) sends to a local
ingest at `http://10.0.2.2:8797/a`, the computer that runs the emulator, for site `NV-9NR1XFQ`;
only that address may use plain HTTP.
