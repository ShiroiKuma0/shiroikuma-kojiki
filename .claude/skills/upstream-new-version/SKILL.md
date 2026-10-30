---
name: upstream-new-version
description: Rebase our fork onto a new upstream release of celzero/rethink-app (RethinkDNS). Use when the user says a new upstream version is out, asks to update/sync to upstream, bump to the new Rethink release, or rebase custom onto the latest upstream.
---

# Rebase the fork onto a new upstream release

This codifies the "new upstream version" half of the fork workflow: move `main` to the new upstream release
tag, rebuild the patched firestack engine on that tag's pin, replay our `custom` customizations on top, and
produce a fresh `+001` build. **`CLAUDE.md` is the reference for every fork feature and gotcha** — this skill
is the procedure; read the feature table there before resolving conflicts.

> **Never `git push` or `git commit` unprompted, and never `adb install`.** Same hard rules as everyday
> development (see CLAUDE.md). The rebase itself rewrites commits, but the post-rebase fixes stay uncommitted
> until 白い熊 types **"Push"**. After the build + delivery you stop and let 白い熊 test.

## Background — track a *released tag*, not `main`

Released tags ship a **proven** firestack; upstream's `main` once dragged in an engine (`379ac52ace`) that
broke WG-relay SSH. So the base is always the latest official **release tag** (`git reset --hard <tag>`),
giving `UPSTREAM_AHEAD=0`. Only track `upstream/main` if 白い熊 explicitly asks.

Versioning (all in `gradle.properties`, consumed by `app/build.gradle.kts`):

- `VERSION_NAME` / `VERSION_CODE` = the tag's (as of 2026-10-30: `0.5.8` / `70`).
- `UPSTREAM_AHEAD` = `git rev-list --count v<VERSION_NAME>..main` → `0` on the tag, and the `-N` drops.
- `BUILD_NUMBER` resets to **`1`** per new tag; displayed zero-padded (`+001`).
- `versionName` = `<VERSION_NAME>+<NNN>`; `versionCode` = `VERSION_CODE * 10000 + BUILD_NUMBER`; arm64 =
  `3 * 10000000 + versionCode` (e.g. `30700001`), always above the previous line, so it installs as an upgrade.
- `firestackRepo=local` (the self-built patched AAR in `app/libs/tun2socks.aar`); `firestackCommit` = the
  tag's pin, recorded for reference (inert while `local`).

## Steps

### 1. Fetch and read the new tag

```bash
git fetch upstream --tags
git tag --list 'v0.5*' --sort=-v:refname | head -3
git show v<NEW>:gradle.properties | grep -E 'VERSION_CODE|firestackCommit|firestackRepo'
git rev-list --count v<OLD>..v<NEW>; git diff --stat v<OLD> v<NEW> | tail -1
```

Also look for **structural moves** before replaying anything: `git diff --stat v<OLD> v<NEW>` for renamed or
deleted directories and build files. v0.5.7 moved the whole Kotlin source set (`app/src/full/java` →
`app/src/main/java`); v0.5.8 moved the Gradle build to the Kotlin DSL. Each needed its own strategy — see
"Rebasing onto a new upstream release" in `CLAUDE.md`.

### 2. Check the DB migration FIRST

Every upstream release that adds a Room migration collides with the fork's `SnoopEvent` migration (four times
so far). Compare:

```bash
f=app/src/main/java/com/celzero/bravedns/database/AppDatabase.kt
git diff v<OLD> v<NEW> -- $f                # upstream's new version + MIGRATION_*
git show custom:$f | grep -n 'version = \|Migration('
```

If upstream took our number: keep upstream's migration untouched, move ours above its whole chain
(`MIGRATION_<N>_<N+1>`, `version = N+1`), and make ours **also apply upstream's skipped step idempotently**
(`CREATE TABLE IF NOT EXISTS` with upstream's exact DDL, or `ALTER TABLE` guarded by
`doesColumnExistInTable`) — a phone on the fork lineage is already stamped at the colliding number and Room
will skip upstream's step. Keep every earlier reconciliation. Check four paths: fresh install · a DB at the
pre-collision number · the fork lineage · the upstream lineage. Memory: `[[kojiki-db-migration-collision]]`.

### 3. Start the engine build (runs while you rebase)

```bash
cd ~/git/firestack && git fetch origin
git log --oneline <old-pin>..<new-pin>; git diff --stat <old-pin> <new-pin>
git checkout -b kojiki-doh-idle-<tag> <new-pin>
git cherry-pick <doh-patch> <alg-patch>     # the two commits on top of the previous kojiki-doh-idle-* branch
```

- **Re-check whether upstream adopted either patch** — `intra/doh/doh.go` (h2 PING health-checks; drop our DoH
  patch only if upstream ships the PING eviction too) and `intra/dnsx/alg.go` / `intra/common.go` (ALG).
- Toolchain: `go.mod` names the Go version; upstream's `jitpack.yml` names the NDK (as of `d3d530ea3e`:
  Go 1.27 at `~/goroot/go1.27.1`, NDK `30.0.16248370`). Install a missing one before building.
- `rm -rf build`, then build **detached** (a few minutes):

```bash
setsid nohup bash -c 'export GOROOT=$HOME/goroot/go1.27.1 PATH=$HOME/goroot/go1.27.1/bin:$HOME/git/firestack/bin:$PATH \
  GOPATH=$HOME/go ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk \
  ANDROID_NDK_HOME=$HOME/android-sdk/ndk/<ndk>; make -C $HOME/git/firestack intra; echo EXIT=$?' \
  > <scratchpad>/firestack-build.log 2>&1 < /dev/null & disown
```

Recipe details: memory `[[firestack-from-source-build]]`.

