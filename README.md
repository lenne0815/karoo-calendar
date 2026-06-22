# Karoo Calendar

Karoo Calendar is a native Hammerhead Karoo app and ride-field extension that shows a Google Calendar day agenda on the device.

The app reads a private Google Calendar iCal/ICS URL, normalizes events for the Karoo timezone, and keeps an app-private offline cache for today plus the next seven days. No calendar URL or calendar data is stored in this repository.

## Features

- Android launcher app for calendar URL setup, manual refresh, sync status, and today's agenda.
- Karoo graphical ride field `DATATYPE_CALENDAR_DAY` for an in-ride day view.
- Opportunistic sync on app open and while the ride field is visible.
- Offline display from the latest app-private cache.
- Ride-field sync indicator:
  - `SYNC HH:mm` for a fresh cache.
  - `STALE HH:mm` when the cache is older than 30 minutes.
  - `CACHE HH:mm` when the latest refresh failed but cached data is available.
  - `NO SYNC` before the first successful refresh.

## Calendar Setup

Use a Google Calendar private iCal URL:

1. Open Google Calendar settings for the target calendar.
2. Copy the private "Secret address in iCal format".
3. Paste it into Karoo Calendar on the device and tap `Save URL` or `Refresh`.

The app is read-only. It does not edit calendar events.

## Build

This project includes the Gradle wrapper.

```bash
./gradlew test assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected Karoo:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.lenne0815.karoocalendar -c android.intent.category.LAUNCHER 1
```

For debug builds only, the calendar URL can be seeded via an Activity extra:

```bash
adb shell am start \
  -n com.lenne0815.karoocalendar/.MainActivity \
  --es com.lenne0815.karoocalendar.DEBUG_ICS_URL "https://example.com/calendar.ics"
```

## Implementation Notes

- Package: `com.lenne0815.karoocalendar`
- Extension id: `karoo-calendar`
- Karoo field type: `DATATYPE_CALENDAR_DAY`
- Calendar cache window: 8 days, including today.
- The bundled `third_party/karoo-ext-lib` module is Hammerhead `karoo-ext` 1.1.8 source from the official release tarball, used to avoid requiring GitHub Packages credentials during local builds.

## Verification

Current checks:

```bash
./gradlew test assembleDebug
```

Native UI verification should be done on a physical Karoo with ADB screenshots and logcat.
