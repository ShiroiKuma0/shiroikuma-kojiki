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

## Background — track a *released tag*, not `main`

> **Why a tag, not `main`.** We previously synced to `upstream/main`, which dragged in a **bleeding-edge
> firestack** (`379ac52ace`, the late-March-2026 `TNT/TZZ` wgproxy rework). That engine **reset the first
> SSH flow after the WG double-hop relay idled** — phone→PC `ssh` failed intermittently. Released tags ship
> a **proven** firestack. So the default base is the latest official **release tag** `v0.5.5x`
> (`git reset --hard <tag>`), giving `UPSTREAM_AHEAD=0` (honest name, no `-N` suffix). See
> `~/tmp/kojiki-rebase-handoff.md` and memory `[[rethinkdns-wg-flap]]`. Only track `upstream/main` if 白い熊
> explicitly wants newer commits and accepts that firestack.

- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track the chosen upstream tag** (currently
  `0.5.5u` / `53` — the `v0.5.5u` tag).
- **`firestackCommit` is pinned to the chosen tag's value** (currently `61187f88c1`). Read it straight from
  the tag — never inherit a `main` firestack commit (step 4b).
- `UPSTREAM_AHEAD` = commits our base sits past tag `v<VERSION_NAME>`
  (`git rev-list --count v<VERSION_NAME>..main`). When we track the tag exactly (the default) this is **`0`**
  and the `-N` suffix drops. **You recompute it here** (step 4); it does **not** affect `versionCode`.
- `BUILD_NUMBER` is **our** fork increment. It **resets to `1`** on each new upstream version.
- Fork `versionName` = `"<VERSION_NAME>-<UPSTREAM_AHEAD>+<BUILD_NUMBER>"` (the `-<UPSTREAM_AHEAD>` is
  dropped when `0`, i.e. when we track an exact tag); `versionCode` = `VERSION_CODE * 10000 + BUILD_NUMBER`.
- The arm64-v8a APK then gets upstream's per-ABI override: `3 * 10000000 + forkVersionCode`.

So when upstream's `VERSION_CODE` climbs (e.g. 58 → 59), our fork's arm64 codes for the new line
(`30590001`, …) all exceed the previous line's (`30580001`, …), keeping upgrades monotonic.

## Steps

