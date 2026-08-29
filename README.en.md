<img src="docs/app-icon.png" width="96" alt="FamilyShare">

# FamilyShare — Private Location Sharing for Families

> [中文](README.md) | **English**

A **private family location sharing** app: family members join the same family by entering a 6-digit code (**owner approval required**), then see each other's live location on a map. It supports low-power background reporting, auto-start on boot, in-app updates, "ring my phone", tracks, blacklist, and a message center. Coordinates use AMap GCJ‑02.

- 📱 Client: `android/` (native Android, Java, AMap 3D Map 9.8.3 + OkHttp)
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
| AMap map | 3D Map SDK 9.8.3 (with built-in location SDK), GCJ-02 coordinates, compass + scale bar |
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
| Map / location | `com.amap.api:3dmap:9.8.3` (built-in location; do not add the separate location package) |
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

Released under the [MIT License](LICENSE).

> ⚠️ If you publish on an app store, note the AMap SDK's commercial terms may differ from the open-source license — verify before publishing.

---

## 📋 Changelog

- 2026.08.29 Updated: homepage now reflects the current features (tracks with a green start point; new sections for blacklist / message center / in-app update / server switch); added an official website link to the README and unified the project name to 家庭共享. ----- Committed 2026.08.29
- 2026.08.29 Updated: tracks now use a single green start point instead of direction arrows; the homepage gained a GitHub repo link and a "self-host" note; ring duration is customizable (10/30/60/90/120 s or custom 1-300 s); ring state is refreshed on app open so the "stop ring" button is visible. ----- Committed 2026.08.29
- 2026.08.29 Updated: server switch (⋮ "Switch server": pick official/custom, add/delete); network layer auto-retries transient network/DNS errors with friendlier messages. Fixed: dots disappearing after server switch, DNS resolution failures on some users. ----- Committed 2026.08.29
- 2026.08.29 Updated: family dialog defaults to "Join" and shows the code field immediately; auto-requests everyone's location on app open; auto-start management now guides a search. Fixed: not seeing other members after joining, code input field that needed a toggle. ----- Committed 2026.08.29

**Star ⭐ if this helps you!**
