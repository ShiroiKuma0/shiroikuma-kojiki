# Changelog — 白い熊 考直

Everything built on top of stock [RethinkDNS](https://github.com/celzero/rethink-app). Current base: the **`v0.5.5y`** upstream tag with its pinned firestack engine (`310d7bc603`) plus the fork’s DoH idle-pool patch.

## 0.5.5y+8

**Headless backup automation — 考直 joins the fleet-wide 保存復元 backup run.**

The app now implements the sister-app **state-export automation contract**, so one external automation task can back up every app on the phone in a single run, this one included, and collect a per-app ✓/✗ summary with individual sizes.

### The wire contract
- **Two new exported actions**, `shiroikuma.kojiki.action.EXPORT_STATE` and `…LIST_CATEGORIES`, handled by a new `StateExportReceiver`. `EXPORT_STATE` runs the existing category-ZIP export **headlessly** — no Activity, no interaction; `LIST_CATEGORIES` returns the exportable categories so a caller can render its own picker. Both action strings derive from `BuildConfig.APPLICATION_ID`, so they can never drift from the manifest's `${applicationId}` intent filters.
- **Extras** (all String, per the family contract): `token`, optional `path` (an absolute directory that overrides the app's own configured export directory), optional `items` (comma-separated category ids; absent = everything), optional `progress_action`, plus the reply trio `reply_action` / `reply_package` / `reply_id`.
- **Replies are a fresh broadcast** to the caller's package with `reply_id` echoed verbatim and `result` = `OK:<path>|<bytes>|<human size>|<n> categories`, `OK:` + the `id⇥label` lines, or `ERROR:<reason>` — exactly one terminal reply, guarded so an async success and a synchronous error can never both fire. No `ResultReceiver` / `PendingIntent` / `Messenger` and no reliance on the ordered-broadcast result: EMUI severs both between third-party apps, and `FLAG_INCLUDE_STOPPED_PACKAGES` is what lets a backgrounded caller hear the answer at all.
- **Distinct failures**, because they debug differently: `ERROR:automation disabled`, `ERROR:bad token`, `ERROR:no-directory`, `ERROR:no-storage-access`, `ERROR:unknown category in items: …`.
- **Progress in real counts, never a percentage** — while exporting, plain broadcasts carry a numbers-first display line (`区分 3/10 — Snoop tags`) plus structured `current` / `total` / `unit`, throttled to at most one per 500 ms with the final one always sent.

### Storage
`path` → the configured SAF export directory → `ERROR:no-directory`. Writing to a caller-supplied absolute directory needs Android's **All-Files-Access**, so the app now declares `MANAGE_EXTERNAL_STORAGE` and asks for it with a dialog the first time the automation switch is turned on. Without the grant nothing breaks: the export falls back to the configured export directory, or replies `ERROR:no-storage-access` when there is none.

### Token & switch
- **Reuses the app's existing automation token** (the same secret `SET_APP_RULE` / `SET_WG_STATE` already use) rather than adding a second one. Newly generated tokens are 24 `SecureRandom` bytes, hex-encoded.
- **Constant-time comparison** (`MessageDigest.isEqual`) now guards *all three* automation receivers, replacing the plain `!=` check.
- **A new master switch, default OFF** — nothing is reachable until it is turned on. Both the switch and the token are excluded from the export, so neither ever travels inside a backup ZIP.

### UI
Two rows inside the existing **Export / Import** section of the 白い熊 考直 UI page, directly below the Export / Import entry — deliberately not a section of its own, because a backup feature belongs where backup lives, in the same place in every sister app:
1. the **master switch**, with a one-line description (and, while All-Files-Access is missing, a tappable warning row that opens the system grant screen);
2. the **token row**, showing the secret abbreviated, copying it in full on tap, with **Regenerate** on the right behind a warning that pasted copies stop working.

### Backup file naming (breaking, deliberate)
Every backup this app writes — from the automation path **and** from the Export/Import panel — is now named:

```
shiroikuma-kojiki_<yyyy-MM-dd_HH-mm-ss>.zip
```

No version, no `-export` infix, no suffixes. 白い熊 keeps every app's backups in one folder, so they must sort and read uniformly across apps. The previous prefix is still recognised by the panel's "last export" line, so older backups stay visible; and it remains **one ZIP per export, always** — every category is an entry inside the single archive.

### Internals
`KojikiExport.export()` gained an `onProgress` callback and now writes categories in declaration order (so progress reads identically every run), and the export-directory helpers moved into `KojikiExport`. The panel and the receiver are therefore two thin callers of one export core, sharing one directory and one naming rule — no export logic is duplicated, and a headlessly-produced ZIP is a perfectly ordinary backup the panel can import.

## 0.5.5y+7

**Export/Import moved onto the UI page; the UI page restyled after the kxkb Keyboard UI; ArcaneChat-style dialog buttons.**

- **Export/Import relocated** from Settings (the old Backup & Restore position) to the **top of the 白い熊 考直 UI page**, as its own section: a bold underlined heading with a name + description row beneath it that opens the category sheet. The Settings row and its divider are gone.
- **kxkb-style page look** for the whole 白い熊 考直 UI page, matching the 白い熊 kxkb fork's Keyboard UI: section headers are 20 sp bold accent with a 2 dp underline exactly as wide as the title text; subgroup labels are 16 sp medium with a short 1.5 dp text-wide underline; sections are separated by thin neutral edge-to-edge hairlines. (Previously every underline stretched the full page width — a `MATCH_PARENT` rule inflating its wrap-content box — and headers were lighter-weight.)
- **Export/Import sheet action row** restyled ArcaneChat-style: fully-rounded **pill** outline buttons sized to their text, a new **Cancel** pill alone on the left (the sheet previously had no cancel button), and **Import + Export** grouped on the right (previously two half-width rectangles).

## 0.5.5y+3

**Complete 白い熊 考直 rebrand across all user-visible text.**

Every user-visible "Rethink" / "RethinkDNS" mention now reads **白い熊 考直** — triggered by the new-app-installed notification still announcing "Rethink blocked recently installed app".

- **All locales, all strings** (42 `values*/strings.xml` files, 1794 replacements): new-app single + bulk notifications, low-memory and accessibility notifications, the biometric "Unlock" prompt, VPN / always-on / lockdown dialogs, onboarding slides, settings descriptions, and notification-channel descriptions. Replacements are case-sensitive, so URLs (`rethinkdns.com`, `rethink-app`) and resource ids stay untouched.
- **49 locale `app_name` overrides dropped** — some locales still forced "rethink" or a translated app name; the untranslatable 白い熊 考直 defaults now apply everywhere.
- **66 transliterated brand names replaced** in locale prose (Hindi रीथिंक, Urdu ریتھینک, Tamil மறுபரிசீலனை, Arabic إعادة التفكير, …).
- A hardcoded brand string in `activity_checkout_proxy.xml` rebranded.

Deliberately kept: the About screen's upstream credit (under "白い熊 考直 is based on Rethink."), the contributors list, resolver/service names that name the external resolver (RDNS, RDNS Plus, "RethinkDNS Basic"), and all internal identifiers (intent keys, DB tables, log tags, the pcap folder).

## 0.5.5y+2

**Real fallback DNS so OS-excluded apps resolve on Huawei/EMUI.**

On Huawei/EMUI, an app set to **Exclude** (Android's OS-level VPN bypass) has its DNS misrouted by the platform to the VPN's advertised DNS servers. The fork only advertised the fake in-tunnel DNS IP (`10.111.222.3`), which an excluded app can't reach (it bypasses the tunnel) — so every lookup failed. Symptom: an excluded app (e.g. Jami) intermittently "not sending", with Huawei's per-app DNS-failure counter climbing live and the OEM's dns-cure/wifipro remediation then strangling connectivity. (Keeping the app in-tunnel via *Bypass Universal* fixed DNS but made firestack's DNS-ALG churn on the app's raw-IPv6 P2P traffic — ~82% CPU — so Exclude + this fix is the right answer for a P2P app.)

The fork now advertises a **real fallback resolver** (Quad9 `9.9.9.9` / `2620:fe::fe`, matching the usual DoH) as a secondary VPN DNS alongside the fake IP. It's trapped identically to the fake IP at every site (`addDnsServer4/6`, `addDnsRoute4/6`, `isVpnDns`, `getFakeDns`), so **in-tunnel apps' queries to it are still captured and answered via the DoH — no leak** — while an excluded app reaches the real resolver directly and resolves.

Validated on-device (Jami back on Exclude): kojiki dropped from 82% to idle with zero engine churn for the app; Jami's Huawei DNS-failure counter froze and Jami works; and no DNS leak — every in-tunnel domain resolved to a synthetic `100.64.0.0/10` ALG IP (the DoH path) with zero `9.9.9.9:53` egress.

## 0.5.5y+1

Rebased the entire fork onto the upstream **`v0.5.5y`** release tag (`VERSION_CODE 63`; 135 commits past `v0.5.5x`). All fork features carried over; the DoH idle-pool self-healing (watchdog + patched engine, see `0.5.5x+14`) is preserved on the new engine.

### Rebase adaptations
- **Patched engine on the new pin:** the DoH idle-pool fix (`IdleConnTimeout` 3m→10s + HTTP/2 PING health-checks) was cherry-picked onto firestack `310d7bc603` (the v0.5.5y pin) and rebuilt from source; wired via `firestackRepo=local`. The engine still exhibited the stock idle-pool bug, so the patch remains necessary.
- **Honest WireGuard status:** migrated to firestack’s Int proxy-status ids (upstream changed them from Long in v0.5.5y).
- **WireGuard restore-verify:** ported to v0.5.5y’s plaintext-config storage (reads back + parses each restored tunnel, dropping unreadable phantoms).
- **Startup crash-loop fix (uid collisions):** upstream now dedupes conflicting proxy-app-mapping rows transactionally, so the fork workaround was reduced to its two surviving improvements — read live DB rows (not the stale cache) and never let a mapping error crash the refresh loop.
- Retired `BackupRestoreBottomSheet` stays removed; upstream’s new `BASE_VERSION_CODE` build field is fed the fork version code.

## 0.5.5x+14

### Self-healing DNS — root cause, engine patch, watchdog

**The problem (diagnosed 2026-07-17, mechanism-proven):** stock firestack pools DoH connections for 3 minutes (`IdleConnTimeout`) with no HTTP/2 keep-alive health-check, while resolvers close idle DoH connections far sooner — measured from the same network: **Quad9 ≤ 30 s, Mullvad ≤ 90 s, Cloudflare > 240 s**. Every DNS lull beyond the resolver’s window left only dead connections in the pool; the next queries were written into them, hung ~10 s, and died as `unexpected EOF` / `http-status: 502` bursts — every few minutes, all day. Downstream, ALG mappings expired (killing established flows of *allowed* apps) and the OS’s own connectivity probes failed through the tunnel, flapping Android’s network validation device-wide — even VPN-*excluded* apps lost connectivity.

**Patched engine** (firestack branch `kojiki-doh-idle` on the `fd3dbcd769` pin):
- `IdleConnTimeout` 3 m → **10 s** — pooled connections never outlive any resolver’s idle window, so the corpse-pool state is structurally impossible on every resolver.
- `http2.ConfigureTransports` with `ReadIdleTimeout = 10 s` / `PingTimeout = 5 s` — half-dead HTTP/2 connections are detected by PING and evicted instead of eating a query.
- Wired via `firestackRepo=local` in `gradle.properties` (flatDir `app/libs/tun2socks.aar`, gitignored); one property flips back to the stock Maven engine.

**DNS watchdog** (`KojikiDnsWatchdog`, fed every DNS transaction):
- Rate-based detection: ≥ 8 upstream failures within a 3-minute sliding window (no consecutive-streak and no cached-flag gating — the wedge is bursty/partial, and the engine’s cacher stamps `cached=true` even on failures).
- First trigger: a **full Go-tunnel recycle** (a real `restartTunnel` with seamless fd hand-off — a plain link update keeps wedged Go transports and dead ICMP netstack endpoints alive), clearing wedged DoH transports and the `endpoint is in invalid state` ICMP wedge.
- Re-trigger within 15 minutes of a restart (with recent successes on record): **automatic failover to Google DoH** plus another recycle. 5-minute action cooldown; every action posts a notification.
- Watchdog telemetry bypasses the level-gated in-app logger (raw logcat lines, tag `KojikiDnsWatchdog`).

**Validation** (on-device, 33 min of worst-case idle-then-burst traffic on Quad9 — the resolver that wedged constantly on stock): **zero** corpse-pool failures; 50 server-side idle kills absorbed by the health-check before any query rode them; one rare HTTP/2 stream-reset incident self-healed by the watchdog in under a second. Quad9 is viable again as a privacy-first primary.

## 0.5.5x+5

### Major features

**Snooping panel — on-device DNS snoop detection**
- A new panel that classifies every DNS query on-device into three tiers of snooping (against the local blocklists) and shows which apps talk to trackers/telemetry, with one-tap blocking of a snooping domain.
- Per-domain **tags/categories**: create, assign, and manage your own labels for snoop domains; tags are included in Export/Import.
- Sorting and filtering (default newest-first), tap an app icon to filter the panel to that app, long-press the icon to open the app.
- Detection is **decoupled from DNS logging**: the classifier runs ahead of the logs-enabled gate, so switching query logging off no longer silences the panel.

**白い熊 考直 UI — configurable theme + global font**
- A new “Custom” app theme, now the **default**, with a “Customize → 白い熊 考直 UI” settings page.
- Full-ARGB colour pickers for background, accent, and text; a global font with configurable family, weight, and size, applied app-wide at runtime.
- Dialogs, bottom sheets, toasts, the home start button, filter chips, FABs, and toggle groups are all themed; accent-on-accent contrast is resolved per-widget (luminance-picked on-colours).
- Ships seeded with the black `#000000` / pure-yellow `#FFFF00` look; pure yellow is used everywhere (the material `#FFEB3B` was purged).
- Black/yellow launcher icon (yellow shield on black, enlarged foreground).

**Export / Import — replaces stock backup/restore**
- Category-based export/import (a ZIP of per-category JSON + font files); the Settings “Backup & Restore” row now opens it.
- Ten categories, each individually selectable: app settings · appearance + fonts · snoop tags · per-app firewall rules · custom domain rules · custom IP rules · WireGuard tunnels + per-app bindings · blocklist selection · DNS custom endpoints (DoH / DoT / DNSCrypt / DNS proxy / ODoH) · proxies (SOCKS5 / HTTP).
- Portable by construction: per-app firewall rules and WireGuard bindings key on **package name**, not uid; rules for apps not yet installed park in a pending store and apply automatically when the app is installed.
- Import correctness: the selected DNS endpoint is actually re-selected; WireGuard import replaces all mappings and re-applies per-app bindings and lockdown so they genuinely stick; imports are future-proof (unknown fields skipped, missing fields take entity defaults, rows upsert).
- Blocklist category exports the **selection + a portable stamp** (list metadata, not the ~100 MB data files), with a pre-restore guard when the on-device blocklist is not downloaded.

**Honest WireGuard status**
- A tunnel with traffic flowing is no longer read as “Failing”: status is derived from actual tunnel behaviour on the one-WG list, the multi-WG list, the config detail screen, **and the home-screen Proxy card**.

**Automation intents**
- `SetAppRuleReceiver`: an external broadcast sets a per-app firewall rule (bypass / exclude / isolate / none, wifi/data granularity), gated by a settings token.
- `SetWgStateReceiver`: an external broadcast enables or disables a named WireGuard tunnel, same token gate.

### WireGuard
- Per-tunnel **export** button on the config detail screen: writes a standard wg-quick `.conf` (after a plaintext-key warning) via SAF, re-importable anywhere — WireGuard configs become portable across installs and keystores.
- Hardened restore: after re-encryption the config is read back and verified; a failed verify is logged and the phantom DB row removed.

### Log tabs
- Tap a log row’s app icon to filter the tab by that app; long-press opens the app.
- Blocked/allowed visibility on log rows plus full density/divider theming under the Custom theme.

### Fixes
- **Startup crash loop**: a stale old-uid row alongside a current-uid row in the WG proxy-app mapping table crashed every launch (`UNIQUE constraint failed` in the startup refresh). The uid update now dedupes from the real DB rows first and can no longer crash the refresh loop; the fix self-heals the corrupt rows on first launch.
- **Blocklist download OOM**: upstream’s ray-id interceptor buffered the whole response body in memory, aborting large blocklist downloads; it now peeks a bounded prefix.
- Readable no-dim dialogs under the Custom theme; app icons are no longer tinted away.
- Honest fork versioning: `versionName` encodes commits-ahead-of-tag when tracking past a release (`<version>-<ahead>+<build>`, the `-<ahead>` dropped at `0`).

### Packaging & fork plumbing
- App id `shiroikuma.kojiki` (installs side-by-side with stock; the code namespace stays `com.celzero.bravedns`), launcher label **白い熊 考直**, page headers and the About screen rebranded with attribution to RethinkDNS.
- Tracks upstream’s **released tags** (currently `v0.5.5x`) with the tag’s pinned firestack engine — deliberately not `main`, whose reworked wgproxy engine regressed idle WireGuard relays.
- `buildFoss` Gradle task: assembles the signed, de-Googled `fdroidFullRelease`, copies the arm64-v8a APK to `~/tmp/`, auto-increments the build number; the F-Droid build gate is extended to cover it (Firebase plugins skipped, a dangling Firebase import removed).
- Fork versioning: `versionCode = <upstream code> * 10000 + <build>` with upstream’s per-ABI offset, so every fork build upgrades cleanly over the previous line.
- Upstream `FUNDING.yml` removed (the fork does not inherit funding links).
