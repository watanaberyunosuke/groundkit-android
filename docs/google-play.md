# Google Play Console answers

Draft answers for the GroundKit Play Console setup, based on the app as of 1.0.0
(versionCode 1). Check them again whenever the app gains a permission, network service
or account feature.

## 1. Store listing (Grow users > Store presence > Main store listing)

Default language: English (Australia), en-AU.

**App name** (30 max)

```
GroundKit
```

**Short description** (80 max, 75 used)

```
Flights, ramp weather, turnaround checklists and shift tools for ramp staff
```

**Full description** (4,000 max). Plain text, because Play does not render the `<b>` tags
used in the F-Droid listing.

```
GroundKit is for apron, ramp and cargo staff. It shows what is coming into and leaving your airport, the weather that matters on the ramp, and keeps track of turnarounds and your shift.

NOW
Airport local time and UTC, ramp weather alerts, wind, visibility, temperature and QNH, a live airspace map, the next arrivals and departures, and airside NOTAMs.

FLIGHTS
Arrivals inbound now with ETA, countdown and delay status, landed and on the ground, and flights due in the next 6 hours. Departures on the ground, due out, departed and coming up. Filter to the airlines you handle.

TURNAROUNDS
A checklist per flight: chocks, cones, GPU, holds, bags and cargo, fuelling, catering, cleaning, water, NOTOC (with dangerous goods only), loadsheet and pushback. One tap stamps the time. Bag and ULD counters, stand, registration, notes and a countdown to the target off-block time.

SHIFT
Time on shift and since your last break, water logged against a target that rises with the heat, fatigue checks from sleep and hours worked, and heat-strain warnings from heart rate. Steps, distance, active energy, heart rate, water and sleep are read from Health Connect, only for the types you allow, and never leave your phone. Handover notes for the next crew.

MAP AND BRIEFING
The airport layout from OpenStreetMap with runways, taxiways, stands, gates and service roads, search, and a route there by road. Decoded METAR and TAF, a 72-hour wind chart, 7-day weather, NOTAMs and 30-day traffic.

BUILT FOR OUTDOOR USE
Large type and touch targets, status shown as icon, word and colour, high-contrast light and dark themes, a theme that goes dark at sunset, and an optional keep-screen-on setting. Every download is kept on the device, so the app opens with the last data when there is no signal.

PRIVATE BY DESIGN
No ads, no analytics and no tracking. An account is optional and only syncs a few settings with the iPhone app and the web dashboard. Your location is used only while a map is on screen and is never stored or sent. GroundKit is open source.

ADVISORY ONLY
Weather alerts, fatigue and heat guidance are advisories. Local procedures, rosters and supervisors take precedence. The map is volunteer-mapped; follow the airport's charts, markings and airside driving rules. GroundKit is an independent project and is not affiliated with any airport or airline.

Flight, weather and NOTAM data come from OpenSky, adsb.lol, aviationweather.gov and Open-Meteo. Map tiles from Esri; airport layouts from OpenStreetMap contributors.
```

**Graphics**

| Asset | Requirement | Status |
|---|---|---|
| App icon | 512 × 512 PNG | Reuse `fastlane/metadata/android/en-US/images/icon.png` |
| Feature graphic | 1024 × 500 PNG or JPEG | Missing; needs making |
| Phone screenshots | 2 to 8; longer side no more than twice the shorter | Use `docs/play/phoneScreenshots/` (1200 × 2400). The F-Droid originals are 1078 × 2400 (2.23:1), which Play rejects; these copies pad each side with the screen's own edge colours (the map screen with its base colour). |

**Store settings**

| Field | Answer |
|---|---|
| App or game | App |
| Category | Productivity |
| Tags | Productivity, Weather, Maps (pick the closest offered) |
| Contact email | Required and shown publicly; an address you are happy to publish |
| Website | https://getgroundkit.com |
| Privacy policy | https://getgroundkit.com/privacy |

## 2. App content (Policy and programmes > App content)

| Declaration | Answer | Reason |
|---|---|---|
| Privacy policy | https://getgroundkit.com/privacy | Already published and matches the app (see section 5) |
| App access | All functionality is available without special access | The account is optional and only syncs settings |
| Ads | No, the app does not contain ads | No ad SDKs |
| Content rating | See section 4 | |
| Target audience | 18 and over only | A work tool for airport ground staff; matches "not directed at children" in the policy |
| News app | No | |
| Government app | No | |
| Financial features | None | |
| Health apps | Yes. Tick the activity and fitness (and workplace wellbeing, if offered) features | The Shift tab reads activity, heart rate, sleep and water |
| Health Connect permissions | Declare each permission (justifications below) | Required before release for any `android.permission.health.*` |
| Advertising ID | No | The app does not use the advertising ID |
| Data safety | See section 3 | |

