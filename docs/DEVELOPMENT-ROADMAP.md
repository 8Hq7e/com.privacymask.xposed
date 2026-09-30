# PrivacyMask Development Baseline & Roadmap

> Status: **Development baseline**
>
> Baseline date: **2026-09-30 (Asia/Shanghai)**
>
> Baseline source revision: **master @ 3997e0dbf8a724ae7c39f355b7f02be3e972bb56**
>
> Baseline app version: **1.2.3 / versionCode 6**
>
> This document is the controlling development plan for subsequent PrivacyMask work. When an
> implementation choice conflicts with this plan, update this document deliberately rather than
> silently drifting from it.

---

## 1. Purpose

PrivacyMask is intended to provide a **consistent virtual regional identity** to selected
applications without changing the real device-wide locale, time zone, location, SIM/carrier
identity, or related regional data.

The project is not considered complete merely because one browser fingerprinting page passes.
The supported target is:

> For every **declared-supported data path and process type**, all scoped applications should
> observe one internally consistent virtual regional identity, while unsupported paths are
> explicitly identified and observable instead of silently being treated as protected.

The primary reference environments are:

| Environment | Role |
|---|---|
| WSA / Android 13 / API 33 / x86_64 + KernelSU GKI 2.1.2 + LSPosed 2.2.0 | fast regression and Chromium validation |
| Xiaomi 15 Pro / Android 16 / API 36 / arm64-v8a + KernelSU LKM 3.3.0 + LSPosed 2.2.0 | real-device acceptance target |

KernelSU GKI vs LKM is an injection-environment difference, not a substitute for API coverage.
Both environments must be validated independently.

---

## 2. Coverage contract

Three different goals must not be conflated:

### Level A — common Android Java/API masking

Common Android framework APIs return the configured virtual identity.

Examples:

- locale / locale list;
- time zone;
- Android location;
- SIM / network country and operator;
- phone number where available;
- selected system properties.

This is achievable inside the current libxposed architecture.

### Level B — cross-API and cross-process consistency

Different supported APIs, SDKs and supported process types must agree with each other.

For example, if the configured identity is Los Angeles / United States:

```text
Locale                       en-US
Country                      US
Time zone                    America/Los_Angeles
SIM / network identity       configured US profile
Location                     configured Los Angeles position
Chromium Intl time zone      America/Los_Angeles
Chromium UTC offset          PDT/PST as appropriate
```

No supported interface should return a contradictory real value.

This is the main target for the 1.x roadmap.

### Level C — universal/native/network invisibility

This would require hiding the real identity from every possible Java, native, isolated-process,
external-process and server-side path.

Examples include:

- libc / native ICU;
- native system properties;
- NDK `AConfiguration`;
- external `getprop`;
- arbitrary native SDKs;
- processes not injected by the framework;
- public IP / IPv6 / WebRTC;
- account-region and historical server-side data.

**PrivacyMask 1.x does not promise Level C.** Native/Zygisk work is deferred until the Java/SDK
coverage contract is stable and measured.

---

## 3. Baseline evidence

At the 1.2.3 baseline:

### Confirmed working on WSA

The WSA Android 13 / x86_64 test environment has runtime evidence for:

```text
PrivacyMask module loaded in Chrome
Remote preferences available
Locale masking active
LocaleList masking active
Chromium TimeZoneMonitor discovered after split class loading
Obfuscated BroadcastReceiver discovered by type
America/Los_Angeles injected into Chromium native time-zone path
```

The browser test subsequently reported:

```text
Intl time zone              America/Los_Angeles
navigator.languages         en-US, en
Intl locale                 en-US
getTimezoneOffset           UTC-7  (during PDT)
```

This is evidence for the tested Chromium path only. It is not evidence that every API or every
App is covered.

### Not yet accepted

The following remain unverified or incomplete:

- Xiaomi 15 Pro / HyperOS Android 16 full runtime behavior;
- real SIM / dual-SIM behavior;
- real cellular tower information;
- real Wi-Fi environment;
- real GNSS/NMEA;
- arbitrary native/NDK consumers;
- generic isolated-process behavior;
- non-Chromium browser engines;
- complete long-duration stability.

---

## 4. Priority model

| Priority | Meaning |
|---|---|
| **P0** | safety / stability / false-success problem; fix before expanding scope |
| **P1** | confirmed or high-confidence real-data bypass; fix before normal Xiaomi use |
| **P2** | lifecycle, consistency, maintainability or observability problem |
| **P3** | native / isolated / advanced wireless expansion; determines ultimate coverage ceiling |

Implementation must follow this order unless a documented dependency requires otherwise.

---

# 5. Phase 0 — Freeze the 1.2.3 evidence baseline

Before additional behavior changes, preserve 1.2.3 as a known reference point.

## Required repository records

Create and maintain:

```text
docs/DEVELOPMENT-ROADMAP.md     <- this document
docs/COVERAGE-CONTRACT.md       <- supported/unsupported interfaces
docs/TEST-MATRIX.md             <- API/process/device verification matrix
docs/KNOWN-LIMITATIONS.md       <- externally visible limitations
```

The latter three may be introduced during subsequent phases, but their contents must ultimately
match this roadmap.

## Every tested release must record

```text
source commit
versionCode / versionName
release APK SHA-256
signing certificate SHA-256
LSPosed build/API level
device/ROM/API/ABI
PrivacyMask configVersion
test timestamp
```

Do not infer installed-binary identity merely from source HEAD.

---

# 6. Phase 1 — P0 safety and hook-state hardening

**Target version: 1.2.4**

**Implementation status:** implemented on `master` as 1.2.4 / versionCode 7. GitHub Actions
debug build #29 completed successfully. WSA Chrome regression subsequently passed: Chromium
reached `HOOKED`, the native time-zone update was sent successfully, and a non-target privileged
process observer expired at the 30-second bound as designed. Xiaomi and generic non-Chromium
runtime verification remain required before Phase 1 is fully accepted.

This phase must not expand the spoofing surface. Its job is to make existing behavior safer and
more deterministic.

## 6.1 Explicitly exclude system_server

Current critical-package exclusion must include the modern LSPosed system scope/package:

```text
system
```

The module must never attempt normal regional-identity hooks inside `system_server`.

Required exclusions must include at least:

```text
system
android
com.android.systemui
com.android.phone
com.android.settings
com.privacymask.xposed
```

**Acceptance**

- accidentally selecting System Framework does not result in PrivacyMask hooks inside
  `system_server`;
- logs clearly state the exclusion rather than reporting fake identity applied.

## 6.2 Replace the Chromium deferred-loader logic with an explicit state machine

The current Chromium compatibility path hooks `ClassLoader.loadClass()` while waiting for
`TimeZoneMonitor`. For Apps that never load Chromium, that observer can otherwise remain for
the process lifetime.

Required states:

```text
UNARMED
  -> candidate Chromium/WebView process detected
ARMED
  -> waiting for TimeZoneMonitor
HOOKED
  -> target hook installed and loader observer removed
FAILED / EXPIRED
  -> observer removed; failure visible
```

Requirements:

- arm only where Chromium/WebView evidence exists;
- bound the observer by lifetime and/or class-load count;
- installation returns a real success result / HookHandle;
- do not mark `installed=true` before the target hook actually exists;
- failure remains retryable where appropriate;
- all observer hooks are removed on success, failure or expiry.

**Acceptance**

- ordinary non-Chromium Apps do not retain permanent ClassLoader hooks;
- Chrome continues to pass the 1.2.3 time-zone regression test;
- logs distinguish `ARMED`, `HOOKED`, `FAILED`, `EXPIRED`.

## 6.3 Return independent TimeZone objects

Do not return one shared mutable template from:

