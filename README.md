<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 考直 icon" />

# 白い熊 考直

**DNS + firewall + WireGuard VPN for Android — rebuilt in black and yellow, with eyes on every snoop.**

A fork of [RethinkDNS](https://github.com/celzero/rethink-app) with **major additions**: self-healing DNS (a watchdog + a patched engine), an on-device Snooping panel, a fully configurable UI theme + global font, portable category-based Export/Import, honest WireGuard status, and external automation intents.

Installs **side-by-side** with RethinkDNS (app id `shiroikuma.kojiki`).

**📥 Latest release: [`0.5.5y+13`](https://github.com/ShiroiKuma0/shiroikuma-kojiki/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-kojiki/releases)

</div>

---

## 🩺 Self-healing DNS
Stock RethinkDNS could lose DNS for the whole device every few minutes: its engine pools DoH connections for 3 minutes while resolvers like Quad9 close idle ones after ≤30 seconds — so every quiet spell filled the pool with dead connections that queries then hung and died on. The fork fixes the engine (a 10-second pool + HTTP/2 PING health-checks, so dead connections are evicted before a query ever rides one) **and** adds a DNS watchdog beneath it: on a burst of upstream failures it recycles the full Go tunnel automatically; if the wedge returns right after, it fails DNS over to a spare resolver — each action announced by a notification. Validated on-device: zero wedge failures in worst-case traffic on the same resolver that previously failed all day.

---

## 🧭 Excluded apps that still resolve
On some phones (notably Huawei/EMUI), an app you fully *exclude* from the VPN has its DNS misrouted by the OS to the VPN's DNS server — which an excluded app can't reach — so it silently loses name resolution and its connectivity gets throttled. The fork advertises a real fallback resolver alongside the in-tunnel one: in-tunnel apps stay on the DoH (no leak), while an excluded app reaches the real resolver directly and resolves normally. Exclude an app and it stays excluded — without breaking its DNS.

---

## 🕵️ Snooping panel
A dedicated panel that watches DNS traffic **on the device itself** and surfaces which apps are phoning home to trackers and telemetry — classified in three tiers against the local blocklists, with per-domain **tags/categories** you create and assign yourself. Tap an app icon to filter the panel to that app, long-press to open it, sort and filter every which way, and cut a snoop off with one tap. Detection runs **independently of DNS logging** — turning query logs off does not blind it.

---

## 🎨 白い熊 考直 UI
A **default “Custom” theme** with every colour user-configurable via full-ARGB pickers (background, accent, text) plus a **global font** — family, weight, and size — applied app-wide at runtime: dialogs, bottom sheets, toasts, the start button, log tables, everything. Ships seeded with 白い熊’s black `#000000` / pure-yellow `#FFFF00` look, matching the black-and-yellow launcher icon.

---

## 📦 Export / Import
A category-based, all-JSON-in-a-ZIP Export/Import that **replaces** the stock backup/restore — ten independent categories (app settings, appearance + fonts, snoop tags, firewall apps/domains/IPs, WireGuard, blocklist selection, DNS endpoints, proxies). Portable by construction: per-app rules key on **package name**, not uid, so an export survives reinstalls and fresh devices; rules for not-yet-installed apps park and apply automatically on install. Lives at the top of the 白い熊 考直 UI page.

---

## 🔒 Honest WireGuard status
Stock Rethink reads a tunnel as “Failing” even while traffic flows through it. The fork checks what the tunnel is actually doing — on the config list, the detail screen, **and the home Proxy card** — so the status you read is the status you have. Each tunnel also gains a standard wg-quick **`.conf` export** button, making configs portable across installs and keystores.

---

## 🤖 Automation intents
Three broadcast receivers let external tools (Tasker, adb, companion apps) drive the app by intent, all gated by one shared token: set a **per-app firewall rule**, **enable/disable a WireGuard tunnel**, or run a **headless backup** — the last one exports the chosen categories to a caller-supplied folder with no UI at all, reporting live progress in real counts and replying with the written path and byte size. It also states which categories start ticked, so a remote picker never has to guess, and it can be **cancelled mid-run**: the export unwinds at the next category boundary and deletes its half-written archive, leaving the backup folder exactly as it found it. That makes 考直 one target in a fleet-wide backup run: a single automation task can back up every app on the phone, this one included, and get a per-app ✓/✗ summary back.

---

## Built on RethinkDNS
A fork of [celzero/rethink-app](https://github.com/celzero/rethink-app) (app id `shiroikuma.kojiki`, so it coexists with the official build). All the heavy lifting — the userspace WireGuard engine, the OpenSnitch-style firewall, the DNS-over-HTTPS/TLS/DNSCrypt client — is RethinkDNS and its [firestack](https://github.com/celzero/firestack) data plane; this fork tracks upstream’s released tags (currently `v0.5.5y`), shipping the tag’s pinned engine with a small fork patch for the DoH idle-pool wedge (offered upstream). The code remains under Apache-2.0.

## Building
```bash
git clone https://github.com/ShiroiKuma0/shiroikuma-kojiki.git
cd shiroikuma-kojiki
# needs JDK 21 and an Android SDK (sdk.dir in local.properties)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFoss
```
`buildFoss` assembles the signed, de-Googled `fdroidFullRelease` variant and copies the arm64-v8a APK to `~/tmp/`. Signing credentials go in a gitignored `keystore.properties`; without them the build is unsigned.
