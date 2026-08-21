# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**白い熊 考直** — a personal fork of [RethinkDNS](https://github.com/celzero/rethink-app) (`celzero/rethink-app`),
an open-source DNS + firewall + userspace-WireGuard VPN app for Android. Written in Kotlin, Apache-2.0.
The Go data-plane (`celzero/firestack`) is a **separate** repo, consumed here as a prebuilt AAR.

This repository (`ShiroiKuma0/shiroikuma-kojiki`) is a fork. We track upstream (`celzero/rethink-app`)
and layer our own customizations on top of it.

## Fork Workflow — READ THIS FIRST

This is the most important section. The whole point of this repo is to maintain a small set of
customizations on top of upstream and rebuild as upstream releases new versions.

### Git remotes & branches

- `origin` → `git@github.com:ShiroiKuma0/shiroikuma-kojiki` — our fork (push here).
- `upstream` → `https://github.com/celzero/rethink-app` — the original (read-only, for rebasing).
- **`main`** mirrors upstream's `main`. We do **not** develop on it.
- **`custom`** is our development branch. **All our work lives here.** This is the default working branch.

Keep our changes **additive / in new files** wherever possible, to minimize rebase conflicts.

### Our customizations (what makes this a fork)

| What | Value | Where |
| --- | --- | --- |
| Installed app ID | `shiroikuma.kojiki` | `gradle.properties` → `APP_ID` |
| Code namespace | `com.celzero.bravedns` (unchanged from upstream) | `gradle.properties` → `APP_NAMESPACE` |
| App launcher label | `白い熊 考直` | `app_name` in `app/src/main/res/values/strings.xml` |
| Signing | reuses the denwa keystore | `keystore.properties` (gitignored) → `~/.android-keystores/shiroikuma-denwa.jks` (alias `denwa`) |
| Feature 1 | external intent to set a per-app firewall rule | `receiver/SetAppRuleReceiver.kt` (+ manifest, settings token) |
| Feature 1b | external intent to enable/disable a WireGuard tunnel | `receiver/SetWgStateReceiver.kt` (full source set; same token) |
| Feature 2 | honest WireGuard status — don’t read “Failing” while traffic still flows | `util/UIUtils.kt` (`honestWgStatusId`), consumed by `OneWgConfigAdapter` / `WgConfigAdapter` / `WgConfigDetailActivity` **and the home Proxy card** (`HomeScreenFragment.getProxyStatus`, keyed `home:`) (full) |
| Feature 3 | 白い熊 考直 UI — a **default** “Custom…” app theme with user-configurable colours (full ARGB: background/accent/text) + a global font (family/weight/size), via a new “Customize → 白い熊 考直 UI” settings page; 白い熊's exported look is seeded as the defaults; dialogs/bottom-sheets/toasts/start-button are themed too | `customui/` (`CustomUiConfig`, `CustomUi`, `ColorPickerDialog`) + `ui/activity/KojikiUiActivity.kt` + `Themes.CUSTOM` (default in `PersistentState.theme`) + `*_kojiki.xml` + `kojiki_toast*` (+ the runtime hook in `BaseActivity.onResume`, `MiscSettingsActivity`, full manifest) |
| Feature 5 | **Per-app notes** (apps view) — 白い熊 応用管理's notes, same operation: a glyph at the end of each row's label line (note glyph = has one, dim “+” = none), tap opens a pre-filled multi-line dialog, **saving blank deletes**, long-press = the note as a tooltip. Keyed by **package name** in its own prefs file (`kojiki_app_notes`) so Export/Import carries it with the generic prefs exporter | `customui/KojikiAppNotes.kt` + `ic_kojiki_note{,_add}.xml` + `list_item_firewall_app.xml` label row + `FirewallAppListAdapter.bindNote` (+ `firewall_app_note_iv` in `CustomUi.applyToTree`'s skip list — its tint carries state) |
| Feature 6 | **App groups / profiles** (apps view) — named sets of apps, in 応用管理's format: the row's **bottom line** carries a filled pill per group the app is in, then a trailing “+” pill. Tap a pill = filter the list to that group · long-press = drop the app from it · tap “+” = the membership checklist (with “New group…”) · long-press “+” = manage (rename/delete). The filter sheet gains a **Groups** chip section + a **Manage** action. Because the bulk-rule toolbar acts on whatever the filter selects, filtering to a group makes the toolbar “apply this rule to the whole group”. Members key on **package name**, never uid. Filtering is a **post-query** `PagingData.filter` (+ a plain list filter for the bulk path), so **no upstream DAO query is touched** — deliberate, for rebase safety | `customui/KojikiAppGroups.kt` (store + dialogs + pill view) + `AppListActivity.Filters.setGroups`/`refreshGroupPills` + `AppInfoViewModel.applyGroupFilter`/`inSelectedGroups` + `FirewallAppListAdapter.bindGroups` + `bottom_sheet_firewall_sort_filter.xml` + `FirewallAppFilterBottomSheet` |
| Feature 6e | **non-app rows** — the synthetic `no_package_<uid>` entries (`RefreshDatabase.insertUnknownApp`: root, `SYSTEM`, any uid whose traffic no package accounts for; the label comes from `AndroidUidConfig`, where upstream renamed `ROOT` → **`ANDROID`**, so “ANDROID” = uid 0). They **do** get notes and groups — they are the rows that most need a “do not block, DNS dies” annotation — even though their key is a uid in package clothing and therefore device-local. Export carries them plus a `__kojiki_nonapp_labels` map of the label each key had; **import prefixes a ⚠ marker note** naming what the key used to be (`markImportedNonApp`, idempotent on the ⚠), and a row that carried only group membership gets that note created, so nothing arrives silently. A **“Non-app” top-level filter** (`TopLevelFilter.NON_APP`) lists them; like the group filter it is a post-query filter on the package-name prefix, since `isSystemApp` does not identify them | `KojikiExport` (`withNonAppLabels` / `markImportedNonApp`) + `TopLevelFilter.NON_APP` + `AppInfoViewModel.applyRowFilters` + `FirewallAppFilterBottomSheet` |
| Feature 6c | **app-list row density** — two traps found together on 0.5.6+004. (1) `firewall_app_details_ll` was `layout_height="0dp"`, so the row's height came only from the 48dp toggles + `minHeight 72dp` and **any taller text was silently clipped** (the traffic line vanished mid-glyph); it is `wrap_content` now, so the row grows to its content. (2) `Widget.Rethink.MaterialCardView` sets `cardUseCompatPadding`, and that padding is reserved from **`maxCardElevation`, not the current elevation** — so zeroing `cardElevation` under the Custom theme left ~5dp of invisible shadow room above and below every flat card. `CustomUi.applyToTree` now also zeroes `maxCardElevation` and clears `useCompatPadding`, which is what actually tightens the list | `list_item_firewall_app.xml` + `CustomUi.applyToTree` (MaterialCardView branch) |
| Feature 6d | **bordered dialogs, app-wide.** `App.Dialog.NoDim` (the style behind ~144 `MaterialAlertDialogBuilder` call sites) pinned `android:windowBackground` to `@android:color/transparent`, so every no-dim dialog had **no surface at all** — the note dialog rendered see-through over the app list, with nothing for a border to sit on. **A Material alert cannot be given a border from a theme — do not try again.** Both levers were tried and both are wrong: `android:windowBackground` is replaced by `MaterialAlertDialogBuilder.create()` at show() time, so a bordered drawable set there is silently discarded (*verified with `aapt2 dump resources` on the shipped APK — the style carried the drawable, the dialog still came up borderless*); `android:background` is applied to the alert's title/content/button panels **individually**, rendering three stacked bordered boxes with clipped corners. A Material alert has no single outer surface to stroke. So the fork's dialogs draw their own: **`customui/KojikiDialog.kt`** — one rounded accent-bordered box on a transparent window, holding title + content + a right-aligned button row (`leading = true` puts one on the left), with the content area clamped to 55 % of screen height so a long list scrolls instead of pushing the buttons off. Helpers: `input` / `helper` / `checkbox` / `row` / `withAlpha`. Upstream's own dialogs stay Material and therefore stay borderless | `customui/KojikiDialog.kt` + `styles.xml` (`App.Dialog.NoDim`, reverted with a warning comment) |
| Feature 6b | the apps-view **filter sheet in black/yellow with an accent border** — the border goes on an **inset content box** (`fs_content_box`), never the full-width panel (side strokes at the screen edge are clipped; `CustomUi.themeBottomSheet` only flattens the panel). Contents are restyled by the new **`CustomUi.applyToDialogTree`** — the activity tree-walk never reaches a dialog's own window — re-run after every async chip rebuild. `BottomSheetDialogThemeKojikiCustom` also stopped being an empty extension: it now mirrors the activity theme's yellow palette, so **every** sheet reads black/yellow statically | `styles_kojiki.xml` + `CustomUi.applyToDialogTree` + `FirewallAppFilterBottomSheet.applyKojikiTheme` |
| Feature 6f | **app-list sort + the uid on every row.** The row's id line now leads with the **uid** (`10050 · yqtrack.app`), and the synthetic `no_package_<uid>` rows — which print no package id at all — show the uid alone instead of an empty line. Sorting is a header glyph beside the filter icon (**not** in the filter sheet: its chip sections already fill a folded screen) opening a `KojikiDialog` with four keys — app name · package id · **uid** · data used — where the active key is drawn in the accent with its arrow and **tapping it reverses** the direction; the choice persists in its own prefs file, and the fast-scroll bubble follows the key (a uid, a byte count) rather than always a letter. A paged list **cannot** be re-sorted in Kotlin (each page is fetched on its own), so the order had to become SQL: upstream's **six** paged queries (all/installed/system × with/without category, all hard-ordered by `lower(appName)`) collapse into one fork query, `AppInfoDAO.getSortedApps`, whose `ORDER BY` is bound by `sortKey`/`descending` through the SQLite CASE idiom (`lower(appName)` closes it as tie-breaker) and which takes the app type + a `noCategory` flag as parameters. **Its WHERE mirrors upstream's — re-check it on every rebase**, since nothing reads those six methods now. Icon trap: header glyphs are **stroke** drawings (`?attr/svgFillColor` is `@android:color/transparent` in every theme, and `CustomUi`'s `setColorFilter` is SRC_ATOP, which keeps a transparent pixel transparent), so a *filled* vector is invisible — cost one build | `customui/KojikiAppSort.kt` + `AppInfoDAO.getSortedApps` + `AppInfoViewModel.getAppInfo`/`appTypeFilter` + `AppListActivity.Filters.loadSort`/`openSortDialog` + `FirewallAppListAdapter.displayLabel`/`getSectionName` + `ic_kojiki_sort.xml` |
| Feature 4 | **Export / Import** — a category-based, all-JSON-in-a-ZIP export/import that **replaces** RethinkDNS's backup/restore (the Settings “Backup & Restore” row + its bottom sheet now open this). Categories (each on/off, default on): app settings · appearance(+fonts) · snoop tags · **app notes · app groups** · firewall apps/domains/IPs · WireGuard · blocklists · DNS custom endpoints (DoH/DoT/DNSCrypt/DNS-proxy/ODoH) · proxies (SOCKS5/HTTP). Future-proof (skip-missing, upsert, entity defaults); **per-app firewall + WG bindings key on package name**, not uid (uid changes per install) — rules for not-yet-installed apps park in `KojikiPendingFw` and apply on install (hook in `RefreshDatabase.insertApp`); DNS endpoint is actually re-selected; WG uses replace-all + `ProxyManager.addProxyToApp` / `WireguardManager.updateLockdownConfig` so bindings + lockdown actually stick | `customui/KojikiExport.kt` + `ui/bottomsheet/ExportImportBottomSheet.kt` + `service/KojikiPendingFw.kt` (main) + `database/RefreshDatabase.kt` hook (retired: `BackupRestoreBottomSheet`, `KojikiBackup`) |
| Feature 7 | **LAN direct path — the WG overlay pinned into the tunnel.** Upstream's own **Configure → VPN → “Do not route Private IPs”** (`persistentState.privateIps`, **default off**) swaps the tun's `0.0.0.0/0` default for `0.0.0.0/0` **minus** 127/8 · 10/8 · 172.16/12 · 192.168/16 · 169.254/16 · 224/3, so LAN traffic leaves via wlan0 instead of tun1 — in **both** directions, since routing is by destination (the reply to a LAN peer is what was actually broken; inbound always arrived fine). **Do not diagnose this with `ping -I wlan0`** — `-I` sets the *source address*, not the route, so it fails even when the theory is right; read `ip route show table 1206` instead. **The trap: that exclusion strands 10.0.0.0/8, and the WG overlay lives there** — the reverse ssh that makes the phone reachable from outside the house dials the PC at **10.9.0.2** over the hub (measured on the PC: `ESTAB 10.9.0.2:22 10.9.0.3:64879` — that one connection *is* the tunnel), so stock behaviour hands it to the home router, which drops it, and the away-from-home path dies. The fork therefore **keeps 10/8 excluded and pins `10.9.0.0/24` back** (`FORK_WG_OVERLAY4`), which is the manoeuvre upstream already performs in the same function for the tun's own `10.111.222.x` — longest-prefix match wins. **Dropping 10/8 from `ipsToExclude` instead is the wrong fix**: it relocates the bug to any 10.x hotel/corporate/ISP LAN, which would stay tunnelled and unreachable. Two conditions this rests on: **Android lockdown must stay off** (always-on + “block connections without VPN” makes the OS blackhole excluded routes — upstream's `TODO: vpn lockdown mode is not handled` sits in `addRoute4`; verified null on this device), and `excludeRoute` is **API 33** while the phone is **API 31**, so route subtraction is the only available mechanism. v6 needs nothing — `addRoute6` already omits ULA/link-local and the overlay is v4-only. Hardcodes the overlay prefix: if it renumbers, the constant moves and the app is rebuilt | `BraveVPNService.addRoute4` (`FORK_WG_OVERLAY4` / `FORK_WG_OVERLAY4_PREFIX` in the companion) — **re-check it survives every rebase**; the toggle itself is upstream's (`TunnelSettingsActivity`, `PersistentState.privateIps`) |

