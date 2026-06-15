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
| Feature 4 | **Export / Import** — a category-based, all-JSON-in-a-ZIP export/import that **replaces** RethinkDNS's backup/restore (the Settings “Backup & Restore” row + its bottom sheet now open this). Categories (each on/off, default on): app settings · appearance(+fonts) · snoop tags · firewall apps/domains/IPs · WireGuard · blocklists · DNS custom endpoints (DoH/DoT/DNSCrypt/DNS-proxy/ODoH) · proxies (SOCKS5/HTTP). Future-proof (skip-missing, upsert, entity defaults); **per-app firewall + WG bindings key on package name**, not uid (uid changes per install) — rules for not-yet-installed apps park in `KojikiPendingFw` and apply on install (hook in `RefreshDatabase.insertApp`); DNS endpoint is actually re-selected; WG uses replace-all + `ProxyManager.addProxyToApp` / `WireguardManager.updateLockdownConfig` so bindings + lockdown actually stick | `customui/KojikiExport.kt` + `ui/bottomsheet/ExportImportBottomSheet.kt` + `service/KojikiPendingFw.kt` (main) + `database/RefreshDatabase.kt` hook (retired: `BackupRestoreBottomSheet`, `KojikiBackup`) |

The app ID is deliberately changed so this fork installs **alongside** upstream Rethink without
conflict. The namespace is intentionally kept as `com.celzero.bravedns` so `R`/`BuildConfig`, all
source packages, and intent action strings remain unchanged — only the installed package id differs.

The 白い熊 考直 UI defaults are black `#000000` + **pure yellow `#FFFF00`** (`PALETTE_BLACK` /
`PALETTE_YELLOW` in `CustomUiConfig`, mirrored by `colors_kojiki.xml` and the launcher-icon
foreground). Never material yellow `#FFEB3B`.

### Versioning & APK naming

We base our version on the upstream **release tag** we track and add a fork increment (`BUILD_NUMBER`).

**We track a released tag, not `main`.** As of 2026-06-15 the base is the **`v0.5.5v` tag** (`VERSION_CODE 59`),
which ships firestack **`22cfc49978`** (the tag's pinned engine). We deliberately do **not** sync to
`upstream/main`: a bleeding-edge firestack (`379ac52ace`, the `TNT/TZZ` wgproxy rework) once reset the first
SSH flow after the WireGuard double-hop relay idled, so we stay on the released tag's engine. The
`UPSTREAM_AHEAD` field still exists to keep the name honest *if* we ever track `main` past a tag; tracking the
tag exactly makes it `0` and the suffix drops.

- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track the chosen tag** (currently `0.5.5v` / `59`).
- `UPSTREAM_AHEAD` = commits our base sits past tag `v<VERSION_NAME>` (`git rev-list --count v0.5.5v..main`).
  Tracking the tag exactly → **`0`**. **Recomputed at rebase time** by the **upstream-new-version** skill; it
  does **not** change between builds, and does **not** affect `versionCode`.
- `BUILD_NUMBER` is **our** increment. It starts at `1` and bumps by `1` on every build with changes.
- Fork `versionName` = `"<VERSION_NAME>-<UPSTREAM_AHEAD>+<BUILD_NUMBER>"`. The `-<UPSTREAM_AHEAD>` is
  **dropped when it is `0`**, so on the tag it reads as the clean `"<VERSION_NAME>+<BUILD_NUMBER>"`
  (e.g. `0.5.5v+1`).
- Fork `versionCode` = `VERSION_CODE * 10000 + BUILD_NUMBER` (e.g. `59 * 10000 + 1 = 590001`).
- The arm64-v8a APK then gets upstream's per-ABI override: `3 * 10000000 + forkVersionCode`
  (e.g. `30590001`). This is **higher** than the previous `v0.5.5u` line (`3053xxxx`), so it installs as a
  normal **upgrade** — no uninstall needed.
- Output APK (copied to `~/tmp` by `buildFoss`) =
  `shiroikuma-kojiki_<VERSION_NAME>-<UPSTREAM_AHEAD>+<BUILD_NUMBER>_arm64-v8a.apk`
  (e.g. `shiroikuma-kojiki_0.5.5v+1_arm64-v8a.apk`).

So the first build on this base is `0.5.5v+1` (`590001` → `30590001`), the next build with changes is `+2`, and so on.

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

**firestack:** consumed as a prebuilt AAR. `gradle.properties` pins `firestackRepo=ossrh` +
`firestackCommit=22cfc49978` (the **`v0.5.5v` tag's engine**), resolved as
`com.celzero:firestack:22cfc49978@aar` from Maven Central (no GitHub token needed). **Do not bump this to a
`main` firestack** (e.g. `379ac52ace`) — that broke WG-relay SSH (see the versioning section). On this engine
the WG double-hop must run **full-tunnel with Lockdown ON** (per-app split wedges the resolver); the hub
supplies internet via NAT. See memory `[[wg-hub-and-dns-architecture]]`.

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
  + dialog/bottom-sheet/toast theming), `ColorPickerDialog` (an ARGB picker). The runtime pass runs from
  **`BaseActivity.onResume`** when the `Custom` theme is active (v0.5.5v *does* have a `BaseActivity`
  chokepoint, so the hook lives there, not on the Application as it did on v0.5.5u). `Themes.customThemeActive`
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
