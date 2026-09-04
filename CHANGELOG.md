# Changelog — 白い熊 考直

Everything built on top of stock [RethinkDNS](https://github.com/celzero/rethink-app). Current base: the **`v0.5.6`** upstream tag with its pinned firestack engine (`61894b7fdb`) plus the fork’s DoH idle-pool patch.

## 0.5.6+027

**The backup can now be driven from outside the app — and survive the phone being wiped.**

### A pasted secret cannot survive a factory reset
The automation this app already answered was gated by a 48-character token: you copied it out of 考直's settings and pasted it into whatever was going to drive the backup. That works right up until the moment it matters most. A wiped phone has no pasted token, no configured anything — and putting the phone back is precisely when you want a companion app to reinstall 考直 and hand it its own configuration back. A gate that only works once the phone is already set up is no gate for setting the phone up.

So the master switch now ships **on**, and the token became a separate switch that ships **off**, with its row hidden until you ask for it. A caller that still sends a token to an app no longer asking for one is **served, not refused** — tokens live in task arguments that outlive the setting they were pasted for, and rejecting them would turn one switch being off into half a backup run mysteriously failing. Both checks now live in a single function, so “automation disabled” and “bad token” cannot drift apart between the paths that report them.

### What replaces the token: knowing who is calling
A broadcast cannot tell you who sent it, and the caller is the one naming where the export gets written — so removing the token needed something better than a `shiroikuma.*` name check, which is not an identity at all: package names are not a namespace anyone owns, and a sideloaded app may call itself anything it likes. Since the caller supplies the destination, a prefix test would have handed such an app the complete data of every sister app in turn — weaker than the token it replaced.

The app therefore answers a **content provider** that asks the framework three questions, each because the one before it is insufficient: an **exact** package name from a short list; the uid the **kernel** reports, since a package may declare an attribution it does not own; and the caller's **signing certificate** against a pinned hash, which is what covers the case a clean phone makes unavoidable — whichever caller is not installed yet is a name anyone can take.

### The data moves through an open file, not a folder
The caller opens the destination itself and passes the open file; 考直 writes bytes into it and does nothing else. Handing over a folder path would have broken three things at once in a backup app: a backup is not a stable directory while it is being assembled and gets renamed on commit; encryption is applied per file it knows about, so a file dropped in from outside would sit in plaintext inside an otherwise encrypted archive; and the integrity manifest is built the same way, so a corrupted archive would come back at restore with no complaint. An open file also stops being usable the moment it is closed, which a folder never does.

Restore is only offered here, never as a broadcast — an import overwrites every firewall rule in the app, and the broadcast side is deliberately open.

### A restore that reported success and lost your rules
Found while building the above, and worth stating plainly because it was silent. The companion app force-stops 考直 the instant an import reports success — deliberately, because an app shut down normally writes its cached settings back out over the import that just happened. The consequence is that anything 考直 had merely *queued* to disk at that moment died with the process, and the restore reported success over rules that were never written.

Fixing the app's own writes was not enough: the settings layer queues its own writes internally, and so does the store where **rules for not-yet-installed apps** wait — which is the clean-phone path specifically, the exact case this feature exists for. Every preferences file is now flushed synchronously before the answer goes out, not after.

### Two more that would only have shown up in use
A caller retrying with a stale job id **crashed the app**: the background worker returned early without first entering the foreground state the platform requires of it once started that way. And the door read its own settings through the app's dependency graph, which is not yet built when a provider call is what starts the process — on a clean phone that would have answered a polite refusal instead of a backup, which is worse than a crash, because it looks like a deliberate no.

### What did not change
**Setting a firewall rule and switching a WireGuard tunnel still require the token, unconditionally.** They are acting operations — they change what this device does on the network — rather than data access, and the clean-phone argument has no force for them: putting a wiped phone back never requires changing a firewall rule from it. Only the backup-side operations relaxed.

## 0.5.6+025

**Three buttons decide how an app is filtered. The app now says what they do.**

### One sentence, on one of three buttons
An app's firewall row offers **Bypass DNS & Firewall**, **Bypass Universal** and **Exclude**, and the distance between them is the whole question: one keeps the resolver and drops the filtering, one keeps the filtering and drops the global rules, one takes the app out of the tunnel entirely. Upstream documents this with a single platform tooltip, attached to the first of the three, which raises a white unthemed box, prints one sentence, and disappears — on a black-and-yellow build it reads as a glitch more than a help.

Long-pressing any of the three now opens a scrollable, near-full-screen dialog in the fork's own bordered box, on the app's own screen **and** on the app-list bulk toolbar where the same three actions apply to a whole filtered set. The pressed button leads with its own paragraph; the other two follow, because the point of the dialog is the contrast between them.

### The table is read off the code, not off the labels
Beneath the paragraphs is a rule-by-rule table — every DNS stage, every universal firewall toggle, the rules set on the app itself, and the tunnel-level consequences — showing for each whether the rule still applies, is waived, or cannot apply at all. It was derived by reading the firewall's decision order rather than the button names, and two of its rows contradict what the labels imply:

- **Both** bypasses waive “block when app is not in use” **and** “block when device is locked”. Both branches return before those checks are ever reached, so neither toggle can touch an app carrying either status. It is easy to assume only one of them goes that far; neither does less.
- **Neither** bypass lifts the app's own **WiFi / mobile-data** toggles. Those are tested earlier than either bypass, so an app set to Bypass DNS & Firewall *and* “block on mobile data” is still blocked on mobile data.

The genuine asymmetry is on the DNS side, and the table states it plainly: a Bypass Universal app still has its blocklisted domains blocked, and still has port 53 trapped by “prevent DNS leaks”, while Bypass DNS & Firewall escapes both. Five notes carry the exceptions — including the one universal rule that survives Bypass Universal (a **Trust** domain rule), and why Android's own lockdown makes Exclude a no-op.

### Small things that made it possible
The fork's dialog already draws its own bordered box, since a Material alert has no single outer surface to stroke; it grew one parameter — how much of the screen the scrolling area may claim — defaulted so that every existing dialog is unmoved, and placed so that no call site changed. The explainer's prose lives in the Kotlin file rather than in `strings.xml`: it is fork-only and English-only, and keeping it out of upstream's translated resource file is one less conflict at every rebase. Upstream's own “explain before the first enable” guard, which used to poke the tooltip on the first tap, now opens the same dialog.

## 0.5.6+021

**The local network leaves by the local interface — without cutting the way home.**

### 0.67 MB/s across the room
With the phone and the PC on the same WiFi, everything between them still went out to a WireGuard hub on the public internet and came back, because the tunnel's routing table held a bare `default` and so claimed `192.168.1.37` along with everything else. Both legs shared the one home uplink: **0.67 MB/s**, against 18.6 over a USB cable. The service on the phone was never the problem — inbound packets arrived on `wlan0` perfectly well; the *reply* was what went into the tunnel and never came out.

Worth recording, because it sent the diagnosis down a false trail: `ping -I wlan0` proves nothing here. `-I` sets the source address, not the route, so the packet still enters the tunnel with the wrong source and fails whether or not the theory being tested is right. The routing table is the evidence.

### Stock has the switch, and as shipped it is a trap
Upstream ships **Configure → VPN → “Do not route Private IPs”** (off by default), which replaces the tunnel's `0.0.0.0/0` with `0.0.0.0/0` minus the private ranges. That is the right mechanism, and here it is the only one: `VpnService.excludeRoute` needs Android 13, and this phone runs 12.

But it subtracts **every** private range at once, `10.0.0.0/8` included — and the WireGuard overlay carrying this phone's reverse-ssh path home lives at `10.9.0.2`. Turn the switch on as shipped and that address goes to the home router, which drops it: the LAN gets faster and the phone becomes unreachable the moment it leaves the house. The one thing that had to keep working is precisely what it breaks.

### Pin the overlay, subtract the rest
So the fork keeps `10/8` excluded and pins `10.9.0.0/24` straight back into the tunnel — the same manoeuvre the routing code already performs a few lines further down for the tunnel's own `10.111.222.x`, with longest-prefix match settling which wins. The obvious alternative, dropping `10/8` from the exclusion list, was rejected: it does not fix the bug so much as relocate it to whichever 10.x hotel or office LAN you walk into next.

Confirmed on-device: the LAN and the router resolve to `wlan0`, the overlay and the hub and the internet stay on the tunnel, `10.9.0.0/24` appears in the tunnel's table beside `10.111.222.x` with the bare default gone, and the reverse tunnel survives the change. Throughput across the room went **0.67 MB/s → roughly 2–8 up and 13–23 down** (one identical incompressible file, no storage at either end). The remaining up/down asymmetry is not the tunnel, the phone or the air — it is the sending PC's own WiFi transmit path, which caps everything that machine sends, to a hub on the internet just as much as to the phone. The switch is meant to stay on from now on; there is nothing to remember before leaving.

Two conditions it rests on, both written into `CLAUDE.md` for the next rebase: Android's own lockdown must stay off — always-on plus “block connections without VPN” makes the OS blackhole excluded routes, and upstream's `TODO: vpn lockdown mode is not handled` sits in that very function — and the overlay prefix is a constant, so if the network renumbers, the constant moves with it.

## 0.5.6+020

**A firewall rule cannot name an app — so the app list stops pretending it can.**

### Shared uids, on the row
Android hands the network stack a **uid**, never a package: `VpnService`'s allow/disallow lists are uid ranges, the kernel's socket owner is a uid, and the Go engine's flow callback is answered with a uid. Packages that share one — `android.uid.system`, uid 1000, which on a Huawei is a dozen `com.huawei.*` services — are a *single* principal, and no rule can tell them apart. The app's own tables have always agreed: firewall rules key on uid, domain and IP rules on `(uid, domain)` and `(uid, ip, port, protocol)`.

The list did not say so. It showed those packages as ordinary separate rows, down to identical byte counts — accounting is per-uid too — so a row implied a per-app decision the model cannot make. Every row's id line now carries **`×11`** in the accent when eleven packages share its uid. It sits at the front of the line, where an ellipsized package name cannot swallow it, and rides the row's existing background bind, so it costs nothing on the main thread.

### The question, restated as *why*
Upstream already refused to apply such a rule silently: it asked first and listed the other apps. What it never explained was the mechanism — and it asked inside a Material alert, which under this theme has no border at all and whose body was a bare list.

Both per-app paths — the row's wifi/data toggles, and every rule on an app's own page — now ask in the fork's bordered dialog, titled `uid 1000 · 11 apps`, stating plainly that Android binds a rule to the uid and that under one uid these apps cannot be ruled apart, then listing them, then naming the one axis that *does* discriminate below the uid: rule the destinations instead, with per-app domain and IP rules. The affirmative button keeps its per-rule label, so it still reads as the change being made.

### The bulk toolbar's blind spot
The toolbar acts on whatever the filter selects — but the rules it writes bind to uids, so a rule aimed at a group containing one Huawei service also lands on the other ten, however far outside the filter they sit. That spill is now computed before the confirm dialog appears and named in it: how many apps outside the selection sit under a uid that is inside it, and which ones.

None of this creates enforcement Android does not have. It stops the list implying enforcement Android does not have.

### The filter sheet's buttons are legible
Clear and Apply rendered as blank yellow bricks — accent text on an accent slab, because Material fills a plain button from `colorPrimary`, which under this theme *is* the accent. They now take the same inversion as the FABs and the start button: black fill, accent border, accent label, deliberately not chip-shaped (square-ish corners, a heavier border) so they never read as one more filter chip in the rows above them.

The border is drawn as a foreground overlay rather than through the button's own stroke API, which silently does nothing once a button's background has been replaced — the first attempt turned the fill black and left no border at all.

## 0.5.6+017

**The apps view tells you the uid, and finally lets you sort it.**

### The uid leads every row
The row's id line now reads `10050 · yqtrack.app`. The uid is the number every adb command, connection log and firewall rule actually speaks in, so having to open an app's page to find it was a small tax paid over and over.

The synthetic `no_package_<uid>` rows gain the most: their “package name” is a placeholder, so that line used to be **empty** on precisely the rows that are hardest to identify — root, `SYSTEM`, and any uid whose traffic no package accounts for. They now print the uid alone.

### A sort order you choose
A sort glyph sits beside the filter icon and opens a dialog with four keys — **app name · package id · uid · data used**. The active key is drawn in the accent colour with its direction arrow, and **tapping it again reverses the order**. The choice is persisted in its own preferences file, so a sort picked once outlives the activity and the app; a long-press on the glyph names the order in force.

It sits in the header rather than in the filter sheet on purpose: the sheet's chip sections already fill a folded screen, and sort is a one-tap decision, not a multi-select. The fast-scroll bubble follows the key too — a uid while sorted by uid, a byte count while sorted by data — instead of always showing a letter that means nothing in that order.

### Why it had to become SQL
A paged list cannot be re-sorted in Kotlin: each page is fetched independently, so a post-query sort only ever sorts *within* a page. The order therefore had to move into the query.

Upstream ships **six** paged queries for this list — all / installed / system, each with and without a category filter — every one of them hard-ordered by `lower(appName)`. They collapse here into a single fork query whose `ORDER BY` is driven by two bound parameters through the standard SQLite `CASE` idiom, with `lower(appName)` closing the list as the tie-breaker so rows sharing a uid keep a stable, readable order. The app type and the “no category selected” case became parameters rather than separate methods. Nothing else about the list changed: the group filter, the Non-app filter and the bulk-rule toolbar all still work on top of it.

### The icon that wasn't there
The first build shipped the sort control invisible. Every glyph in this app is a **stroke** drawing — `?attr/svgFillColor` is `@android:color/transparent` in all six themes — and the Custom theme could not rescue a filled one either, since its icon tint is `setColorFilter` (SRC_ATOP), which keeps the source's alpha. Transparent stays transparent. The icon is drawn in stroke now, at the same weight as the refresh and filter icons beside it.

## 0.5.6+015

**The app name never truncates, and the package id gets a line of its own.**

The note pill shares the label line with the app label — and the label carried the package id as well, so the note only ever got whatever the id left over. Making room for the note by shrinking the label just cut the app's name off instead (`HMS C…`). Both halves of that are now fixed at the root.

### The package id moved to its own line
It sits under the app name, smaller, and keeps every bit of its configurability from the 白い熊 考直 UI page — size, colour, font family, weight and italic — with the same fallbacks it always had: an unset size tracks the app name's at the id scale, and an unset colour is a dimmed copy of the name's, so it still follows the per-type user/system name colours by itself. It is a real view now rather than a span inside the label, and joins the row's other bind-time-styled views in the theme walk's skip list.

The point is not the id: it is that the label line is now short, so the note can have most of the row without the name giving up anything.

### The name is structurally un-shrinkable
The line's weight moved off the app name and onto a **spacer** between the name and the pill. The spacer absorbs the slack — which is what holds the pill against the right edge — and because the name carries no weight, `LinearLayout` has nothing to take from it when space runs short. It always shows in full, and no more. The note's own `maxWidth` is what keeps a very long note from running past the spacer and being clipped.

## 0.5.6+013

**Every dialog in the app now wears the fork's bordered box — not just the fork's own.**

`0.5.6+012` gave the fork's dialogs a single rounded, accent-bordered box and left upstream's ~144 alerts as Material drew them. This finishes the job: all of them are routed through a new **`KojikiAlertDialogBuilder`**, a drop-in subclass of `MaterialAlertDialogBuilder`.

A subclass rather than a rewrite, deliberately. Every existing builder chain — `setItems`, `setMultiChoiceItems`, `setSingleChoiceItems`, `setView`, adapters, custom listeners — keeps working untouched, and each call site changes by exactly one identifier. That is the difference between a mechanical sweep over 69 upstream-owned files and hand-porting 144 dialogs, which would conflict on every rebase.

The border is painted on **`parentPanel`** — AppCompat's single outer container of the alert layout — with `topPanel`, `contentPanel`, `customPanel` and `buttonPanel` cleared, so exactly one border is drawn and the box keeps its corners. Painting a plain view background is deterministic, which neither theme attribute is: `android:windowBackground` is replaced by `MaterialAlertDialogBuilder.create()` at show time, and `android:background` is applied to each panel separately and renders three stacked bordered boxes.

The hook is an **attach-state listener rather than `setOnShowListener`**, because several call sites set a show-listener of their own and would have silently replaced ours. Off the Custom theme the whole mechanism is inert and every dialog renders exactly as upstream drew it.

## 0.5.6+012

**The apps view gains per-app notes and app groups — 白い熊 応用管理's two annotations, in kojiki's terms — and the groups turn the bulk-rule toolbar into a per-group weapon.**

### Per-app notes
Free text per app, for the thing the rule itself can never say: *why*. “Do not exclude — DNS dies on this phone.” “Needed for Hikvision.” The row's label line ends in a pill: when the app has a note it holds the note glyph plus the note's own text on one ellipsized line, drawn at low alpha so it reads as a margin note rather than a second title; when it has none it is the glyph alone, and the full text is always a long-press away as a tooltip. Tapping opens a pre-filled multi-line dialog, and **saving it blank deletes the note** — 応用管理's exact contract.

Notes are keyed by **package name**, never uid, in their own preferences file, so they survive reinstalls and travel through Export/Import untouched.

### App groups (profiles)
Named sets of apps — 仕事, 通信, whatever you need — shown as outlined pills on the row's bottom line, sharing the traffic-counter line so they cost the row no extra height. **Tap a pill** and the whole list filters to that group; **long-press** and the app leaves it. The trailing “+” opens a membership checklist with a *New group…* action; long-pressing it manages the group list itself (rename, delete). The filter sheet gains a **Groups** chip section and a **Manage** action of its own.

The payoff is the bulk-rule toolbar. It has always acted on whatever the current filter selects — so filtering to a group aims *the entire toolbar* at that group: **one tap on a pill, one on “block on metered”, and the whole group is done.**

Group membership is keyed by package name too, and both the filter and the bulk path are applied as **post-query filters** rather than SQL. That is deliberate: not one upstream DAO query is touched, so this cannot conflict on a rebase.

### Notes and groups on the uid-only rows — carried, but never trusted
The synthetic `no_package_<uid>` rows (root — shown as **ANDROID** — `SYSTEM`, and any uid whose traffic no package accounts for) get notes and groups as well. They are, after all, exactly the rows that most deserve a “do not block, DNS dies” annotation.

Their key is a uid wearing a package's name, so it means something only on the device it was made on. Export therefore carries the label each key had, and **import prefixes a ⚠ marker note** naming what it used to be — *“Imported: this was ‘ANDROID’ (uid 0) on the other device — check it still applies.”* A row that carried only group membership gets that note created for it, because membership arriving silently is the whole hazard. The marker is idempotent, so importing the same backup twice never stacks it.

A new **Non-app** top-level filter lists those rows, so you can see how many there are before touching any of them.

### Both stores are Export/Import categories
**App notes** and **App groups** join the category list, on by default, exported and imported like everything else.

### Dialogs draw their own border now
The fork's dialogs are drawn by a new `KojikiDialog`: one rounded, accent-bordered black box holding the title, the content and a right-aligned button row, with the content area clamped so a long list scrolls inside the box instead of pushing the buttons off screen.

This replaces two failed attempts at doing it from the theme, both recorded in the style so they are not tried again: `android:windowBackground` is **replaced** by `MaterialAlertDialogBuilder.create()` at show time, so a border set there is discarded outright; and `android:background` is applied to the alert's title, content and button panels *individually*, which renders three stacked bordered boxes with clipped corners. A Material alert simply has no single outer surface for a theme to stroke.

### The filter sheet in black and yellow
The apps-view filter sheet now carries the fork's look: an accent border on an **inset content box** — a full-width panel's side strokes land at the screen edge and are clipped — with its chips, buttons and text restyled by the new `CustomUi.applyToDialogTree`, which extends the Custom-theme pass into a dialog's own window (the activity tree-walk never reaches one). `BottomSheetDialogThemeKojikiCustom` also stopped being an empty extension of the true-black theme and now mirrors the activity theme's yellow palette, so **every** sheet reads black/yellow rather than inheriting the parent theme's green accent.

### Two row-layout traps, fixed
- **Rows were silently clipping their own text.** `firewall_app_details_ll` was `0dp` high, so the row's height came only from the 48 dp toggles and a 72 dp minimum — anything taller was cut off mid-glyph. It is `wrap_content` now.
- **The gaps between rows were invisible shadow.** `cardUseCompatPadding` reserves its padding from **`maxCardElevation`, not the current elevation**, so zeroing `cardElevation` under the Custom theme left ~5 dp of shadow room above and below every flat card, on top of its margins. The theme pass now clears both, which tightens every card in the app.

### Packaging
The build counter is **zero-padded to three digits** in the versionName, the APK filename and therefore the release tag (`0.5.6+012`), so builds sort in build order instead of putting `+10` before `+3`. `versionCode` keeps the unpadded integer, so upgrade ordering is unchanged; the two pre-padding names (`0.5.6+1`, `0.5.6+2`) are left exactly as they were published.

## 0.5.6+2

**Rebased onto the v0.5.6 release — 388 upstream commits — and, for the first time, the patched engine is built on exactly the tag’s own pin.**

### The upstream jump
The base moves from the `v0.5.5y` tag to **`v0.5.6`** (`VERSION_CODE 67`), 388 commits later. `versionCode` becomes `30670001`+, comfortably above the previous `3063xxxx` line, so it installs as a normal upgrade rather than a downgrade. All 60 fork commits were replayed on top.

### Upstream took half of our DoH fix — the fork keeps the other half
The idle-pool wedge we filed as [celzero/firestack#241](https://github.com/celzero/firestack/issues/241) has been **partly adopted upstream**: firestack `7ece230c` lowers the DoH connection-pool idle timeout from 3 minutes to 30 seconds, and its comment quotes our own Quad9 measurement back to us.

That is the easy half. **30 seconds sits exactly on the shortest idle window we measured** (Quad9 closes at ≤30 s), so a pooled connection can still be handed a query at the very moment the resolver drops it — and upstream added **no HTTP/2 PING health-checks**, so a half-dead connection is still discovered by losing a query rather than by a ping. The fork therefore keeps its full patch on top: a **10-second** pool plus `ReadIdleTimeout`/`PingTimeout` PING eviction.

The upside of the rebase: the patched AAR is now built on **`61894b7f` — precisely the engine `v0.5.6` pins**, so for the first time the fork’s engine matches its tag exactly instead of running ahead of it.

### The version-31 collision (and why the first build crashed)
Worth recording, because it will recur. `AppDatabase` version **31 came to mean two different schemas**: an earlier fork build stamped 31 after *its* `MIGRATION_30_31` created the Snooping panel’s `SnoopEvent` table, while upstream’s `v0.5.6` stamps 31 after *its* `MIGRATION_30_31` creates the `Sponsor` table.

Renumbering the fork’s migration to `31 → 32` was necessary but not sufficient: a database on the fork lineage is already stamped 31, so Room skips upstream’s `30 → 31` entirely, `Sponsor` is never created, and the app dies on open with `Migration didn't properly handle: Sponsor … Found: columns = { }`. `MIGRATION_31_32` now **also creates `Sponsor` idempotently**, with upstream’s exact DDL, so both lineages converge on the same schema at 32 — the same reconciliation this migration already carried for an earlier `26 → 27` collision.

### Carried across upstream’s refactors
- **`Logger` moved** out of the default package into `com.celzero.bravedns.util`; the ten fork-owned files that imported it are repointed.
- **`isVpnDns()` was extracted** into a new `TunFlowManager`, so the Huawei/EMUI real-fallback-DNS trap moved there with it — excluded apps still resolve, in-tunnel queries are still trapped to the DoH.
- **`VpnController.braveVpnService` → `rvpn`**, reconnecting the DNS watchdog’s entry points.
- **Upstream deleted `updateVpnConnectionState()` and the log-list `debounceJob`**; the tap-an-icon uid filter keeps its immediate, no-debounce behaviour because the query already fires at once.
- **`R.color.dividerBlack` is gone** (dividers moved onto Material 3 `?attr/colorSurfaceVariant`), so the configurable row divider now draws from the fork’s own colour and cannot be removed by a future rebase. The firewall row also became a card, so the divider is re-pinned to its bottom edge.
- **`ProxyManager.updateApp`** gained an upstream recovery path for a lost base row, which is kept — on top of the fork’s own hardening, which reads the database rather than the possibly stale in-memory cache and refuses to let a mapping conflict crash the refresh loop.
- **The rebrand sweep was re-run** over upstream’s new strings: 1829 replacements across 38 locales and 49 locale `app_name` overrides dropped, while upstream’s credit, the rethinkdns.com links and the resolver names (RDNS, RDNS Plus) stay untouched.

## 0.5.5y+13

**The backup contract grows a “starts ticked” answer and a real cancel — a stopped export now stops, and leaves nothing behind.**

### `LIST_CATEGORIES` states its own defaults
The 保存復元 contract gained an optional **fourth field** on each category line, saying whether that item **starts ticked** in the caller's picker — the app's decision to state, not the picker's to guess. Every line now reads `id⇥label⇥⇥on|off`: an **empty parent field** (this app's categories are flat) followed by the flag, which is positional and optional, so nothing already written breaks.

- **Nothing is marked `off`.** The rule is for things that are large, derived *and* re-creatable — downloaded tiles, a regenerable thumbnail cache — and this app exports none of that: rules, keys, endpoints and tags are all small and irreplaceable. Every line goes out as `on`, which is still worth sending: it is the app stating a default rather than the picker assuming one, and any category added later inherits a field that is already there.
- An **absent `items` extra** now resolves through a new `Cat.defaults()` — the `on` set — instead of “everything”. Identical today, and correct by construction the day a category ships `off`.
- **The in-app Export/Import sheet seeds from the same flag**, checkboxes and select-all alike, so the panel you use by hand and the automation picker start from one statement of intent.

### `CANCEL_EXPORT` — stopping a backup actually stops it
Added to the contract after a cancelled export elsewhere in the fleet ran to completion anyway and delivered a backup that had been called off. 自由作業盤's 中止 button used to only stop *listening*; it now fires a real cancel at the app, declared as a third action on the same exported receiver.

- **Fire-and-forget.** The cancel never replies — not `OK:`, not an error, not even on a bad token — and it is a **silent no-op** when nothing is running, or when its optional `reply_id` names a run that already ended. Safe to send at any time.
- **It unwinds at a category boundary**, never mid-`write()`, never by interrupting a thread or killing the process: the export core polls the caller's cancel signal between zip entries only.
- **The half-written archive is deleted**, on both the absolute-path and SAF branches, in the same `finally` that now covers *every* failure — so a crashed export no longer leaves a short ZIP behind either. A cancelled export leaves the backup directory exactly as it found it, with nothing that could be mistaken for a finished backup.
- **`ERROR:cancelled` goes out as the original request's terminal reply**, through the same one-shot guard that carries a success, so the two can never both fire. It is sent even if nobody is still listening — it is what proves the run ended rather than continuing unseen.
- **A second concurrent export is refused** (`ERROR:export already running`), which is what makes a cancel with no `reply_id` unambiguous. The run state is static — every broadcast gets a fresh receiver instance, so the cancel arrives on a different one than the export it stops — and lock-guarded, so a cancel racing a run's own teardown cannot leave a stale flag armed for the next export.

Nothing else moved: no new categories, no renamed ids, no change to `EXPORT_STATE`, the token, or the reply machinery. This app's export path has no foreground service or wakelock to unwind — the broadcast is held open with `goAsync()` and released on every path already.

## 0.5.5y+12

**The “Apps” card opens from its own title, and re-opening the app lands you back on the screen you left.**

### The Apps card is one target again
The Apps card's title is a full-width `TextView` layered over the card, and it carried its own click listener into a handler whose else-branch does nothing — so tapping the word **Apps**, its icon, or anywhere on that line silently swallowed the tap, and only the stats area below it opened the app list. The title now opens the app list too; the whole card behaves as a single target.

### Re-opening from the launcher returns to where you were
Leaving the app from, say, the Apps list and tapping the icon again always dropped you back on the home screen. The launcher entry — the app-lock gate, or the home screen itself when the app-lock alias is off — is `launchMode="singleTask"`, so a launcher tap runs `performClearTop` and finishes every activity above the task root; stock reinforces that with `android:finishOnTaskLaunch="true"` on nearly every sub-activity.

Relaxing the launch mode would have fixed it and also let re-entry **skip the biometric app-lock gate**, so the fork keeps the gate and rebuilds the destination behind it instead:

- **The screen you are on is recorded** as you use it, from `BaseActivity.onResume` — the fork's app-wide chokepoint — and re-launched on top of the home screen once the home screen is reached from a plain launcher tap (`ACTION_MAIN` through the lock gate, or `CATEGORY_LAUNCHER` directly). Both `onCreate` and `onNewIntent` attempt it, because the gate's `CLEAR_TOP` hand-off can recycle a live home screen instead of creating one. The lock still runs first — you authenticate, *then* land where you left.
- **Entry points are never restored** — home, app-lock, welcome, pause, the notification handler, bubbles, checkout — and landing on one clears the memory: sitting on the home screen *is* “nothing to restore”. Only this app's own activities are ever re-opened.
- **The record is one-shot**, consumed on restore and re-recorded when the restored screen resumes, so a restore that fails can never trap you in a loop. Back from the restored screen returns to the home screen as usual.
- Restores carry `FLAG_ACTIVITY_NO_ANIMATION` so the hand-off doesn't flicker, and only primitive/String extras survive the intent round-trip — data URIs are dropped deliberately, since the read permission behind them would not survive it either.

## 0.5.5y+11

**The app id on every firewall row — visible, searchable, and sizable; and no more false “DNS wedged” alert after an update.**

### Firewall → Apps: the package id on the row
- **Every row's label line now reads `App Name  package.id`** — the name bolded, the package id trailing it. The app list's search box *already* matched the package name (the query is `appName like … or uid like … or packageName like …`), so the id was a searchable key that was never shown; printing it also tells apart the several apps that ship under an identical display name (two “32 secs”, and so on). The search hint now reads “Search apps by uid, name, id” to say so.
- Synthetic `no_package_<uid>` rows — uids with no real package behind them — print no id.

### …and it is fully stylable
- **A new “App id (package name)” group** on the 白い熊 考直 UI page, sitting with the app-name groups under *Firewall list*: colour, font family, weight, italic, and **size** — the same five controls every other row element has.
- **Unset, the id tracks the app name**: its size is a fraction of the name's, and its colour is a dimmed copy of the name's — so it follows the per-type user/system name colours by itself, without a second setting to keep in sync. Set any field and your value wins outright.
- **The app name got its lead back.** Stock's layout gives the name `extra_large` 16 sp against the row's 12 sp status and traffic lines; the Custom theme had been flattening it to the plain global font size. An unset name size now falls back to the global size scaled up, so the name reads as the row's headline again — and both name and id came out larger than before as a result.
- **The name's bold is a default, not an override.** Configure a weight for the app name (per-element or global) and that weight is used verbatim; otherwise the name is bolded so it separates from the id. Without this the new weight slider would have been dead for that element.
- Composition lives in one place, `CustomUi.applyFirewallLabel`, and runs after the row's theme pass, since the fallbacks read the label's *final* size and colour. The proxy-key marker folded into it — setting the label text would otherwise have wiped the appended key.

### DNS watchdog: startup is not a wedge
Opening the app right after an update reliably raised a **“DNS wedged — tunnel restarted”** notification, with nothing wedged at all. Every tunnel bring-up opens with a burst of DNS failures — the `VpnService` was just torn down, every app on the device retries at once, and the engine is still dialling its first DoH connection — and that burst cleared the failure threshold on its own. Two gates now stand in front of a diagnosis:

- **A warm-up window** after a tunnel comes up, during which failures are dropped outright, so they cannot count later either. It is armed from both bring-up paths — a freshly created adapter and the watchdog's own recycle.
- **At least one upstream resolution since that tunnel came up.** A wedge is by definition “DNS worked, then it stopped”; with nothing ever resolved, the cause is a dead network or a bad config, which a tunnel recycle would not cure anyway.

If the arming hook somehow never fires, the watchdog arms itself off the first DNS transaction it sees — it can never go permanently silent. Genuine wedges still trigger exactly as before, just not during a tunnel's first minutes.

## 0.5.5y+8

**Headless backup automation — 考直 joins the fleet-wide 保存復元 backup run.**

The app now implements the sister-app **state-export automation contract**, so one external automation task can back up every app on the phone in a single run, this one included, and collect a per-app ✓/✗ summary with individual sizes.

### The wire contract
- **Two new exported actions**, `shiroikuma.kojiki.action.EXPORT_STATE` and `…LIST_CATEGORIES`, handled by a new `StateExportReceiver`. `EXPORT_STATE` runs the existing category-ZIP export **headlessly** — no Activity, no interaction; `LIST_CATEGORIES` returns the exportable categories so a caller can render its own picker. Both action strings derive from `BuildConfig.APPLICATION_ID`, so they can never drift from the manifest's `${applicationId}` intent filters.
- **Extras** (all String, per the family contract): `token`, optional `path` (an absolute directory that overrides the app's own configured export directory), optional `items` (comma-separated category ids; absent = everything), optional `progress_action`, plus the reply trio `reply_action` / `reply_package` / `reply_id`.
- **Replies are a fresh broadcast** to the caller's package with `reply_id` echoed verbatim and `result` = `OK:<path>|<bytes>|<human size>|<n> categories`, `OK:` + the `id⇥label` lines, or `ERROR:<reason>` — exactly one terminal reply, guarded so an async success and a synchronous error can never both fire. No `ResultReceiver` / `PendingIntent` / `Messenger` and no reliance on the ordered-broadcast result: EMUI severs both between third-party apps, and `FLAG_INCLUDE_STOPPED_PACKAGES` is what lets a backgrounded caller hear the answer at all.
- **Distinct failures**, because they debug differently: `ERROR:automation disabled`, `ERROR:bad token`, `ERROR:no-directory`, `ERROR:no-storage-access`, `ERROR:unknown category in items: …`.
- **Progress in real counts, never a percentage** — while exporting, plain broadcasts carry a numbers-first display line (`区分 3/10 — Snoop tags`) plus structured `current` / `total` / `unit`, throttled to at most one per 500 ms with the final one always sent.

### Storage
`path` → the configured SAF export directory → `ERROR:no-directory`. Writing to a caller-supplied absolute directory needs Android's **All-Files-Access**, so the app now declares `MANAGE_EXTERNAL_STORAGE` and asks for it with a dialog the first time the automation switch is turned on. Without the grant nothing breaks: the export falls back to the configured export directory, or replies `ERROR:no-storage-access` when there is none.

### Token & switch
- **Reuses the app's existing automation token** (the same secret `SET_APP_RULE` / `SET_WG_STATE` already use) rather than adding a second one. Newly generated tokens are 24 `SecureRandom` bytes, hex-encoded.
- **Constant-time comparison** (`MessageDigest.isEqual`) now guards *all three* automation receivers, replacing the plain `!=` check.
- **A new master switch, default OFF** — nothing is reachable until it is turned on. Both the switch and the token are excluded from the export, so neither ever travels inside a backup ZIP.

### UI
Two rows inside the existing **Export / Import** section of the 白い熊 考直 UI page, directly below the Export / Import entry — deliberately not a section of its own, because a backup feature belongs where backup lives, in the same place in every sister app:
1. the **master switch**, with a one-line description (and, while All-Files-Access is missing, a tappable warning row that opens the system grant screen);
2. the **token row**, showing the secret abbreviated, copying it in full on tap, with **Regenerate** on the right behind a warning that pasted copies stop working.

### Backup file naming (breaking, deliberate)
Every backup this app writes — from the automation path **and** from the Export/Import panel — is now named:

```
shiroikuma-kojiki_<yyyy-MM-dd_HH-mm-ss>.zip
```

No version, no `-export` infix, no suffixes. 白い熊 keeps every app's backups in one folder, so they must sort and read uniformly across apps. The previous prefix is still recognised by the panel's "last export" line, so older backups stay visible; and it remains **one ZIP per export, always** — every category is an entry inside the single archive.

### Internals
`KojikiExport.export()` gained an `onProgress` callback and now writes categories in declaration order (so progress reads identically every run), and the export-directory helpers moved into `KojikiExport`. The panel and the receiver are therefore two thin callers of one export core, sharing one directory and one naming rule — no export logic is duplicated, and a headlessly-produced ZIP is a perfectly ordinary backup the panel can import.

## 0.5.5y+7

**Export/Import moved onto the UI page; the UI page restyled after the kxkb Keyboard UI; ArcaneChat-style dialog buttons.**

- **Export/Import relocated** from Settings (the old Backup & Restore position) to the **top of the 白い熊 考直 UI page**, as its own section: a bold underlined heading with a name + description row beneath it that opens the category sheet. The Settings row and its divider are gone.
- **kxkb-style page look** for the whole 白い熊 考直 UI page, matching the 白い熊 kxkb fork's Keyboard UI: section headers are 20 sp bold accent with a 2 dp underline exactly as wide as the title text; subgroup labels are 16 sp medium with a short 1.5 dp text-wide underline; sections are separated by thin neutral edge-to-edge hairlines. (Previously every underline stretched the full page width — a `MATCH_PARENT` rule inflating its wrap-content box — and headers were lighter-weight.)
- **Export/Import sheet action row** restyled ArcaneChat-style: fully-rounded **pill** outline buttons sized to their text, a new **Cancel** pill alone on the left (the sheet previously had no cancel button), and **Import + Export** grouped on the right (previously two half-width rectangles).

## 0.5.5y+3

**Complete 白い熊 考直 rebrand across all user-visible text.**

Every user-visible "Rethink" / "RethinkDNS" mention now reads **白い熊 考直** — triggered by the new-app-installed notification still announcing "Rethink blocked recently installed app".

- **All locales, all strings** (42 `values*/strings.xml` files, 1794 replacements): new-app single + bulk notifications, low-memory and accessibility notifications, the biometric "Unlock" prompt, VPN / always-on / lockdown dialogs, onboarding slides, settings descriptions, and notification-channel descriptions. Replacements are case-sensitive, so URLs (`rethinkdns.com`, `rethink-app`) and resource ids stay untouched.
- **49 locale `app_name` overrides dropped** — some locales still forced "rethink" or a translated app name; the untranslatable 白い熊 考直 defaults now apply everywhere.
- **66 transliterated brand names replaced** in locale prose (Hindi रीथिंक, Urdu ریتھینک, Tamil மறுபரிசீலனை, Arabic إعادة التفكير, …).
- A hardcoded brand string in `activity_checkout_proxy.xml` rebranded.

Deliberately kept: the About screen's upstream credit (under "白い熊 考直 is based on Rethink."), the contributors list, resolver/service names that name the external resolver (RDNS, RDNS Plus, "RethinkDNS Basic"), and all internal identifiers (intent keys, DB tables, log tags, the pcap folder).

## 0.5.5y+2

**Real fallback DNS so OS-excluded apps resolve on Huawei/EMUI.**

On Huawei/EMUI, an app set to **Exclude** (Android's OS-level VPN bypass) has its DNS misrouted by the platform to the VPN's advertised DNS servers. The fork only advertised the fake in-tunnel DNS IP (`10.111.222.3`), which an excluded app can't reach (it bypasses the tunnel) — so every lookup failed. Symptom: an excluded app (e.g. Jami) intermittently "not sending", with Huawei's per-app DNS-failure counter climbing live and the OEM's dns-cure/wifipro remediation then strangling connectivity. (Keeping the app in-tunnel via *Bypass Universal* fixed DNS but made firestack's DNS-ALG churn on the app's raw-IPv6 P2P traffic — ~82% CPU — so Exclude + this fix is the right answer for a P2P app.)

The fork now advertises a **real fallback resolver** (Quad9 `9.9.9.9` / `2620:fe::fe`, matching the usual DoH) as a secondary VPN DNS alongside the fake IP. It's trapped identically to the fake IP at every site (`addDnsServer4/6`, `addDnsRoute4/6`, `isVpnDns`, `getFakeDns`), so **in-tunnel apps' queries to it are still captured and answered via the DoH — no leak** — while an excluded app reaches the real resolver directly and resolves.

Validated on-device (Jami back on Exclude): kojiki dropped from 82% to idle with zero engine churn for the app; Jami's Huawei DNS-failure counter froze and Jami works; and no DNS leak — every in-tunnel domain resolved to a synthetic `100.64.0.0/10` ALG IP (the DoH path) with zero `9.9.9.9:53` egress.

## 0.5.5y+1

Rebased the entire fork onto the upstream **`v0.5.5y`** release tag (`VERSION_CODE 63`; 135 commits past `v0.5.5x`). All fork features carried over; the DoH idle-pool self-healing (watchdog + patched engine, see `0.5.5x+14`) is preserved on the new engine.

### Rebase adaptations
- **Patched engine on the new pin:** the DoH idle-pool fix (`IdleConnTimeout` 3m→10s + HTTP/2 PING health-checks) was cherry-picked onto firestack `310d7bc603` (the v0.5.5y pin) and rebuilt from source; wired via `firestackRepo=local`. The engine still exhibited the stock idle-pool bug, so the patch remains necessary.
- **Honest WireGuard status:** migrated to firestack’s Int proxy-status ids (upstream changed them from Long in v0.5.5y).
- **WireGuard restore-verify:** ported to v0.5.5y’s plaintext-config storage (reads back + parses each restored tunnel, dropping unreadable phantoms).
- **Startup crash-loop fix (uid collisions):** upstream now dedupes conflicting proxy-app-mapping rows transactionally, so the fork workaround was reduced to its two surviving improvements — read live DB rows (not the stale cache) and never let a mapping error crash the refresh loop.
- Retired `BackupRestoreBottomSheet` stays removed; upstream’s new `BASE_VERSION_CODE` build field is fed the fork version code.

## 0.5.5x+14

### Self-healing DNS — root cause, engine patch, watchdog

**The problem (diagnosed 2026-07-17, mechanism-proven):** stock firestack pools DoH connections for 3 minutes (`IdleConnTimeout`) with no HTTP/2 keep-alive health-check, while resolvers close idle DoH connections far sooner — measured from the same network: **Quad9 ≤ 30 s, Mullvad ≤ 90 s, Cloudflare > 240 s**. Every DNS lull beyond the resolver’s window left only dead connections in the pool; the next queries were written into them, hung ~10 s, and died as `unexpected EOF` / `http-status: 502` bursts — every few minutes, all day. Downstream, ALG mappings expired (killing established flows of *allowed* apps) and the OS’s own connectivity probes failed through the tunnel, flapping Android’s network validation device-wide — even VPN-*excluded* apps lost connectivity.

**Patched engine** (firestack branch `kojiki-doh-idle` on the `fd3dbcd769` pin):
- `IdleConnTimeout` 3 m → **10 s** — pooled connections never outlive any resolver’s idle window, so the corpse-pool state is structurally impossible on every resolver.
- `http2.ConfigureTransports` with `ReadIdleTimeout = 10 s` / `PingTimeout = 5 s` — half-dead HTTP/2 connections are detected by PING and evicted instead of eating a query.
- Wired via `firestackRepo=local` in `gradle.properties` (flatDir `app/libs/tun2socks.aar`, gitignored); one property flips back to the stock Maven engine.

**DNS watchdog** (`KojikiDnsWatchdog`, fed every DNS transaction):
- Rate-based detection: ≥ 8 upstream failures within a 3-minute sliding window (no consecutive-streak and no cached-flag gating — the wedge is bursty/partial, and the engine’s cacher stamps `cached=true` even on failures).
- First trigger: a **full Go-tunnel recycle** (a real `restartTunnel` with seamless fd hand-off — a plain link update keeps wedged Go transports and dead ICMP netstack endpoints alive), clearing wedged DoH transports and the `endpoint is in invalid state` ICMP wedge.
- Re-trigger within 15 minutes of a restart (with recent successes on record): **automatic failover to Google DoH** plus another recycle. 5-minute action cooldown; every action posts a notification.
- Watchdog telemetry bypasses the level-gated in-app logger (raw logcat lines, tag `KojikiDnsWatchdog`).

**Validation** (on-device, 33 min of worst-case idle-then-burst traffic on Quad9 — the resolver that wedged constantly on stock): **zero** corpse-pool failures; 50 server-side idle kills absorbed by the health-check before any query rode them; one rare HTTP/2 stream-reset incident self-healed by the watchdog in under a second. Quad9 is viable again as a privacy-first primary.

## 0.5.5x+5

### Major features

**Snooping panel — on-device DNS snoop detection**
- A new panel that classifies every DNS query on-device into three tiers of snooping (against the local blocklists) and shows which apps talk to trackers/telemetry, with one-tap blocking of a snooping domain.
- Per-domain **tags/categories**: create, assign, and manage your own labels for snoop domains; tags are included in Export/Import.
- Sorting and filtering (default newest-first), tap an app icon to filter the panel to that app, long-press the icon to open the app.
- Detection is **decoupled from DNS logging**: the classifier runs ahead of the logs-enabled gate, so switching query logging off no longer silences the panel.

**白い熊 考直 UI — configurable theme + global font**
- A new “Custom” app theme, now the **default**, with a “Customize → 白い熊 考直 UI” settings page.
- Full-ARGB colour pickers for background, accent, and text; a global font with configurable family, weight, and size, applied app-wide at runtime.
- Dialogs, bottom sheets, toasts, the home start button, filter chips, FABs, and toggle groups are all themed; accent-on-accent contrast is resolved per-widget (luminance-picked on-colours).
- Ships seeded with the black `#000000` / pure-yellow `#FFFF00` look; pure yellow is used everywhere (the material `#FFEB3B` was purged).
- Black/yellow launcher icon (yellow shield on black, enlarged foreground).

**Export / Import — replaces stock backup/restore**
- Category-based export/import (a ZIP of per-category JSON + font files); the Settings “Backup & Restore” row now opens it.
- Ten categories, each individually selectable: app settings · appearance + fonts · snoop tags · per-app firewall rules · custom domain rules · custom IP rules · WireGuard tunnels + per-app bindings · blocklist selection · DNS custom endpoints (DoH / DoT / DNSCrypt / DNS proxy / ODoH) · proxies (SOCKS5 / HTTP).
- Portable by construction: per-app firewall rules and WireGuard bindings key on **package name**, not uid; rules for apps not yet installed park in a pending store and apply automatically when the app is installed.
- Import correctness: the selected DNS endpoint is actually re-selected; WireGuard import replaces all mappings and re-applies per-app bindings and lockdown so they genuinely stick; imports are future-proof (unknown fields skipped, missing fields take entity defaults, rows upsert).
- Blocklist category exports the **selection + a portable stamp** (list metadata, not the ~100 MB data files), with a pre-restore guard when the on-device blocklist is not downloaded.

**Honest WireGuard status**
- A tunnel with traffic flowing is no longer read as “Failing”: status is derived from actual tunnel behaviour on the one-WG list, the multi-WG list, the config detail screen, **and the home-screen Proxy card**.

**Automation intents**
- `SetAppRuleReceiver`: an external broadcast sets a per-app firewall rule (bypass / exclude / isolate / none, wifi/data granularity), gated by a settings token.
- `SetWgStateReceiver`: an external broadcast enables or disables a named WireGuard tunnel, same token gate.

### WireGuard
- Per-tunnel **export** button on the config detail screen: writes a standard wg-quick `.conf` (after a plaintext-key warning) via SAF, re-importable anywhere — WireGuard configs become portable across installs and keystores.
- Hardened restore: after re-encryption the config is read back and verified; a failed verify is logged and the phantom DB row removed.

### Log tabs
- Tap a log row’s app icon to filter the tab by that app; long-press opens the app.
- Blocked/allowed visibility on log rows plus full density/divider theming under the Custom theme.

### Fixes
- **Startup crash loop**: a stale old-uid row alongside a current-uid row in the WG proxy-app mapping table crashed every launch (`UNIQUE constraint failed` in the startup refresh). The uid update now dedupes from the real DB rows first and can no longer crash the refresh loop; the fix self-heals the corrupt rows on first launch.
- **Blocklist download OOM**: upstream’s ray-id interceptor buffered the whole response body in memory, aborting large blocklist downloads; it now peeks a bounded prefix.
- Readable no-dim dialogs under the Custom theme; app icons are no longer tinted away.
- Honest fork versioning: `versionName` encodes commits-ahead-of-tag when tracking past a release (`<version>-<ahead>+<build>`, the `-<ahead>` dropped at `0`).

### Packaging & fork plumbing
- App id `shiroikuma.kojiki` (installs side-by-side with stock; the code namespace stays `com.celzero.bravedns`), launcher label **白い熊 考直**, page headers and the About screen rebranded with attribution to RethinkDNS.
- Tracks upstream’s **released tags** (currently `v0.5.5x`) with the tag’s pinned firestack engine — deliberately not `main`, whose reworked wgproxy engine regressed idle WireGuard relays.
- `buildFoss` Gradle task: assembles the signed, de-Googled `fdroidFullRelease`, copies the arm64-v8a APK to `~/tmp/`, auto-increments the build number; the F-Droid build gate is extended to cover it (Firebase plugins skipped, a dangling Firebase import removed).
- Fork versioning: `versionCode = <upstream code> * 10000 + <build>` with upstream’s per-ABI offset, so every fork build upgrades cleanly over the previous line.
- Upstream `FUNDING.yml` removed (the fork does not inherit funding links).
