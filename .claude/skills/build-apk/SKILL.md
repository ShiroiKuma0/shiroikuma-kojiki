---
name: build-apk
description: Build the signed FOSS (fdroidFull) release APK with the buildFoss Gradle task, then deliver it automatically via the global /after-build skill (adb push if a phone is connected, else scp to skhw — no transfer prompt). ALWAYS build automatically after making code changes that are ready to test — and whenever the user asks to build — without asking permission to build first. Use after completing any code change in this repo, or whenever the user asks to build the app, build the APK, make a release build, or build and send to the phone.
---

# Build the FOSS release APK and optionally send to phone

> **Never ask whether to build — just build.** This skill applies **automatically the
> moment you finish any code change in this repo** (as well as whenever the user explicitly
> asks to build). As soon as a change is complete and ready to test, run the build right
> away — do not wait to be told. Do **not** ask "shall I build?" / "want me to run
> buildFoss?" / "want me to build the APK so you can test?" — every such question is wrong.
> Delivery is automatic too: after a successful build, the APK is sent via the global
> **/after-build** skill with no transfer prompt. So: finish the change → always build →
> *then* deliver via /after-build.

> **The push destination is ALWAYS `/sdcard/tmp/`.** Every `adb push` of the APK
> goes to `/sdcard/tmp/<apk name>` — **never** `/sdcard/Download/` or anywhere
> else. Create `/sdcard/tmp` if needed and push there.

> **Never run `adb install` (or `pm install`).** The /after-build delivery may copy the APK
> to the phone with `adb push` (automatically, no prompt), but
> **the user installs the APK themselves** from the phone's file manager. Do not
> install it for them under any circumstances.

> **Never `git commit` or `git push` on your own.** Building does not include
> committing. After building (and the optional `adb push`), the user tests the
> build themselves. **Only when the user explicitly types "Push"** do you then
> `git commit` the changes and `git push origin custom` (use `--force-with-lease`
> if `custom` was rebased). The user's **"Push"** means *commit-and-push-to-the-fork*
> — it is unrelated to the `adb push` file copy in step 4.

> **ALWAYS end every build by delivering the APK via the global /after-build skill** —
> it runs `/adb-check` UNSANDBOXED, then `/adb-push` to `/sdcard/tmp/` if a phone is
> connected, otherwise `/scp` to `skhw:~/tmp/`, announcing the filename. This is
> mandatory and applies to *every* successful build, even verification builds and even
> when the user didn't mention transferring. Never ask "scp or adb push?" or "is the
> phone connected?" — /after-build decides on its own.

## Steps

1. **Note the output filename.** Read the current version and build number:
   - `grep -E 'VERSION_NAME|VERSION_CODE|UPSTREAM_AHEAD|BUILD_NUMBER' gradle.properties`
   - The APK will be `shiroikuma-kojiki_<VERSION_NAME>-<UPSTREAM_AHEAD>+<BUILD_NUMBER>_arm64-v8a.apk`
     (the `-<UPSTREAM_AHEAD>` part is dropped when it is `0`), using the `BUILD_NUMBER` value **before**
     the build (the task bumps it afterward). E.g. `shiroikuma-kojiki_0.5.5u+1_arm64-v8a.apk`
     (`UPSTREAM_AHEAD=0`, so no `-N`).
   - The arm64-v8a installed `versionCode` = `3 * 10000000 + (VERSION_CODE * 10000 + BUILD_NUMBER)`
     (e.g. for `53` / `1`: `30530001`). `buildFoss` prints this as `>>> versionCode <n>`.

2. **Build** (needs JDK 21 — the default `java` on this machine is too old for the toolchain;
   Gradle's auto-provisioned JDK 17 toolchain at `~/.gradle/jdks/` is used for compilation):
   - `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFoss < /dev/null`
     (the `< /dev/null` guarantees it never blocks on stdin)
   - `buildFoss` runs `assembleFdroidFullRelease` (the de-Googled FOSS release; Firebase is skipped
     for `fdroid` builds), copies the signed **arm64-v8a** split APK to `~/tmp/<apk name>`, and
     auto-increments `BUILD_NUMBER` in `gradle.properties`.
   - The task prints `>>> <path>` and `>>> versionCode <n>`; use those to confirm the exact filename
     and code, and confirm `BUILD SUCCESSFUL`.
   - The build runs writes to the shared `~/.gradle` cache and downloads the firestack AAR, so it must
     run with the sandbox disabled (`dangerouslyDisableSandbox: true`) — the established pattern here.

3. **At the end of every build, ALWAYS deliver via the global /after-build skill** — no exceptions,
   no asking. As soon as the build reports `BUILD SUCCESSFUL` and the APK is in `~/tmp/`, invoke
   **/after-build**: it runs `/adb-check` UNSANDBOXED (a sandboxed check falsely reports no device),
   then `/adb-push` to `/sdcard/tmp/` if a phone is connected, otherwise `/scp` to `skhw:~/tmp/`, and
   announces the filename that landed. Never `adb install` — the user installs manually from
   `/sdcard/tmp/`.

## Note — deliver directly, do not rely on a task prompt

This repo's `buildFoss` task (`app/build.gradle`) has **no** interactive prompt — it only builds,
copies the arm64-v8a APK to `~/tmp`, and bumps `BUILD_NUMBER`. Delivering the APK via **/after-build**
is Claude's job (step 3), done automatically after the build.

## Build details

- **Flavor/variant:** dimension `releaseChannel` = {`play`, `fdroid`, `website`}, dimension
  `releaseType` = {`full`}. We ship **`fdroidFull`** (de-Googled). The release task is
  `assembleFdroidFullRelease`; release builds produce split APKs (x86, armeabi-v7a, arm64-v8a, x86_64)
  plus a universal APK — `buildFoss` picks the **arm64-v8a** split.
- **firestack** is consumed as a prebuilt AAR, pinned in `gradle.properties`
  (`firestackRepo=ossrh`, `firestackCommit=379ac52ace`), resolved from Maven Central.

## Signing

Release signing is non-interactive: `app/build.gradle` reads credentials from `keystore.properties`
(falling back to `SIGNING_*` env vars). This fork reuses the `shiroikuma-denwa` keystore
(`~/.android-keystores/shiroikuma-denwa.jks`, alias `denwa`); `keystore.properties` is gitignored.
Upstream's `release` build type doesn't assign a signing config (it signs in CI), so the fork wires
`signingConfig signingConfigs.config` into `release` when creds are present. If neither
`keystore.properties` nor the env vars are present the build is unsigned and the APK will not install.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
