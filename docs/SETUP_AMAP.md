# 高德开放平台：申请 Key 并配置

App 使用高德地图 Android SDK（3D 地图 + 定位），必须先在[高德开放平台](https://lbs.amap.com/)申请 **Android 平台** 的 Key，并与本工程的包名、签名 SHA1 绑定，否则地图/定位不生效。

## 1. 注册与认证

1. 访问 <https://lbs.amap.com/>，用高德账号登录（个人开发者需完成实名认证，免费）。
2. 控制台 → 「应用管理」→「我的应用」→「创建新应用」。

## 2. 添加 Key

创建应用后，点击「添加 Key」：

| 项 | 填写 |
| --- | --- |
| 服务平台 | **Android平台** |
| 发布版安全码 SHA1 | 见下方获取方式 |
| PackageName | `com.family.share`（必须与本工程 `applicationId` 完全一致） |

### 获取签名 SHA1

**调试签名（默认使用）：**

```bash
# Windows（keytool 在 JDK bin 下）
keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

输出中的 `SHA1:` 行即为所需值（形如 `AB:CD:EF:...`）。

**正式签名（发布时）：**

```bash
keytool -list -v -keystore 你的release.keystore
```

## 3. 复制 Key

「我的应用」中对应 Key 即为 32 位字符串，复制它。

## 4. 配置到工程

编辑 `android/gradle.properties`：

```properties
AMAP_KEY=你的32位Key
```

该值会在构建时注入 `AndroidManifest.xml` 的 `com.amap.api.v2.apikey` 与 `BuildConfig.AMAP_KEY`，无需改代码。

> 也可以命令行覆盖：`./gradlew assembleDebug -PAMAP_KEY=你的Key -PSERVER_URL=https://your-server.example.com`

## 5. 验证

安装运行后地图应正常显示底图、定位能返回坐标。若：

- **地图空白**：Key 无效 / 包名或 SHA1 与绑定不一致 / 服务平台选错（选了 Web 端而不是 Android 平台）。
- **定位失败**：确认已授予定位权限（含“始终允许”）；室内打开 WiFi/数据网络。
- **控制台报错**：Logcat 过滤 `amap`，按错误码排查（如 `IO 异常` 多为网络，`INVALID_USER_SCODE` 为 Key 校验失败）。

## 6. 集成轻量版地图SDK（本工程使用）

本工程使用**高德「轻量版地图SDK」V1.3.2**（合包含 地图 1.3.2 + 搜索 9.7.4 + 定位 6.4.9）。
它基于 WebView 渲染、**不含原生 `.so`**，因此安装包比 3D 地图 SDK 小很多；但它**不在 Maven 仓库**，需要手动集成：

1. 下载：<https://a.amap.com/lbs/static/amap_3dmap_lite/Lite3DMap.zip>
2. 解压后，把其中的 jar（形如 `Lite3DMap_1.3.2_AMapSearch_9.7.4_AMapLocation_6.4.9_*.jar`）复制到 `android/app/libs/`
3. `app/build.gradle` 已改为 `implementation fileTree(dir: 'libs', include: ['*.jar'])`，无需再引入 `com.amap.api:3dmap`

包名与 3D 地图 SDK 一致（`com.amap.api.maps` / `com.amap.api.location`），**Key 的申请与绑定方式不变**（仍绑定包名 + SHA1，勾选地图与定位服务）。

> 代码差异：轻量版地图对象是**异步就绪**的（`MapView.getMapAsyn(OnMapReadyListener)`，没有 `getMap()`），且其 `UiSettings` 只有手势开关（无指南针/缩放按钮/默认刻度尺）；本工程刻度尺为自绘，`App` 中也不再调用地图的 `updatePrivacyShow/Agree`（轻量版无此接口）。

## 补充

- 高德坐标为国测局 GCJ-02 坐标系，本工程定位 SDK 默认输出 GCJ-02，与高德地图展示坐标系一致，无需转换。
- 每个 Key 有免费配额（地图/定位每日有调用量上限），家庭场景完全够用。