```java
java.util.TimeZone.getDefault()
android.icu.util.TimeZone.getDefault()
```

Every call must receive an independent object consistent with Android/ICU semantics.

**Acceptance**

1. caller reads default time zone;
2. caller mutates returned object (`setID`, `setRawOffset`);
3. caller reads default again;
4. the second result is still the configured virtual zone.

Also test concurrent reads.

---

# 7. Phase 2 — Atomic configuration model

**Target version: 1.3.0**

**Implementation status:** implemented on `master` as 1.3.0 / versionCode 8. Schema v2 stores
identity fields, hook flags and configVersion as one authoritative JSON value. Legacy 1.2.x keys
are read only for one-time migration. Fresh installs use one deterministic default profile and
randomization is explicit-only. UI debounce is flushed on Activity stop; validation distinguishes
invalid input from persistence failure and surfaces consistency warnings. Runtime migration and
multi-process same-configVersion verification are still required before Phase 2 is accepted.

Configuration correctness is a privacy requirement. Different target processes must not obtain
mixed identities.

## 7.1 Replace field-by-field persistence with one validated snapshot

Introduce a versioned schema such as:

```json
{
  "schemaVersion": 2,
  "configVersion": 17,
  "identity": {
    "countryIso": "us",
    "mcc": "310",
    "mnc": "260",
    "simOperatorName": "T-Mobile US",
    "networkOperatorName": "T-Mobile US",
    "timezone": "America/Los_Angeles",
    "localeLanguageTag": "en-US",
    "phoneNumber": "...",
    "latitude": 34.05,
    "longitude": -118.24
  },
  "hooks": {
    "...": true
  }
}
```

The exact serialization may change, but the invariants must not:

- complete identity written atomically;
- configVersion belongs to the same snapshot;
- target process reads one complete snapshot;
- validation happens before the snapshot becomes active.

## 7.2 Stable initialization

Do not use:

```text
target App before UI -> hard-coded German fallback
first UI open         -> random identity
```

Instead:

```text
no saved config
-> one documented DEFAULT_PROFILE
```

Randomization occurs only after explicit user action.

## 7.3 Save lifecycle

The current debounce model must not lose a valid last edit when the Activity stops.

Requirements:

- pending legal changes are flushed at lifecycle exit;
- invalid changes remain unapplied and visible as invalid;
- save result clearly differentiates validation failure from persistence failure.

## 7.4 Validation

Validate the complete identity, including:

- finite latitude/longitude (`NaN` / infinity rejected);
- IANA time-zone semantics compatible across Java / ICU / supported engines;
- normalized BCP 47 locale representation;
- MCC/MNC shape;
- phone-format policy;
- cross-field consistency warnings.

Custom values remain allowed, but contradictory combinations should produce a warning.

---

# 8. Phase 3 — Locale and region completeness

**Target version: 1.4.0**

Current Locale coverage is useful but not yet internally complete on API 33+.

## 8.1 LocaleManager (Android 13+)

Support:

```java
LocaleManager.getSystemLocales()
```

Expected result: configured virtual system locale list.

Do **not** blindly overwrite App-specific language semantics.

`getApplicationLocales()` must preserve the distinction between:

- no App-specific override;
- an explicit App-specific override.

The virtual system identity and the App's own language preference are separate concepts.

## 8.2 Configuration consistency

Current `Configuration.getLocales()` masking is insufficient because Apps can also observe:

```java
Configuration.locale
Configuration.mcc
Configuration.mnc
Configuration.toString()
Configuration.writeToParcel()
```

Move toward a sanitized configuration-copy boundary rather than one isolated getter hook.

Desired invariant:

```text
getLocales()
locale
mcc
mnc
toString()
Parcel
```

all describe one consistent virtual configuration where that path is declared supported.

Do not mutate arbitrary App-owned Configuration objects without understanding their origin.

## 8.3 Settings locale path

Cover the readable system-locale setting where applicable:

```text
Settings.System["system_locales"]
```

Only the intended key should be substituted.

## 8.4 Locale regression matrix

Test at least:

- `Locale.getDefault()`;
- both Locale categories;
- `LocaleList.getDefault()`;
- `LocaleList.getAdjustedDefault()`;
- `LocaleManager.getSystemLocales()`;
- `Configuration.getLocales()`;
- `Configuration.locale/mcc/mnc`;
- Configuration parcel/string behavior;
- Chrome/WebView navigator language;
- real device system-language change after launch.

---

# 9. Phase 4 — Time-zone lifecycle completeness

**Target version: 1.4.x**

The Chromium initialization path works at the baseline, but initialization alone is not the
complete lifecycle.

## 9.1 Keep ordinary time-zone paths consistent

Supported Java paths include:

```text
java.util.TimeZone
android.icu.util.TimeZone
java.time / ZoneId
selected SystemProperties
```

Add cache/internal paths only where necessary and verified; avoid speculative hooks.

## 9.2 Chromium: rewrite subsequent real time-zone changes

Current architecture injects the fake zone when Chromium initializes.

Required behavior:

```text
real ACTION_TIMEZONE_CHANGED
      real zone ID
           |
           v
PrivacyMask rewrite
           |
           v
configured fake zone
           |
           v
Chromium native ICU
           |
           v
renderer / V8 / workers
```

All supported Chromium time-zone updates must remain pinned to the configured virtual zone.

## 9.3 Lifecycle tests

Test:

- cold start;
- warm start;
- background/foreground;
- real device time-zone change;
- automatic time-zone change;
- DST boundary behavior;
- renderer;
- dedicated worker;
- shared/service worker where available;
- WebView;
- newly created and already-created date/Intl objects where relevant.

Do not change UTC wall-clock time as a substitute for time-zone masking.

---

# 10. Phase 5 — Location redesign

**Target version: 1.5.0**

This is the largest architectural change in the 1.x roadmap.

## Problem with the current global getter model

Current hooks such as:

```java
Location.getLatitude()
Location.getLongitude()
Location.getAccuracy()
```

cannot distinguish:

- a real device-current-location object;
- an App-created destination;
- a saved route point;
- history data.

They can therefore both leak data and corrupt normal App behavior.

A real Location object may still contain real coordinates internally while getter hooks return
fake coordinates. Other methods/serialization can then disagree.

## Required design: sanitize at location ingress boundaries

Preferred model:

```text
system / provider location enters scoped App
              |
              v
clone Location
              |
              v
rewrite complete supported current-location state
              |
              v
deliver sanitized clone to App
```

Do not globally reinterpret every Location object in the process as "device current location".

## Priority ingress paths

Support and verify:

- `LocationManager.getLastKnownLocation()`;
- current-location APIs;
- `requestLocationUpdates` listener delivery;
- batched results;
- PendingIntent delivery where safely supportable;
- GMS/Fused results when corresponding classes are present.

For GMS/Fused, use optional/dynamic adapters rather than compile-time assumptions where possible.

## Object-consistency acceptance

For a sanitized device-location result:

```text
getLatitude/getLongitude
toString
Parcel round trip
distance/bearing calculations
accuracy state
```

must no longer reveal or use the real source coordinates.

For an App-created destination `Location`, PrivacyMask must not replace its coordinates with the
device identity location.

## Preserve API semantics

Do not convert:

```text
null -> fake Location
permission denied -> success
timeout -> immediate fake result
cancelled -> delivered result
provider unavailable -> available
```

Privacy masking must not destroy normal control-flow semantics.

## Raw GNSS/NMEA policy

Introduce a separate advanced policy.

Initial safe option:

```text
Raw GNSS / NMEA: BLOCK / UNAVAILABLE
```

Do not fabricate satellite physics until there is a well-defined and tested model.

---

# 11. Phase 6 — Telephony, cell, Wi-Fi and radio-location surface

