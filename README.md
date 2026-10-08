# GroundKit (Android)

An Android app for apron, ramp and cargo staff, built on the same backend as
[motherduck-aviation-data-analysis](../motherduck-aviation-data-analysis): the MotherDuck
warehouse behind its Vercel API. The backend is used unchanged:

- `GET /api/tables/<schema>.<table>`: the allow-listed warehouse tables as Parquet (ZSTD), the same exports the web Dive loads into DuckDB-WASM;
- `GET /api/live/<icao>`: live aircraft within 500 NM (OpenSky, falling back to adsb.lol).

The base URL is `API_BASE` in `app/build.gradle.kts`.

The app was called Ramp Ops until October 2026. Its application ID changed with the name (now `com.harrydatahub.groundkit`), so GroundKit installs as a new app; shifts and notes kept by Ramp Ops on a phone are not carried over.

## What it shows

| Tab | For ramp staff |
|---|---|
| **Now** | Airport local time and UTC, ramp weather alerts, wind/visibility/temperature/QNH, a link to the airspace map, the next arrivals and departures, airside NOTAMs |
| **Arrivals** | Inbound now (ETA, countdown, delay status), landed and on the ground, regular flights coming up in the next 6 h, the last 3 h |
| **Departures** | On the ground and due out (late if past the usual time), departed, coming up, earlier |
| **Map** | *Airport*: the layout from OpenStreetMap (runways, taxiways with their letters, stands, gates, holding points, aprons, terminals, cargo buildings, service roads), your position, search for a gate, stand, taxiway or building, and a route there by road. *Airspace*: live traffic, as below |
| **Briefing** | Decoded METAR plus raw METAR and TAF, 72 h wind chart, 7-day weather for every airport, NOTAMs (airside filter, search), 30-day traffic |
| **Turns** | A turnaround checklist per flight, as in the iOS app: chocks, cones, GPU, holds, bags and cargo off, fuelling, catering, cleaning, water, bags and cargo loaded, NOTOC (with dangerous goods only), loadsheet, holds closed, GPU off, chocks off, pushback. One tap stamps the time, tap again to undo. Bag and ULD counters, stand, registration, notes, and a countdown to the target off-block time. Start one from any flight's details, or add one |
| **Shift** | Time on shift and since the last logged break, water logged against a target that rises with the heat, fatigue (sleep before the shift, rest, hours this week), heat-strain warnings from heart rate, and from Health Connect: steps, distance, active energy and heart rate. A summary when the shift ends. Handover notes for the next crew, recent shifts |

The **airspace map** opens from Now and from any flight, or from the Map tab: live aircraft coloured by delay status, observed arrival/departure paths, the 50 NM terminal area. Pinch to zoom, tap an aircraft for details.

Ramp-specific additions not in the web Dive:

