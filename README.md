<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 考直 icon" />

# 白い熊 考直

**DNS + firewall + WireGuard VPN for Android — rebuilt in black and yellow, with eyes on every snoop.**

A fork of [RethinkDNS](https://github.com/celzero/rethink-app) with **major additions**: self-healing DNS (a watchdog + a patched engine), an on-device Snooping panel, per-app notes and app groups, a fully configurable UI theme + global font, portable category-based Export/Import, honest WireGuard status, and external automation intents.

Installs **side-by-side** with RethinkDNS (app id `shiroikuma.kojiki`).

**📥 Latest release: [`0.5.6+021`](https://github.com/ShiroiKuma0/shiroikuma-kojiki/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-kojiki/releases)

</div>

---

## 🩺 Self-healing DNS
Stock RethinkDNS could lose DNS for the whole device every few minutes: its engine pools DoH connections for 3 minutes while resolvers like Quad9 close idle ones after ≤30 seconds — so every quiet spell filled the pool with dead connections that queries then hung and died on. The fork fixes the engine (a 10-second pool + HTTP/2 PING health-checks, so dead connections are evicted before a query ever rides one) **and** adds a DNS watchdog beneath it: on a burst of upstream failures it recycles the full Go tunnel automatically; if the wedge returns right after, it fails DNS over to a spare resolver — each action announced by a notification. Validated on-device: zero wedge failures in worst-case traffic on the same resolver that previously failed all day.

---

## 🧭 Excluded apps that still resolve
On some phones (notably Huawei/EMUI), an app you fully *exclude* from the VPN has its DNS misrouted by the OS to the VPN's DNS server — which an excluded app can't reach — so it silently loses name resolution and its connectivity gets throttled. The fork advertises a real fallback resolver alongside the in-tunnel one: in-tunnel apps stay on the DoH (no leak), while an excluded app reaches the real resolver directly and resolves normally. Exclude an app and it stays excluded — without breaking its DNS.

---

## 🏠 Your own LAN, off the tunnel
A VPN that routes `0.0.0.0/0` swallows the local network too: with the phone and the PC on the same WiFi, every byte between them still went out to a WireGuard hub on the internet and back — **0.67 MB/s**, both legs sharing the one home uplink. Stock has a switch for this (*Do not route Private IPs*), but it subtracts **every** private range at once, which strands the WireGuard overlay the phone's own path home rides on: turn it on as shipped and the phone goes unreachable the moment you leave the house. The fork pins that overlay back into the tunnel while everything else private leaves by the real interface — so the LAN is direct **and** the way home survives. Across the room: **0.67 MB/s → roughly 2–8 up and 13–23 down**, with nothing to remember before you walk out the door.

---

## 🕵️ Snooping panel
A dedicated panel that watches DNS traffic **on the device itself** and surfaces which apps are phoning home to trackers and telemetry — classified in three tiers against the local blocklists, with per-domain **tags/categories** you create and assign yourself. Tap an app icon to filter the panel to that app, long-press to open it, sort and filter every which way, and cut a snoop off with one tap. Detection runs **independently of DNS logging** — turning query logs off does not blind it.

---

## 📝 Notes and groups on every app
Firewall rules say *what*; they can never say *why*. Every row in the apps view gets a **free-text note** — “do not exclude, DNS dies on this phone”, “needed for Hikvision” — shown inline on the row itself and edited in one tap, with a blank note deleting itself.

Rows also carry **groups** (profiles): named sets of apps shown as pills. Tap a pill to filter the list to that group, long-press to drop the app from it, “+” to add. And because the bulk-rule toolbar acts on whatever the filter selects, filtering to a group aims the entire toolbar at it — **one tap on a pill, one on “block on metered”, and the whole group is done.**

Both are keyed by package name, so they survive reinstalls and travel in Export/Import. Even the uid-only rows (root, `SYSTEM`, and any traffic no package accounts for — reachable through a **Non-app** filter) can be annotated; those are carried across devices with a ⚠ marker telling you to re-check them, never silently trusted.

---

## 🔢 An app list you can actually read
Every row leads its id line with the **uid** — `10050 · yqtrack.app` — the number adb, the connection logs and every firewall rule actually speak in; the uid-only rows, which have no package id to print, finally show something instead of a blank line. And the list **sorts**: by app name, package id, uid, or data used, ascending or descending, from a glyph beside the filter icon — tap the marked key again to reverse it. Your choice sticks between visits, and the fast-scroll bubble follows the key you picked instead of always showing a letter.

---

## 🧬 Shared uids, said out loud
A firewall rule cannot name an app: Android hands the network stack a **uid**, so packages sharing one — a dozen `com.huawei.*` services under uid 1000, say — are a single principal that no rule can tell apart. Stock shows them as ordinary separate rows and only mentions it once you are already applying a rule. Here every such row is marked **`×11`** on its id line, both per-app confirmations explain *why* one tap moves eleven apps (and point at the one axis that does discriminate below a uid — per-app domain and IP rules), and the bulk toolbar names the apps a rule will reach **outside** your current filter because they share a uid with something inside it.

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
A fork of [celzero/rethink-app](https://github.com/celzero/rethink-app) (app id `shiroikuma.kojiki`, so it coexists with the official build). All the heavy lifting — the userspace WireGuard engine, the OpenSnitch-style firewall, the DNS-over-HTTPS/TLS/DNSCrypt client — is RethinkDNS and its [firestack](https://github.com/celzero/firestack) data plane; this fork tracks upstream’s released tags (currently `v0.5.6`), shipping the tag’s pinned engine with a small fork patch for the DoH idle-pool wedge (offered upstream as [firestack#241](https://github.com/celzero/firestack/issues/241), and since **partly adopted** — upstream now shortens the pool to 30 s, though that still sits exactly on the shortest observed resolver idle window and adds no HTTP/2 PING health-checks, so the fork keeps its own 10 s + PING patch on top). The code remains under Apache-2.0.

## Building
```bash
git clone https://github.com/ShiroiKuma0/shiroikuma-kojiki.git
cd shiroikuma-kojiki
# needs JDK 21 and an Android SDK (sdk.dir in local.properties)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFoss
```
`buildFoss` assembles the signed, de-Googled `fdroidFullRelease` variant and copies the arm64-v8a APK to `~/tmp/`. Signing credentials go in a gitignored `keystore.properties`; without them the build is unsigned.
