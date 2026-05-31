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

The app ID is deliberately changed so this fork installs **alongside** upstream Rethink without
conflict. The namespace is intentionally kept as `com.celzero.bravedns` so `R`/`BuildConfig`, all
source packages, and intent action strings remain unchanged — only the installed package id differs.

### Versioning & APK naming

We base our version on upstream and add a fork increment (`BUILD_NUMBER`).

- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track upstream** (currently `0.5.5u` / `58`).
- `BUILD_NUMBER` is **our** increment. It starts at `1` and bumps by `1` on every build with changes.
- Fork `versionName` = `"<VERSION_NAME>+<BUILD_NUMBER>"` (e.g. `0.5.5u+1`).
- Fork `versionCode` = `VERSION_CODE * 10000 + BUILD_NUMBER` (e.g. `58 * 10000 + 1 = 580001`).
- The arm64-v8a APK then gets upstream's per-ABI override: `3 * 10000000 + forkVersionCode`
  (e.g. `30580001`), which stays monotonic across builds and upstream bumps.
- Output APK (copied to `~/tmp` by `buildFoss`) = `shiroikuma-kojiki_<VERSION_NAME>+<BUILD_NUMBER>_arm64-v8a.apk`
  (e.g. `shiroikuma-kojiki_0.5.5u+1_arm64-v8a.apk`).

So the first build is `+1` (`580001` → `30580001`), the next build with changes is `+2`, and so on.

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
We ship **`fdroidFull`** (de-Googled: the `deGoogled` logic skips Firebase when the task name contains
`fdroid`). The FOSS release variant/task is therefore `fdroidFullRelease` / `assembleFdroidFullRelease`.

**firestack:** consumed as a prebuilt AAR. `gradle.properties` pins `firestackRepo=ossrh` +
`firestackCommit=379ac52ace`, resolved as `com.celzero:firestack:379ac52ace@aar` from Maven Central
(no GitHub token needed).

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
- **`receiver/`** — broadcast receivers, including our `SetAppRuleReceiver` (Feature 1).
- **`database/`** — Room DB + DAOs/repositories (e.g. `AppInfo`, connection tracking).
- **`ui/`** — activities/fragments/adapters; **`util/`** — helpers; **DI** via Koin.
- Per-app firewall state lives in `FirewallManager` (`FirewallStatus`: `BYPASS_UNIVERSAL(2)`,
  `EXCLUDE(3)`, `ISOLATE(4)`, `NONE(5)`, `UNTRACKED(6)`, `BYPASS_DNS_FIREWALL(7)`;
  `ConnectionStatus`: `BOTH(0)`, `UNMETERED(1)`, `METERED(2)`, `ALLOW(3)`).
  `suspend fun updateFirewallStatus(uid, firewallStatus, connectionStatus)` applies a rule;
  `getAppInfoByPackage(pkg)` / `getAppInfoByUid` / `getPackageNameByUid` resolve apps.

## Key Configuration Files

- `gradle.properties` — fork app id/namespace, version name/code, `BUILD_NUMBER`, firestack pin.
- `app/build.gradle` (Groovy) — Android config, flavors, signing, fork version logic, the `buildFoss` task.
- `keystore.properties` — signing config (gitignored; points to `~/.android-keystores/shiroikuma-denwa.jks`).
- `local.properties` — `sdk.dir` (gitignored).
