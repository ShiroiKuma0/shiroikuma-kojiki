# Snooping panel — on-device DNS snoop detection & one-tap control

Fork feature brief. Self-contained; build it in this repo. No off-device
components (no hub, no server, no email). Target build variant:
**`fdroidFullRelease`**, applicationId `shiroikuma.kojiki`.

## Goal

On the phone, surface suspected telemetry/snooping DNS requests, show **which
app** made each and **when**, and let the user **block / allow / dismiss** in one
tap — all from local data.

## Why on-device (rationale, so it isn't re-litigated)

An earlier design routed phone DNS through the WG hub, classified domains there,
emailed a digest, and used a `kojiki://` deep link to bounce back to the phone.
Rejected: the hub only sees the client IP, never the app. The phone's `DnsLog`
table already records `uid`, `appName`, `packageName`, `queryStr`, `time`,
`isBlocked`, and `blockLists` per query — so attribution and blocking are both
local. The hub was pure overhead. Everything below lives in the app.

DNS resolution is unchanged. For the richest classification signal, the user
should keep **on-device blocklists** enabled with the privacy/tracker category on
(the classifier reads `DnsLog.blockLists`). The feature is resolver-agnostic.

## Build

1. **`SnoopClassifier`** (`app/src/main/.../service/` or `util/`) — input a
   `DnsLog`, output `{isSnoop, severity, category, reason}`. Signals OR'd:
   - `DnsLog.blockLists` non-empty / tracker category — strong positive.
   - Bundled matcher list from `assets/snoop_matchers.txt` (starter below).
   - Host regex: `metric|telemetry|analytic|tracking|crashlytic|sentry|beacon|collector|datacollect`.
   - **Hook it at the DnsLog insert path** (in `DnsLogTracker`, wherever
     `DnsLogRepository.insert(...)` runs) so classification is per-insert, never a
     full-table scan. On a positive, upsert a `SnoopEvent`.

2. **`SnoopEvent`** Room entity + DAO + repository (`app/src/main/.../database/`)
   — persistent, deduped by `(uid, domain)`. Fields: `uid`, `appName`,
   `packageName`, `domain`, `firstSeen`, `lastSeen`, `count`, `severity`,
   `category`, `lastBlocked: Boolean`, `dismissed: Boolean`. Outlives `DnsLogs`
   pruning (the retention win). Bump the DB version + add a migration following
   the existing ones in `app/src/main/.../database/AppDatabase.kt`.

3. **Snooping panel** (`app/src/full/.../ui/`) — Fragment or Activity listing
   `SnoopEvent`s, grouped by app by default (toggle to by-domain),
   severity-highlighted, with search/filter. Use the paging pattern from
   `DnsLogDAO.getDnsLogsByName(...)` (`PagingSource`). Per row:
   - App icon + name via `FirewallManager.getAppInfoByUid(uid)` +
     `Utilities.getIcon(ctx, packageName, appName)`.
   - Domain, last-seen + count, blocked/allowed state, severity badge.
   - Actions: **Block / Trust** — reuse `CustomDomainRulesBtmSheet(cd)`
     (already does block/trust/none; per-app via `cd.uid`, or global via
     `Constants.UID_EVERYBODY`); **Dismiss** (`dismissed=true`, persists);
     **Open app** → `AppInfoActivity` extra `INTENT_UID`; optional open
     per-app domain logs.
   - Add an entry point from the home dashboard.
   - Default one-tap block = **per-app**; global one level deeper.

4. **Optional daily notification** — a worker that counts new high-severity
   `SnoopEvent`s since last run and posts a notification that opens the panel
   (replaces the dropped email digest). Follow the repo's existing
   WorkManager/notification patterns.

## Verified hooks (paths + symbols)

- `app/src/main/java/com/celzero/bravedns/database/DnsLog.kt` — `uid`, `appName`,
  `packageName`, `queryStr`, `time`, `isBlocked`, `blockLists`.
- `app/src/main/java/com/celzero/bravedns/database/DnsLogDAO.kt` —
  `getDnsLogsByName(searchString): PagingSource<Int, DnsLog>` (LIKE on
  `queryStr`/`responseIps`/`appName`) — template for the panel query.
- `DnsLogRepository.kt` / `DnsLogTracker` — classifier hook point.
- `app/src/main/java/com/celzero/bravedns/service/DomainRulesManager.kt` —
  `suspend fun block(domain, uid, ips="", type: DomainType)`,
  `addDomainRule(d, status, type, uid)`; `Status{NONE=0,BLOCK=1,TRUST=2}`,
  `DomainType{DOMAIN=0,WILDCARD=1}`.
- `app/src/main/java/com/celzero/bravedns/util/Constants.kt` —
  `const val UID_EVERYBODY = -1000`.
- `app/src/main/java/com/celzero/bravedns/database/CustomDomain.kt` — constructor
  for the `cd` passed to the bottom sheet.
- `app/src/full/java/com/celzero/bravedns/ui/bottomsheet/CustomDomainRulesBtmSheet.kt`
  — reuse; `.show(fragmentManager, tag)`.
- `FirewallManager.getAppInfoByUid(uid)` / `getAppNamesByUid(uid)`;
  `Utilities.getIcon(ctx, packageName, appName)` / `getDefaultIcon(ctx)`
  (`app/src/main/.../util/Utilities.kt`).
- `app/src/full/java/com/celzero/bravedns/ui/activity/AppInfoActivity.kt` — launch
  with extra `INTENT_UID = "UID"`.
- New panel activity goes in `app/src/full/AndroidManifest.xml` (mimic an existing
  `<activity>` block). No deep-link/scheme needed — the panel is in-app.

## `assets/snoop_matchers.txt` (starter)

```
# Huawei / OEM telemetry
metrics*.hicloud.com
logbak*.hicloud.com
logservice*.hicloud.com
grs*.huawei.com
*.dbankcloud.*
*.hwcloudtest.cn
# Generic mobile analytics / crash / attribution SDKs
app-measurement.com
*.crashlytics.com
*.google-analytics.com
*.googletagmanager.com
*.appsflyer.com
*.adjust.com
*.branch.io
*.sentry.io
*.bugsnag.com
*.mixpanel.com
api.segment.io
*.amplitude.com
*.umeng.com
*.flurry.com
*.sensorsdata.*
graph.facebook.com
```

## Verify (end-to-end)

1. Build `fdroidFullRelease`, install on the phone (`shiroikuma.kojiki`).
2. Provoke a known telemetry lookup (a Huawei component resolving
   `metrics.data.hicloud.com`, or any app resolving `app-measurement.com`) →
   it appears in the panel with the right app, time, and non-trivial severity.
3. Tap **Block** (per-app and global) → a `CustomDomain` BLOCK rule is written
   and later resolutions show `isBlocked=true` in `DnsLog`.
4. Tap **Dismiss** → row hides and stays hidden after reopening.
5. Let `DnsLogs` prune / clear DNS logs → `SnoopEvent` history persists.
6. Optional: trigger the notification worker → it fires with the new-snoop count
   and opens the panel.
