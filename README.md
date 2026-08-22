# PrivacyMask

Xposed module (libxposed API 102) that feeds hooked apps a fake, user-configurable identity
instead of the device's real one.

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
- Lets you generate a random identity or edit one by hand, and optionally force-stops target
  packages via root so they pick up a change immediately.

## What it doesn't do

- Doesn't touch network traffic — your real IP is still visible to anything doing
  IP-based geolocation. Pair with a VPN exiting in the country you're spoofing for that.
- Doesn't cover native (NDK) code or APIs outside the list above.
- Doesn't defeat root/hook detection — some banking/anti-fraud apps refuse to run at all on a
  rooted, hooked device, independent of anything this module does.
- Doesn't choose target apps itself — that's the framework Manager's scope screen; the
  in-app "extra filter" field is only a subset filter on top of it.

## Known limitations

- Requires a framework that advertises `PROP_CAP_REMOTE` (remote preferences support).
  Without it, `getRemotePreferences()` throws `UnsupportedOperationException`.
- Hooks apply per-process; a process already running before you change the config needs a
  restart (force-stop, or the Apply button's root-based auto-restart) to pick up new values.
- Requires Vector 2.2+ or another libxposed API-102-compatible framework, and root
  (Magisk/KernelSU with Zygisk/NeoZygisk).

## Responsible use

This is meant for protecting your own privacy on your own device — e.g. stopping ad/analytics
SDKs from fingerprinting your location. Using it to deceive services where your real location
or identity is a legal or contractual requirement (regional pricing, financial identity
verification, etc.) may violate those services' terms; that's on the user.