**Target version: 1.6.0**

This phase is essential for Xiaomi acceptance because WSA cannot reproduce a real cellular and
GNSS environment.

## 11.1 Android 13+ phone-number APIs

Add and verify:

```java
SubscriptionManager.getPhoneNumber(int)
SubscriptionManager.getPhoneNumber(int, int)
```

where present.

## 11.2 ServiceState

Define supported behavior for:

```text
operator numeric
operator alpha long
operator alpha short
roaming-related identity where relevant
```

Listener/callback results must remain consistent with direct getters.

## 11.3 Carrier IDs

Evaluate and support as required:

```java
getSimCarrierId()
getSimCarrierIdName()
getSimSpecificCarrierId()
getSimSpecificCarrierIdName()
```

Do not invent unstable values without a documented profile model.

## 11.4 CellInfo / CellIdentity

Do not begin by fabricating plausible tower topology.

Preferred initial policy:

```text
Radio identity profile       -> configured
Cell-location details        -> unavailable / empty where API semantics permit
```

If fake cell topology is later added, MCC/MNC/TAC/CID/NCI/PCI and radio generation must be
internally coherent.

## 11.5 Wi-Fi

At minimum evaluate:

```text
WifiInfo.getSSID()
WifiInfo.getBSSID()
WifiManager.getConnectionInfo()
WifiManager.getScanResults()
```

Recommended privacy-first policy:

```text
SSID      -> stable configurable fake value or redacted
BSSID     -> stable locally-administered fake MAC
scan list -> empty/unavailable where semantically valid
```

The aim is preventing real BSSID-based geolocation, not simulating an entire real Los Angeles
Wi-Fi neighborhood.

## 11.6 BLE / RTT / additional radio surfaces

Treat Wi-Fi RTT, BLE scans and other proximity signals as explicit policy items. Do not claim
coverage until tested with the required permissions.

---

# 12. Phase 7 — Capability reporting and observability

**Target version: 1.7.0**

Protective failure behavior is useful for App stability, but a privacy module must not silently
fall back to real values while claiming success.

## Per-process CapabilityReport

Track at least:

```text
package
process name
configVersion
framework/API capability
hook install result
engine adapters discovered
engine adapters active
unsupported paths
failures
```

Example:

```text
com.android.chrome

Config version                 18

Locale.default                 ACTIVE
LocaleList                     ACTIVE
LocaleManager.system           ACTIVE
Configuration                  ACTIVE

TimeZone.java                  ACTIVE
TimeZone.ICU                   ACTIVE
Chromium.TimeZoneMonitor       ACTIVE
Chromium native update         ACTIVE

Location ingress              NOT USED
Telephony                      NO DATA / PERMISSION

Generic native timezone        UNSUPPORTED
Generic isolated process       UNVERIFIED
```

UI wording must distinguish:

- service connected;
- configuration saved;
- target process seen;
- hook installed;
- hook actually hit;
- unsupported;
- failed.

Do not use one generic "Applied fake identity" line as proof of complete protection.

---

# 13. Phase 8 — Probe App and test infrastructure

Test infrastructure should evolve in parallel with development, not after features are finished.

## 13.1 Probe App

Add a dedicated test application that produces structured results.

Suggested probes:

```json
{
  "locale.default": "...",
  "locale.display": "...",
  "locale.format": "...",
  "localeList.default": "...",
  "localeManager.system": "...",
  "configuration.locales": "...",
  "configuration.locale": "...",
  "configuration.mcc": "...",
  "configuration.mnc": "...",
  "timezone.java": "...",
  "timezone.icu": "...",
  "timezone.zoneId": "...",
  "location.getter": "...",
  "location.toString": "...",
  "location.parcel": "...",
  "subscription.phone": "...",
  "serviceState.operator": "...",
  "cellInfo": "...",
  "wifi.ssid": "...",
  "wifi.bssid": "..."
}
```