The app ID is deliberately changed so this fork installs **alongside** upstream Rethink without
conflict. The namespace is intentionally kept as `com.celzero.bravedns` so `R`/`BuildConfig`, all
source packages, and intent action strings remain unchanged — only the installed package id differs.

The 白い熊 考直 UI defaults are black `#000000` + **pure yellow `#FFFF00`** (`PALETTE_BLACK` /
`PALETTE_YELLOW` in `CustomUiConfig`, mirrored by `colors_kojiki.xml` and the launcher-icon
foreground). Never material yellow `#FFEB3B`.

### Versioning & APK naming

We base our version on the upstream **release tag** we track and add a fork increment (`BUILD_NUMBER`).

**We track a released tag, not `main`.** As of 2026-08-09 the base is the **`v0.5.6` tag** (`VERSION_CODE 67`,
commit `21b8206b7`, 2026-08-09), whose pinned engine is firestack **`61894b7fdb`** — and for the first time we
actually ship that engine's commit, with one patch on top; see the firestack note below. We deliberately do
**not** sync to `upstream/main`: a bleeding-edge firestack (`379ac52ace`, the `TNT/TZZ` wgproxy rework) once
reset the first SSH flow after the WireGuard double-hop relay idled, so we stay on the released tag's engine.
The `UPSTREAM_AHEAD` field still exists to keep the name honest *if* we ever track `main` past a tag; tracking
the tag exactly makes it `0` and the suffix drops.

