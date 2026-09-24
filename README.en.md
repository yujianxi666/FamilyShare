<img src="docs/app-icon.png" width="96" alt="FamilyShare">

# FamilyShare — Private Location Sharing for Families

> [中文](README.md) | **English**

A **private family location sharing** app: family members join the same family by entering a 6-digit code (**owner approval required**), then see each other's live location on a map. It supports low-power background reporting, auto-start on boot, in-app updates, "ring my phone", tracks, blacklist, and a message center. Coordinates use AMap GCJ‑02.

- 📱 Client: `android/` (native Android, Java, AMap SDK 3dmap 9.8.3 + OkHttp)
- ☕ Server: `server/` (Java / Spring Boot 3.3.4, REST + WebSocket, Redis persistence)
- 📄 Docs: `docs/` (protocol, AMap key guide, icon)
- 🌐 Website: [https://fms.uiero.com](https://fms.uiero.com)

> Built as a **family/self-hosted** project: there is **no account system** (`deviceId` is the identity, the family code is the credential), and location data is stored **only on your own server**. For production, put it behind HTTPS/WSS and set a strong API token.

---

## ✨ Features

| Feature | Description |
| --- | --- |
| Live location sharing | Request to join with a 6-digit code (owner approval) → color pins on the map; online/offline status updates live; member-list avatar shares the pin color |
| Low-power background + boot | Foreground service (`dataSync`) + `AlarmManager.setAndAllowWhileIdle` + `BOOT_COMPLETED` relaunch + 5-minute watchdog |
| AMap map | Full 3D map SDK 9.8.3 (native rendering, basemap and labels in the same layer, so **place names do not drift while zooming**), GCJ-02, self-drawn scale bar |
| Real-time refresh | Someone taps "Refresh" → server sends `report-now` over WebSocket → target locates immediately and reports → everyone updates in seconds |
| Refresh on open | Every time the app opens (including returning from background) in a family with >1 member, it auto-requests a fresh location from everyone (bottom toast, no popup) |
| Member detail | Battery, network (incl. WiFi name), coarse address, accuracy, one-tap navigation, ring |
| Ring my phone | "Ring" in the detail view → target phone rings for 30 s + notification |
| Tracks | Any member can enable a track for others/self (1/3/5 min or custom) → same-color polyline on the map with direction arrows |
| Offline mode | ⋮ menu "Go offline/online": your own location stops updating and you appear gray to others |
| Blacklist | Owner can "remove only / remove & ban"; ⋮ "Blacklist" to view/un-ban |
| Message center | Join requests (owner approves/rejects) and join invitations |
| In-app update | Silent check on boot + ⋮ "Check update"; MD5 comparison; progress + verify + one-tap install |
| Bug feedback | ⋮ "Report bug" → written to `server/bugs.json`, with an admin page at `/bugadmin/<token>` |
| Server switch | ⋮ menu "Switch server": pick the official server (address hidden) or a custom one (address shown); add (host/IP + optional port + http/https) and delete; defaults to official; auto-reconnects on switch |
| Network retry | Auto-retries transient network/DNS errors a few times and shows friendlier messages (e.g., "Domain resolution failed, check network or DNS") |

---

## 🧱 Tech Stack

| Layer | Tech |
| --- | --- |
| Client | Java, Gradle / AGP 8.5.2 / Gradle 8.7 / JDK 17+, minSdk 23 / targetSdk 34 |
| Map / location | AMap **full 3D map SDK 9.8.3** (Maven: `com.amap.api:3dmap:9.8.3`; it already bundles location, so do **not** add `com.amap.api:location` separately), native rendering with `.so`, release APK ≈ 17MB |
| Networking | OkHttp 4.12.0 (REST + WebSocket) |
| UI | Material 1.12.0 / AppCompat / RecyclerView |
| Server | Spring Boot 3.3.4 (starter-web + starter-websocket), Java 17, Maven, embedded Tomcat, port 3000 |
| Persistence | Redis (per-entity keys, full sync + delete reconciliation every 2 s); avatars as files, bug feedback as a file |

---

## 📥 Requirements

| Dependency | Version / Notes |
| --- | --- |
| JDK | 17+ (21 works too) |
| Maven | 3.9.x |
| Android Studio / Gradle | Open `android/`; AS generates the Gradle 8.7 wrapper (or run `gradle wrapper`) |
| Redis | Required by the server (default `127.0.0.1:6379`, see `server/src/main/resources/application.properties`) |
| AMap account | Request a "Maps + Location" Android Key (see `docs/SETUP_AMAP.md`) |
| Node (optional) | ≥22, for `server/smoke-test.mjs` protocol smoke test |

---

## 🚀 Quick Start

### 1. Server

```bash
cd server
mvn -DskipTests package                 # produces target/family-share-server.jar
java -jar target/family-share-server.jar # needs local Redis, listens on 0.0.0.0:3000
curl http://127.0.0.1:3000/api/health   # -> {"status":"ok",...}
```

**Configure first** `server/src/main/resources/application.properties`:
- `app.api-token`: must equal the client `API_TOKEN` (access token).
- `app.bug-admin-token`: path token for `/bugadmin/<token>`.
- `spring.data.redis.password`: your Redis password (leave empty if none).

Change port: `java -jar target/family-share-server.jar --server.port=3001`.

### 2. Client

> The map SDK comes straight from Maven: `com.amap.api:3dmap:9.8.3` (full 3D map, native rendering with bundled `.so`) — no jar needs to be downloaded manually.

1. Request an AMap Android Key per `docs/SETUP_AMAP.md` (bind package `com.family.share` + your signing SHA1, enable **Maps + Location**).
2. Edit `android/gradle.properties`:

   ```properties
   AMAP_KEY=<your amap key>
   SERVER_URL=https://<your server host or ip>[:port]
   API_TOKEN=<must match server app.api-token>
   ```

3. Android Studio → Sync → Run; or from the CLI:

   ```bash
   cd android
   ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
   ./gradlew assembleRelease      # release APK
   ```

### 3. First use (two phones)

1. Phone A: open → the family dialog defaults to "Join"; switch to "**Create family**" → set a nickname → get a 6-digit code (accept the privacy notice).
2. Phone B: open → "Join family" → enter the code → submit, wait for A to approve in ⋮ "Messages" and you're auto-added.
3. Grant "Allow all the time" location on both; ⋮ → Permission settings → enable battery optimization exemption and auto-start management (guided search).
4. The map shows each other; tap a member for details; ⋮ → refresh / messages / offline / check update.

---

## 📁 Structure

```
FAMILYSHARE/
├── android/            # Android client
│   ├── gradle.properties      # ★ AMAP_KEY / SERVER_URL / API_TOKEN
│   └── app/src/main/          # source, layouts, resources
├── server/             # Spring Boot server
│   ├── src/main/java/...      # ApiController / WsHandler / Store / token filter
│   ├── src/main/resources/    # application.properties, website/privacy/rights pages, bugadmin
│   ├── pom.xml / run-server.bat / smoke-test.mjs / update.json
├── docs/               # PROTOCOL.md, SETUP_AMAP.md, app-icon.png
└── .gitignore
```

---

## 🔌 Protocol

- Full REST + WebSocket protocol: [`docs/PROTOCOL.md`](docs/PROTOCOL.md).
- Coordinates are always AMap GCJ-02; the server only passes them through.
- Identity model: `deviceId` (a UUID generated on first launch) is the identity; `familyId` + 6-digit `code` make a family; joining requires owner approval.
- Access token: apart from the `/api/health`, `/downloads/**`, `/bugadmin/**` whitelist, all `/api/**`, `/ws`, `/icons/**` require `X-Api-Token` or `token`.

---

## 🗺️ AMap Key
Follow [`docs/SETUP_AMAP.md`](docs/SETUP_AMAP.md) to request and configure it (Android-platform Key, bound package + SHA1, enable Maps/Location).

---

## ⚠️ Security & Disclaimer

- There is **no account system**; the family code is the credential and the blacklist is enforced by the creator's identity. Keep the access token and family codes safe.
- Location data lives on **your own server**; for public deployments add an HTTPS/WSS reverse proxy and tighten `network_security_config.xml` to your domain only.

---

## 📮 Provide a server

If you are willing to provide a server for this project, feel free to contact: **3557665817@qq.com**.

---

## 📄 License

Released under the [CC BY-SA 4.0](LICENSE) license (Creative Commons Attribution-ShareAlike 4.0 International), copyright (c) 2026 KeepHope2901.

- You are free to **share** (copy and redistribute) and **adapt** (remix, transform, build upon) this work, including commercially.
- You must give **appropriate credit** (name the author, link the license, indicate changes) and **distribute your contributions under the same license** (CC BY-SA 4.0).
- Full license text: [LICENSE](LICENSE); official text: <https://creativecommons.org/licenses/by-sa/4.0/legalcode.en>

---

## 📋 Changelog

- 2026.09.12 Fixed two multi-family issues: (1) **"Family code" wrongly reported "not joined a family"** — it read the legacy single-family `family_code` field, which is empty after switching families; it now reads the current family's own code (the `family_codes` map), and adding a family also writes the legacy field as a fallback. (2) **A member label from the previous family could briefly appear in the current family when switching during a refresh** — the location result callback read "the current family" instead of the family the report was started for, and that in-flight refresh continued after the switch; reports and broadcasts are now bound to the **family id captured when the report started**, and the service keeps a **family generation** (bumped on every family switch) so results from a stale generation are discarded; WebSocket member locations also carry the current family id and the UI filters by family. After switching, the app reports its own location once so the new family sees it quickly.

- 2026.09.12 Fixed "no family to choose in the switch-family dialog": the previous implementation rendered the list with `AlertDialog.setItems()` and relied only on local storage (when that is out of sync with the server the list came out empty). It now **requests `GET /api/family/my` when the dialog opens** to get the real family list and renders it as hand-built rows (each showing the family code, member count, owner badge and a ✓ for the current family), with a "Create / Join family" entry at the bottom; if the server is unreachable it falls back to the local record, and when there is genuinely no family it shows the create/join guide.
- 2026.09.12 Multi-family interaction and display changes from feedback: (1) **removed the "swipe left/right to switch family" gesture and the side fades**, replaced by a **"Switch family" button below "Locate me"** (opens the list of joined families with the current one ticked; with a single family it offers create/join and keeps the existing family); (2) the title **no longer shows the "family x/y" numbering** — just "code · family members"; (3) fixed **family data bleeding across switches**: after switching, a late member-list response or location broadcast from the previous family could overwrite the new one (seen as "people refreshed in family 1 showing up in family 2") — a **family generation** counter is now bumped on every switch/disband/removal and every member-list response and location broadcast is checked against both the generation and the family id, mismatches are dropped; location broadcasts now carry the family id for that check; (4) removed the dead zoom-time label-suppression code left over from the lite WebView map and **stopped calling `setLayoutParams` on the MapView** (with the native 3D SDK that can recreate the GL surface and crash the map's native thread).

- 2026.09.12 Fixed "the member panel stretched to the full screen and the map disappeared": the newly added "swipe to switch family" fade views used `layout_height="match_parent"` while their parent (the FrameLayout wrapping the member list) is `wrap_content`, so those two full-height children inflated it to the whole screen, which in turn made `panelBody` / the panel height compute as full-screen and pushed the map away completely (located by measuring with `adb shell uiautomator dump`: `bottomPanel` came back 2578px tall). It is now a **single fixed-height (72dp) horizontal fade layer** (`bg_fade_horizontal`, one layer drawing both the left and right falloff) and the two one-sided fade drawables were removed; the whole layout was re-audited and, apart from the root container and the map, no view uses a `match_parent` height any more.

- 2026.09.12 **Fixed "crashes immediately on launch"**: when switching back to the full 3D map SDK, the map initialisation (including `initScaleBar()` / `updateScaleBarPosition()`) was moved ahead of the panel views' `findViewById`, so `onCreate` touched a still-null `bottomPanel` and threw `NullPointerException: View.getWidth() on a null object reference`, crashing at startup (diagnosed by pulling the stack trace with adb). Now the synchronously-ready map only registers its camera listener, and all panel/scale-bar/marker initialisation is deferred until every view exists (`postPanelReadyInit()` → `mapView.post`), with null-safe fallbacks in `updateScaleBarPosition()` / `panelFullHeight()` / `panelHeaderHeight()`. Verified on a real device: launches fine, no exceptions in logcat.

- 2026.09.12 Added **multi-family support**: one device can now belong to several families at once, and **creating or joining a family no longer leaves the existing ones** (the server no longer removes the device from other families when creating a family or approving a join; a new `GET /api/family/my` lists every family the device belongs to). On the main screen you **swipe the member list left/right to switch families** (left = next, right = previous; vertical drags still scroll the list) and the title shows "code · family x/y"; with more than one family the list gets **transparent fades on both edges plus a breathing "swipe to switch family" hint** (coexisting with the top/bottom member fades). The owner menu gained **"Disband family"** (confirm, then the family is deleted; all members receive the WS `family-disbanded` event and drop it — if the device still has other families it switches automatically, otherwise it returns to the create/join guide). Being removed from one family now removes only that family instead of clearing everything. The family list and each family's code are stored locally (`family_ids` / `family_codes`); old single-family data migrates automatically.

- 2026.09.12 **Switched the map SDK back from the "Lite V1.3.2 (WebView-rendered)" to the "full 3D map SDK 9.8.3 (native rendering)"**: the lite SDK is WebView + AMap JS API, where the basemap (tiles) and the place-name labels are two layers with different refresh cadence, so during a zoom animation place names move faster than the basemap (AMap's own FAQ confirms the basemap is not refreshed in real time while the map moves) and an app cannot correct it; the full SDK renders natively with basemap and labels in one layer, removing the problem at the root. Changes: `app/build.gradle` uses the Maven dependency `com.amap.api:3dmap:9.8.3` again (**it already bundles location — do not add `com.amap.api:location`, or the build fails with duplicate classes**) and the manually placed lite jar was removed from `app/libs/`; `MainActivity` goes back from the async `getMapAsyn()` to the synchronous `getMap()`. The release APK grows from ~3.3MB to **17MB** (the trade-off for correct rendering). Zoom-time label suppression is now off by default (unnecessary with native rendering; the constant is kept in case the lite SDK is ever used again).
- 2026.09.12 Removed the two diagnostic entries added in the previous build (the ⋮ menu "Hide basemap text" and the "Marker calibration" dialog) as requested, together with their fields and strings. Added **zoom-time label suppression** (`HIDE_MAP_TEXT_WHILE_ZOOMING`, on by default) for "the basemap place name moves faster than the basemap during zoom animations": the basemap text is not drawn while zooming and is drawn again when the animation ends, so place names no longer smear (the trade-off is a brief disappearance during the zoom, then they appear in the correct place). The **map container size re-alignment** stays (re-measure the WebView container on map ready, on layout change and when returning from background) to rule out a stale container size making the label layer scale at the wrong ratio. If it still does not improve, either hide basemap text entirely or switch back to the full 3D map SDK (native rendering, basemap and labels in the same layer).
- 2026.09.12 Investigated "when zooming out the basemap place name (e.g. 武汉) moves faster than the basemap and only realigns when the zoom finishes": AMap's official FAQ states that with the JS API / lite SDK the **basemap is not refreshed in real time while the map is moving (including zoom animations)** — the basemap and the label overlay are two layers with different refresh cadence, which is SDK behaviour that an app cannot correct directly. To rule out a stale container size making the label layer scale at the wrong ratio, this build adds **map container size re-alignment**: on map ready, on layout change and when returning from background the WebView container is re-measured and the viewport ratio recomputed (if that was the cause on your device, this alone fixes it). The ⋮ menu also has "Hide basemap text" so you can keep only the family markers if you don't need place names (can be made the default).
- 2026.09.12 Fixed the panel drag feel: reverted the "commit as soon as the threshold is crossed" behaviour (which made the panel snap back while your finger was still down) — the decision is now made **only on release**, and from the absolute distance pulled rather than a ratio of the total travel: pulling up more than 4dp from the collapsed state expands, pulling down more than 16dp from the expanded state collapses, and a quick flick still wins by direction; the panel also follows the finger in either direction during the drag. Removed the "N more family members" text from the member list (only the top/bottom transparent fades remain; the clipped row fades out naturally).
- 2026.09.12 Panel/list interactions redone from feedback: (1) expanding no longer requires pulling a long distance — pulling up 22dp from the collapsed state expands immediately (the threshold commits during the drag, no need to release), and a quick upward flick expands too; (2) the grey drag handle's touch area grew from 88×32dp to the full row × 40dp (the thin visual bar is unchanged), making it much easier to grab; (3) **removed the "pull down at the top of the list to collapse" feature** — the list only scrolls now, collapse via the grey handle/header; (4) the list height is now "3 rows + ~40% of the next row" so you can tell who is next. Fixed the "N more members" hint undercounting by one: the partially visible row is now counted too (5 members no longer shows "1 more"), and when the list is clipped at the top it also shows "N more family members above, swipe down". Added a "Marker calibration" item (⋮ menu): nudge the vertical position of member markers in 2dp steps (to correct the few-pixel offset some phones get from density/DPR mismatch), plus a "use built-in markers" escape hatch to test whether the issue comes from our custom bitmap maths.
- 2026.09.12 Fixed the marker's position relative to its coordinate changing while zooming: in the collapsed (dot) style the anchor was the bottom edge of the *name label*, which pushed the whole marker about one label-height below the real position; it is now "label on top, dot below" with the anchor exactly at the dot's bottom — the same pin-point behaviour as the expanded card's tail tip, so switching styles no longer makes markers jump. When the member list is clipped it now shows a half row plus a transparent fade and a "N more family members, swipe up" hint (fades at both top and bottom), so it is obvious that more members are below. Rewrote the panel gestures: pulling up from the collapsed state now expands after only 30% of the distance (a quick upward flick expands immediately) instead of requiring a long pull; release decisions also use fling velocity; fixed "the pull-down-to-collapse only works once, then stops working" and the corrupted state when a gesture is interrupted mid-way, while keeping the rule that reaching the top of the list does not collapse it — you must reach the top and then pull down again.
- 2026.09.12 Added a "Hide basemap text" diagnostic toggle (⋮ menu): the lite map SDK draws city/place-name labels with its own bundled JS and they cannot be controlled per layer, so if a particular phone shows misplaced basemap text while zooming, this toggle tells you whether the problem is the text layer or the tile/GPU rendering layer.
- 2026.09.12 Fixed marker/name-label offset on some phones after zooming out: the lite map SDK sizes marker icons and their anchor offset in the web layer as "bitmap pixels / screen density", so when the bitmap dimensions are not divisible by that density the two roundings disagree and the marker (with its name text) drifts from the real coordinate. Marker bitmaps are now aligned to the screen density and the anchor is exactly the bitmap's bottom-center (the tail tip sits on the point), so the position stays correct at any zoom. UI improvements in the same pass: below zoom 13 markers collapse into a small dot + tiny name label and expand back into full cards when you zoom in (no more overlapping labels); accuracy circles auto-hide when zoomed out or when there are many members; track lines now fade by time (older points lighter, newest brightest); the members panel is now a card list (row cards, avatar ring, an empty-state guide card with a "Create / Join family" button); expanding the panel springs slightly and fades its content in. ----- Committed 2026.09.12
- 2026.09.12 Fixed hidden issues: (1) when a new location request replaced a pending one, the previous waiter never got a callback (tapping "refresh" repeatedly could hang) — the old request is now cancelled first; (2) with a single located member, fitting to `LatLngBounds` could jump to an odd zoom — a single point now uses a fixed zoom; (3) after changing an avatar the URL stays the same, so markers and the list kept the old image — the cache is now force-invalidated on upload; (4) when ring playback failed the app still showed the "ringing" notification and stop button — failures are now silent; (5) marker icons were rebuilt on every location update (and the SDK keeps every icon bitmap in memory forever) — icons are now cached per name/avatar, saving memory and CPU on long runs; (6) the system JobScheduler watchdog was not cancelled when sharing was turned off, waking the device every 15 minutes for nothing — now cancelled too. ----- Committed 2026.09.12
- 2026.09.12 Server security & robustness: member list / blacklist now verify the requester belongs to that family, join-request status can only be queried by the requester, and the owner can no longer remove or ban themselves; fixed the delete reconciliation that never worked (it iterated the same index set it was about to rewrite), which let stale Redis keys pile up; Redis loading moved to a background thread so startup is no longer blocked; join requests, ownership transfer and member removal are now persisted immediately instead of waiting for the 2-second cycle; WebSocket connection state updates and family broadcasts are locked/snapshotted to avoid concurrent modification; request/family IDs now use UUIDs; the bug-admin path token became the `app.bug-admin-token` property. ----- Committed 2026.09.12
- 2026.09.12 Version alignment: `server/update.json` versionCode/versionName now match the client (1 / 1.0.0). The client actually compares APK MD5 when checking for updates, so this only removes a config-level inconsistency. ----- Committed 2026.09.12
- 2026.09.10 For licensing reasons the AMap Lite Map SDK jar is no longer distributed with this repo (`android/app/libs/*.jar` / `*.aar` are now git-ignored); the client build section documents the prerequisite (download Lite3DMap.zip yourself and put its jar into `android/app/libs/`). ----- Committed 2026.09.10
- 2026.09.10 Switched the map to AMap's Lite Map SDK V1.3.2 (WebView-rendered, no native `.so`, bundles location 6.4.9): dropped the Maven `3dmap` dependency and vendored the SDK jar in `app/libs/`; the map object is now async-ready (`getMapAsyn` — map ops are skipped until ready, then markers/tracks are drawn and the camera fits); removed the compass / zoom-controls / scale-controls and map privacy calls the lite SDK does not support. The release APK dropped from ~17 MB to ~3.3 MB. ----- Committed 2026.09.10
- 2026.09.10 Reduced the app size: the release build now enables R8 minification + resource shrinking and packages only ARM real-device ABIs (dropping the armeabi / x86_64 native libs). The release APK went from ~28 MB+ to ~17 MB. AMap and ZXing keep rules were added so minification cannot break them at runtime. Note: to install a release build on an x86 emulator, relax the ABI filter yourself. ----- Committed 2026.09.10
- 2026.09.10 Improved background keep-alive: added a system-managed JobScheduler watchdog (15-min period, persisted across reboots, complementing the existing alarm + foreground service), plus a gentle reminder every 3 days when the app is not whitelisted for battery optimization / auto-start. The app's About dialog now links the GitHub repo; the website download button now points to GitHub instead of Huawei AppGallery, and the privacy / rights pages gained the GitHub link. The server gained a read-only status dashboard (/dashboard/&lt;token&gt;: total users, online devices, families, CPU, memory, in/out bandwidth and load curves) — aggregate-only data, no private information, and no way to modify anything. ----- Committed 2026.09.10
- 2026.08.30 The detail page now keeps the scale bar visible; the About dialog shows the app filing number (鄂ICP备15020522号-2A); the join-family page's "Scan to join" is now a prominent button and a scanned code joins directly (no extra confirm tap). ----- Committed 2026.08.30
- 2026.08.30 Added QR codes: the family-code dialog now shows a QR code under the number; the join-family dialog gained a grey "Scan to join" link below the two inputs (camera permission is only requested when it is tapped); the member list now sorts the owner to the top, and when there are more than 4 members the 5th is half-visible as a scroll hint. ----- Committed 2026.08.30
- 2026.08.30 Refined when the list collapses on pull-down: it only collapses when the list is already at the top and you pull down again. The pull that scrolls the list up to the top does not collapse (you must reach the top, then pull down once more), and swiping up can no longer collapse the panel. ----- Committed 2026.08.30
- 2026.08.30 Refined the refresh & panel interaction: a top toast now says "xxx's location updated" when a family member's position updates (rate-limited to avoid a burst of toasts); swiping up while expanded now only scrolls the member list and no longer collapses the panel; the grey handle row expands on swipe-up from anywhere on it; the member list shows at most 4 rows and scrolls for more, and pulling down after reaching the top collapses the panel. ----- Committed 2026.08.30
- 2026.08.29 Updated the join request: it now checks on going online and auto-joins once approved; closing the waiting dialog or reopening the app keeps checking. Fixed: users could only join if approved on the spot, and closing the dialog forced a re-apply. ----- Committed 2026.08.29
- 2026.08.29 Updated: refresh-location feedback now shows a toast at the top; all sub-menu "Cancel" buttons became "Back"; panel swipe directions corrected (collapsed+up expands, expanded+down collapses, expanded+up does nothing); the member list shows at most 4 rows and scrolls for more. ----- Committed 2026.08.29
- 2026.08.29 Updated: homepage now reflects the current features (tracks with a green start point; new sections for blacklist / message center / in-app update / server switch); added an official website link to the README and unified the project name to 家庭共享. ----- Committed 2026.08.29
- 2026.08.29 Updated: tracks now use a single green start point instead of direction arrows; the homepage gained a GitHub repo link and a "self-host" note; ring duration is customizable (10/30/60/90/120 s or custom 1-300 s); ring state is refreshed on app open so the "stop ring" button is visible. ----- Committed 2026.08.29
- 2026.08.29 Updated: server switch (⋮ "Switch server": pick official/custom, add/delete); network layer auto-retries transient network/DNS errors with friendlier messages. Fixed: dots disappearing after server switch, DNS resolution failures on some users. ----- Committed 2026.08.29
- 2026.08.29 Updated: family dialog defaults to "Join" and shows the code field immediately; auto-requests everyone's location on app open; auto-start management now guides a search. Fixed: not seeing other members after joining, code input field that needed a toggle. ----- Committed 2026.08.29

**Star ⭐ if this helps you!**