The probe should include:

```text
main process
normal :remote process
service process
isolatedProcess test
WebView
native/JNI probe
```

## 13.2 Test layers

### L1 — JVM / unit tests

Test:

- config parsing and migrations;
- atomic snapshot validation;
- profile validation;
- locale tag normalization;
- phone profile logic;
- consistency rules.

### L2 — Android emulator / automated instrumentation

At minimum:

- API 33 x86_64;
- API 36-compatible test environment when practical.

This layer is for regression, not final hardware acceptance.

### L3 — target-device acceptance

Run the same signed APK and configuration on:

- WSA;
- Xiaomi 15 Pro.

The physical Xiaomi is mandatory for:

- SIM;
- dual-SIM if applicable;
- real cell data;
- Wi-Fi;
- GNSS/NMEA;
- HyperOS behavior;
- arm64-specific native work.

---

# 14. Phase 9 — Optional native/Zygisk expansion

**Target version: 2.0.0-exp, later 2.0.0**

Do not begin this phase merely because root is already present.

Enter this phase only after:

1. Java/SDK coverage is stable;
2. probe tests identify native paths as the remaining material bypass;
3. required behavior is documented.

Potential targets:

```text
native system properties
libc time-zone processing
tzset / localtime / localtime_r
native ICU default time zone
NDK AConfiguration language/country
native/isolated execution contexts
```

Requirements:

- feature flag: `Native masking [Experimental]`;
- separate x86_64 and arm64-v8a validation;
- crash containment and rollback;
- no assumption that native masking solves network/server-side information.

Native work is not a license to hook system_server broadly.

---

# 15. Version roadmap

| Version | Scope | Exit condition |
|---|---|---|
| **1.2.3** | evidence baseline; working WSA Chromium Locale/TZ path | frozen reference |
| **1.2.4** | P0 safety, system exclusion, Chromium loader state machine, TimeZone clone semantics | Chrome regression passes; no permanent loader hook in ordinary Apps |
| **1.3.0** | atomic config snapshot, stable initialization, lifecycle-safe save, validation | multiple processes read one complete configVersion |
| **1.4.0** | LocaleManager, Configuration consistency, system locale path, time-zone lifecycle | Java + Chrome + WebView Locale/TZ matrix passes |
| **1.5.0** | Location ingress redesign | sanitized Location is internally consistent; App-owned Locations unaffected |
| **1.6.0** | phone-number APIs, ServiceState, carrier, Cell policy, Wi-Fi, GNSS policy | Xiaomi real-radio acceptance passes |
| **1.7.0** | CapabilityReport, probe App, process/engine matrix, CI gates | two-device supported-path regression is reproducible |
| **2.0.0-exp** | optional native/Zygisk masking | x86_64 + arm64 native probes pass with feature disabled by default |
| **2.0.0** | stable published coverage contract | every declared-supported path has repeatable evidence |

Versions may be split into patch/minor releases as implementation requires, but dependency order and
exit conditions must be preserved.

---

# 16. Required implementation order

The preferred development sequence is:

```text
1. P0 safety / hook-state correctness
2. Atomic configuration / validation
3. Locale + time-zone lifecycle
4. Location redesign
5. Telephony + Cell + Wi-Fi + GNSS policy
6. Capability reporting + probe/testing infrastructure
7. Native/Zygisk only if evidence justifies it
```

Do not skip directly from browser success to native hooking while known Java/API contradictions
remain.

---

# 17. Release gates

A release that claims a data channel is supported must satisfy all applicable gates.

## Consistency

The same signed APK + same configVersion must produce the same virtual identity on all supported
interfaces.

## No supported-path real-value escape

For supported paths, tests must verify that the real value is not observable via:

- alternate getters;
- object fields where in scope;
- string representation;
- Parcel/serialization;
- callbacks/events;
- lifecycle changes.

## Semantic preservation

Masking must not unnecessarily alter:

- permission failure;
- null/no-data state;
- cancellation;
- timeout;
- provider availability;
- App-owned data objects.

## Lifecycle

Verify:

- cold/warm startup;
- process recreation;
- background/foreground;
- configuration changes;
- system locale/time-zone changes;
- module configuration changes.

## Failure observability

If a hook is not installed or a path is unsupported, this must be visible in capability status.
A privacy-sensitive failure must not be represented merely as success because the App did not
crash.

---

# 18. Two-device acceptance matrix

Every major release must use the same signed APK build and as nearly identical a PrivacyMask
configuration as possible.

| Test family | WSA API 33 x86_64 | Xiaomi API 36 arm64 |
|---|---:|---:|
| Locale Java APIs | required | required |
| LocaleManager | required | required |
| Configuration | required | required |
| Java/ICU/ZoneId time zone | required | required |
| Chrome Intl/offset | required | required |
| WebView | required | required |
| time-zone change lifecycle | required | required |
| basic Location | required | required |
| Location object consistency | required | required |
| App-owned Location preservation | required | required |
| phone / SubscriptionManager | limited/no-SIM | required |
| ServiceState | limited | required |
| CellInfo | limited | required |
| Wi-Fi | required where available | required |
| GNSS/NMEA | not representative | required |
| ordinary remote process | required | required |
| isolated process probe | required | required |
| native/JNI probe | required when phase enabled | required when phase enabled |
| stability / repeated restart | required | required |

WSA success must never be used as a substitute for Xiaomi radio/GNSS acceptance.

---

# 19. Known non-goals / explicit boundaries

Unless a later roadmap revision explicitly promotes them into supported scope, the following are
not guaranteed by PrivacyMask 1.x:

- public IP / IPv6 / WebRTC network exit;
- VPN exit consistency;
- account registration region;
- server-side historical location;
- payment/region records;
- Advertising ID / Android ID;
- IMEI/IMSI/ICCID beyond specifically supported telephony paths;
- device model/manufacturer/build fingerprint;
- ABI/CPU/hardware identity;
- sensor fingerprint;
- screen/display fingerprint;
- root/KernelSU/LSPosed detection;
- installed-App detection;
- arbitrary native/NDK access;
- execution environments not injected or indirectly controlled by a supported adapter.

These are separate fingerprinting domains.

---

# 20. Engineering rules

The following rules apply to future development:

1. **Prefer ingress sanitization over global getter substitution** for complex mutable objects.
2. **Do not expand system-process scope** to compensate for missing App-side coverage.
3. **Do not fabricate complex real-world radio data unless a coherent model exists.**
4. **Preserve Android API semantics** (permission/null/timeout/cancel/unavailable).
5. **Treat engine-private hooks as adapters**, not universal Android coverage.
6. **Make hook failure observable.**
7. **Bind every compatibility claim to an APK/version/device/process/API test.**
8. **Never infer Xiaomi acceptance from WSA success.**
9. **Keep native work optional until its risk/benefit is proven.**
10. **Update this roadmap intentionally when scope or architecture changes.**

---

# 21. Definition of Done for the 1.x line

PrivacyMask 1.x can be called a stable regional-identity masking implementation when:

- P0 safety items are closed;
- configuration is atomic and versioned;
- all declared Java/SDK regional-identity paths return one consistent identity;
- Location objects are sanitized without corrupting App-owned geographic objects;
- real Xiaomi SIM/cell/Wi-Fi/GNSS behavior has been tested against the declared policy;
- Chrome and WebView locale/time-zone lifecycle tests pass;
- supported process types are explicitly enumerated;
- unsupported/native/network paths are visible and documented;
- probe results are reproducible on WSA and Xiaomi with the same release artifact;
- no release claim depends solely on one browser fingerprint page.

The 1.x completion criterion is **measured, declared coverage with consistent semantics**, not an
absolute claim that no software or remote service can ever infer the real device region.
