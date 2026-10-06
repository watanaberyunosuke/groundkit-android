# Ramp Ops (Android)

An Android app for apron, ramp and cargo staff, built on the same backend as
[motherduck-aviation-data-analysis](../motherduck-aviation-data-analysis): the MotherDuck
warehouse behind its Vercel API. The backend is used unchanged:

- `GET /api/tables/<schema>.<table>`: the allow-listed warehouse tables as Parquet (ZSTD), the same exports the web Dive loads into DuckDB-WASM;
- `GET /api/live/<icao>`: live aircraft within 500 NM (OpenSky, falling back to adsb.lol).

The base URL is `API_BASE` in `app/build.gradle.kts`.

## What it shows

| Tab | For ramp staff |
|---|---|
| **Now** | Airport local time and UTC, ramp weather alerts, wind/visibility/temperature/QNH, a link to the airspace map, the next arrivals and departures, airside NOTAMs |
| **Arrivals** | Inbound now (ETA, countdown, delay status), landed and on the ground, regular flights coming up in the next 6 h, the last 3 h |
| **Departures** | On the ground and due out (late if past the usual time), departed, coming up, earlier |
| **Briefing** | Decoded METAR plus raw METAR and TAF, 72 h wind chart, 7-day weather for every airport, NOTAMs (airside filter, search), 30-day traffic |
| **Shift** | Time on shift with a break prompt every 2 hours, water logged against a target that rises with the heat, and from Health Connect: steps, distance, active energy and heart rate. Handover notes for the next crew, recent shifts |

The **map** opens from Now and from any flight: live aircraft coloured by delay status, observed arrival/departure paths, the 50 NM terminal area. Pinch to zoom, tap an aircraft for details.

Ramp-specific additions not in the web Dive:

- **Ramp weather alerts** from the latest METAR: thunderstorm or hail, gusts and high wind (thresholds set in Settings to match station limits), low visibility, freezing conditions, heat stress by heat index and cold by wind chill (the iOS app's thresholds), rain, plus a note when the METAR is old. They are advisories; local procedures take precedence.
- **Filters**: all flights, all-cargo operators (by ICAO designator), or *My airlines* (the IATA or ICAO codes of the airlines you handle).
- **Built for outdoor use**: large type and touch targets, status shown as icon, word and colour, a high-contrast light theme and a hi-vis dark theme, an optional keep-screen-on setting, pull to refresh.
- **Offline**: every table download is kept on the device. Without signal the app opens with the last data and says how old it is.

There are no airline schedules in the data, so, as in the Dive, direction, usual time and delay come from each callsign's last 30 days at the airport. ETA is ground speed to the 50 NM ring plus the airport's median time inside it.

## Shift and Health Connect

The Shift tab is the Android counterpart of the iOS app's Shift tab, with Health Connect in place of HealthKit:

- It reads steps, distance, active energy, heart rate and water since the shift started, every 2 minutes while the tab is open, and saves the water logged with the +250 / +500 ml buttons. Only the types the user allows are read. `HealthRationaleActivity` is the privacy explanation Health Connect links to from its permission screen.
- Health Connect has no noise-exposure type, so there is no noise tile or 85 dB warning as on iOS; a standing hearing-protection reminder takes its place.
- Shifts and handover notes are kept in a file on the device (`ShiftStore`). iOS syncs them through iCloud; on Android they are restored by Auto Backup but do not sync between devices.
- The water target follows the iOS guidance: 300 ml an hour, 500 from a feels-like 27 °C, 750 from 32 °C. Guidance only, not medical advice.

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

- the Parquet reader, cell by cell, against DuckDB's reading of real API exports and of synthetic files covering every codec, page version and encoding (`app/src/test/resources/parquet/make_fixtures.py`);
- `FlightHistory` against the Dive's own `historyQ` SQL, run in DuckDB on HKG's real flights (`app/src/test/resources/history/make_history_fixture.py`);
- placement, ETA, delay bands, landing detection, boards, ramp alerts, operator filters and the live JSON;
- heat index and wind chill against the iOS app's test values, the water target, break prompts and the shift records file.

To regenerate the fixtures, run the scripts with the aviation project's Python environment (it has `duckdb`).
