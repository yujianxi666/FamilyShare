# 通信协议说明

> 完整协议以根目录 `README.md` 第 5 节为准，本文档为简明版。坐标统一为**高德坐标系 GCJ-02**（高德定位 SDK 默认输出，高德地图默认使用），服务端仅透传、不转换。

## 1. REST 接口

基础地址：`SERVER_URL`（gradle.properties 配置），JSON 请求体。

| 方法 | 路径 | 请求体 | 响应 |
| --- | --- | --- | --- |
| POST | `/api/family/create` | `{deviceId, name}` | `{familyId, code}` |
| POST | `/api/family/join` | `{code, deviceId, name}` | `{status:"pending", requestId}`（**加入需群主同意**）；已是成员返回 `{status:"ok",familyId,code}`；404=码不存在或被拉黑 |
| GET | `/api/family/join/status?requestId=&deviceId=` | - | `{status:"pending"\|"approved"\|"rejected"}`；approved 含 `familyId,code`（申请方轮询；`deviceId` 须为申请人本人） |
| POST | `/api/family/join/handle` | `{familyId, ownerDeviceId, requestId, approve}` | `{status:"ok"}`；403=非群主 |
| GET | `/api/family/join/list?familyId=&deviceId=` | - | `[{requestId,deviceId,name,createdAt}]`（仅群主，待审批） |
| GET | `/api/family/members?familyId=[&deviceId=]` | - | `[{deviceId,name,online,offlineMode,isOwner,track,trackInterval,location,trajectory,avatar}]`（isOwner=是否群主；带 `deviceId` 时校验其确属该家庭） |
| POST | `/api/family/member/remove` | `{familyId, ownerDeviceId, targetDeviceId, ban}` | `{status:"ok"}`（非创建者 403；群主不能移除自己 400；ban=true 加入黑名单；被移出者立即收到 `member-removed` 退出） |
| GET | `/api/family/banned?familyId=&deviceId=` | - | `[{deviceId,name,bannedAt}]`（仅创建者） |
| POST | `/api/family/member/unban` | `{familyId, ownerDeviceId, targetDeviceId}` | `{status:"ok"}` |
| POST | `/api/family/owner/transfer` | `{familyId, ownerDeviceId, newOwnerDeviceId}` | `{status:"ok"}`；403=非创建者 |
| GET | `/api/family/my?deviceId=` | - | `[{familyId,code,isOwner,memberCount,createdAt}]`（该设备加入的**全部**家庭；同一设备可同时属于多个家庭，客户端左右滑动切换） |
| POST | `/api/family/disband` | `{familyId, ownerDeviceId}` | `{status:"ok"}`；403=非群主。解散后家庭被删除，全员收到 WS `family-disbanded`，客户端把该家庭从家庭列表中移除 |
| POST | `/api/family/invite` | `{familyId, fromDeviceId, targetDeviceId, code, fromName}` | `{status:"ok"}` 或 `{status:"offline"}`（WS 推送 `invite`） |
| POST | `/api/location/report` | `{deviceId,familyId,lat,lng,accuracy,ts,battery,network,address}` | `{status:"ok"}` |
| POST | `/api/location/request` | `{familyId, requesterId, targetDeviceId}` | `{status:"ok"}` 或 `{status:"offline"}` |
| POST | `/api/member/track` | `{familyId, ownerDeviceId, targetDeviceId, track, intervalMs}` | `{status:"ok"}`；intervalMs>0 设更新间隔 |
| POST | `/api/member/offline` | `{familyId, deviceId, offline}` | `{status:"ok"}` |
| POST | `/api/ring/request` | `{familyId, requesterId, targetDeviceId, name}` | `{status:"ok"}` 或 `{status:"offline"}` |
| POST | `/api/avatar/upload` | multipart：`deviceId,familyId,file` | `{status:"ok",url}`（存 `icons/<deviceId>.jpg`，限 1MB） |
| POST | `/api/bug/report` | `{deviceId,name,content}` | `{status:"ok"}`（追加写 `server/bugs.json`） |
| GET | `/api/bug/list` | - | `[{id,deviceId,name,content,time,resolved?}]`（web 管理页） |
| POST | `/api/bug/update` | `{id,content?,resolved?}` | `{status:"ok"}`（改文本/标记完成；404=不存在） |
| GET | `/api/update/latest` | - | `{versionCode,versionName,note,url,hasApk,md5,size}`（禁缓存；每次实时读文件并算 MD5） |
| GET | `/downloads/app-release.apk` | - | APK 静态下载 |
| GET | `/bugadmin/{token}` | - | Bug 管理网页，须带路径口令（如 `/bugadmin/yjx120606`） |
| GET | `/api/health` | - | `{status:"ok", time}` |

> **访问口令**：白名单 `/api/health`、`/downloads/**`、`/bugadmin/**` 外，其余 `/api/**`、`/ws`、`/icons/**` 都必须在请求头 `X-Api-Token` 或查询参数 `token` 携带访问口令（服务端 `app.api-token`、客户端 `API_TOKEN`，需一致）。