**Health Connect permission justifications** (one per data type in the declaration form)

| Permission | Justification |
|---|---|
| READ_STEPS | Shows steps taken during the current shift on the Shift screen, so ground staff can see their workload. |
| READ_DISTANCE | Shows distance walked during the current shift on the Shift screen. |
| READ_ACTIVE_CALORIES_BURNED | Shows active energy during the shift and raises the water target on heavy shifts. |
| READ_HEART_RATE | Shows average and peak heart rate during the shift and warns of heat strain when heart rate stays high in hot conditions. |
| READ_HYDRATION | Shows water drunk during the shift against a target that rises with the heat. |
| WRITE_HYDRATION | Saves the water the user logs with the app's water buttons, so it appears in Health Connect with their other water records. |
| READ_SLEEP | Reads sleep in the 48 hours before a shift for the fatigue check. |

Shared statement for the form: health data is read only to show figures and guidance in
the app. It is processed only on the device, never sent to GroundKit or any third party,
and never used for advertising.

## 3. Data safety

Under Google's rules, data processed only on the device is not "collected". So location
and health data are not declared, and handing data to service providers that work for you
(Supabase, Vercel) is not "sharing".

**Overview**

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes (every endpoint is HTTPS) |
| Which account creation methods does your app support? | Username and password (email and password); OAuth (Google) |
| Delete account URL | https://getgroundkit.com/privacy (the Accounts section gives the steps) |
| Can users request deletion of some data without deleting their account? | No |

**Data types**

| Category / type | Collected | Shared | Ephemeral | Required or optional | Purposes |
|---|---|---|---|---|---|
| Personal info / Email address | Yes | No | No | Optional | Account management, App functionality |
| Personal info / Name | Yes | No | No | Optional | Account management |
| Personal info / User IDs | Yes | No | No | Optional | Account management, App functionality |
| App activity / Other actions | Yes | No | No | Optional | App functionality |

"Other actions" covers the synced settings (home airport, appearance, keep screen on, wind
limits, dashboard layout and home clock). This is a judgement call; there is no closer
type.

**Not declared**, with the reason:

| Type | Why not |
|---|---|
| Location (precise, approximate) | Used only on the device while a map is open; routing runs on the device |
| Health and fitness | Read from Health Connect and processed only on the device |
| Turnarounds, shifts, handover notes | Stored only on the device (and in Android's own backup) |
| Device or other IDs | None read |
| App info and performance (crash logs, diagnostics) | No crash reporting or analytics |

Server IP addresses that Vercel, Supabase, Esri and the Overpass servers see in their logs
are not collected by the app for any purpose, and the privacy policy already covers them.

## 4. Content rating (IARC questionnaire)

| Question | Answer |
|---|---|
| Category | All other app types |
| Violence, fear, sexuality, language, controlled substances, crude humour | No to all |
| Gambling or simulated gambling | No |
| Can users interact or exchange content with each other? | No (handover notes stay on the device) |
| Does the app share the user's current location with other users? | No |
| Does the app allow users to buy digital goods? | No |
| Is the app a web browser or search engine? | No |
| Is the app primarily a news or educational product? | No |

Expected rating: Everyone, PEGI 3, ACB G.

## 5. Privacy policy

https://getgroundkit.com/privacy already covers accounts, Health Connect, location,
on-device records, network requests and children, and matches the code. Changes worth
making before Play review:

1. **Add an email contact.** At present the only contact is "Questions can be raised on
   GitHub". A GitHub issue is public, which is not a good place for someone's privacy
   request, and Play expects a privacy contact. Suggested wording for "Changes and contact":

   > Questions or requests about your data: email <address>. Bugs and feature requests
   > can be raised on GitHub.

2. **Name the Overpass servers.** The Android app falls back through overpass-api.de,
   overpass.private.coffee and maps.mail.ru (run by VK in Russia). They see the device's IP
   address and the airport requested. Suggested replacement for that bullet:

   > OpenStreetMap Overpass servers (overpass-api.de, with overpass.private.coffee and
   > maps.mail.ru as fallbacks) for airport layouts, downloaded once per airport, and Apple
   > Maps (iPhone and iPad) or Esri map tiles (Android) for the base map.

   Alternatively, remove the mail.ru fallback from `AirportLayouts.kt`.

3. **Add a Health Connect statement** under "Health data" (Google's Health Connect policy
   looks for it):

   > GroundKit's use of information received from Health Connect adheres to the Health
   > Connect Permissions policy, including the Limited Use requirements.

4. **Optional: a deletion anchor.** Give the Accounts heading an id (for example
   `#delete-account`) and use that as the Play delete-account URL, so reviewers land on
   the steps.
