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
| Feature 1b | external intent to enable/disable a WireGuard tunnel | `receiver/SetWgStateReceiver.kt` (same token) |
| Feature 2 | honest WireGuard status — don’t read “Failing” while traffic still flows | `util/UIUtils.kt` (`honestWgStatusId`), consumed by `OneWgConfigAdapter` / `WgConfigAdapter` / `WgConfigDetailActivity` **and the home Proxy card** (`HomeScreenFragment.getProxyStatus`, keyed `home:`) (full) |
| Feature 3 | 白い熊 考直 UI — a **default** “Custom…” app theme with user-configurable colours (full ARGB: background/accent/text) + a global font (family/weight/size), via a new “Customize → 白い熊 考直 UI” settings page; 白い熊's exported look is seeded as the defaults; dialogs/bottom-sheets/toasts/start-button are themed too | `customui/` (`CustomUiConfig`, `CustomUi`, `ColorPickerDialog`) + `ui/activity/KojikiUiActivity.kt` + `Themes.CUSTOM` (default in `PersistentState.theme`) + `*_kojiki.xml` + `kojiki_toast*` (+ the runtime hook in `BaseActivity.onResume`, `MiscSettingsActivity`, full manifest) |
| Feature 5 | **Per-app notes** (apps view) — 白い熊 応用管理's notes, same operation: a glyph at the end of each row's label line (note glyph = has one, dim “+” = none), tap opens a pre-filled multi-line dialog, **saving blank deletes**, long-press = the note as a tooltip. Keyed by **package name** in its own prefs file (`kojiki_app_notes`) so Export/Import carries it with the generic prefs exporter. **v0.5.7 added upstream's OWN per-app notes** — an `AppInfo.notes` column (+ length triggers) with its own UI on the app *detail* screen. The two coexist and do not collide visually (ours is the list row), but they are two separate stores for the same idea; whether to fold ours onto the upstream column is an open decision | `customui/KojikiAppNotes.kt` + `ic_kojiki_note{,_add}.xml` + `list_item_firewall_app.xml` label row + `FirewallAppListAdapter.bindNote` (+ `firewall_app_note_iv` in `CustomUi.applyToTree`'s skip list — its tint carries state) |
| Feature 6 | **App groups / profiles** (apps view) — named sets of apps, in 応用管理's format: the row's **bottom line** carries a filled pill per group the app is in, then a trailing “+” pill. Tap a pill = filter the list to that group · long-press = drop the app from it · tap “+” = the membership checklist (with “New group…”) · long-press “+” = manage (rename/delete). The filter sheet gains a **Groups** chip section + a **Manage** action. Because the bulk-rule toolbar acts on whatever the filter selects, filtering to a group makes the toolbar “apply this rule to the whole group”. Members key on **package name**, never uid. Filtering is a **post-query** `PagingData.filter` (+ a plain list filter for the bulk path), so **no upstream DAO query is touched** — deliberate, for rebase safety | `customui/KojikiAppGroups.kt` (store + dialogs + pill view) + `AppListActivity.Filters.setGroups`/`refreshGroupPills` + `AppInfoViewModel.applyGroupFilter`/`inSelectedGroups` + `FirewallAppListAdapter.bindGroups` + `bottom_sheet_firewall_sort_filter.xml` + `FirewallAppFilterBottomSheet` |
| Feature 6e | **non-app rows** — the synthetic `no_package_<uid>` entries (`RefreshDatabase.insertUnknownApp`: root, `SYSTEM`, any uid whose traffic no package accounts for; the label comes from `AndroidUidConfig`, where upstream renamed `ROOT` → **`ANDROID`**, so “ANDROID” = uid 0). They **do** get notes and groups — they are the rows that most need a “do not block, DNS dies” annotation — even though their key is a uid in package clothing and therefore device-local. Export carries them plus a `__kojiki_nonapp_labels` map of the label each key had; **import prefixes a ⚠ marker note** naming what the key used to be (`markImportedNonApp`, idempotent on the ⚠), and a row that carried only group membership gets that note created, so nothing arrives silently. A **“Non-app” top-level filter** (`TopLevelFilter.NON_APP`) lists them; like the group filter it is a post-query filter on the package-name prefix, since `isSystemApp` does not identify them | `KojikiExport` (`withNonAppLabels` / `markImportedNonApp`) + `TopLevelFilter.NON_APP` + `AppInfoViewModel.applyRowFilters` + `FirewallAppFilterBottomSheet` |
| Feature 6c | **app-list row density** — two traps found together on 0.5.6+004. (1) `firewall_app_details_ll` was `layout_height="0dp"`, so the row's height came only from the 48dp toggles + `minHeight 72dp` and **any taller text was silently clipped** (the traffic line vanished mid-glyph); it is `wrap_content` now, so the row grows to its content. (2) `Widget.Rethink.MaterialCardView` sets `cardUseCompatPadding`, and that padding is reserved from **`maxCardElevation`, not the current elevation** — so zeroing `cardElevation` under the Custom theme left ~5dp of invisible shadow room above and below every flat card. `CustomUi.applyToTree` now also zeroes `maxCardElevation` and clears `useCompatPadding`, which is what actually tightens the list | `list_item_firewall_app.xml` + `CustomUi.applyToTree` (MaterialCardView branch) |
| Feature 6d | **bordered dialogs, app-wide.** `App.Dialog.NoDim` (the style behind ~144 `MaterialAlertDialogBuilder` call sites) pinned `android:windowBackground` to `@android:color/transparent`, so every no-dim dialog had **no surface at all** — the note dialog rendered see-through over the app list, with nothing for a border to sit on. **A Material alert cannot be given a border from a theme — do not try again.** Both levers were tried and both are wrong: `android:windowBackground` is replaced by `MaterialAlertDialogBuilder.create()` at show() time, so a bordered drawable set there is silently discarded (*verified with `aapt2 dump resources` on the shipped APK — the style carried the drawable, the dialog still came up borderless*); `android:background` is applied to the alert's title/content/button panels **individually**, rendering three stacked bordered boxes with clipped corners. A Material alert has no single outer surface to stroke. So the fork's dialogs draw their own: **`customui/KojikiDialog.kt`** — one rounded accent-bordered box on a transparent window, holding title + content + a right-aligned button row (`leading = true` puts one on the left), with the content area clamped to 55 % of screen height so a long list scrolls instead of pushing the buttons off. Helpers: `input` / `helper` / `checkbox` / `row` / `withAlpha`. **Upstream's ~144 alerts** are routed through **`KojikiAlertDialogBuilder`** (a drop-in `MaterialAlertDialogBuilder` subclass; one identifier per call site, so the sweep over upstream's files stays mechanical — re-run it after every rebase, `grep MaterialAlertDialogBuilder\(` must find nothing outside tests) whose `create()` attaches `CustomUi.themeAlertSurface`: it paints AppCompat's `parentPanel` with the same box. **Two traps that made that border invisible from +014 to +028:** `App.Dialog.NoDim`'s `android:background=?attr/colorSurface` is stamped by the inflater on **every** view under the dialog theme (title row, message scroller, button bar — not only the four panels), so opaque full-width fills covered a stroke drawn in the panel's *background* and only a corner sliver survived. Hence the stroke is the panel's **foreground** (nothing can paint over it), those theme fills are **stripped** (`stripThemeFills`, matching the theme's resolved `android:background` colour only), and the panel `clipToOutline`s so a lazily-created list row cannot poke a square corner out. Floating dialogs whose surface is a `MaterialCardView` in a transparent window (`WgSsidDialog`, `RpnSsidDialog`) get the box via `CustomUi.themeCardDialog`. Full-screen `Dialog` subclasses (`WgIncludeAppsDialog`, `WgAddPeerDialog`, `DnsCryptRelaysDialog`, `GenericHopDialog`, `CustomLanIpDialog` — all given the *activity* theme, so `windowIsFloating=false`) are pages, not boxes, and stay unbordered on purpose, like the bottom sheets | `customui/KojikiDialog.kt` + `customui/KojikiAlertDialogBuilder.kt` + `CustomUi.themeAlertSurface` / `paintDialogBox` / `themeCardDialog` + `styles.xml` (`App.Dialog.NoDim`, with the warning comments) |
| Feature 6b | the apps-view **filter sheet in black/yellow with an accent border** — the border goes on an **inset content box** (`fs_content_box`), never the full-width panel (side strokes at the screen edge are clipped; `CustomUi.themeBottomSheet` only flattens the panel). Contents are restyled by the new **`CustomUi.applyToDialogTree`** — the activity tree-walk never reaches a dialog's own window — re-run after every async chip rebuild. `BottomSheetDialogThemeKojikiCustom` also stopped being an empty extension: it now mirrors the activity theme's yellow palette, so **every** sheet reads black/yellow statically | `styles_kojiki.xml` + `CustomUi.applyToDialogTree` + `FirewallAppFilterBottomSheet.applyKojikiTheme` |
| Feature 6f | **app-list sort + the uid on every row.** The row's id line now leads with the **uid** (`10050 · yqtrack.app`), and the synthetic `no_package_<uid>` rows — which print no package id at all — show the uid alone instead of an empty line. Sorting is a header glyph beside the filter icon (**not** in the filter sheet: its chip sections already fill a folded screen) opening a `KojikiDialog` with four keys — app name · package id · **uid** · data used — where the active key is drawn in the accent with its arrow and **tapping it reverses** the direction; the choice persists in its own prefs file, and the fast-scroll bubble follows the key (a uid, a byte count) rather than always a letter. A paged list **cannot** be re-sorted in Kotlin (each page is fetched on its own), so the order had to become SQL: upstream's **six** paged queries (all/installed/system × with/without category, all hard-ordered by `lower(appName)`) collapse into one fork query, `AppInfoDAO.getSortedApps`, whose `ORDER BY` is bound by `sortKey`/`descending` through the SQLite CASE idiom (`lower(appName)` closes it as tie-breaker) and which takes the app type + a `noCategory` flag as parameters. **Its WHERE mirrors upstream's — re-check it on every rebase**, since nothing reads those six methods now. **v0.5.7 added upstream's OWN sort** (`AppListActivity.SortOption` NAME/PACKAGE/UID, a `sort` argument on its six paged queries, and a chip section in the filter sheet). Ours is a superset — it adds **data used** and a **reversible direction** — so the fork keeps `getSortedApps` and the header glyph, and the now-dead upstream chip section was **removed** from `bottom_sheet_firewall_sort_filter.xml` (with `remakeSortChipsUi`/`makeSortChip`) so there is exactly one sort control. Icon trap: header glyphs are **stroke** drawings (`?attr/svgFillColor` is `@android:color/transparent` in every theme, and `CustomUi`'s `setColorFilter` is SRC_ATOP, which keeps a transparent pixel transparent), so a *filled* vector is invisible — cost one build | `customui/KojikiAppSort.kt` + `AppInfoDAO.getSortedApps` + `AppInfoViewModel.getAppInfo`/`appTypeFilter` + `AppListActivity.Filters.loadSort`/`openSortDialog` + `FirewallAppListAdapter.displayLabel`/`getSectionName` + `ic_kojiki_sort.xml` |
| Feature 4 | **Export / Import** — a category-based, all-JSON-in-a-ZIP export/import that **replaces** RethinkDNS's backup/restore (the Settings “Backup & Restore” row + its bottom sheet now open this). Categories (each on/off, default on): app settings · appearance(+fonts) · snoop tags · **app notes · app groups** · firewall apps/domains/IPs · WireGuard · blocklists · DNS custom endpoints (DoH/DoT/DNSCrypt/DNS-proxy/ODoH) · proxies (SOCKS5/HTTP). Future-proof (skip-missing, upsert, entity defaults); **per-app firewall + WG bindings key on package name**, not uid (uid changes per install) — rules for not-yet-installed apps park in `KojikiPendingFw` and apply on install (hook in `RefreshDatabase.insertApp`); DNS endpoint is actually re-selected; WG uses replace-all + `ProxyManager.addProxyToApp` / `WireguardManager.updateLockdownConfig` so bindings + lockdown actually stick. **WG conf files are PLAINTEXT since upstream `e4ea1ddb7` (v0.5.5y base)** — `exportWireGuard` reads them via `WireguardConfigFileManager` (encrypted reader only as a fallback for an unmigrated file); reading a plaintext file through `EncryptedFileManager` throws, and from 0.5.5y to 0.5.6+027 that was swallowed into an **empty `wireguard.json`** in every export (found 2026-09-11 when a new-phone restore came up without the tunnel). Re-check the reader on every rebase | `customui/KojikiExport.kt` + `ui/bottomsheet/ExportImportBottomSheet.kt` + `service/KojikiPendingFw.kt` (main) + `database/RefreshDatabase.kt` hook (retired: `BackupRestoreBottomSheet`, `KojikiBackup`) |
| Feature 7 | **LAN direct path — the WG overlay pinned into the tunnel.** Upstream's own **Configure → VPN → “Do not route Private IPs”** (`persistentState.privateIps`, **default off**) swaps the tun's `0.0.0.0/0` default for `0.0.0.0/0` **minus** 127/8 · 10/8 · 172.16/12 · 192.168/16 · 169.254/16 · 224/3, so LAN traffic leaves via wlan0 instead of tun1 — in **both** directions, since routing is by destination (the reply to a LAN peer is what was actually broken; inbound always arrived fine). **Do not diagnose this with `ping -I wlan0`** — `-I` sets the *source address*, not the route, so it fails even when the theory is right; read `ip route show table 1206` instead. **The trap: that exclusion strands 10.0.0.0/8, and the WG overlay lives there** — the reverse ssh that makes the phone reachable from outside the house dials the PC at **10.9.0.2** over the hub (measured on the PC: `ESTAB 10.9.0.2:22 10.9.0.3:64879` — that one connection *is* the tunnel), so stock behaviour hands it to the home router, which drops it, and the away-from-home path dies. The fork therefore **keeps 10/8 excluded and pins `10.9.0.0/24` back** (`FORK_WG_OVERLAY4`), which is the manoeuvre upstream already performs in the same function for the tun's own `10.111.222.x` — longest-prefix match wins. **Dropping 10/8 from `ipsToExclude` instead is the wrong fix**: it relocates the bug to any 10.x hotel/corporate/ISP LAN, which would stay tunnelled and unreachable. Two conditions this rests on: **Android lockdown must stay off** (always-on + “block connections without VPN” makes the OS blackhole excluded routes — upstream's `TODO: vpn lockdown mode is not handled` sits in `addRoute4`; verified null on this device), and `excludeRoute` is **API 33** while the phone is **API 31**, so route subtraction is the only available mechanism. v6 needs nothing — `addRoute6` already omits ULA/link-local and the overlay is v4-only. Hardcodes the overlay prefix: if it renumbers, the constant moves and the app is rebuilt | `BraveVPNService.addRoute4` (`FORK_WG_OVERLAY4` / `FORK_WG_OVERLAY4_PREFIX` in the companion) — **re-check it survives every rebase**; the toggle itself is upstream's (`TunnelSettingsActivity`, `PersistentState.privateIps`) |
| Feature 8 | **Firewall-rule help dialog** — long-pressing **Bypass DNS & Firewall / Bypass Universal / Exclude**, on the app screen *and* on the app-list bulk toolbar, opens a near-full-screen scrollable `KojikiDialog` instead of upstream's white platform tooltip (one unthemed sentence, on only one of the three): a paragraph per button with the pressed one leading, a rule-by-rule table of what each waives, then the notes. The table is read off `TunFirewallManager.firewall()`'s decision order and states the two things the UI never did — **both** bypasses waive “app not in use” and “device locked” (both branches `return` before that tail), and **neither** lifts the row's own WiFi/mobile-data toggles (`appBlocked` is tested earlier); `TunDnsManager.onQuery` consults only `bypassDnsFirewall`, which is why Bypass Universal still gets its blocklisted domains blocked. Text lives in the Kotlin file, **not** `strings.xml` — fork-only English, one less conflict in upstream's translated resource. `KojikiDialog.show` gained a `maxContentFraction` (default unchanged at `0.55`; the help passes `0.78`) placed **before** the trailing `content` lambda, so no call site moved. Upstream's explain-before-first-enable tap guard (`showBypassToolTip` + `performLongClick()`) now opens the same dialog directly | `customui/KojikiFirewallHelp.kt` + `KojikiDialog.show(maxContentFraction)` + the long-press wiring in `AppInfoActivity` / `AppListActivity` |
| Feature 9 | **Full-width content — upstream's 600dp cap is NOT applied.** v0.5.7 added `BaseActivity.applyMaxContentWidth()`: on any window wider than `MAX_CONTENT_WIDTH_DP` (600dp) it pins the content view to 600dp and centres it inside `android.R.id.content`. 白い熊's phone is a **tri-fold** — unfolded it is **~819dp** wide, so the cap rendered every screen as a narrow column between **wide black bars** (reported 2026-09-27 on `0.5.7+001`). The fork therefore does **not** call it from `onPostCreate`; upstream's `applyMaxContentWidth` / `capContentChildWidth` are left intact but unused (`@Suppress("unused")`) so the next rebase still diffs cleanly. **That was only half of it**: the home screen carries its OWN caps in XML — the scroll content's `android:maxWidth="720dp"` + `layout_gravity="center_horizontal"`, and `layout_constraintWidth_max="720dp"` on `fhs_control_cluster` (the STOP row) — in **both** `layout/` and `layout-w600dp/fragment_home_screen.xml`. Those are removed too, else the app list goes full width while the home page stays inset (exactly what 白い熊 saw on `0.5.7+002`). **Re-check all three after every rebase** — upstream may move the call or re-add the XML caps. Grep is `720dp`. The sibling 600dp caps on *dialogs* (`UIUtils.DIALOG_MAX_WIDTH_DP`, 33 call sites) and *bottom sheets* (`BaseBottomSheetDialogFragment.MAX_WIDTH_DP`) are deliberately **left alone**: a dialog narrower than the screen is normal, and the fork's sheet border already assumes an inset box | `ui/BaseActivity.kt` (`onPostCreate`) |

The app ID is deliberately changed so this fork installs **alongside** upstream Rethink without
conflict. The namespace is intentionally kept as `com.celzero.bravedns` so `R`/`BuildConfig`, all
source packages, and intent action strings remain unchanged — only the installed package id differs.

The 白い熊 考直 UI defaults are black `#000000` + **pure yellow `#FFFF00`** (`PALETTE_BLACK` /
`PALETTE_YELLOW` in `CustomUiConfig`, mirrored by `colors_kojiki.xml` and the launcher-icon
foreground). Never material yellow `#FFEB3B`.

### Versioning & APK naming

We base our version on the upstream **release tag** we track and add a fork increment (`BUILD_NUMBER`).

**We track a released tag, not `main`.** As of 2026-09-27 the base is the **`v0.5.7` tag** (`VERSION_CODE 69`,
commit `070232086`, 2026-09-20), whose pinned engine is firestack **`c4a33649be`** — and we ship that engine's
commit with two patches on top; see the firestack note below. We deliberately do
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
> (`CREATE TABLE IF NOT EXISTS`, upstream's exact DDL). This has now happened three times — `26→27`
> (`WgConfigFiles.modifiedTs`), `30→31` (`Sponsor`, which shipped broken in `0.5.6+1`) and — on the `v0.5.7`
> base — `31→32`, where upstream adds **`AppInfo.notes` plus its length triggers** (its `30→31` adds the column
> too, so a fork DB stamped `32` skips *both*). All three reconciliations now live in **`MIGRATION_35_36`**
> (DB `version = 36`): upstream owns `31→32` … `34→35`, so ours sits above the whole upstream chain. Note
> upstream also **renumbers its own migrations between tags** — do not assume `30→31` still means what it
> meant last release; read the new chain before renumbering. Sanity-check four paths: fresh install ·
> pre-collision version · fork lineage · upstream lineage.

- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track the chosen tag** (currently `0.5.7` / `69`).
- `UPSTREAM_AHEAD` = commits our base sits past tag `v<VERSION_NAME>` (`git rev-list --count v0.5.7..main`).
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
`firestackCommit=c4a33649be` is the `v0.5.7` tag's value and is **inert** while `firestackRepo=local` — it
records the AAR's upstream base, it does not describe what is in the AAR. What is actually built:

- Repo `~/git/firestack`, branch **`kojiki-doh-idle-057`** (the `v0.5.6`-era branch `kojiki-doh-idle`, base
  `61894b7f`, is kept for reference; it is what shipped up to `0.5.6+034`).
- Base = celzero's **`origin/n2`** (firestack's default branch) at **`c4a33649be`** — **exactly the `v0.5.7`
  pin**, so as of 2026-09-27 the engine matches the tag rather than running ahead of it.
- Plus **two** fork patches, both of which cherry-picked clean onto the new base:
  - *"doh: keep pooled conns shorter-lived than server idle-timeouts; h2 PING health-checks"* — the DoH
    idle-pool fix filed upstream as **celzero/firestack#241**. See memory `[[kojiki-dns-wedge-and-watchdog]]`;
    the Kotlin-side companion is `service/KojikiDnsWatchdog.kt`.
  - *"alg: recover an aged-out alg mapping instead of dropping the flow"* — filed as
    **celzero/firestack#252**. See memory `[[kojiki-alg-stale-mapping-fix]]`.
- **Upstream has PARTLY adopted the DoH fix** — `IdleConnTimeout` is **30 s** at `c4a33649be` (its comment
  quotes our own Quad9 measurement). Keep our patch anyway: 30 s sits *exactly* on the shortest idle window
  we measured (Quad9 closes at ≤30 s), and upstream **still ships no h2 PING health-checks** (re-verified on
  `c4a33649be`, 2026-09-27), so a half-dead connection is still found only by losing a query. Ours is
  **10 s** + `http2.ConfigureTransports` with `ReadIdleTimeout`/`PingTimeout`. Re-check on every rebase; if
  upstream ever ships the PING eviction too, this patch can be dropped.
- Rebuild recipe: memory `[[firestack-from-source-build]]` (Go + gomobile + NDK → `make intra`). Firestack
  requires **Go 1.27** from this pin on — `~/goroot/go1.27.1` (installed 2026-09-27; `~/goroot/go` is still
  1.26.0 and the Makefile forces `GOTOOLCHAIN=local`, so build with
  `GOROOT=$HOME/goroot/go1.27.1 PATH=$HOME/goroot/go1.27.1/bin:$PATH`). `rm -rf build` before `make intra`
  so the Go-runtime overlay regenerates for the current toolchain, else the build reuses one patched for the
  old Go. `gobind` must be installed alongside `gomobile` or the bind fails with "gobind was not found".
- Verify the shipped engine: `libgojni.so` unzipped from the APK is **NOT** SHA256-identical to the one in
  `~/git/firestack/build/intra/tun2socks.aar` — packaging re-aligns it (an ~8-byte size delta), so that check
  raises a false alarm. Compare **marker strings** instead: `strings libgojni.so | grep` for a patch marker
  (`resolved alg domain`, from the ALG fix) *and* for a symbol new to the base you pinned (`onPrequery` is in
  `c4a33649be` but not `61894b7f`). Both present ⇒ the APK carries our patched engine on the right base.

To fall back to the **stock** `v0.5.7` engine, set `firestackRepo=ossrh` — you keep upstream's 30 s half of
the DoH fix but lose the PING eviction, and you lose the ALG recovery entirely. **Do not bump `firestackCommit` to a `main`-lineage firestack** (e.g. `379ac52ace`) — that broke
WG-relay SSH (see the versioning section). On this engine the WG double-hop must run **full-tunnel with
Lockdown ON** (per-app split wedges the resolver); the hub supplies internet via NAT. See memory
`[[wg-hub-and-dns-architecture]]`.

### Rebasing onto a new upstream release

> **v0.5.7 moved the whole Kotlin source set.** `app/src/full/java/**` no longer exists — all 246 files
> moved to `app/src/main/java/**` at the same relative path (only `ManageRpnPurchaseBtmSht.kt` and
> `WgIncludeAppsDialog.kt` were dropped outright). `app/src/full/res/` survives, but
> **`layout-sw600dp/` was renamed `layout-w600dp/`** — a *current-width* qualifier, not smallest-width, so
> home-screen cards must be added to `layout/` **and** `layout-w600dp/`.
>
> Replaying 90 fork commits across that move is hopeless commit-by-commit (45 of them touch it, and
> `HomeScreenFragment.kt` changed too much for rename detection to pair). What worked: **rewrite the fork's
> own history onto the new paths first**, then rebase —
> `git filter-branch --index-filter` over `<old-main>~1..custom`, rewriting index paths
> `app/src/full/java/ → app/src/main/java/` and `app/src/full/res/layout-sw600dp/ → …/layout-w600dp/`,
> then `git rebase --onto main <rewritten-old-main> custom`. Every modify/delete conflict then became an
> ordinary content conflict. Enable `rerere` first; it replays repeated resolutions.

When the user says a new upstream version is out, follow the **upstream-new-version** skill. In short:
1. `git fetch upstream --tags`.
2. Advance `main` to the new upstream release.
3. Rebase `custom` onto `main`, preserving every customization in the table above.
4. Set `VERSION_NAME` / `VERSION_CODE` to the new upstream values and **reset `BUILD_NUMBER` to `1`**.
5. Build the new `+1` version with `./gradlew buildFoss`; continue further changes as `+2`, `+3`, …

### HARD RULES (do not violate)

- **Deliver every build automatically — never ask.** The global **/after-build** skill owns delivery
  and this repo follows it without exception: on `BUILD SUCCESSFUL`, run `/adb-check` **unsandboxed**,
  then `/adb-push` the APK to `/sdcard/tmp/` if the phone is reachable, otherwise `/scp` it to
  `skhw:~/tmp/` — one target, first success wins. Do **not** ask "shall I push it?", do **not** ask
  whether the phone is connected, and do **not** raise an `AskUserQuestion` about the transfer.
  (Superseded 2026-08-24: this rule used to demand that prompt. It does not any more.)
- **Never install the APK.** 白い熊 installs it manually from `/sdcard/tmp/`; `adb install` / `pm install`
  are forbidden under all circumstances. Never delete or overwrite an APK already sitting there.
- **Build freely without asking**, and deliver freely too. **STOP after each build** for 白い熊 to
  test — the build-and-deliver ends the turn; do not start the next piece of work on your own.
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
- **`customui/`** — our 白い熊 考直 UI (Feature 3): `CustomUiConfig` (a SharedPreferences
  store; seeds 白い熊's exported look as the defaults), `CustomUi` (the runtime applier + font/typeface system
  + dialog/bottom-sheet/toast theming; `applyToDialogTree` extends that pass to a dialog's/sheet's own
  window, which the activity walk cannot reach), `ColorPickerDialog` (an ARGB picker),
  `KojikiAppNotes` + `KojikiAppGroups` (the apps-view notes and groups — both package-name-keyed
  prefs stores that also own their dialogs). The runtime pass runs from
  **`BaseActivity.onResume`** when the `Custom` theme is active (`app/src/main/.../ui/BaseActivity.kt` — this
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
