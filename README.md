<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 考直 icon" />

# 白い熊 考直

**DNS + firewall + WireGuard VPN for Android — rebuilt in black and yellow, with eyes on every snoop.**

A fork of [RethinkDNS](https://github.com/celzero/rethink-app) with **major additions**: self-healing DNS (a watchdog + a patched engine), connections that recover instead of dying when a DNS mapping ages out, per-app time-ordered logs, an on-device Snooping panel, per-app notes and app groups, frozen apps that keep their rules, a fully configurable UI theme + global font, portable category-based Export/Import that can be driven from outside the app, honest WireGuard status, switchable diagnostics, and external automation intents — including a guard that lets a companion app check the internet and cure a wedged VPN.

Installs **side-by-side** with RethinkDNS (app id `shiroikuma.kojiki`).

**📥 Latest release: [`0.5.8+019`](https://github.com/ShiroiKuma0/shiroikuma-kojiki/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-kojiki/releases)

</div>

---

## 🩺 Self-healing DNS
Stock RethinkDNS could lose DNS for the whole device every few minutes: its engine pools DoH connections for 3 minutes while resolvers like Quad9 close idle ones after ≤30 seconds — so every quiet spell filled the pool with dead connections that queries then hung and died on. The fork fixes the engine (a 10-second pool + HTTP/2 PING health-checks, so dead connections are evicted before a query ever rides one) **and** adds a DNS watchdog beneath it: on a burst of upstream failures it recycles the full Go tunnel automatically; if the wedge returns right after, it fails DNS over to a spare resolver for 15 minutes and then goes back to the one you chose (waiting longer each time it relapses) — each action announced by a notification. It also notices the quiet kind of wedge, where questions go out and simply never come back: half a minute of that and the engine is recycled. Validated on-device: zero wedge failures in worst-case traffic on the same resolver that previously failed all day.

And when you press **STOP**, the VPN stops — with a progress box if it takes more than a moment — instead of being rebuilt behind your back by the next network change.

---

## 🧭 Excluded apps that still resolve
On some phones (notably Huawei/EMUI), an app you fully *exclude* from the VPN has its DNS misrouted by the OS to the VPN's DNS server — which an excluded app can't reach — so it silently loses name resolution and its connectivity gets throttled. The fork advertises a real fallback resolver alongside the in-tunnel one: in-tunnel apps stay on the DoH (no leak), while an excluded app reaches the real resolver directly and resolves normally. Exclude an app and it stays excluded — without breaking its DNS.

---

## 🏠 Your own LAN, off the tunnel
A VPN that routes `0.0.0.0/0` swallows the local network too: with the phone and the PC on the same WiFi, every byte between them still went out to a WireGuard hub on the internet and back — **0.67 MB/s**, both legs sharing the one home uplink. Stock has a switch for this (*Do not route Private IPs*), but it subtracts **every** private range at once, which strands the WireGuard overlay the phone's own path home rides on: turn it on as shipped and the phone goes unreachable the moment you leave the house. The fork pins that overlay back into the tunnel while everything else private leaves by the real interface — so the LAN is direct **and** the way home survives. Across the room: **0.67 MB/s → roughly 2–8 up and 13–23 down**, with nothing to remember before you walk out the door.

---

## 🔌 Connections that recover instead of dying
With advanced DNS filtering on, every answer gets a synthetic address so a connection can still be tied to the domain behind it. Stock forgets what that address means sooner than the app stops using it — and then drops the connection, before the firewall sees it, so nothing is logged and nothing explains it. Apps fail intermittently and work the instant the VPN is switched off. The fork re-resolves the domain instead: it consults the probable mapping stock computes and throws away, and the day of expired mappings stock keeps but never reads, then dials the current addresses. If there is genuinely nothing left to dial, the flow still reaches the firewall, so the block is attributed, logged, and decided by your rules rather than disappearing. Blocking is untouched — a blocklisted or rule-blocked domain stays blocked through the recovered path.

A restart is the harsher version of the same problem: the engine keeps those mappings in memory only, so every update, crash or force-stop throws them all away while apps still hold the old addresses. Stock dials each one as if it were real and waits about 20 seconds for nothing — after one install the engine logged over a thousand such misses, several for each stuck connection, and most apps stayed offline for about five minutes. The fork turns such a connection down at once, so the app looks the name up again and is back within seconds.

---

## 🕰️ Every app's own log, with times
An app's page tells you *what it contacted most*. It could not tell you *what it contacted at 21:04* — the aggregates carry counts, not clocks, and the sparse entries hide under the busy ones. A **Connection log** row now opens the full Network or DNS log already filtered to that app, every row with its timestamp, its rule and its details sheet. The same filter behind tapping an app's icon in the log — which, it turned out, had never actually filtered the merged view.

---

## 🕵️ Snooping panel
A dedicated panel that watches DNS traffic **on the device itself** and surfaces which apps are phoning home to trackers and telemetry — classified in three tiers against the local blocklists, with per-domain **tags/categories** you create and assign yourself. Tap an app icon to filter the panel to that app, long-press to open it, sort and filter every which way, and cut a snoop off with one tap. Detection runs **independently of DNS logging** — turning query logs off does not blind it.

---

## 📝 Notes and groups on every app
Firewall rules say *what*; they can never say *why*. Every row in the apps view gets a **free-text note** — “do not exclude, DNS dies on this phone”, “needed for Hikvision” — shown inline on the row itself and edited in one tap, with a blank note deleting itself.

Rows also carry **groups** (profiles): named sets of apps shown as pills. Tap a pill to filter the list to that group, long-press to drop the app from it, “+” to add. And because the bulk-rule toolbar acts on whatever the filter selects, filtering to a group aims the entire toolbar at it — **one tap on a pill, one on “block on metered”, and the whole group is done.**

The note is the same one the app's own detail page shows — one note per app, edited from wherever you happen to be. Groups are keyed by package name; a note is set aside when its app is uninstalled and comes back when it returns, so both survive reinstalls and travel in Export/Import. Even the uid-only rows (root, `SYSTEM`, and any traffic no package accounts for — reachable through a **Non-app** filter) can be annotated; those are carried across devices with a ⚠ marker telling you to re-check them, never silently trusted.

The pill row under the search field is **yours to arrange**: any filter — firewall state, installed/system/non-app, a category, one of your groups, or **apps that carry a note** — can sit in it, in the order you choose, with the rest hidden. Hold a pill and move it to reorder; hold and release for its menu. Filtering to a group or to your annotated apps also aims the bulk-rule toolbar at exactly those rows.

---

## 🧊 Frozen apps keep their rules
Freeze an app with 白い熊 応用管理 and Android hides it — and stock RethinkDNS, which never asks for hidden apps, takes that for an uninstall: the row and every rule on it are deleted, and unfreezing the app greets you with a “new app installed” prompt. Here a frozen app keeps its row, its uid and all its rules for as long as it sleeps, and wakes without a word. The list shows it as **`· hidden`**, **`· suspended`** or **`· disabled`**, dimmed, and a **Hidden / frozen** filter lists exactly those apps — with the bulk toolbar aimed at them, should you want one rule for everything that is frozen.

---

## 🔢 An app list you can actually read
Every row leads its id line with the **uid** — `10050 · yqtrack.app` — the number adb, the connection logs and every firewall rule actually speak in; the uid-only rows, which have no package id to print, finally show something instead of a blank line. And the list **sorts**: by app name, package id, uid, or data used, ascending or descending, from a glyph beside the filter icon — tap the marked key again to reverse it. Your choice sticks between visits, and the fast-scroll bubble follows the key you picked instead of always showing a letter.

---

## 🧬 Shared uids, said out loud
A firewall rule cannot name an app: Android hands the network stack a **uid**, so packages sharing one — a dozen `com.huawei.*` services under uid 1000, say — are a single principal that no rule can tell apart. Stock shows them as ordinary separate rows and only mentions it once you are already applying a rule. Here every such row is marked **`×11`** on its id line, both per-app confirmations explain *why* one tap moves eleven apps (and point at the one axis that does discriminate below a uid — per-app domain and IP rules), and the bulk toolbar names the apps a rule will reach **outside** your current filter because they share a uid with something inside it.

---

## 📖 The firewall explains itself
**Bypass DNS & Firewall**, **Bypass Universal**, **Exclude** — three buttons whose differences decide whether an app is filtered, exempt, or gone from the tunnel altogether. Stock explains them with a single white tooltip, on one of the three, that flashes one sentence and vanishes. Long-press any of them here — on an app’s own screen, or on the bulk toolbar that applies them to a whole filtered set — and you get the real answer: a paragraph per button, then a rule-by-rule table of exactly what each one waives, read straight off the firewall’s decision order rather than off the labels. It settles the question the UI never answered: **both** bypasses drop “block when app is not in use” *and* “block when device is locked”. And a bypassed app no longer looks switched off: its WiFi and mobile-data toggles stay lit — a bypass sets both to allowed — each wearing the badge of the bypass that is on, so the list itself tells you which one an app has.

---

## 🎨 白い熊 考直 UI
A **default “Custom” theme** with every colour user-configurable via full-ARGB pickers (background, accent, text) plus a **global font** — family, weight, and size — applied app-wide at runtime: dialogs, bottom sheets, toasts, the start button, log tables, everything. Ships seeded with 白い熊’s black `#000000` / pure-yellow `#FFFF00` look, matching the black-and-yellow launcher icon.

---

## 📦 Export / Import
A category-based, all-JSON-in-a-ZIP Export/Import that **replaces** the stock backup/restore — ten independent categories (app settings, appearance + fonts, snoop tags, firewall apps/domains/IPs, WireGuard, blocklist selection, DNS endpoints, proxies). Portable by construction: per-app rules key on **package name**, not uid, so an export survives reinstalls and fresh devices; rules for not-yet-installed apps park and apply automatically on install — and the install-time notification names the rule the app actually got, rather than stock’s unconditional “blocked”. The WireGuard category carries each tunnel’s full config, flags and bound apps — read as the plain-text files upstream now writes, so a restore on a new phone brings the tunnel with it. Lives at the top of the 白い熊 考直 UI page.

---

## 🔒 Honest WireGuard status
Stock Rethink reads a tunnel as “Failing” even while traffic flows through it. The fork checks what the tunnel is actually doing — on the config list, the detail screen, **and the home Proxy card** — so the status you read is the status you have. Each tunnel also gains a standard wg-quick **`.conf` export** button, making configs portable across installs and keystores.

---

## 🤖 Automation, and a backup that survives a wiped phone
External tools (Tasker, adb, companion apps) can drive the app by intent: set a **per-app firewall rule**, **enable/disable a WireGuard tunnel**, ask for the **VPN's health** (tunnel, DNS answers, resolver, WireGuard handshakes) and **restart the engine, stop or start the VPN** — each answered once it has actually happened, so a watchdog app can probe the internet itself and cure a wedge step by step — or run a **headless backup** that exports the chosen categories with no UI at all — reporting live progress in real counts, saying which categories start ticked so a remote picker never has to guess, and replying with the written path and byte size. It can be **cancelled mid-run**: the export unwinds at the next category boundary and deletes its half-written archive, leaving the backup folder exactly as it found it.

One switch decides whether any of them needs a shared token: off, they all work without one. **The backup side works without it either way** — because a token you pasted into another app is exactly what a factory reset destroys, and restoring a wiped phone is the one moment you need this most. In its place the app answers a **data door** that identifies who is calling: an exact package name, the uid the kernel reports, and a pinned signing certificate. A companion backup app can hand over an open file and have 考直 write its whole configuration into it — every firewall rule, WireGuard tunnel, note and DNS endpoint — then hand the same file back on a fresh install and get the phone's filtering back without a single tap. Rules for apps that are not installed yet simply wait for them.

---

## 🖥️ The whole screen, unfolded
Upstream caps the app's content at 600dp and centres it, which suits a tablet and wastes a foldable: unfolded, a tri-fold is wide enough that every screen became a narrow column between black bars. The fork drops that cap — and the two further 720dp caps the home screen carries in its own layout — so the cards, the list and the controls use the width the device actually has. Dialogs and sheets stay deliberately inset, because a dialog that spans a tri-fold is worse, not better.

---

## 🩻 Diagnostics, off until you want them
A **Diagnostics** section on the 白い熊 考直 UI page puts every fork diagnostic behind its own switch — all off by default, and off means nothing is recorded and nothing runs. Turn one on and it keeps a capped, timestamped trace you can read on the page or pull with adb: **screen leaks** (closed screens still held in memory, with a one-shot heap dump), **VPN restarts** (every engine start and stop with its reason, and how many connections still aim at addresses from before the restart), the **DNS watchdog**'s failures and actions, **WireGuard health** per tunnel, and **thread dumps** written whenever the app stalls, so the next mystery outage leaves evidence. Stored traces can be exported to a directory you pick or purged in one go. The first of them has already paid for itself: it found the theme code holding every screen ever opened in memory.

---

## Built on RethinkDNS
A fork of [celzero/rethink-app](https://github.com/celzero/rethink-app) (app id `shiroikuma.kojiki`, so it coexists with the official build). All the heavy lifting — the userspace WireGuard engine, the OpenSnitch-style firewall, the DNS-over-HTTPS/TLS/DNSCrypt client — is RethinkDNS and its [firestack](https://github.com/celzero/firestack) data plane; this fork tracks upstream’s released tags (currently `v0.5.8`), shipping the tag’s pinned engine with three fork patches on top. The first fixes the DoH idle-pool wedge (offered upstream as [firestack#241](https://github.com/celzero/firestack/issues/241), and since **partly adopted** — upstream now shortens the pool to 30 s, though that still sits exactly on the shortest observed resolver idle window and adds no HTTP/2 PING health-checks, so the fork keeps its own 10 s + PING patch); the second recovers a connection whose DNS mapping has aged out instead of dropping it unlogged (offered as [firestack#252](https://github.com/celzero/firestack/issues/252)); the third turns down at once a connection to a stand-in address left over from before a restart, so the app looks the name up again instead of hanging ~20 s (offered as [firestack#256](https://github.com/celzero/firestack/issues/256)). The code remains under Apache-2.0.

## Building
```bash
git clone https://github.com/ShiroiKuma0/shiroikuma-kojiki.git
cd shiroikuma-kojiki
# needs JDK 21 and an Android SDK (sdk.dir in local.properties)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildFoss
```
`buildFoss` assembles the signed, de-Googled `fdroidFullRelease` variant and copies the arm64-v8a APK to `~/tmp/`. Signing credentials go in a gitignored `keystore.properties`; without them the build is unsigned.
