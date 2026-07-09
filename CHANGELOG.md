# Changelog — 白い熊 考直

Everything built on top of stock [RethinkDNS](https://github.com/celzero/rethink-app). Current base: the **`v0.5.5x`** upstream tag with its pinned firestack engine (`fd3dbcd769`).

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