### 4. Advance `main` and rebase `custom`

```bash
git config rerere.enabled true
git branch -f custom-pre-<tag> custom          # names match publish-version's cleanup globs
git branch -f pre-<tag>-main-rebase main
git checkout main && git reset --hard v<NEW>
git checkout custom && git rebase main
.claude/skills/upstream-new-version/rebase-loop.sh      # re-run after each hand resolution
```

`rebase-loop.sh` auto-resolves only the mechanical conflicts: `README.md` and `gradle.properties` take the
replayed commit's side, and Groovy build files deleted upstream are `git rm`-ed. Every other conflict stops
for a decision. Resolution rules that held on v0.5.8:

- **Upstream restructured something we touched** → port our change onto the new structure; never force the
  old diff back. If upstream replaced a dialog of ours with its own sheet (Tasker, pcap on v0.5.8), upstream's
  version wins.
- **Union, don't pick**, where both sides added lines (e.g. `onPostCreate` keeps upstream's new
  `TextSelectionGuard` line but still leaves out `applyMaxContentWidth()`).
- **Strings** → upstream's wording, re-branded (“Rethink” → “白い熊 考直”).
- **`AppDatabase.kt`** → resolve straight into the final migration shape from step 2 at the first conflict.
  Then **re-read the final file**: later commits that touched the old migration may merge into the wrong
  block silently.

### 5. Finish the files the replay left behind

- **`gradle.properties`**: `VERSION_NAME`/`VERSION_CODE` = the tag's, `UPSTREAM_AHEAD=0`, `BUILD_NUMBER=1`,
  `firestackRepo=local`, `firestackCommit=<new pin>`, and update the engine comment block (branch name,
  pin, Go/NDK).
- **Build scripts** (Kotlin DSL since v0.5.8): `app/build.gradle.kts` must carry the `foss` de-Google gate,
  `forkVersionName`/`forkVersionCode` (used for the `full` flavor's `versionCode`, `versionName` and
  `BASE_VERSION_CODE`), `namespace = APP_NAMESPACE`, `applicationId = APP_ID`, the `SIGNING_*` env fallback,
  `"local" -> ":tun2socks@aar"` in `firestackDependency()`, `androidx.documentfile`, and the `buildFoss`
  task; `settings.gradle.kts` carries the `firestackRepo=local` `flatDir`. If upstream changed the build
  files again, port the whole fork delta in one piece:
  `git diff v<OLD> custom-pre-<tag> -- app/build.gradle* settings.gradle* build.gradle*`.
- **Engine**: when `EXIT=0` is in the log, `cp ~/git/firestack/build/intra/tun2socks.aar app/libs/`.

### 6. Verify the customizations

Config-only sanity check: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:tasks --group=build`
(must list `buildFoss`). Then, per `CLAUDE.md`'s feature table:

| Check | How |
| --- | --- |
| Every dialog bordered | `grep -rln 'MaterialAlertDialogBuilder(' app/src/main app/src/full` finds only `KojikiAlertDialogBuilder.kt`; sweep new call sites to `KojikiAlertDialogBuilder` (+ its import) |
| Full width (Feature 9) | no `applyMaxContentWidth()` call in `BaseActivity.onPostCreate`; `grep 720dp` finds only the fork's comments in both `fragment_home_screen.xml` |
| Private-IPs toggle (Feature 7) | `HomeScreenActivity.removeThisMethod` does not assign `privateIps`; `FORK_WG_OVERLAY4` still pinned in `BraveVPNService.addRoute4` |
| App sort (Feature 6f) | if upstream changed `AppInfoDAO`'s paged queries, mirror the WHERE in `getSortedApps` |
| Frozen apps (Feature 10) | `RefreshDatabase` still queries through `KojikiFrozenApps.installedPackages` |
| Parked rules / notes | `KojikiPendingFw.applyTo` + `KojikiAppNotes.applyParked` hooks in `RefreshDatabase` |
| WG export (Feature 4) | `KojikiExport.exportWireGuard` reads plaintext configs via `WireguardConfigFileManager` |
| CustomUi hook | `BaseActivity.onResume` |
| Receivers | `SET_APP_RULE` / `SET_WG_STATE` in the manifests; check any new upstream receiver for an action clash (v0.5.8's `VpnControlReceiver` has its own) |
| Rebrand | every `values*/strings.xml` string still carrying “Rethink” is either a kept name (`about_*`, `contributors_list`, `download_ip_info_desc`, `rethink_terms`, `whats_new_version_update`, the Rethink+/RPN product names, the TV `app_name`) or gets rebranded; URLs/domains stay |
| Firebase | no `import com.google.firebase` in `service/BraveVPNService.kt` |

### 7. Build `+001` and deliver

Build via the **build-apk** skill (detached; `buildFoss` bumps `BUILD_NUMBER` to `2`), then confirm the
APK's `lib/arm64-v8a/libgojni.so` carries the patched engine on the new base — marker strings, not hashes:
`resolved alg domain` (ALG patch) plus a string literal new to the pin (find one with
`git diff <old-pin> <new-pin> -- '*.go' | grep '^+'`, and confirm the previous APK lacks it). Deliver with
the global **/after-build** skill.

### 8. Stop

Report what needed real decisions, and list the remaining work numbered. On 白い熊's **"Push"**: commit the
post-rebase fixes as one `fork: adapt to the v<NEW> base …` commit, `git push --force-with-lease origin
custom`, `git push origin main`. Then update `CLAUDE.md` (base, migration, engine branch, any new
structural lesson) and the memories. The `custom-pre-<tag>` / `pre-<tag>-main-rebase` branches are deleted by
**publish-version** once the release is out.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with
Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides
the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