> **When the next tag lands, check the DB migration FIRST.** Every upstream release that adds a Room migration
> collides with ours, because the fork's `SnoopEvent` migration claims a version number too — so one version
> number ends up naming two different schemas and the app crashes on open with
> `Migration didn't properly handle: <Table> … Found: columns = { }`. **Renumbering ours is not enough**: a
> device on the fork lineage is already stamped at that number, so Room skips upstream's migration entirely and
> upstream's table is never created. Our renumbered migration must **also create upstream's table idempotently**
> (`CREATE TABLE IF NOT EXISTS`, upstream's exact DDL). This has now happened twice — `26→27`
> (`WgConfigFiles.modifiedTs`) and `30→31` (`Sponsor`, which shipped broken in `0.5.6+1`). Both reconciliations
> live in `MIGRATION_31_32`. Sanity-check four paths: fresh install · pre-collision version · fork lineage ·
> upstream lineage.

- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track the chosen tag** (currently `0.5.6` / `67`).
- `UPSTREAM_AHEAD` = commits our base sits past tag `v<VERSION_NAME>` (`git rev-list --count v0.5.6..main`).
  Tracking the tag exactly → **`0`**. **Recomputed at rebase time** by the **upstream-new-version** skill; it
  does **not** change between builds, and does **not** affect `versionCode`.
- `BUILD_NUMBER` is **our** increment. It starts at `1` and bumps by `1` on every build with changes.
  It is stored in `gradle.properties` as a **plain integer** and **zero-padded to three digits** (`%03d`)
  wherever it is *displayed* — versionName, APK filename, release tag — so builds sort in build order.
- Fork `versionName` = `"<VERSION_NAME>-<UPSTREAM_AHEAD>+<NNN>"`, where `<NNN>` is the padded
  `BUILD_NUMBER`. The `-<UPSTREAM_AHEAD>` is **dropped when it is `0`**, so on the tag it reads as the
  clean `"<VERSION_NAME>+<NNN>"` (e.g. `0.5.6+003`).
- Fork `versionCode` = `VERSION_CODE * 10000 + BUILD_NUMBER` (unpadded arithmetic;
  e.g. `67 * 10000 + 3 = 670003`).
- The arm64-v8a APK then gets upstream's per-ABI override: `3 * 10000000 + forkVersionCode`
  (e.g. `30670003`). This is **higher** than the previous `v0.5.5y` line (`3063xxxx`), so it installs as a
  normal **upgrade** — no uninstall needed.
- Output APK (copied to `~/tmp` by `buildFoss`) =
  `shiroikuma-kojiki_<VERSION_NAME>-<UPSTREAM_AHEAD>+<NNN>_arm64-v8a.apk`
  (e.g. `shiroikuma-kojiki_0.5.6+003_arm64-v8a.apk`).

So the first build on this base was `0.5.6+1` (`670001` → `30670001`), then `0.5.6+2`. **Zero-padding
landed on 2026-08-10** at `BUILD_NUMBER = 3`, so that build is named `0.5.6+003` (`670003` → `30670003`)
and everything after it is `+004`, `+005`, … — only the two pre-padding names (`0.5.6+1`, `0.5.6+2`) sort
out of order. Release tags in this repo are the version string verbatim, matching the APK filename exactly
(`0.5.5y+13`, `0.5.6+2`, `0.5.6+003`) — see the **publish-version** skill.

### Building

Requires **JDK 21** (the default `java` on this machine is too old for the toolchain) and the
**Android SDK** (`sdk.dir` in the gitignored `local.properties` → `/home/shiroikuma/android-sdk`):

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFoss < /dev/null
```

See the **build-apk** skill for the full build-and-push procedure.

`buildFoss` (defined in `app/build.gradle`):
1. builds `assembleFdroidFullRelease` (the de-Googled FOSS release; signed via `keystore.properties`),
2. copies the **arm64-v8a** split APK to `~/tmp/shiroikuma-kojiki_<version>_arm64-v8a.apk`,
3. **auto-increments `BUILD_NUMBER`** in `gradle.properties` for the next build.

**Flavors:** dimension `releaseChannel` = {`play`, `fdroid`, `website`}, dimension `releaseType` = {`full`}.
We ship **`fdroidFull`** (de-Googled). The upstream gate is `isFdroidBuild = taskNames.contains("fdroid")`,
which misses our `buildFoss` task — so the fork extends it to `… || taskNames.contains("foss")`, skipping the
Firebase plugins. (A dangling unused `import com.google.firebase.Firebase` in `service/BraveVPNService.kt` is
also removed, else the de-Googled compile fails with `Unresolved reference 'firebase'`.) The FOSS release
variant/task is `fdroidFullRelease` / `assembleFdroidFullRelease`.

**firestack:** normally consumed as a prebuilt AAR from Maven Central — but **we currently ship a self-built
patched engine**, so `gradle.properties` sets `firestackRepo=local` (not `ossrh`), which makes Gradle read
`app/libs/tun2socks.aar` (gitignored, ~28 MB) instead of resolving `com.celzero:firestack:<commit>@aar`.
`firestackCommit=61894b7fdb` is the `v0.5.6` tag's value and is **inert** while `firestackRepo=local` — it
records the AAR's upstream base, it does not describe what is in the AAR. What is actually built:

- Repo `~/git/firestack`, branch **`kojiki-doh-idle`** (pushed to `fork` = `ShiroiKuma0/firestack`).
- Base = celzero's **`origin/n2`** (firestack's default branch) at **`61894b7f`** — **exactly the `v0.5.6`
  pin**, so as of 2026-08-09 the engine matches the tag rather than running ahead of it.
- Plus our one patch **`662629b7`** — *"doh: keep pooled conns shorter-lived than server idle-timeouts;
  h2 PING health-checks"*, the DoH idle-pool fix filed upstream as **celzero/firestack#241**. See memory
  `[[kojiki-dns-wedge-and-watchdog]]`; the Kotlin-side companion is `service/KojikiDnsWatchdog.kt`.
- **Upstream has PARTLY adopted that fix** — firestack `7ece230c` lowers `IdleConnTimeout` 3 min → **30 s**
  (its comment quotes our own Quad9 measurement). Keep our patch anyway: 30 s sits *exactly* on the shortest
  idle window we measured (Quad9 closes at ≤30 s), and upstream still adds **no h2 PING health-checks**, so a
  half-dead connection is still found by losing a query. Ours is **10 s** + `http2.ConfigureTransports` with
  `ReadIdleTimeout`/`PingTimeout`. On the next rebase, re-check whether upstream has closed the rest of the
  gap; if it ever ships the PING eviction too, this patch can be dropped.
- Rebuild recipe: memory `[[firestack-from-source-build]]` (Go + gomobile + NDK → `make intra`). Firestack now
  requires **Go 1.26** (`~/goroot/go` is 1.26.0); `rm -rf build` before `make intra` so the Go-runtime overlay
  regenerates for the current toolchain, else the build reuses one patched for the old Go.
- Verify the shipped engine: `libgojni.so` unzipped from the APK must be SHA256-identical to the one in
  `~/git/firestack/build/intra/tun2socks.aar`.

To fall back to the **stock** `v0.5.6` engine, set `firestackRepo=ossrh` — you keep upstream's 30 s half of
the fix but lose the PING eviction. **Do not bump `firestackCommit` to a `main`-lineage firestack** (e.g. `379ac52ace`) — that broke
WG-relay SSH (see the versioning section). On this engine the WG double-hop must run **full-tunnel with
Lockdown ON** (per-app split wedges the resolver); the hub supplies internet via NAT. See memory
`[[wg-hub-and-dns-architecture]]`.

### Rebasing onto a new upstream release

When the user says a new upstream version is out, follow the **upstream-new-version** skill. In short:
1. `git fetch upstream --tags`.
2. Advance `main` to the new upstream release.
3. Rebase `custom` onto `main`, preserving every customization in the table above.
4. Set `VERSION_NAME` / `VERSION_CODE` to the new upstream values and **reset `BUILD_NUMBER` to `1`**.
5. Build the new `+1` version with `./gradlew buildFoss`; continue further changes as `+2`, `+3`, …

### HARD RULES (do not violate)

- **Never install APKs to the phone automatically.** After building, **ask** the user (via
  `AskUserQuestion`). Only when they confirm, `adb push` the APK to `/sdcard/tmp/` — the user installs it
  manually from there. Do **not** use `adb install` / `pm install` under any circumstances.
- **Build freely without asking.** The *only* question after a build is the adb-push question.
  **STOP after each build** for the user to test.
- **Never commit or push on your own.** Develop and build, let the user test, and **only commit/push
  when the user explicitly types "Push".** Then `git commit` and `git push origin custom`
  (use `--force-with-lease` if `custom` was rebased).

## Architecture (orientation)

RethinkDNS is a VPN service that does DNS resolution + per-app firewalling + WireGuard proxying in a
userspace tunnel. The Kotlin app is the control plane / UI; `firestack` (Go) is the data plane.

- **`service/`** — the core. `BraveVPNService` (the `VpnService`), `FirewallManager`
  (`object … : KoinComponent`; per-app firewall status cache + DB), `ProxyManager` / `WireguardManager`
  (WireGuard proxy state), `VpnController`, `PersistentState`, `RethinkDnsApplication`.
- **`receiver/`** — broadcast receivers, including our `SetAppRuleReceiver` (Feature 1) and
  `SetWgStateReceiver` (Feature 1b).
- **`customui/`** (full source set) — our 白い熊 考直 UI (Feature 3): `CustomUiConfig` (a SharedPreferences
  store; seeds 白い熊's exported look as the defaults), `CustomUi` (the runtime applier + font/typeface system
  + dialog/bottom-sheet/toast theming; `applyToDialogTree` extends that pass to a dialog's/sheet's own
  window, which the activity walk cannot reach), `ColorPickerDialog` (an ARGB picker),
  `KojikiAppNotes` + `KojikiAppGroups` (the apps-view notes and groups — both package-name-keyed
  prefs stores that also own their dialogs). The runtime pass runs from
  **`BaseActivity.onResume`** when the `Custom` theme is active (`app/src/full/.../ui/BaseActivity.kt` — this
  chokepoint exists from v0.5.5v onward, so the hook lives there, not on the Application as it did on
  v0.5.5u; re-check it survives each rebase). `Themes.customThemeActive`
  mirrors `CustomUi.customThemeActive` into `main` so main-only helpers (toasts) can theme too. The Custom
  theme is the **default** (`PersistentState.theme` defaults to `Themes.CUSTOM.id`).
- **`database/`** — Room DB + DAOs/repositories (e.g. `AppInfo`, connection tracking).
- **`ui/`** — activities/fragments/adapters; **`util/`** — helpers; **DI** via Koin.
- Per-app firewall state lives in `FirewallManager` (`FirewallStatus`: `BYPASS_UNIVERSAL(2)`,
  `EXCLUDE(3)`, `ISOLATE(4)`, `NONE(5)`, `UNTRACKED(6)`, `BYPASS_DNS_FIREWALL(7)`;
  `ConnectionStatus`: `BOTH(0)`, `UNMETERED(1)`, `METERED(2)`, `ALLOW(3)`).
  `suspend fun updateFirewallStatus(uid, firewallStatus, connectionStatus)` applies a rule;
  `getAppInfoByPackage(pkg)` / `getAppInfoByUid` / `getPackageNameByUid` resolve apps.

## Troubleshooting / known gotchas

### Restoring an old Rethink backup (`.rbk`) kills ALL DNS — the `connectionStatus=0` trap

**Symptom:** after restoring a pre-existing RethinkDNS backup, *every* tunnelled app (browser, shell,
Termux) gets `unknown host`; netd logs `res_nsend: ipv4_invalid_type:1`; firestack may not even dial
the DoH resolver. Apps set to `EXCLUDE` (e.g. Jami) keep working because they bypass the tunnel.

**Root cause** (diagnosed 2026-06-14 via a 13-variant `.rbk` bisection): the old backup's `AppInfo`
rows carry per-app **`connectionStatus = 0`** (`ConnectionStatus.BOTH`) on hundreds of apps —
**including the system `com.android.networkstack…`, uid 1000**. On this build `FirewallManager`
firewalls any app whose `connectionStatus != ConnectionStatus.ALLOW` (`BOTH` renders as
`R.string.block` = "block on both wifi+data"). Blocking the system networkstack kills the device
resolver → DNS dies for everything. It is a **cross-version artifact**: `conn=0` was harmless under
the older config the backup was made on, but means "block" here (a fresh install defaults every app
to `ALLOW(3)`).

**Fix:** normalize `AppInfo.connectionStatus 0 → 3 (ALLOW)`. This restores DNS while **preserving
every intentional rule** in `firewallStatus` (BYPASS_UNIVERSAL/EXCLUDE/ISOLATE/BYPASS_DNS_FIREWALL).
**Any per-app-firewall import/restore path we build MUST apply this `0 → ALLOW` mapping.**

**Exonerated — do NOT re-chase these:** prefs (`dns_alg`, `disallow_dns_bypass`, `use_max_mtu`,
`block_*` flags), CustomIp/CustomDomain rules, custom DoH/DoT endpoints, the WG row + its proxy
mappings (firestack falls back to Base when a bound WG is inactive).

### Diagnosing DNS / connectivity on the phone (don't waste hours on the wrong metric)

- **`ping` is useless when `dns_alg` is on.** Names resolve to synthetic `100.64.0.0/10` ALG IPs that
  never answer ICMP, so `ping` shows resolution but 100% packet loss *even when all is well*. **Test
  with `curl` by-name HTTP code:** `curl -sS -m12 -o /dev/null -w "%{http_code}\n" https://example.com/`.
  (`curl https://<literal-IP>` fails by design — it trips `block_unknown_connections`, not a fault.)
- **Reading/editing a `.rbk`'s `bravedns.db` needs `PRAGMA wal_checkpoint(TRUNCATE)` first** — the
  backup ships an uncheckpointed WAL, so opening the `.db` alone shows a *stale pre-WAL* state (this
  invalidated three diagnosis rounds). Restore variants must also bundle 0-byte `*.db-wal`/`-shm` to
  overwrite the device's leftover WAL.

## Key Configuration Files

- `gradle.properties` — fork app id/namespace, version name/code, `BUILD_NUMBER`, firestack pin.
- `app/build.gradle` (Groovy) — Android config, flavors, signing, fork version logic, the `buildFoss` task.
- `keystore.properties` — signing config (gitignored; points to `~/.android-keystores/shiroikuma-denwa.jks`).
- `local.properties` — `sdk.dir` (gitignored).

## Commit convention — no Claude attribution

Do **not** add any `Co-Authored-By: Claude …` trailer — nor a "🤖 Generated with Claude Code" / Anthropic-attribution line — to commit messages or PR bodies in this repo. 白い熊 does not want Claude attribution in the history; this **overrides** the harness's default to append such a trailer. End commit messages at the last line of the body. (The existing history was scrubbed of these trailers on 2026-06-08; the global rule lives in `~/.claude/CLAUDE.md`.)
