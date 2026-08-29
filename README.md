<img src="docs/app-icon.png" width="96" alt="FamilyShare">

# 家庭位置共享（FamilyShare）

> [English](README.en.md) | **中文**

一个**家庭私享的位置共享 App**：家人用 6 位家庭码申请加入同一家庭（**需群主同意**），在地图上实时看到彼此位置；支持低功耗后台上报、开机自启、应用内更新、响铃、轨迹、黑名单、消息中心等。坐标使用高德坐标系（GCJ-02）。

- 📱 客户端：`android/`（原生 Android，Java，高德 3D 地图 9.8.3 + OkHttp）
- ☕ 服务端：`server/`（Java / Spring Boot 3.3.4，REST + WebSocket，Redis 持久化）
- 📄 文档：`docs/`（协议、高德 Key 申请、图标）

> 本项目为“家庭自用”而设计：**无账号体系**（`deviceId` 即身份、家庭码即授权凭证），位置数据仅存储到你自己部署的服务器。生产环境建议加 HTTPS/WSS 反代，并妥善设置访问口令。

---

## ✨ 功能特性

| 功能 | 说明 |
| --- | --- |
| 实时位置共享 | 6 位家庭码申请加入（需群主同意）→ 地图彩色图钉实时展示；上下线状态实时变化；成员列表头像与地图标点同色 |
| 低功耗后台 + 开机自启 | 前台服务（`dataSync`）+ `AlarmManager.setAndAllowWhileIdle` 自调度 + `BOOT_COMPLETED` 自动拉起 + 5 分钟看门狗 |
| 高德地图 | 3D 地图 SDK 9.8.3（内置定位 SDK），GCJ-02 坐标，指南针 + 刻度尺 |
| 实时刷新 | 家人点「刷新」→ 服务端 WebSocket 下发 `report-now` → 目标立即定位上报 → 全家人秒级更新 |
| 打开自动刷新 | 每次打开 App（含从后台返回）且在多人家庭时，自动向全员请求一次实时位置并底部提示 |
| 成员详情 | 电量、网络（含 WiFi 名称）、粗略地址、精度、一键导航、响铃 |
| 响铃 | 详情页「响铃」→ 目标手机响铃 30 秒 + 通知 |
| 轨迹 | 任意成员可为成员/自己开启轨迹（1/3/5 分钟或自定义间隔）→ 地图同色连线 + 方向箭头 |
| 下线模式 | ⋮ 菜单「下线/上线」：开启后自己不更新位置、对他人显示灰色离线 |
| 黑名单 | 创建者移出时可「仅移出 / 移出并拉黑」；⋮「黑名单管理」查看/解除 |
| 消息中心 | 集中展示入群申请（群主可同意/拒绝）与加入邀请 |
| 应用内更新 | 启动静默检查 + ⋮「检查更新」；MD5 比对；下载进度 + 校验 + 一键安装 |
| 反馈/Bug 上报 | ⋮「反馈Bug」→ 服务端写入 `server/bugs.json`，附管理页 `/bugadmin/<token>` |

---

## 🧱 技术栈

| 层 | 技术 |
| --- | --- |
| 客户端 | Java、Gradle / AGP 8.5.2 / Gradle 8.7 / JDK 17+，minSdk 23 / targetSdk 34 |
| 地图/定位 | `com.amap.api:3dmap:9.8.3`（内置定位，勿再单独引入 location 包） |
| 网络 | OkHttp 4.12.0（REST + WebSocket） |
| UI | Material 1.12.0 / AppCompat / RecyclerView |
| 服务端 | Spring Boot 3.3.4（starter-web + starter-websocket），Java 17，Maven，内嵌 Tomcat，端口 3000 |
| 持久化 | Redis（按实体分 key，每 2 秒全量同步 + 删除对账）；头像为文件、Bug 反馈为文件 |

---

## 📥 环境要求

| 依赖 | 版本/说明 |
| --- | --- |
| JDK | 17+（21 亦可） |
| Maven | 3.9.x |
| Android Studio / Gradle | 打开 `android/` 由 AS 生成 Gradle 8.7 wrapper；或命令行 `gradle wrapper` |
| Redis | 服务端运行必需（默认 `127.0.0.1:6379`，见 `server/src/main/resources/application.properties`） |
| 高德账号 | 申请“地图 + 定位”Android Key（见 `docs/SETUP_AMAP.md`） |
| Node（可选） | ≥22，用于 `server/smoke-test.mjs` 协议冒烟测试 |

