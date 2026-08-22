# PrivacyMask

Feeds apps a fake timezone, SIM/carrier, locale and GPS location so they can\'t fingerprint your real location or country.

## What it does

- Hooks `TelephonyManager` (SIM/network country ISO, MCC/MNC, carrier names, `getLine1Number`),
  `Location` / `LocationManager.getLastKnownLocation`, `TimeZone.getDefault()`, and
  `Locale.getDefault()` in every process the framework has scoped this module to.
- Reads the current fake identity from remote preferences
  (`XposedInterface#getRemotePreferences`, read-only on the hooked side); PrivacyMask's own UI
  writes to the same group via `XposedService#getRemotePreferences` (writable there).
- Applies hooks from `onPackageReady()`, before the target app's `Application.onCreate()` runs.
- Works immediately after install + a force-stop/reopen of the target app — PrivacyMask's own
  UI never needs to be opened first. If its remote preferences are still empty, hooks use the
  built-in defaults in `FakeConfig`.
- Lets you generate a random identity or edit one by hand — pick the fake location either by
  dragging a pin on the built-in map or by typing latitude/longitude directly, both on the same
  screen and kept in sync.
- Every hook group (telephony, subscription info, location, time zone, locale, system
  properties) has its own on/off switch in the UI, so you can fake only what you need.
- Saves and applies automatically as soon as anything changes — there's no separate Apply
  step. A process that's already running still needs you to force-stop and reopen it (or
  reboot) yourself to pick up the change; PrivacyMask never force-stops anything on its own
  and never needs or requests root for that.

## What it doesn't do

- Doesn't touch network traffic — your real IP is still visible to anything doing
  IP-based geolocation. Pair with a VPN exiting in the country you're spoofing for that.
- Doesn't cover native (NDK) code or APIs outside the list above.
- Doesn't defeat root/hook detection — some banking/anti-fraud apps refuse to run at all on a
  rooted, hooked device, independent of anything this module does.
- Doesn't choose target apps itself — that's entirely your framework Manager's scope screen
  (Vector/LSPosed); PrivacyMask has no package-selection UI of its own.
- Doesn't force-stop anything for you — after a change, already-running target apps need a
  manual force-stop + reopen (or reboot) from you to pick it up.
- Doesn't draw real coastlines on its location picker — the map is an offline
  latitude/longitude grid with a few reference cities for orientation, not map tiles (no
  internet permission, no Maps SDK, no API key). Good enough to drop a pin roughly where you
  want; use the numeric fields for precision.

## Known limitations

- Requires a framework that advertises `PROP_CAP_REMOTE` (remote preferences support).
  Without it, `getRemotePreferences()` throws `UnsupportedOperationException`.
- Hooks apply per-process; a process already running before you change the config needs a
  restart (manual force-stop + reopen, or a reboot) to pick up new values.
- Requires Vector 2.2+ or another libxposed API-102-compatible framework, and root
  (Magisk/KernelSU with Zygisk/NeoZygisk).

## Responsible use

This is meant for protecting your own privacy on your own device — e.g. stopping ad/analytics
SDKs from fingerprinting your location. Using it to deceive services where your real location
or identity is a legal or contractual requirement (regional pricing, financial identity
verification, etc.) may violate those services' terms; that's on the user.