`location`：`{lat,lng,accuracy,ts,battery(-1=未知),network("WiFi（SSID）"/移动网络/无网络),address("省市区街道")}`
`trajectory`：`[{lat,lng,accuracy,ts,...}]`（仅 `track=true` 返回，最多 300 点）

### 身份模型

- `deviceId`：App 首次启动生成的 UUID，持久化在本地，即设备身份。
- `familyId` + 6 位 `code`：家庭。凭家庭码提交**加入申请**，由群主（`owner`）同意后才入群（这就是“已授权”）；群主可移出/拉黑/转让群主，也可**一键解散家庭**。
- **同一设备可同时属于多个家庭**（创建/加入新家庭不会退出原有家庭）；客户端保存已加入家庭列表，在主页面成员列表上左右滑动切换当前查看的家庭。WebSocket / 上报都只针对「当前家庭」，切换家庭时客户端会重连。
- 所有接口需携带统一的访问口令，且会校验设备是否属于该家庭，非家庭成员上报/拉取一律 403。

## 2. WebSocket 通道

地址：`wss://fms.uiero.com/ws?deviceId=xx&familyId=xx&name=xx&token=<API口令>&offline=1(可选)`。
`token` 必须正确；校验失败或被拉黑 → 关闭 4001；在线时被移出 → 关闭 4002。心跳由客户端 OkHttp `pingInterval(30s)` 维持。

### 服务器 → 客户端消息

| type | 字段 | 说明 |
| --- | --- | --- |
| `hello` | - | 连接成功确认 |
| `report-now` | `from` | 有家人正在查看 → **立即定位并上报一次** |
| `location-update` | `deviceId,name,lat,lng,accuracy,ts,battery,network,address` | 某成员新位置（广播全家庭） |
| `member-status` | `deviceId,online,offlineMode` | 上线/离线/下线模式 |
| `track-changed` | `deviceId,track,intervalMs` | 轨迹开关变化（连接时也推送一次本机状态） |
| `ring` | `from,name` | 家人请求本机响铃 |
| `member-removed` | `deviceId` | 成员被移出（被移出者本人也会直接收到，立即退出） |
| `member-joined` | `deviceId,name` | 新成员加入 |
| `owner-changed` | `deviceId` | 群主转让（deviceId=新群主） |
| `invite` | `code,from,name` | 收到加入家庭邀请（存客户端「消息」列表） |
| `join-request` | `requestId,deviceId,name` | 有人申请加入本家庭（群主审批，存「消息」列表） |
| `family-disbanded` | `familyId,by` | 家庭被群主解散（客户端把该家庭从本地家庭列表移除；若还有其它家庭则自动切换过去） |

## 3. 上报节奏

```
每30分钟:  AlarmManager(setAndAllowWhileIdle) → 定位一次 → POST /api/location/report → 广播 location-update
轨迹开启:  每5分钟一次（track-changed 通知目标切换）
实时查看:  查看者 POST /api/location/request → 服务器向目标 ws 发 report-now
           → 目标立即定位 → POST /api/location/report → 广播 location-update → 所有端标记实时移动
上线时:    WS 连接成功后立即上报一次
下线模式:  客户端跳过上报，WS 带 offline=1，服务器标记成员离线（灰色）
```

## 4. 数据存储（Redis）

主数据存于 Redis（按实体分 key），连接配置在 `server/src/main/resources/application.properties`（`spring.data.redis.host/port/password`）。key 示例：

| 类型 | Key | 值 |
| --- | --- | --- |
| 家庭 | `familyshare:family:<familyId>` | `{id,code,owner,createdAt,members[],banned[]}` |
| 家庭索引 | `familyshare:families` | Set\<familyId\> |
| 位置 | `familyshare:location:<deviceId>` | `{lat,lng,accuracy,ts,battery,network,address}` |
| 位置索引 | `familyshare:locations` | Set\<deviceId\> |
| 轨迹 | `familyshare:trajectory:<deviceId>` | `[ {lat,lng,accuracy,ts}, ... ]` |
| 轨迹索引 | `familyshare:trajectories` | Set\<deviceId\> |
| 入群申请 | `familyshare:joinreq:<requestId>` | `{id,familyId,code,deviceId,name,createdAt,status}` |
| 入群索引 | `familyshare:joinrequests` | Set\<requestId\> |

服务端内存为工作集，启动时从 Redis 载入，此后每 2 秒“全量同步 + 删除对账”。头像仍是文件（`server/icons/`），Bug 反馈仍为文件（`server/bugs.json`）。

## 5. 生产化建议（可选）

- 部署公网时加 HTTPS/WSS（Nginx 反代 + 证书），`network_security_config.xml` 收紧为仅允许域名。
- 加简单鉴权：登录口令 → 换取 token，`deviceId` 与 token 绑定。
- 位置数据加密存储；设置数据保留期（如 24 小时后清理旧位置）。
- 家庭码可设有效期/重新生成，防止泄露后无限加入。