---

## 🚀 快速开始

### 1. 服务端

```bash
cd server
mvn -DskipTests package                 # 产出 target/family-share-server.jar
java -jar target/family-share-server.jar # 需本机 Redis，监听 0.0.0.0:3000
curl http://127.0.0.1:3000/api/health   # -> {"status":"ok",...}
```

- **必须先配置** `server/src/main/resources/application.properties`：
  - `app.api-token`：与客户端 `API_TOKEN` 一致（访问口令）。
  - `app.bug-admin-token`：Bug 管理页 `/bugadmin/<token>` 的路径口令。
  - `spring.data.redis.password`：你的 Redis 密码（无密码留空）。
- 改端口：`java -jar target/family-share-server.jar --server.port=3001`。

### 2. 客户端

1. 按 `docs/SETUP_AMAP.md` 申请高德 Android Key（绑定包名 `com.family.share` + 你的签名 SHA1，勾选**地图 + 定位**）。
2. 编辑 `android/gradle.properties`：

   ```properties
   AMAP_KEY=<你的高德Key>
   SERVER_URL=https://<你的服务器域名或IP>[:端口]
   API_TOKEN=<与服务器 app.api-token 一致>
   ```

3. Android Studio → Sync → Run；或命令行：

   ```bash
   cd android
   ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
   ./gradlew assembleRelease      # 发布包
   ```

### 3. 首次使用（两台手机）

1. 手机 A：进入 → 家庭设置默认「加入家庭」，请切到「**创建家庭**」→ 设昵称 → 得到 6 位家庭码（同意隐私说明）。
2. 手机 B：进入 → 「加入家庭」→ 输家庭码 → 提交申请，等 A 在 ⋮「消息」中同意后自动入群。
3. 两台都授权定位「始终允许」；⋮ → 权限设置 → 开启忽略电池优化、自启动管理（引导搜索）。
4. 地图显示彼此位置；点成员行看详情；⋮ → 一键刷新 / 消息 / 下线 / 检查更新。

---

## 📁 目录结构

```
FAMILYSHARE/
├── android/            # Android 客户端
│   ├── gradle.properties      # ★ AMAP_KEY / SERVER_URL / API_TOKEN
│   └── app/src/main/          # 源码、布局、资源
├── server/             # Spring Boot 服务端
│   ├── src/main/java/...      # ApiController / WsHandler / Store / 访问口令过滤
│   ├── src/main/resources/    # application.properties、官网/隐私/权利页、bugadmin
│   ├── pom.xml / run-server.bat / smoke-test.mjs / update.json
├── docs/               # PROTOCOL.md、SETUP_AMAP.md、app-icon.png
└── .gitignore
```

---

## 🔌 协议

- 完整 REST + WebSocket 协议见 [`docs/PROTOCOL.md`](docs/PROTOCOL.md)。
- 坐标统一为高德坐标系 GCJ-02；服务端只透传不转换。
- 身份模型：`deviceId`（首启生成的 UUID）即身份，`familyId` + 6 位 `code` 即家庭；加入家庭需群主同意。
- 访问口令：除 `/api/health`、`/downloads/**`、`/bugadmin/**` 白名单外，`/api/**`、`/ws`、`/icons/**` 都要带 `X-Api-Token` 或 `token`。

---

## 🗺️ 高德 Key
按 [`docs/SETUP_AMAP.md`](docs/SETUP_AMAP.md) 申请并配置（Android 平台 Key，绑定包名 + SHA1，勾选地图/定位服务）。

---

## ⚠️ 安全与免责

- 该设计**无账号体系**，家庭码即授权凭证、黑名单凭创建者身份判定；请妥善保管访问口令与家庭码。
- 位置数据保存在**你自己部署的服务器**；公网部署建议增加 HTTPS/WSS 反向代理，并把 `network_security_config.xml` 收紧为仅允许你的域名。

---

## 📄 License

本项目采用 [MIT License](LICENSE) 开源。

> ⚠️ 若你在应用市场发布，请注意高德 SDK 的商用许可条款可能与开源许可存在差异，发布前请自行确认。

---

**Star ⭐ 如果这个项目对你有帮助！**
