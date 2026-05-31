---
name: upstream-new-version
description: Rebase our fork onto a new upstream release of celzero/rethink-app (RethinkDNS). Use when the user says a new upstream version is out, asks to update/sync to upstream, bump to the new Rethink release, or rebase custom onto the latest upstream.
---

# Rebase the fork onto a new upstream release

This codifies the "new upstream version" half of the fork workflow. The goal: move `main` to the new
upstream release, replay our `custom` customizations on top of it, and produce a fresh `+1` build.

> **Never `git push` or `git commit` unprompted, and never `adb install`.** Same hard rules as everyday
> development (see CLAUDE.md). After the rebase + build you stop and let the user test; you only
> `git commit` + `git push` when they explicitly type **"Push"**.

## Background — how versioning works here

- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track upstream** (currently `0.5.5u` / `58`).
- `BUILD_NUMBER` is **our** fork increment. It **resets to `1`** on each new upstream version.
- Fork `versionName` = `"<VERSION_NAME>+<BUILD_NUMBER>"`; `versionCode` = `VERSION_CODE * 10000 + BUILD_NUMBER`.
- The arm64-v8a APK then gets upstream's per-ABI override: `3 * 10000000 + forkVersionCode`.

So when upstream's `VERSION_CODE` climbs (e.g. 58 → 59), our fork's arm64 codes for the new line
(`30590001`, …) all exceed the previous line's (`30580001`, …), keeping upgrades monotonic.

## Steps

1. **Fetch upstream:**
   - `git fetch upstream --tags`
   - Identify the new release. Upstream develops on `main` (well ahead of the latest `v0.5.5x` tag), so
     usually you track `upstream/main`. Confirm the new `VERSION_CODE` from upstream's
     `gradle.properties`: `git show upstream/main:gradle.properties | grep -E 'VERSION_CODE'`
     and the `android:versionName` in `app/src/main/AndroidManifest.xml` (upstream's user-facing tag).
     Decide the new `VERSION_NAME` (the release tag without the leading `v`, e.g. `0.5.5v`).

2. **Advance `main` to the new upstream** (it mirrors upstream, no fork work lives there):
   - `git checkout main`
   - `git merge --ff-only upstream/main` (or `git reset --hard <tag>` to track an exact tag).

3. **Rebase `custom` onto the new `main`:**
   - `git checkout custom`
   - `git rebase main`
   - Resolve conflicts so **all** our customizations survive (see the table below). Conflict-prone files:
     `gradle.properties`, `app/build.gradle`, `app/src/main/AndroidManifest.xml`,
     `app/src/main/res/values/strings.xml`, `app/src/full/.../MiscSettingsActivity.kt`,
     `app/src/full/res/layout/activity_misc_settings.xml`. If upstream restructured a file we touch,
     **port** our change to the new structure rather than forcing the old diff.

4. **Update versioning in `gradle.properties`:**
   - Set `VERSION_NAME` / `VERSION_CODE` to the **new upstream** values.
   - **Reset `BUILD_NUMBER` to `1`.**

5. **Verify our customizations are intact** (after resolving the rebase):

   | What | Expected value | Where |
   | --- | --- | --- |
   | Installed app ID | `shiroikuma.kojiki` | `gradle.properties` → `APP_ID` |
   | Code namespace | `com.celzero.bravedns` (unchanged) | `gradle.properties` → `APP_NAMESPACE` |
   | App launcher label | `白い熊 考直` | `app_name` in `app/src/main/res/values/strings.xml` |
   | Fork version logic | `forkVersionName` / `forkVersionCode` + `buildFoss` task | `app/build.gradle` |
   | `applicationId = APP_ID`, `namespace = APP_NAMESPACE` | not swapped | `app/build.gradle` |
   | Release signing wired | `signingConfig signingConfigs.config` in `release` build type | `app/build.gradle` |
   | Feature 1 (receiver) | `receiver/SetAppRuleReceiver.kt` (main) + main manifest `<receiver>` + token in `PersistentState` + Misc settings row/dialog | several files |
   | Feature 1b (WG receiver) | `receiver/SetWgStateReceiver.kt` (full) + full manifest `<receiver>` (SET_WG_STATE) | `app/src/full/...` |
   | Feature 3 (Tasker) | token-based `SET_APP_RULE` / `SET_WG_STATE` broadcasts still resolvable | (client-side; verify after build) |

   Sanity check that the build script still evaluates:
   `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:tasks --group=build` (a config-only task).

6. **Build the new `+1`** via the **build-apk** skill
   (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFoss < /dev/null`), then **ask** before
   any `adb push`. This is the first build of the new upstream line (`<newVersion>+1`).

7. **Stop.** Let the user test. Commit/push only on their explicit **"Push"**: `custom` was rebased so
   it needs `git push --force-with-lease origin custom`; `main` is a fast-forward (`git push origin main`).

## Notes

- Keep our changes a **small, legible layer** on top of upstream — prefer rebasing (linear history) over
  merging, so the customization set stays easy to audit and replay. Keep edits additive / in new files
  (the receiver and skills are new files; the gradle/manifest/strings/settings edits are the only
  in-place touches and are the ones that may conflict).
- After resetting `BUILD_NUMBER` to `1`, the first `buildFoss` produces `<newVersion>+1` and bumps to `2`.