- **Ramp weather alerts** from the latest METAR: thunderstorm or hail, gusts and high wind (thresholds set in Settings to match station limits), low visibility, freezing conditions, heat stress by heat index and cold by wind chill (the iOS app's thresholds), rain, plus a note when the METAR is old. They are advisories; local procedures take precedence.
- **Filters**: all flights or *My airlines* (the IATA or ICAO codes of the airlines you handle). There is no cargo-only filter, since passenger flights carry belly cargo too; flights the backend tags as freighters (`is_freighter` on the flight marts and `/api/live`, from its `cargo_operators` list) are tagged *Freighter* instead.
- **Built for outdoor use**: large type and touch targets, status shown as icon, word and colour, high-contrast light and dark themes in the GroundKit blues (navy in light, light blue in dark; amber is kept for caution only), an optional keep-screen-on setting, pull to refresh.
- **Themes**: *Auto* (the default) follows the phone's dark theme setting, including its own schedule. *Sunset* goes dark from sunset to sunrise at the selected airport, whatever the phone is set to, so a night shift goes dark on its own; sun times use the Astronomical Almanac's low-precision formulae (`domain/Solar.kt`, within a minute or two). *Light* and *Dark* are fixed.
- **Offline**: every table download is kept on the device. Without signal the app opens with the last data and says how old it is.

There are no airline schedules in the data, so, as in the Dive, direction, usual time and delay come from each callsign's last 30 days at the airport. ETA is ground speed to the 50 NM ring plus the airport's median time inside it.

## Shift and Health Connect

The Shift tab is the Android counterpart of the iOS app's Shift tab, with Health Connect in place of HealthKit:

- It reads steps, distance, active energy, heart rate and water since the shift started, and sleep from the 48 h before it, every 2 minutes while the tab is open, and saves the water logged with the +250 / +500 ml buttons. Only the types the user allows are read. `HealthRationaleActivity` is the privacy explanation Health Connect links to from its permission screen.
- Health Connect has no noise-exposure type, so there is no noise tile or 85 dB warning as on iOS; a standing hearing-protection reminder takes its place.
- Shifts and handover notes are kept in a file on the device (`ShiftStore`). iOS syncs them through iCloud; on Android they are restored by Auto Backup but do not sync between devices.
- The water target follows the iOS guidance: 300 ml an hour, 500 from a feels-like 27 °C, 750 from 32 °C. Guidance only, not medical advice.

## Airport map and location

- The layout comes from the [Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API) (OpenStreetMap, no key), not from the warehouse: first the aerodrome's outline for a box, then everything mapped in it. It is downloaded once per airport (HKG is about 2.7 MB), kept in `noBackupFilesDir/layouts`, and refreshed after 30 days; tap the attribution line to refresh it sooner. When the main server is busy the app tries two mirrors.
- Cargo buildings are those tagged as warehouses or named for cargo, freight, logistics, express, mail or the big integrators. Service roads (where tugs, dollies and other GSE drive) are drawn in orange.
- Routes are the shortest path along the mapped roads (`RoadGraph` in `domain/AirportLayout.kt`), keeping to one-way roads where it can, with an estimate at 25 km/h. They never use taxiways or runways. OSM is mapped by volunteers, so the app says to follow the airport's charts, markings and airside driving rules.
- Your position comes from the platform's location providers (no Play services), only while a map is on screen, and only after you allow it from the map's location button. It is not stored or sent anywhere.

## Turnarounds

- Kept in a file on the device (`TurnaroundStore`), like shifts: restored by Auto Backup, not synced between devices. iOS syncs them through iCloud.
- The target off-block time is entered as airport local time: today, or tomorrow when that is more than 12 h ago.
- Taps in the first 0.8 s after opening a turnaround are ignored, so the tap that opened it (or a gloved double tap) does not mark a step.

## Fatigue and heat strain

- **Sleep** is read from Health Connect for the 48 h before the shift (or before now, off shift), less any time marked awake. The checks are the prior sleep/wake model from ICAO's FRMS manual (Dawson and McCulloch): at least 5 h sleep in the 24 h before duty, 12 h in the 48 h before, and no longer awake than the sleep in those 48 h.
- **Rest and hours** come from the shifts on the device: under 11 h between shifts, or over 48 h worked in 7 days, gets a caution (the EU Working Time Directive's figures).
- **Heat strain**: when it feels like 27 °C or more, a heart rate staying over 180 minus your age for 5 minutes is a warning, and within 15 bpm of that a caution (NIOSH, 2016). Age is optional in Settings; without it, 40 is assumed. The lowest reading in the 5 minutes decides, so one spike does not count.
- **Breaks** are logged with a button; the break prompt shows after 2 hours without one. **Ending a shift** keeps a summary with it: time, breaks and the longest stretch without one, water against the target, steps, distance, active energy, average and peak heart rate, and sleep before it.
- All guidance, not medical advice: rosters, the employer's fatigue and heat procedures, and supervisors decide.

## How it is built

- Kotlin, Jetpack Compose, Material 3, one `AppViewModel`; minSdk 26.
- `data/parquet`: a small pure-Kotlin Parquet reader (Thrift footer, v1/v2 pages, PLAIN / dictionary / RLE / DELTA / BYTE_STREAM_SPLIT, ZSTD via zstd-jni, Snappy, GZIP). It covers the flat tables the API exports; nested columns are skipped.
- `domain`: the Dive's SQL and TypeScript logic ported to Kotlin: `FlightHistory` (usual times), `LiveTraffic` (direction, ETA, delay status, feed memory, boards), `AirportSnapshot`, plus `RampAlerts` and `Operators`.
- Polling runs only while the app is on screen: live positions every 2 minutes and tables every 10, matching the API's edge cache.
- The map draws Esri's grey canvas tiles (the same as the Dive, no key) on a Compose `Canvas`, with no map SDK.

## Build and test

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

The unit tests check:

- sunrise and sunset against published times for HKG and LHR, and polar night;
- the fatigue, rest, weekly-hours and heat-strain rules, breaks and the shift summary file;
- the OpenStreetMap layout parser, place search and road routes (including one-way roads);
- the Parquet reader, cell by cell, against DuckDB's reading of real API exports and of synthetic files covering every codec, page version and encoding (`app/src/test/resources/parquet/make_fixtures.py`);
- `FlightHistory` against the Dive's own `historyQ` SQL, run in DuckDB on HKG's real flights (`app/src/test/resources/history/make_history_fixture.py`);
- placement, ETA, delay bands, landing detection, boards, ramp alerts, operator filters and the live JSON;
- heat index and wind chill against the iOS app's test values, the water target, break prompts and the shift records file;
- turnaround steps (NOTOC with dangerous goods only), next step and progress, the off-block time across midnight, and the turnarounds file.

To regenerate the fixtures, run the scripts with the aviation project's Python environment (it has `duckdb`).