1. **Fetch upstream and pick the release tag:**
   - `git fetch upstream --tags`
   - List recent release tags: `git tag --list 'v0.5.5*' --sort=-v:refname | head`. Pick the **latest
     official release tag** `v<VERSION_NAME>` (the default), **or** the exact tag 白い熊's working stock runs.
   - Read that tag's `VERSION_CODE` **and** firestack pin: `git show v<VERSION_NAME>:gradle.properties |
     grep -E 'VERSION_CODE|firestackCommit|firestackRepo'`. The new `VERSION_NAME` is the tag without the
     leading `v` (e.g. `0.5.5v`).

2. **Advance `main` to the release tag** (it mirrors upstream, no fork work lives there):
   - `git checkout main`
   - `git reset --hard v<VERSION_NAME>` — track the **exact tag** (base == tag ⇒ `UPSTREAM_AHEAD=0`).
     (Only `git merge --ff-only upstream/main` if 白い熊 explicitly wants to track `main` past the tag.)

3. **Rebase `custom` onto the new `main`:**
   - `git checkout custom`
   - `git rebase main`
   - Resolve conflicts so **all** our customizations survive (see the table below). Conflict-prone files:
     `gradle.properties`, `app/build.gradle`, `app/src/main/AndroidManifest.xml`,
     `app/src/main/res/values/strings.xml`, `app/src/full/.../MiscSettingsActivity.kt`,
     `app/src/full/res/layout/activity_misc_settings.xml`. If upstream restructured a file we touch,
     **port** our change to the new structure rather than forcing the old diff.

4. **Update versioning + firestack pin in `gradle.properties`:**
   - **(4a) Version:** set `VERSION_NAME` / `VERSION_CODE` to the chosen tag's values (`VERSION_NAME` = tag
     without the leading `v`). **Recompute `UPSTREAM_AHEAD`** = `git rev-list --count v<VERSION_NAME>..main`;
     when we track the tag exactly (the default) this is `0` and the `-N` suffix drops. **Reset `BUILD_NUMBER`
     to `1`.**
   - **(4b) firestack pin (the whole point of tag-tracking):** set `firestackRepo` / `firestackCommit` to the
     chosen tag's values (from step 1: `git show v<VERSION_NAME>:gradle.properties | grep firestack`). **Never
     let a `main` firestack commit (e.g. `379ac52ace`) survive the rebase** — that engine broke WG-relay SSH.

   **Gotchas when the base is an older tag than the previous build** (e.g. the `0.5.5u` downgrade from `main`):
   - `app/build.gradle` de-Google gate: older bases use `isFdroidBuild = taskNames.contains("fdroid")`, which
     misses our `buildFoss` task → keep the fork's `|| taskNames.contains("foss")` so Firebase is skipped.
   - A dangling `import com.google.firebase.Firebase` in `service/BraveVPNService.kt` (unused) fails the
     de-Googled compile on some bases — remove it if the build reports `Unresolved reference 'firebase'`.
   - `UPSTREAM_AHEAD=0` lowers `versionCode` vs a `main`-based build (e.g. `30530001` < `30580001`), so it
     installs as a **downgrade** — 白い熊 must uninstall the previous build first.

5. **Verify our customizations are intact** (after resolving the rebase):

   | What | Expected value | Where |
   | --- | --- | --- |
   | Installed app ID | `shiroikuma.kojiki` | `gradle.properties` → `APP_ID` |
   | Code namespace | `com.celzero.bravedns` (unchanged) | `gradle.properties` → `APP_NAMESPACE` |
   | App launcher label | `白い熊 考直` | `app_name` in `app/src/main/res/values/strings.xml` |
   | Fork version logic | `forkVersionName` (incl. `UPSTREAM_AHEAD` suffix) / `forkVersionCode` + `buildFoss` task | `app/build.gradle` |
   | `applicationId = APP_ID`, `namespace = APP_NAMESPACE` | not swapped | `app/build.gradle` |
   | Release signing wired | `signingConfig signingConfigs.config` in `release` build type | `app/build.gradle` |
   | firestack pinned to tag | `firestackCommit` = the tag's value (e.g. `61187f88c1`), **not** a `main` commit | `gradle.properties` |
   | de-Google gate covers buildFoss | `isFdroidBuild = … || taskNames.contains("foss")` | `app/build.gradle` |
   | Feature 1 (receiver) | `receiver/SetAppRuleReceiver.kt` (main) + main manifest `<receiver>` + token in `PersistentState` + Misc settings row/dialog | several files |
   | Feature 1b (WG receiver) | `receiver/SetWgStateReceiver.kt` (full) + full manifest `<receiver>` (SET_WG_STATE) | `app/src/full/...` |
   | Feature 2 (honest WG status) | `UIUtils.honestWgStatusId` + 3 call-sites; needs firestack `RouterStats.rx/lastOK`, `Backend.TOK/TKO` (drop if absent) | `app/src/full/...` |
   | Feature 3 (白い熊 考直 UI) | `customui/*` + `KojikiUiActivity` + `Themes.CUSTOM`; runtime `CustomUi.applyTo` hook lives in **`RethinkDnsApplication.onActivityResumed`** (move to `BaseActivity.onResume` only if that class exists on the new base) | `app/src/full/...` |
   | Feature (Tasker) | token-based `SET_APP_RULE` / `SET_WG_STATE` broadcasts still resolvable | (client-side; verify after build) |

   Sanity check that the build script still evaluates:
   `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:tasks --group=build` (a config-only task).

6. **Build the new `+1`** via the **build-apk** skill
   (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFoss < /dev/null`), then deliver it via
   the global **/after-build** skill (no transfer prompt). This is the first build of the new upstream line (`<newVersion>+1`).

7. **Stop.** Let the user test. Commit/push only on their explicit **"Push"**: `custom` was rebased so
   it needs `git push --force-with-lease origin custom`; `main` is a fast-forward (`git push origin main`).

## Notes

- Keep our changes a **small, legible layer** on top of upstream — prefer rebasing (linear history) over
  merging, so the customization set stays easy to audit and replay. Keep edits additive / in new files
  (the receiver and skills are new files; the gradle/manifest/strings/settings edits are the only
  in-place touches and are the ones that may conflict).
- After resetting `BUILD_NUMBER` to `1`, the first `buildFoss` produces
  `<newVersion>-<UPSTREAM_AHEAD>+1` (or `<newVersion>+1` when `UPSTREAM_AHEAD` is `0`) and bumps to `2`.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
