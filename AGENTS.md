# AGENTS.md — RevTale

Android app (Kotlin, minSdk 33, no Compose yet) that talks to the Bluetooth dash of connected motorcycles.
Package `io.github.revtale`. Build/test: `./gradlew testDebugUnitTest assembleDebug` (JDK 17+).

## Who owns what
- **Logic (do not rewrite without asking):** `BleService.kt` (foreground service: auto-connect, link, nav, ride
  recorder), `Protocol.kt` (frame format), `RideStats.kt` (analytics), `SessionLog.kt`. Their unit tests must stay green.
- **UI (yours to redesign):** `MainActivity.kt` is a plain programmatic View UI and can be replaced completely
  (Jetpack Compose + Material 3 is fine; add the dependencies you need).

## Service API the UI uses (`BleService.instance`, null until the service runs)
- State: `gattState` ("idle" | "connecting" | "connected" | "disconnected"), `autoMode`, `linkOn`, `bikeName()`,
  `selected`, `devices` (scan results: addr, name, rssi, seen), `lastDecoded["5A10" | "5A11" | "5A18" | "5A19"]` (hex of
  the decoded frame; read fields with `Protocol.u(bytes, pos1, len)`), `buttonChanges(n)`, `linkKey`.
- Connection: `BleService.startAuto(ctx)`, `startSession(scan)`, `stopSession()`, `startScan()`, `stopScan()`,
  `select(addr)`, `connect()`, `disconnect()`.
- Cluster: `startPhoneLink(name)`, `stopPhoneLink()`, `sendTextSlot(id, text)`, `showClaude()`, `unreadSms`,
  `showNavigation(Protocol.NavStep, maneuver)`, `stopNavigation()`, `navDemo()`.
- Google Maps → cluster: `NavListener` (notification listener; the UI must offer a button that opens
  `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`), `lastNavInfo`, pref `mapsToCluster` (default on).
  Arrow test (parked only): `showArrowCode(code)` then `savePicto(Maneuver, code)`; `pictoOverrides()`.
  `Pictograms.CONFIRMED` maps every maneuver but the roundabout (filmed on the real cluster; 37 draws a house, so roundabouts go as text until calibration picks an arrow); "Head <compass>" shows the straight arrow
  (compass arrows 50–57 exist but read as "turn back" for south, so they are not used for it).
  Only UNKNOWN turns fall back to text on the custom line.
- Emulator (Developer → Emulator): `startSimulation()` / `stopSimulation()`, `simulating`, `simBike` (pretend bike:
  `auto`, `targetKmh`, `mode`), `virtualCluster.view` (what the app sent to the cluster). Frames from `SimBike` go through
  the same decode path as real notifications; writes go to `VirtualCluster` instead of GATT. Emulated rides aren't saved.
- Calls (`Calls.kt`, pref `callsToCluster`): the caller's contact name in slot 5B43, or the number when nothing of
  the name is drawable (`CallerText`); i switch (5A10 b12 = 4) declines while ringing; after a declined/missed call
  "CALL DECLINED" until i is let go, then "HOLD I: TEXT BACK" for 30 s; a long i press sends pref `callBackSms`; the cluster confirms via 5B4A b5.
  Emulator: `onCallState(...)` and `simBike.infoHeld`.
- Rides: `rides()` (CSV files, newest first), `loadRide(file)` → `RideSummary`, `RideStats.etaMinutes`, `RideStats.reachKm`.
- Custom line extras (all through `keepLine`, never while a call/text owns the line): weather before a route's first
  turn (`Weather.kt`, needs pref `weatherKey` from weatherunion.com; Open-Meteo fills in where it has no station; the
  destination comes from `Places.navigate` within 15 min, else "HERE"), ETA every other 8 s when the next turn is
  ≥ 2 km away (`ClusterLines.eta`), trip summary 10 s after the engine stops past 0.5 km (`ClusterLines.trip`).
- Calibration (`Calibration.kt`, `CalibrationScreen.kt`, pref `calibration`): the rider presses each switch and answers
  what the dash shows; `SwitchFinder` diffs 5A10/5A11 against a baseline. A calibrated i switch replaces 5A10 b12 = 4;
  `Calibration.fuelMax` replaces the fixed 6 bars. No calibration = the RTR 160 4V defaults.
- Places (`Places.kt`, pref `places`), parking (`Parking.kt`, saved in `rideStopped()`), bikes list (`Bikes.kt`, pref
  `bikes`; the active one stays in `bike`/`bikeName`), backup zip (`Backup.kt`), ride score / monthly report
  (`RideScore.kt`), GPX (`Gpx.kt`), share image (`RideCard.kt`), problem report (`BugReport.kt`), widget + quick tile
  (`HomeWidget.kt`), no-notification mode via companion-device presence (`BikePresence.kt`, pref `presenceMode`),
  update checks every 6 h without the service (`UpdateJob` in `Updates.kt`).
- Prefs (`BleService.prefs(ctx)`): `bike`, `bikeName`, `name`, `autoConnect`, `autoLink`, `recordRides`, `detailedLog`,
  `onboarded`, `weatherOnCluster`, `etaOnCluster`, `rideSummaryOnCluster`, `parkedAlert`.

## Live telemetry fields (1-based byte positions, see PROTOCOL.md)
`5A10`: speed b3, odometer b4–6 ÷10 km, fuel bars b7, top speed b10, throttle b11, RPM b17–18.
`5A11`: gear b6 (0 = N). `5A18`: battery volts b12 ÷10, engine temp b9 (°C, engine head: 71→145 °C in a 16-min city crawl),
intake air b8 (°C, ≈ outside air while the engine is cold). Rides record both (`Sample.engineC/airC`); `Heat.kt` finds
hot stretches (≥ `Heat.HOT`) and their likely cause (traffic / riding / weather / long ride). `5A19`: Trip A km b7/b10, avg speed b14/b15.

## Hard rules
1. Owner-bike lock: connect and write only to the saved bike (`bike` pref). Never auto-pick a nearby device.
2. Only display, info and navigation frames go to the bike. No vehicle control, settings or OTA.
3. No distracting UI or cluster animations while moving (speed > 0). The one exception the rider asked for: text longer
   than the custom line scrolls through it (`Marquee`, pref `scrollText`, default on), so nothing is cut mid-word.
4. Open source: no maker logos or official artwork, no vendor APK or decompiled code, no ride logs or GPS in the repo.
5. The cluster can only draw `A-Z 0-9 ! " / : ; < = > ? @ [ \ ] ^ _` `` ` `` and space. Use `Protocol.clusterText`.
