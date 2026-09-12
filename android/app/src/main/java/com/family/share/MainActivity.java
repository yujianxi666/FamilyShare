package com.family.share;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.Circle;
import com.amap.api.maps.model.CircleOptions;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.family.share.config.AppConfig;
import com.family.share.data.Member;
import com.family.share.data.Prefs;
import com.family.share.network.Api;
import com.family.share.location.LocationHelper;
import com.family.share.service.LocationReportService;
import com.family.share.ui.CropView;
import com.family.share.ui.FamilySetupDialog;
import com.family.share.ui.MemberAdapter;
import com.family.share.ui.MemberColors;
import com.family.share.ui.ScaleLineDrawable;
import com.family.share.util.AvatarLoader;
import com.family.share.util.DeviceInfo;
import com.family.share.util.QrCode;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主界面：高德地图实时展示家人位置。
 * - 点击家人：地图定位到TA + 请求实时刷新一次位置（服务器推送 report-now 指令）
 * - 长按家人：家庭创建者可移出成员
 * - 定位我：立即触发一次本机定位上报
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSION = 100;
    private static final int REQ_PERMISSION_BG = 101;

    private MapView mapView;
    private AMap aMap;
    /** 地图是否已就绪（完整版 3D SDK 同步就绪，getMap() 返回后即可用） */
    private boolean mapReady;
    private RecyclerView memberList;
    private MemberAdapter adapter;
    private TextView tvStatus;
    private TextView tvEmpty;
    /** 空状态引导卡片（没有家人时显示创建/加入家庭入口） */
    private View emptyCard;
    private View statusDot;
    private View statusPill;
    private String pendingFocusDeviceId = "";
    /** 成员清单里「本机排最前」用的 deviceId */
    private String myDeviceId = "";
    /** 成员人数简洁标签（如“家庭成员（3）”） */
    private TextView tvMemberCount;
    /** 广播接收器是否已注册（onResume/onPause 幂等保护，避免重复注册/注销崩溃） */
    private boolean receiverRegistered;
    /** 成员列表顶部/底部渐隐与「还有更多」提示（列表被裁剪时说明还有成员） */
    private View listTopMore;
    private View listBottomMore;

    // 下载更新进度
    private AlertDialog downloadDialog;
    private TextView tvDownloadPercent;
    private ProgressBar downloadProgress;
    private okhttp3.Call downloadCall;
    private boolean downloadCancelled;

    /** 右上角 ⋮ 自绘菜单（点击任意项后自动关闭，保证下次打开显示最新状态） */
    private AlertDialog overflowMenuDialog;

    /** 权限设置对话框：从系统设置返回后刷新各权限状态 */
    private AlertDialog permDialog;
    private final List<TextView> permStatusViews = new ArrayList<>();

    /** 黑名单管理对话框：解除拉黑后在原对话框内刷新列表 */
    private AlertDialog banDialog;

    /** 消息中心对话框 */
    private AlertDialog messageDialog;

    /** 服务器切换对话框 */
    private AlertDialog serverDialog;

    /** 头像选择（Activity Result API，替代已过时的 startActivityForResult） */
    private final ActivityResultLauncher<String> avatarPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) {
                    return;
                }
                try {
                    Bitmap bmp = decodeSampled(uri, 2048);
                    if (bmp == null) {
                        toast(getString(R.string.toast_avatar_decode_failed));
                    } else {
                        showCropDialog(bmp);
                    }
                } catch (Exception e) {
                    toast(getString(R.string.toast_avatar_decode_failed));
                }
            });

    // ---- 二维码：家庭码展示（生成）+ 加入家庭页「扫码加入」（权限仅点击时申请） ----
    /** 扫码共享句柄：joinCode=加入对话框的家庭码输入框，扫码成功后把识别到的码填进去 */
    private final FamilySetupDialog.ScanToken familyScanToken = new FamilySetupDialog.ScanToken();
    /** 扫码结果：识别到二维码后交给加入对话框（填入家庭码并直接确认加入） */
    private final ActivityResultLauncher<ScanOptions> scanLauncher = registerForActivityResult(
            new ScanContract(), result -> {
                String code = result.getContents();
                if (code == null || code.trim().isEmpty()) {
                    return;
                }
                code = code.trim();
                if (familyScanToken.onScanned != null) {
                    familyScanToken.onScanned.accept(code);
                } else if (familyScanToken.joinCode != null) {
                    familyScanToken.joinCode.setText(code);
                    toast(getString(R.string.toast_code_scanned));
                }
            });
    /** 相机权限：只在点击「扫码加入」后才请求；授权成功后若在等待扫码则自动启动扫码 */
    private final ActivityResultLauncher<String> cameraPermLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    if (familyScanToken.pendingScan) {
                        familyScanToken.pendingScan = false;
                        scanLauncher.launch(scanOptions());
                    }
                } else {
                    toast(getString(R.string.toast_need_camera_permission));
                }
            });

    /** 二维码扫码参数：仅识别二维码 QR_CODE，竖向锁定 */
    private ScanOptions scanOptions() {
        return new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true);
    }

    // 面板收缩（灰色小横条 + 下滑/上滑手势）
    private View bottomPanel;
    private View panelBody;
    private View panelHeader;
    private boolean panelCollapsed;
    /** 成员详情是否打开（详情页灰色小横条用于收起/展开，与列表互斥，避免两个界面叠加） */
    private boolean detailOpen;
    private View btnBack;
    private LinearLayout detailArea;
    /** 响铃时显示在主页面头部的「关闭响铃」按钮 */
    private TextView btnStopRing;

    // 轨迹线（成员轨迹连线）
    private final Map<String, Polyline> trackLines = new HashMap<>();
    /** 轨迹起点标记（每条轨迹一个单独的绿色起点，随轨迹线增删） */
    private final Map<String, Marker> trackStartMarkers = new HashMap<>();
    /** 详情页高亮的轨迹线（加粗显示），关闭详情时还原 */
    private Polyline highlightTrack;
    /** 当前被高亮轨迹的成员 deviceId（列表刷新后重新加粗） */
    private String highlightDeviceId = "";

    private final Map<String, Member> members = new HashMap<>();
    private final Map<String, Marker> markers = new HashMap<>();
    /** 成员精度圈（以角标为圆心，半径=定位精度） */
    private final Map<String, Circle> accuracyCircles = new HashMap<>();
    /**
     * 成员标点图标缓存（deviceId -> 图标描述）。
     * 标点图标只与「昵称 / 头像 / 是否本机」有关，与经纬度无关：
     * 缓存后位置刷新不再重复生成位图。
     * 必要性：轻量版地图SDK 会把每个图标位图按 id 缓存进内存（不回收），
     * 若每次位置更新都新建图标，长时间运行会持续累积位图，且每次都要重新编码 PNG。
     */
    private final Map<String, MemberIcon> iconCache = new HashMap<>();

    /** 缓存的成员标点图标：key 记录生成时的昵称/头像，变化时重建 */
    private static final class MemberIcon {
        final String key;
        final BitmapDescriptor descriptor;

        MemberIcon(String key, BitmapDescriptor descriptor) {
            this.key = key;
            this.descriptor = descriptor;
        }
    }
    /** 打开/回到前台时，若在多人家庭则自动向全员请求一次实时位置（只在一次成员列表渲染后消费，用底部 toast 提示、不弹窗） */
    private boolean entryRefreshArmed;
    /** 最近一次切换服务器的时间戳：用于防止切换后成员列表被清空导致绿点消失 */
    private long serverSwitchAt;

    /** 家人列表最多同时显示的成员行数：超过则可上下滚动，底部露出下一行的一部分作为"还有更多"提示 */
    private static final float MAX_VISIBLE_MEMBERS = 3.6f;
    /** 单行成员的高度（px），首次需要固定列表高度时按样例项测量一次 */
    private int memberRowHeightPx;
    /** 上一帧「位置更新」文字提示时间戳：限制频率，避免多成员同时上报时连续弹多个提示 */
    private long lastLocationUpdateToastAt;

    /** 消息中心条目：joinRequest=入群申请（群主审批），invite=加入邀请 */
    private static class MessageItem {
        final String type;
        final String deviceId;   // 申请人 deviceId（joinRequest）或空
        final String name;       // 申请人昵称 / 邀请人昵称
        final String requestId;  // joinRequest 申请 id
        final String code;       // invite 家庭码
        final long time;

        MessageItem(String type, String deviceId, String name, String requestId, String code) {
            this.type = type;
            this.deviceId = deviceId;
            this.name = name;
            this.requestId = requestId;
            this.code = code;
            this.time = System.currentTimeMillis();
        }
    }

    private final List<MessageItem> messages = new ArrayList<>();

    private final BroadcastReceiver uiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (AppConfig.BROADCAST_LOCATION_UPDATE.equals(action)) {
                // 只接受「当前家庭」的位置广播：切换家庭后，上一个家庭迟到的广播必须丢弃（避免串台）
                String broadcastFamily = intent.getStringExtra("familyId");
                if (broadcastFamily != null && !broadcastFamily.isEmpty()
                        && !broadcastFamily.equals(Prefs.get(MainActivity.this).familyId())) {
                    return;
                }
                Member m = new Member();
                m.deviceId = intent.getStringExtra("deviceId");
                m.name = intent.getStringExtra("name");
                m.lat = intent.getDoubleExtra("lat", 0);
                m.lng = intent.getDoubleExtra("lng", 0);
                m.accuracy = intent.getFloatExtra("accuracy", 0);
                m.ts = intent.getLongExtra("ts", System.currentTimeMillis());
                m.battery = intent.getIntExtra("battery", -1);
                m.network = intent.getStringExtra("network");
                m.address = intent.getStringExtra("address");
                m.online = true;
                m.hasLocation = true;
                if (m.deviceId.equals(myDeviceId)) {
                    // 自己：下线模式下显示灰色离线
                    m.online = !Prefs.get(MainActivity.this).offlineMode();
                }
                // 若用户刚点击了该成员（等待实时位置），收到新位置时自动跳转过去
                applyMember(m, m.deviceId.equals(pendingFocusDeviceId));
                // 文字反馈：家人位置更新时提示「xxx 的位置已更新」（自己跳过；限频避免多成员同时上报时连续弹多个）
                if (!m.deviceId.equals(myDeviceId)) {
                    long now = System.currentTimeMillis();
                    if (now - lastLocationUpdateToastAt >= 1500) {
                        lastLocationUpdateToastAt = now;
                        String nm = (m.name != null && !m.name.isEmpty()) ? m.name : m.deviceId;
                        toastTop(getString(R.string.toast_location_updated, nm));
                    }
                }
            } else if (AppConfig.BROADCAST_MEMBER_STATUS.equals(action)) {
                if (intent.getBooleanExtra("trackChanged", false)) {
                    // 轨迹开关变化 -> 重拉成员列表刷新轨迹线
                    loadMembers();
                    return;
                }
                String deviceId = intent.getStringExtra("deviceId");
                boolean online = intent.getBooleanExtra("online", false);
                Member m = members.get(deviceId);
                if (m != null && m.deviceId != null && !m.deviceId.isEmpty()) {
                    // 防绿点误消失：刚上报过位置（2分钟内）说明该成员连接在线，
                    // 忽略偶发的 member-status 离线广播（重连/网络抖动时），避免在线成员被误判为离线。
                    if (!online && m.hasLocation && (System.currentTimeMillis() - m.ts) < 120_000) {
                        online = true;
                    }
                    m.online = online;
                    if (members.size() == adapter.getItemCount()) {
                        adapter.onMemberUpdated(m, myDeviceId);
                    } else {
                        refreshAdapter();
                    }
                }
            } else if (AppConfig.BROADCAST_SERVICE_STATUS.equals(action)) {
                updateStatusUi(intent.getIntExtra("status", AppConfig.STATUS_CONNECTING));
            } else if (AppConfig.BROADCAST_RING_STARTED.equals(action)) {
                // 本机正在响铃：显示「关闭响铃」按钮
                if (btnStopRing != null) {
                    btnStopRing.setVisibility(View.VISIBLE);
                }
            } else if (AppConfig.BROADCAST_RING_STOPPED.equals(action)) {
                if (btnStopRing != null) {
                    btnStopRing.setVisibility(View.GONE);
                }
            } else if (AppConfig.BROADCAST_MEMBER_REMOVED.equals(action)) {
                String deviceId = intent.getStringExtra("deviceId");
                if (deviceId != null && deviceId.equals(myDeviceId)) {
                    handleSelfRemoved();
                } else if (deviceId != null && !deviceId.isEmpty()) {
                    Member removed = members.get(deviceId);
                    String name = removed != null && !removed.name.isEmpty() ? removed.name : deviceId;
                    removeMemberFromUi(deviceId);
                    toast(getString(R.string.toast_member_removed, name));
                }
            } else if (AppConfig.BROADCAST_MEMBER_JOINED.equals(action)) {
                // 新成员加入：立即刷新成员列表
                loadMembers();
            } else if (AppConfig.BROADCAST_OWNER_CHANGED.equals(action)) {
                // 群主已转让：本机是新群主时更新本地标记
                String deviceId = intent.getStringExtra("deviceId");
                if (deviceId != null && deviceId.equals(myDeviceId)) {
                    Prefs.get(MainActivity.this).isOwner(true);
                    toast(getString(R.string.toast_owner_transferred_to_me));
                }
            } else if (AppConfig.BROADCAST_INVITE.equals(action)) {
                // 收到加入家庭邀请：存入消息中心，由用户在 ⋮ 菜单「消息」中处理
                String code = intent.getStringExtra("code");
                String fromName = intent.getStringExtra("name");
                addInviteMessage(code, fromName);
                toast(getString(R.string.toast_invite_received));
            } else if (AppConfig.BROADCAST_JOIN_REQUEST.equals(action)) {
                // 有人申请加入本家庭（仅群主审批）：存入消息中心
                if (Prefs.get(MainActivity.this).isOwner()) {
                    addJoinRequestMessage(intent.getStringExtra("requestId"),
                            intent.getStringExtra("deviceId"), intent.getStringExtra("name"));
                    toast(getString(R.string.toast_join_request_added));
                }
            } else if (AppConfig.BROADCAST_FAMILY_DISBANDED.equals(action)) {
                // 当前家庭被群主解散：从本地家庭列表移除，若还有其它家庭则自动切过去
                String familyId = intent.getStringExtra("familyId");
                onFamilyDisbanded(familyId);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        myDeviceId = Prefs.get(this).deviceId();

        // 地图（完整版 3D 地图 SDK：原生渲染，地图对象同步就绪）
        mapView = findViewById(R.id.mapView);
        mapView.onCreate(savedInstanceState);
        // 布局稳定后再对一次容器尺寸，避免容器尺寸过期导致标注层按错误比例移动
        mapView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> scheduleMapRelayout());
        aMap = mapView.getMap();
        if (aMap != null) {
            mapReady = true;
            aMap.setMapType(AMap.MAP_TYPE_NORMAL);
            // 注意：这里不能再直接调用 initScaleBar()/updateScaleBarPosition()——
            // 此刻面板相关 View（bottomPanel 等）还没 findViewById，会空指针崩溃（历史 bug）。
            // 面板与刻度尺的初始化统一放到所有 View 就绪后（见 postPanelReadyInit()）。
        }

        // 家人列表
        memberList = findViewById(R.id.memberList);
        memberList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MemberAdapter(getLayoutInflater(), new MemberAdapter.Listener() {
            @Override
            public void onClick(Member m) {
                onMemberClick(m);
            }

            @Override
            public void onLongClick(Member m) {
                onMemberLongClick(m);
            }
        });
        memberList.setAdapter(adapter);
        // 关闭列表动画：位置刷新时只更新对应行（notifyItemChanged），避免整列表闪动/绿点闪烁。
        // 不直接用 setSupportsChangeAnimations（不同 androidx/recyclerview 版本该 API 存在性不一），
        // 置空动画器最稳妥；本应用成员列表较小，瞬时更新观感更好。
        memberList.setItemAnimator(null);

        tvStatus = findViewById(R.id.tvStatus);
        tvEmpty = findViewById(R.id.tvEmpty);
        // 空状态引导卡片：没有家人时显示「创建 / 加入家庭」入口
        emptyCard = findViewById(R.id.emptyCard);
        View btnEmptyAction = findViewById(R.id.btnEmptyAction);
        if (btnEmptyAction != null) {
            btnEmptyAction.setOnClickListener(v -> showFamilySetup());
        }
        statusDot = findViewById(R.id.statusDot);
        statusPill = findViewById(R.id.statusPill);
        findViewById(R.id.btnLocateMe).setOnClickListener(v -> onLocateMe());
        // 切换家庭按钮（在「定位我」下方；只有一个家庭时也可用来新增家庭）
        btnSwitchFamily = findViewById(R.id.btnSwitchFamily);
        if (btnSwitchFamily != null) {
            btnSwitchFamily.setOnClickListener(v -> showSwitchFamilyDialog());
        }

        // 响铃时显示的「关闭响铃」按钮（收到响铃开始广播后出现）
        btnStopRing = findViewById(R.id.btnStopRing);
        btnStopRing.setOnClickListener(v -> sendToService(AppConfig.ACTION_STOP_RING));

        // 面板：灰色小横条（点击切换 + 跟随手指线性收缩/展开）
        bottomPanel = findViewById(R.id.bottomPanel);
        panelBody = findViewById(R.id.panelBody);
        panelHeader = findViewById(R.id.panelHeader);
        btnBack = findViewById(R.id.btnBack);
        detailArea = findViewById(R.id.detailArea);
        View dragHandle = findViewById(R.id.dragHandle);
        dragHandle.setOnClickListener(v -> togglePanel());
        PanelDragTouchListener dragListener = new PanelDragTouchListener();
        // 标题栏 + 灰色小横条：按下拖动可收缩/展开面板
        panelHeader.setOnTouchListener(dragListener);
        // 让标题行可点击：clickable 视图会把 DOWN 之后的 MOVE/UP 也交给 OnTouchListener，
        // 从而使「灰条那一行任意位置」都能上滑展开面板（否则非 clickable 的标题行收不到后续拖动事件）。
        panelHeader.setClickable(true);
        dragHandle.setOnTouchListener(dragListener);
        // 详情页：在详情任意区域下拉即可收缩详情（不返回列表）
        detailArea.setOnTouchListener(dragListener);
        // 成员列表：只负责滚动列表本身（切换家庭改用「切换家庭」按钮，不再用左右滑动）
        // 成员人数标签
        tvMemberCount = findViewById(R.id.tvMemberCount);
        // 列表渐隐 + 「还有更多家人」提示
        listTopMore = findViewById(R.id.listTopMore);
        listBottomMore = findViewById(R.id.listBottomMore);
        memberList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                updateMoreMembersHint();
            }
        });

        // 返回键 / 侧滑返回：在成员详情等子页面时返回主界面，而不是直接退出 App
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isDetailOpen()) {
                    closeDetail();
                } else {
                    // 无子页面：走默认返回（退出）
                    setEnabled(false);
                    MainActivity.this.getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        // 左上角一键刷新
        AppCompatImageButton btnRefresh = findViewById(R.id.btnRefresh);
        btnRefresh.setOnClickListener(v -> refreshAll());

        // 右上角 ⋮ 折叠菜单（家庭码 / 切换家庭 / 后台保活）
        AppCompatImageButton btnOverflow = findViewById(R.id.btnOverflow);
        btnOverflow.setOnClickListener(this::showOverflowMenu);
        // 沉浸式状态栏下，让按钮避开状态栏区域
        ViewCompat.setOnApplyWindowInsetsListener(btnOverflow.getRootView(), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            FrameLayout.LayoutParams lpOverflow = (FrameLayout.LayoutParams) btnOverflow.getLayoutParams();
            lpOverflow.topMargin = top + dp(12);
            btnOverflow.setLayoutParams(lpOverflow);
            FrameLayout.LayoutParams lpRefresh = (FrameLayout.LayoutParams) btnRefresh.getLayoutParams();
            lpRefresh.topMargin = top + dp(12);
            btnRefresh.setLayoutParams(lpRefresh);
            return insets;
        });

        // 所有 View 都已就绪：做面板/刻度尺/标点的初始化（放在最后，避免用到未初始化的 View）
        postPanelReadyInit();

        // 首启引导：隐私 -> 运行时权限 -> 后台定位 -> 电池优化，按顺序一个个弹，避免一次性弹出太多权限弹窗
        if (Prefs.get(this).familyId().isEmpty()) {
            // 首次使用：先建/入家庭，进入家庭后再依次引导权限（见 onDone 里的 startFirstRunGuidedFlow）
            showFamilySetup();
        } else {
            startServiceIfNeeded();
            loadMembers();
            startFirstRunGuidedFlow();
        }

        // 启动时静默检查一次更新（有新版才弹窗，无新版不打扰）
        checkUpdate(true);
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        scheduleMapRelayout(); // 从后台/系统设置返回后，重新对齐一次容器尺寸
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(AppConfig.BROADCAST_LOCATION_UPDATE);
            filter.addAction(AppConfig.BROADCAST_MEMBER_STATUS);
            filter.addAction(AppConfig.BROADCAST_SERVICE_STATUS);
            filter.addAction(AppConfig.BROADCAST_RING_STARTED);
            filter.addAction(AppConfig.BROADCAST_RING_STOPPED);
            filter.addAction(AppConfig.BROADCAST_MEMBER_REMOVED);
            filter.addAction(AppConfig.BROADCAST_MEMBER_JOINED);
            filter.addAction(AppConfig.BROADCAST_OWNER_CHANGED);
            filter.addAction(AppConfig.BROADCAST_INVITE);
            filter.addAction(AppConfig.BROADCAST_JOIN_REQUEST);
            filter.addAction(AppConfig.BROADCAST_FAMILY_DISBANDED);
            // 广播均来自本应用自身，声明 NOT_EXPORTED 兼容 Android 13+
            ContextCompat.registerReceiver(this, uiReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true; // 幂等：避免极端时序下重复注册/重复注销导致崩溃
        }
        // 打开/回到前台：刷新家庭列表与左右滑动提示
        loadFamilyIds();
        // 打开/回到前台：查询当前响铃状态，若正在响铃则显示「关闭响铃」按钮，避免不知如何关闭
        sendToService(AppConfig.ACTION_QUERY_RING);
        // 打开/回到前台：若在多人家庭，列出成员后自动向全员请求一次实时位置（底部 toast 提示，不弹窗）
        entryRefreshArmed = true;
        // 回到前台时刷新一次状态
        if (!Prefs.get(this).familyId().isEmpty()) {
            loadMembers();
        }
        // 从系统设置跳转返回后，刷新权限对话框内的状态文字
        refreshPermissionStatus();
        // 保活提醒：未加入电池优化/自启动白名单时，每隔几天温和提醒一次
        maybeRemindKeepAlive();
        // 周期探测服务器连通性，避免连接状态卡在“连接中”
        startHealthPolling();
        // 周期刷新成员列表（保底，每 30 秒一次）
        startPeriodicRefresh();
        // 查询自己提交的 Bug 是否已被处理，若被标记完成则主动告知
        checkBugResolved();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        if (receiverRegistered) {
            try {
                unregisterReceiver(uiReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        stopHealthPolling();
        stopPeriodicRefresh();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        // 交给地图保存相机状态（切后台/旋转回来视野不重置）
        mapView.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        mapView.onDestroy();
        super.onDestroy();
    }

    // ---------------- 多家庭：用按钮切换（不再用左右滑动） ----------------

    /** 本机已加入的家庭 ID 列表（与 Prefs 同步） */
    private final java.util.List<String> familyIds = new java.util.ArrayList<>();
    /** 正在切换家庭（避免重复触发） */
    private boolean switchingFamily;
    /**
     * 家庭切换代号：每次切换/解散/被移出都自增。
     * 用来丢弃「上一个家庭」迟到的成员列表/位置广播响应，
     * 否则切到第二个家庭后，第一个家庭的刷新结果会串进来（历史 bug）。
     */
    private int familyGeneration;
    private Button btnSwitchFamily;

    /** 从本地记录同步家庭列表（并在需要时与服务器核对） */
    private void loadFamilyIds() {
        familyIds.clear();
        familyIds.addAll(Prefs.get(this).familyIds());
        String active = Prefs.get(this).familyId();
        if (active != null && !active.isEmpty() && !familyIds.contains(active)) {
            familyIds.add(0, active);
            Prefs.get(this).familyIds(familyIds);
        }
        updateFamilySwitchButton();
        updateFamilyTitle();
    }

    /** 当前家庭在家庭列表中的下标（不在列表返回 -1） */
    private int activeFamilyIndex() {
        String active = Prefs.get(this).familyId();
        return familyIds.indexOf(active);
    }

    /** 标题栏只显示「当前家庭名 · 家庭成员」（不再显示第几个/共几个的数字编号） */
    private void updateFamilyTitle() {
        TextView tvTitle = findViewById(R.id.tvTitle);
        if (tvTitle == null) {
            return;
        }
        if (familyIds.size() <= 1) {
            tvTitle.setText(R.string.title_family_share);
            return;
        }
        tvTitle.setText(getString(R.string.title_family_name, currentFamilyName()));
    }

    /** 当前家庭显示名：优先家庭码，其次「家庭」 */
    private String currentFamilyName() {
        String code = Prefs.get(this).familyCodeOf(Prefs.get(this).familyId());
        return code.isEmpty() ? getString(R.string.title_family_short) : code;
    }

    /** 「切换家庭」按钮：只有一个家庭时也显示（用于新增家庭） */
    private void updateFamilySwitchButton() {
        if (btnSwitchFamily == null) {
            return;
        }
        btnSwitchFamily.setVisibility(Prefs.get(this).familyId().isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * 切换家庭弹窗：列出所有已加入的家庭供选择（不会退出任何家庭）。
     * 打开时**先从服务器拉取真实家庭列表**（/api/family/my），避免本地记录不同步导致"没有家庭可选"；
     * 列表用手工构建的视图，不依赖 AlertDialog.setItems。
     */
    private void showSwitchFamilyDialog() {
        Prefs prefs = Prefs.get(this);
        if (prefs.familyId().isEmpty()) {
            showFamilySetup();
            return;
        }
        if (!safeUi()) {
            return;
        }
        final AlertDialog[] holder = new AlertDialog[1];
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView loading = new TextView(this);
        loading.setText(R.string.toast_loading);
        loading.setPadding(dp(12), dp(16), dp(12), dp(16));
        loading.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        listBox.addView(loading);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(listBox);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_switch_family_title)
                .setView(scroll)
                .setNegativeButton(R.string.btn_back, null)
                .create();
        holder[0] = dialog;
        dialog.show();

        Api.myFamilies(myDeviceId, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    java.util.List<String> ids = new ArrayList<>();
                    java.util.List<String> labels = new ArrayList<>();
                    java.util.List<Boolean> ownerFlags = new ArrayList<>();
                    try {
                        org.json.JSONArray arr = new org.json.JSONArray(body);
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.getJSONObject(i);
                            String fid = o.optString("familyId");
                            if (fid.isEmpty()) {
                                continue;
                            }
                            ids.add(fid);
                            String code = o.optString("code");
                            if (code.isEmpty()) {
                                code = prefs.familyCodeOf(fid);
                            }
                            int members = o.optInt("memberCount", 0);
                            labels.add((code.isEmpty() ? getString(R.string.title_family_short) : code)
                                    + (members > 0 ? "（" + members + getString(R.string.family_member_unit) + "）" : ""));
                            ownerFlags.add(o.optBoolean("isOwner", false));
                        }
                    } catch (Exception ignored) {
                    }
                    if (ids.isEmpty()) {
                        // 服务器没返回就把本地记录兜底显示出来
                        for (String fid : prefs.familyIds()) {
                            ids.add(fid);
                            String code = prefs.familyCodeOf(fid);
                            labels.add(code.isEmpty() ? getString(R.string.title_family_short) : code);
                            ownerFlags.add(fid.equals(prefs.familyId()) && prefs.isOwner());
                        }
                    }
                    listBox.removeAllViews();
                    if (ids.isEmpty()) {
                        // 确实没有家庭：引导创建/加入
                        TextView tv = new TextView(MainActivity.this);
                        tv.setText(R.string.dialog_add_family_hint);
                        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
                        tv.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text_secondary));
                        tv.setTextSize(13f);
                        listBox.addView(tv);
                        return;
                    }
                    // 与服务器对齐本地家庭列表
                    prefs.familyIds(ids);
                    familyIds.clear();
                    familyIds.addAll(ids);
                    updateFamilyTitle();
                    updateFamilySwitchButton();

                    final String activeId = prefs.familyId();
                    for (int i = 0; i < ids.size(); i++) {
                        final String fid = ids.get(i);
                        boolean isActive = fid.equals(activeId);
                        StringBuilder sb = new StringBuilder();
                        if (isActive) {
                            sb.append("✓ ");
                        }
                        sb.append(labels.get(i));
                        if (ownerFlags.get(i)) {
                            sb.append("  ").append(getString(R.string.owner_badge));
                        }
                        if (isActive) {
                            sb.append("  ").append(getString(R.string.family_current));
                        }
                        TextView row = new TextView(MainActivity.this);
                        row.setText(sb.toString());
                        row.setTextSize(15f);
                        row.setPadding(dp(14), dp(14), dp(14), dp(14));
                        row.setTextColor(ContextCompat.getColor(MainActivity.this,
                                isActive ? R.color.primary : R.color.text_primary));
                        row.setBackground(ContextCompat.getDrawable(MainActivity.this, R.drawable.bg_menu_card));
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rlp.setMargins(dp(4), dp(4), dp(4), dp(4));
                        row.setLayoutParams(rlp);
                        row.setOnClickListener(v -> {
                            if (holder[0] != null) {
                                holder[0].dismiss();
                            }
                            if (!fid.equals(Prefs.get(MainActivity.this).familyId())) {
                                switchToFamily(fid);
                            }
                        });
                        listBox.addView(row);
                    }
                    // 底部：新建/加入一个家庭（不影响已加入的家庭）
                    TextView add = new TextView(MainActivity.this);
                    add.setText(getString(R.string.dialog_add_family_ok));
                    add.setTextSize(15f);
                    add.setPadding(dp(14), dp(14), dp(14), dp(14));
                    add.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.primary));
                    LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    alp.setMargins(dp(4), dp(6), dp(4), dp(4));
                    add.setLayoutParams(alp);
                    add.setOnClickListener(v -> {
                        if (holder[0] != null) {
                            holder[0].dismiss();
                        }
                        showFamilySetup();
                    });
                    listBox.addView(add);
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    toast(getString(R.string.toast_network_error, msg));
                });
            }
        });
    }

    /** 切换到指定家庭（只切换当前查看的家庭，不退出任何家庭） */
    private void switchToFamily(String targetFamilyId) {
        if (targetFamilyId == null || targetFamilyId.isEmpty() || switchingFamily) {
            return;
        }
        if (targetFamilyId.equals(Prefs.get(this).familyId())) {
            return;
        }
        switchingFamily = true;
        // 家庭代号 +1：让上一个家庭迟到的网络/广播响应失效（避免数据串台）
        familyGeneration++;
        Prefs.get(this).familyId(targetFamilyId);   // 只改「当前家庭」，不动家庭列表
        // 收尾：清理当前视图状态（成员、标点、轨迹），避免显示上一个家庭的人
        members.clear();
        for (String id : new ArrayList<>(markers.keySet())) {
            Marker mk = markers.remove(id);
            if (mk != null) {
                mk.remove();
            }
            removeAccuracyCircle(id);
        }
        iconCache.clear();
        for (Polyline p : trackLines.values()) {
            p.remove();
        }
        trackLines.clear();
        for (Marker mk : trackStartMarkers.values()) {
            if (mk != null) {
                mk.remove();
            }
        }
        trackStartMarkers.clear();
        refreshAdapter();
        closeDetail();
        final int gen = familyGeneration;
        bottomPanel.animate().alpha(0.25f).setDuration(120).withEndAction(() -> {
            if (gen != familyGeneration) {
                switchingFamily = false;
                return; // 期间又切换了，放弃本次收尾
            }
            sendToService(AppConfig.ACTION_RECONNECT);
            loadMembers();
            updateFamilyTitle();
            updateFamilySwitchButton();
            bottomPanel.animate().alpha(1f).setDuration(180)
                    .withEndAction(() -> switchingFamily = false).start();
            toastTop(getString(R.string.toast_family_switched, currentFamilyName()));
        }).start();
    }

    /** 该响应是否仍属于「当前查看的家庭」（家庭切换后丢弃上一个家庭的迟到数据） */
    private boolean isCurrentFamily(String familyId, int generation) {
        return generation == familyGeneration
                && familyId != null
                && familyId.equals(Prefs.get(this).familyId());
    }

    /**
     * 当前家庭被解散（自己解散或被群主解散）：从本地家庭列表移除该家庭；
     * 若还有其它家庭则自动切到下一个，否则回到「创建/加入家庭」引导。
     */
    private void onFamilyDisbanded(String familyId) {
        if (familyId == null || familyId.isEmpty()) {
            return;
        }
        Prefs prefs = Prefs.get(this);
        String name = prefs.familyCodeOf(familyId);
        boolean wasActive = familyId.equals(prefs.familyId());
        prefs.removeFamily(familyId);
        // 家庭代号 +1：丢弃该家庭迟到的响应，避免和现在显示的家庭串台
        familyGeneration++;
        loadFamilyIds();
        if (!wasActive) {
            updateFamilySwitchButton();
            return; // 被解散的不是当前查看的家庭，只需把它从列表里去掉
        }
        toastTop(getString(R.string.toast_family_disbanded,
                name.isEmpty() ? getString(R.string.title_family_short) : name));
        if (prefs.familyId().isEmpty()) {
            // 没有其它家庭了：停止共享并引导重新创建/加入
            prefs.shareEnabled(false);
            sendToService(AppConfig.ACTION_STOP);
            members.clear();
            for (String id : new ArrayList<>(markers.keySet())) {
                Marker mk = markers.remove(id);
                if (mk != null) {
                    mk.remove();
                }
                removeAccuracyCircle(id);
            }
            iconCache.clear();
            trackLines.clear();
            trackStartMarkers.clear();
            refreshAdapter();
            closeDetail();
            updateFamilyTitle();
            updateFamilySwitchButton();
            toast(getString(R.string.toast_last_family_disbanded));
            showFamilySetup();
        } else {
            // 自动切到另一个家庭
            switchingFamily = false;
            switchToFamily(prefs.familyId());
        }
    }

    /** 群主一键解散当前家庭（二次确认） */
    private void showDisbandFamilyDialog() {
        Prefs prefs = Prefs.get(this);
        if (prefs.familyId().isEmpty() || !prefs.isOwner()) {
            return;
        }
        String code = prefs.familyCodeOf(prefs.familyId());
        String extra = code.isEmpty() ? "" : ("\n\n家庭码：" + code);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_disband_title)
                .setMessage(getString(R.string.dialog_disband_message) + extra)
                .setPositiveButton(R.string.dialog_disband_ok, (d, w) -> disbandCurrentFamily())
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    private void disbandCurrentFamily() {
        final String familyId = Prefs.get(this).familyId();
        if (familyId.isEmpty()) {
            return;
        }
        Api.disbandFamily(familyId, myDeviceId, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    toast(getString(R.string.toast_disband_done));
                    onFamilyDisbanded(familyId);
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
            }
        });
    }

    /** 面板与刻度尺相关 View 是否都已就绪（未就绪前不要做刻度尺/面板计算） */
    private boolean panelViewsReady() {
        return bottomPanel != null && panelBody != null && panelHeader != null
                && scaleBar != null && tvScaleText != null && scaleLine != null;
    }

    /**
     * 面板/刻度尺初始化：必须在所有 View findViewById 完成后再执行
     * （否则 panelFullHeight() 会用到还是 null 的 bottomPanel）。
     */
    private void postPanelReadyInit() {
        if (!mapReady || aMap == null || !panelViewsReady()) {
            return;
        }
        mapView.post(() -> {
            if (!panelViewsReady()) {
                return;
            }
            initScaleBar(); // 自绘刻度尺（注册相机监听 + 首次定位）
            refreshMarkerStyleIfNeeded(currentZoom());
            for (Member m : members.values()) {
                updateMarker(m);
            }
            updateTrackLines(new ArrayList<>(members.values()));
            updateScaleBarPosition();
            if (!members.isEmpty()) {
                fitCameraToMembers();
            }
            scheduleMapRelayout();
        });
    }

    // ---------------- 成员与地图 ----------------

    private void applyMember(Member m, boolean moveCamera) {
        if (m.deviceId == null || m.deviceId.isEmpty()) {
            return;
        }
        boolean existed = members.containsKey(m.deviceId);
        Member prev = members.get(m.deviceId);
        if (prev != null) {
            if (m.name == null || m.name.isEmpty()) {
                m.name = prev.name;
            }
            // WS 位置广播只携带位置/电量/网络/地址，不携带头像/轨迹/下线/群主标记：
            // 必须沿用旧值，否则每次实时上报都会把头像抹成首字彩圈、轨迹/下线/群主标识被清空
            if (m.avatar == null || m.avatar.isEmpty()) {
                m.avatar = prev.avatar;
            }
            m.track = prev.track;
            m.offlineMode = prev.offlineMode;
            m.trajectory = prev.trajectory;
            m.isOwner = prev.isOwner;
        }
        members.put(m.deviceId, m);
        updateMarker(m);
        if (existed) {
            // 已有成员：只刷新该行，避免整表刷新导致“绿点闪烁”
            adapter.onMemberUpdated(m, myDeviceId);
            updateCountAndEmpty();
        } else {
            refreshAdapter();
        }
        if (moveCamera && m.hasLocation) {
            moveCamera(m.lat, m.lng);
            if (m.deviceId.equals(pendingFocusDeviceId)) {
                pendingFocusDeviceId = "";
            }
        }
    }

    /** 标点锚点：底边中点（与卡片左右中心、圆角中心一致），保证标点左右不偏 */
    private static final float MARKER_ANCHOR_U = 0.5f;
    /** 标点锚点：位图底边（= 尾巴尖端，位图最后一行就是尾尖所在行） */
    private static final float MARKER_ANCHOR_V = 1f;

    /**
     * 标点样式：地图放大时用完整卡片（头像 + 昵称），缩小时折叠成小圆点。
     * 缩小后标点与文字密集，卡片会互相压字、看不清位置，折叠成圆点后视野干净、位置也更准。
     */
    private boolean markerDotMode;
    /** 样式切换的缩放阈值：低于该级别折叠为圆点，高于则展开为卡片（留 0.25 级迟滞，避免边界抖动） */
    private static final float DOT_MODE_ZOOM = 13f;
    /** 缩放低于该级别时不画精度圈（圈会连成一片，反而看不清人员分布） */
    private static final float ACCURACY_CIRCLE_MIN_ZOOM = 14f;

    /** 地图容器尺寸快照：用于发现 WebView 容器尺寸与实际渲染尺寸不一致（会让底图标注层按错误比例移动） */
    private int mapLastW;
    private int mapLastH;

    /**
     * 地图容器尺寸对齐（轻量版 WebView 渲染时的兜底）。
     * 用完整版原生 3D SDK 时不需要、也不该做：给 MapView 重新 setLayoutParams 会触发 GL 表面重建，
     * 而地图的原生 GL 线程对这种重建很敏感（可能直接 SIGABRT 崩溃）。
     * 因此这里只在容器尺寸真的从 0 变成有效值时做一次「请求重新布局」，不再改 LayoutParams。
     */
    private void scheduleMapRelayout() {
        if (mapView == null) {
            return;
        }
        mapView.post(() -> {
            if (mapView == null || mapView.getWidth() <= 0 || mapView.getHeight() <= 0) {
                return;
            }
            if (mapView.getWidth() == mapLastW && mapView.getHeight() == mapLastH) {
                return;
            }
            mapLastW = mapView.getWidth();
            mapLastH = mapView.getHeight();
            // 只请求重新布局，不做 setLayoutParams（避免重建 GL 表面）
            mapView.requestLayout();
        });
    }

    private void updateMarker(Member m) {
        if (!mapReady || aMap == null) {
            return; // 地图未就绪：就绪后统一补画
        }
        if (!m.hasLocation) {
            removeAccuracyCircle(m.deviceId);
            return;
        }
        BitmapDescriptor icon = memberIcon(m);
        Marker marker = markers.get(m.deviceId);
        if (marker == null) {
            MarkerOptions opt = new MarkerOptions()
                    .position(new LatLng(m.lat, m.lng))
                    .icon(icon)
                    .title(m.name)
                    .anchor(MARKER_ANCHOR_U, MARKER_ANCHOR_V);
            marker = aMap.addMarker(opt);
            markers.put(m.deviceId, marker);
        } else {
            marker.setIcon(icon);
            // 位图尺寸可能因昵称长度/样式切换变化，锚点按比例重新对齐到「底边中点」
            marker.setAnchor(MARKER_ANCHOR_U, MARKER_ANCHOR_V);
            marker.setPosition(new LatLng(m.lat, m.lng));
        }
        marker.setTitle(m.name);
        loadMarkerAvatar(m);
        updateAccuracyCircle(m);
    }

    /**
     * 缩放变化后刷新标点样式（卡片 <-> 圆点）与精度圈显隐。
     * 仅在跨越阈值时重建图标，避免每帧重绘。
     */
    private void refreshMarkerStyleIfNeeded(float zoom) {
        // 阈值带迟滞：折叠用 12.75、展开用 13.0，避免正好停在阈值上时反复重建全部图标
        float threshold = markerDotMode ? DOT_MODE_ZOOM - 0.25f : DOT_MODE_ZOOM;
        boolean dot = zoom < threshold;
        if (dot != markerDotMode) {
            markerDotMode = dot;
            iconCache.clear(); // 样式变了：图标需要按新样式重建
            for (Member m : members.values()) {
                updateMarker(m);
            }
        }
        // 精度圈：缩得太小时隐藏（否则多个圈叠在一起糊成一片）
        for (String id : new ArrayList<>(accuracyCircles.keySet())) {
            Circle c = accuracyCircles.get(id);
            if (c != null) {
                c.setVisible(zoom >= ACCURACY_CIRCLE_MIN_ZOOM);
            }
        }
    }

    /**
     * 取成员标点图标（带缓存）：昵称/头像/是否本机/标点样式不变时复用同一张位图。
     * 位置刷新非常频繁，而图标内容与位置无关，缓存可避免重复生成位图与 PNG 编码。
     */
    private BitmapDescriptor memberIcon(Member m) {
        String name = (m.name == null || m.name.isEmpty()) ? "?" : m.name;
        String avatar = m.avatar == null ? "" : m.avatar;
        String key = name + "\u0000" + avatar + "\u0000" + m.deviceId.equals(myDeviceId)
                + "\u0000" + markerDotMode;
        MemberIcon cached = iconCache.get(m.deviceId);
        if (cached != null && cached.key.equals(key)) {
            return cached.descriptor;
        }
        BitmapDescriptor descriptor = buildMemberIcon(m);
        iconCache.put(m.deviceId, new MemberIcon(key, descriptor));
        // 注意：旧图标可能仍被地图上的旧帧引用，此处不主动 recycle，交由 GC/地图管理，避免闪白或崩溃
        return descriptor;
    }

    /**
     * 头像更新后强制重绘标点：清掉图标缓存、已解码头像缓存与下载缓存。
     * 头像 URL 通常固定为 icons/&lt;deviceId&gt;.jpg（内容变了但地址没变），只比对 URL 无法发现更新。
     */
    private void invalidateMemberIconAndAvatar(String deviceId) {
        iconCache.remove(deviceId);
        Member cur = members.get(deviceId);
        if (cur != null) {
            if (cur.avatar != null && !cur.avatar.isEmpty()) {
                AvatarLoader.evict(cur.avatar);
            }
            cur.avatarBitmap = null;
            cur.avatarBitmapFor = null;
        }
    }

    /** 成员标点颜色：本机为品牌色，其他成员按其 deviceId 稳定取色 */
    private int memberColor(Member m) {
        return m.deviceId.equals(myDeviceId) ? MemberColors.selfColor() : MemberColors.colorFor(m.deviceId);
    }

    /**
     * 生成成员标点图标：
     * - 放大时（markerDotMode=false）：成员颜色圆角卡片 + 头像/首字 + 名字 + 底部三角尾巴；
     * - 缩小时（markerDotMode=true） ：小圆点（团队色描边 + 头像/首字）+ 名字小标签。
     * 两种样式都保持「锚点 = 位图底边中点」，且位图宽高都是 density 的整数倍，位置不会漂。
     */
    private BitmapDescriptor buildMemberIcon(Member m) {
        return markerDotMode ? buildMemberDotIcon(m) : buildMemberCardIcon(m);
    }

    /** 位图宽高对齐到屏幕密度的整数倍，保证网页侧尺寸/偏移都是整数（见下方 buildMemberCardIcon 注释） */
    private static void alignToDensity(int[] wh, float density) {
        int step = Math.max(1, Math.round(density));
        for (int i = 0; i < wh.length; i++) {
            if (wh[i] % step != 0) {
                wh[i] += step - (wh[i] % step);
            }
        }
    }

    /** 把头像（或首字）画到圆形区域内，带白色描边环 */
    private void drawAvatarCircle(android.graphics.Canvas c, Member m, int color,
                                  float cx, float cy, float radius, float density) {
        String name = (m.name == null || m.name.isEmpty()) ? "?" : m.name;
        int size = Math.round(radius * 2);
        // 描边环（外圈团队色 + 内圈白），在浅色底图上也能与地图区分开
        android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(android.graphics.Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1.5f, 2f * density));
        ring.setColor(color);
        c.drawCircle(cx, cy, radius - ring.getStrokeWidth() / 2f, ring);
        android.graphics.Paint white = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        white.setColor(android.graphics.Color.WHITE);
        c.drawCircle(cx, cy, radius - ring.getStrokeWidth(), white);

        Bitmap av = m.avatarBitmap;
        float inner = radius - ring.getStrokeWidth() * 1.5f;
        if (av != null && m.avatar != null && m.avatar.equals(m.avatarBitmapFor)) {
            android.graphics.Path circleClip = new android.graphics.Path();
            circleClip.addCircle(cx, cy, inner, android.graphics.Path.Direction.CW);
            int sc = c.save();
            c.clipPath(circleClip);
            c.drawBitmap(Bitmap.createScaledBitmap(av, size, size, true), cx - size / 2f, cy - size / 2f, null);
            c.restoreToCount(sc);
        } else {
            android.graphics.Paint ip = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            ip.setColor(color);
            ip.setTextSize(inner * 1.1f);
            ip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            ip.setTextAlign(android.graphics.Paint.Align.CENTER);
            float ib = cy - (ip.descent() + ip.ascent()) / 2f;
            c.drawText(name.substring(0, 1), cx, ib, ip);
        }
    }

    /**
     * 缩小地图时的标点样式：名字小标签 + 小圆点（头像/首字）。
     * 布局从上到下为「名字标签 → 间距 → 圆点」，圆点底边与位图底边对齐，
     * 因此锚点(0.5, 1) 正好落在圆点底部中心 —— 与卡片模式的尾尖压点方式一致，
     * 缩放切换样式时标点相对坐标点的位置关系不会发生变化。
     */
    private BitmapDescriptor buildMemberDotIcon(Member m) {
        float d = getResources().getDisplayMetrics().density;
        int nameSize = Math.round(10 * d);
        int dotR = Math.round(11 * d);          // 圆点半径
        int dotPad = Math.round(3 * d);         // 标签与圆点之间的间距
        int labelPadH = Math.round(5 * d);
        int labelH = Math.round(nameSize + 5 * d);
        int maxNameW = Math.round(64 * d);
        int color = memberColor(m);
        String name = (m.name == null || m.name.isEmpty()) ? "?" : m.name;

        android.text.TextPaint tp = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(nameSize);
        tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tp.setColor(android.graphics.Color.WHITE);
        tp.setTextAlign(android.graphics.Paint.Align.LEFT);
        String shownName = tp.measureText(name) > maxNameW
                ? android.text.TextUtils.ellipsize(name, tp, maxNameW, android.text.TextUtils.TruncateAt.END).toString()
                : name;
        int textW = (int) Math.ceil(tp.measureText(shownName));

        int labelW = labelPadH * 2 + textW;
        int totalW = Math.max(dotR * 2 + Math.round(6 * d), labelW);
        int totalH = labelH + dotPad + dotR * 2;   // 标签在上、圆点在下（圆点贴位图底边）
        int[] wh = {totalW, totalH};
        alignToDensity(wh, d);
        totalW = wh[0];
        totalH = wh[1];

        Bitmap bmp = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);

        // 圆点：底边与位图底边对齐（画布内 y 轴向下，圆心 = totalH - dotR）
        float dotCy = totalH - dotR;
        drawAvatarCircle(c, m, color, totalW / 2f, dotCy, dotR, d);

        // 名字标签（深色圆角底 + 白字），帮助在低缩放下仍能分辨是谁
        float labelTop = 0f;
        android.graphics.Paint label = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        label.setColor(0xCC1A1A2E);
        float lr = labelH / 2f;
        c.drawRoundRect((totalW - labelW) / 2f, labelTop, (totalW + labelW) / 2f, labelTop + labelH, lr, lr, label);
        float tb = labelTop + labelH / 2f - (tp.descent() + tp.ascent()) / 2f;
        c.drawText(shownName, (totalW - textW) / 2f, tb, tp);
        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    /**
     * 放大时的标点样式：成员颜色圆角卡片 + 头像/首字 + 名字 + 底部三角尾巴。
     * 锚点 = 位图底边中点（尾尖位于最后一行，正好压在定位点上）。
     */
    private BitmapDescriptor buildMemberCardIcon(Member m) {
        float d = getResources().getDisplayMetrics().density;
        int avatarSize = Math.round(30 * d);
        int pad = Math.round(6 * d);
        int gap = Math.round(6 * d);
        int tailH = Math.round(9 * d);
        int nameSize = Math.round(12 * d);
        int maxNameW = Math.round(96 * d);
        int color = memberColor(m);

        android.text.TextPaint tp = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(nameSize);
        tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tp.setColor(android.graphics.Color.WHITE);
        tp.setTextAlign(android.graphics.Paint.Align.LEFT);
        String name = (m.name == null || m.name.isEmpty()) ? "?" : m.name;
        // 超长昵称截断为省略号，避免超出卡片
        String shownName = tp.measureText(name) > maxNameW
                ? android.text.TextUtils.ellipsize(name, tp, maxNameW, android.text.TextUtils.TruncateAt.END).toString()
                : name;
        int nameW = Math.max(Math.round(18 * d), (int) Math.ceil(tp.measureText(shownName)));

        int cardW = pad * 2 + avatarSize + gap + nameW;
        int cardH = pad * 2 + Math.max(avatarSize, nameSize + 6);
        int totalW = cardW;
        int totalH = cardH + tailH;

        // 位图尺寸对齐到屏幕密度的整数倍：
        // 轻量版地图SDK 用「位图像素 / density」作为网页里的图标尺寸与偏移量（AMap.Icon size/offset），
        // 若位图宽高不能被 density 整除（如 2.625/2.75 等非整数密度、或计算出的奇数宽高），
        // 网页侧尺寸与偏移会各自取整，标点（含其中的名字文字）就会相对真实经纬度产生几个像素的偏移，
        // 缩小地图时标点与文字更密集，偏移会非常明显。按 density 取整后，尺寸与偏移都是整数，不再错位。
        int[] wh = {totalW, totalH};
        alignToDensity(wh, d);
        totalW = wh[0];
        totalH = wh[1];

        Bitmap bmp = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);

        // 卡片（垂直居中：上下留白与放大后的位图居中，尾尖仍在最后一行正中）
        float cardTop = (totalH - tailH - cardH) / 2f;
        float cardBottom = cardTop + cardH;
        android.graphics.Paint cardPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        cardPaint.setColor(color);
        float radius = 9 * d;
        c.drawRoundRect(0, cardTop, cardW, cardBottom, radius, radius, cardPaint);

        // 头像（白色圆底 + 头像/首字）
        float circleR = avatarSize / 2f;
        float cx = pad + circleR, cy = cardTop + pad + circleR;
        drawAvatarCircle(c, m, color, cx, cy, circleR, d);

        // 名字
        float tx = pad + avatarSize + gap;
        float tb = cardTop + cardH / 2f - (tp.descent() + tp.ascent()) / 2f;
        c.drawText(shownName, tx, tb, tp);

        // 底部三角尾巴（指向定位点：尾尖落在最后一行，配合 anchor(0.5, 1) 精确压点）
        android.graphics.Path tail = new android.graphics.Path();
        float tcx = totalW / 2f, tw = 8 * d;
        tail.moveTo(tcx - tw, cardBottom);
        tail.lineTo(tcx + tw, cardBottom);
        tail.lineTo(tcx, totalH);
        tail.close();
        c.drawPath(tail, cardPaint);

        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    /** 标点头像异步加载：头部头像 URL 变化时刷新，加载完成重绘标点 */
    private void loadMarkerAvatar(Member m) {
        if (m.avatar == null || m.avatar.isEmpty()) {
            return;
        }
        if (m.avatarBitmap != null && m.avatar.equals(m.avatarBitmapFor)) {
            return;
        }
        final String reqUrl = m.avatar;
        AvatarLoader.load(reqUrl, bmp -> runOnUiThread(() -> {
            if (bmp == null) {
                return;
            }
            Member cur = members.get(m.deviceId);
            if (cur == null) {
                return;
            }
            cur.avatarBitmap = bmp;
            cur.avatarBitmapFor = reqUrl;
            if (reqUrl.equals(cur.avatar)) {
                updateMarker(cur);
            }
        }));
    }

    /**
     * 成员精度圈：以角标为圆心、半径=定位精度（±N 米）。
     * 半透明绿色填充，边缘 75% 不透明深绿色描边；角标保留在圆心。
     */
    private void updateAccuracyCircle(Member m) {
        if (!mapReady || aMap == null) {
            return;
        }
        double radius = m.accuracy > 0 ? m.accuracy : 0;
        if (radius <= 0 || !m.hasLocation) {
            removeAccuracyCircle(m.deviceId);
            return;
        }
        LatLng center = new LatLng(m.lat, m.lng);
        boolean show = currentZoom() >= ACCURACY_CIRCLE_MIN_ZOOM;
        Circle circle = accuracyCircles.get(m.deviceId);
        if (circle == null) {
            circle = aMap.addCircle(new CircleOptions()
                    .center(center)
                    .radius(radius)
                    .strokeColor(MemberColors.ACCURACY_STROKE)
                    .strokeWidth(2)
                    .fillColor(MemberColors.ACCURACY_FILL));
            accuracyCircles.put(m.deviceId, circle);
        } else {
            circle.setCenter(center);
            circle.setRadius(radius);
        }
        circle.setVisible(show);
    }

    /** 当前地图缩放级别（地图未就绪时返回 0，等价于「很小」） */
    private float currentZoom() {
        if (!mapReady || aMap == null) {
            return 0f;
        }
        try {
            CameraPosition cam = aMap.getCameraPosition();
            return cam == null ? 0f : cam.zoom;
        } catch (Exception e) {
            return 0f;
        }
    }

    private void removeAccuracyCircle(String deviceId) {
        Circle c = accuracyCircles.remove(deviceId);
        if (c != null) {
            c.remove();
        }
    }

    private void loadMembers() {
        final String familyId = Prefs.get(this).familyId();
        if (familyId.isEmpty()) {
            return;
        }
        final int gen = familyGeneration;
        Api.listMembers(familyId, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    // 家庭已切换/解散：丢弃上一个家庭的迟到响应，避免"第一个家庭的人出现在第二个家庭"
                    if (!isCurrentFamily(familyId, gen)) {
                        return;
                    }
                    try {
                        applyMemberList(Member.listFromJson(body), true);
                    } catch (Exception e) {
                        toast(getString(R.string.toast_network_error, "数据解析失败"));
                    }
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    if (!isCurrentFamily(familyId, gen)) {
                        return;
                    }
                    toast(getString(R.string.toast_network_error, msg));
                });
            }
        });
    }

    /** 用服务器返回的成员列表整体刷新成员/标记/列表（fitCamera 控制是否移动视野） */
    private void applyMemberList(List<Member> list, boolean fitCamera) {
        // 切换服务器后：若新服务器一时返回空列表/尚未就绪，保留上一次成员列表，避免绿点整体消失
        if (System.currentTimeMillis() - serverSwitchAt < 5000 && list.isEmpty() && !members.isEmpty()) {
            toast(getString(R.string.toast_server_empty));
            refreshAdapter();
            return;
        }
        // 记录切换前各成员的上线/位置状态，用于重连瞬间“保活”绿点，避免闪烁
        Map<String, Member> prev = new HashMap<>(members);
        members.clear();
        Set<String> ids = new HashSet<>();
        for (Member m : list) {
            if (m.deviceId.isEmpty()) {
                continue;
            }
            // 服务器切换/重连瞬间：新列表可能因 WS 尚未连上而把成员标记为离线；
            // 若该成员此前在线、且刚上报过位置（2 分钟内），保持绿点在线，避免闪烁
            if (!m.online) {
                Member old = prev.get(m.deviceId);
                if (old != null && old.online && old.hasLocation
                        && (System.currentTimeMillis() - old.ts) < 120_000) {
                    m.online = true;
                }
            }
            ids.add(m.deviceId);
            members.put(m.deviceId, m);
            updateMarker(m);
        }
        // 以服务端返回的 isOwner 为准同步本机群主权限（避免只靠 WS owner-changed 漏更新，
        // 导致“有群主头衔却无管理员权限/菜单无管理员项”）
        Member self = members.get(myDeviceId);
        if (self != null) {
            Prefs.get(this).isOwner(self.isOwner);
        }
        removeStaleMarkers(ids);
        updateTrackLines(list);
        refreshAdapter();
        if (fitCamera) {
            fitCameraToMembers();
        }
        // 打开/回到前台：在“多人家庭”内自动向全员请求一次实时位置（底部 toast 提示，不弹窗）
        maybeAutoRefreshOnEntry();
    }

    /** 打开/回到前台时，若处于多人家庭则向所有成员请求一次实时位置（refreshAll 会以 toast 在底部提示） */
    private void maybeAutoRefreshOnEntry() {
        if (!entryRefreshArmed) {
            return;
        }
        entryRefreshArmed = false; // 已消费：每次打开/回到前台只执行一次
        String familyId = Prefs.get(this).familyId();
        if (familyId.isEmpty()) {
            return;
        }
        if (members.size() <= 1) {
            return; // 仅自己：无其他成员可刷新
        }
        // 向全员请求一次实时位置（非本机成员）
        refreshAll();
    }

    /** 绘制/更新成员的轨迹连线（带指向箭头） */
    private void updateTrackLines(List<Member> list) {
        if (!mapReady || aMap == null) {
            return;
        }
        Set<String> keep = new HashSet<>();
        for (Member m : list) {
            if (m.track && m.trajectory.size() >= 2) {
                keep.add(m.deviceId);
            }
        }
        // 移除不再有轨迹的
        List<String> stale = new ArrayList<>();
        for (String id : trackLines.keySet()) {
            if (!keep.contains(id)) {
                stale.add(id);
            }
        }
        for (String id : stale) {
            Polyline p = trackLines.remove(id);
            if (p != null) {
                p.remove();
            }
            removeTrackStart(id);
        }
        // 更新/新增轨迹线
        for (Member m : list) {
            if (!keep.contains(m.deviceId)) {
                continue;
            }
            int baseColor = MemberColors.colorFor(m.deviceId);
            List<Integer> colors = trackGradientColors(baseColor, m.trajectory.size());
            Polyline p = trackLines.get(m.deviceId);
            if (p != null) {
                p.setPoints(m.trajectory);
                // 逐点颜色（旧点淡、新点亮，方向一目了然）。轻量版 SDK 的 Polyline 没有
                // setColorValues，但 setColor/setPoints 都是改 options 后重建，这里同样处理。
                PolylineOptions opts = p.getOptions();
                if (opts != null) {
                    opts.colorValues(colors);
                    p.setOptions(opts);
                }
            } else {
                p = aMap.addPolyline(new PolylineOptions()
                        .addAll(m.trajectory)
                        .width(5)
                        .color(baseColor)
                        .colorValues(colors));
                trackLines.put(m.deviceId, p);
            }
            updateTrackStart(m);
        }
        // 列表刷新后若详情页仍打开，重新加粗该成员轨迹
        if (highlightDeviceId != null && !highlightDeviceId.isEmpty() && isDetailOpen()) {
            highlightTrack(highlightDeviceId);
        }
    }

    /**
     * 轨迹线的渐变配色：整条线只取有限个色标（AMap 的 colorValues 是逐点颜色，
     * 点数很多时全部生成会用大量内存/带宽），这里把点分段，旧的淡、新的亮。
     */
    private static List<Integer> trackGradientColors(int baseColor, int pointCount) {
        List<Integer> out = new ArrayList<>();
        if (pointCount <= 0) {
            return out;
        }
        final int steps = Math.min(10, pointCount); // 色标数量上限
        for (int i = 0; i < pointCount; i++) {
            // 位置比例 0(最旧) -> 1(最新)
            double ratio = pointCount == 1 ? 1.0 : (double) i / (pointCount - 1);
            int step = (int) Math.floor(ratio * (steps - 1));
            float alphaRatio = 0.30f + 0.70f * (steps == 1 ? 1f : (float) step / (steps - 1));
            int alpha = Math.max(60, Math.min(255, Math.round(255 * alphaRatio)));
            out.add((baseColor & 0x00FFFFFF) | (alpha << 24));
        }
        return out;
    }

    /** 添加/更新某成员轨迹的绿色起点标记（单独的一个绿点） */
    private void updateTrackStart(Member m) {
        if (!mapReady || aMap == null) {
            return;
        }
        if (m.trajectory.isEmpty()) {
            removeTrackStart(m.deviceId);
            return;
        }
        LatLng start = m.trajectory.get(0);
        Marker mk = trackStartMarkers.get(m.deviceId);
        BitmapDescriptor icon = BitmapDescriptorFactory.fromResource(R.drawable.ic_track_start);
        if (mk == null) {
            mk = aMap.addMarker(new MarkerOptions().position(start).icon(icon).anchor(0.5f, 0.5f));
            trackStartMarkers.put(m.deviceId, mk);
        } else {
            mk.setPosition(start);
            mk.setIcon(icon);
        }
    }

    /** 移除某成员的轨迹起点标记 */
    private void removeTrackStart(String deviceId) {
        Marker mk = trackStartMarkers.remove(deviceId);
        if (mk != null) {
            mk.remove();
        }
    }

    /** 详情页：加粗高亮某成员的轨迹线 */
    private void highlightTrack(String deviceId) {
        clearTrackHighlight();
        highlightDeviceId = deviceId == null ? "" : deviceId;
        Polyline p = trackLines.get(deviceId);
        if (p == null) {
            return;
        }
        p.setWidth(10);
        highlightTrack = p;
    }

    /** 关闭详情：还原被加粗的轨迹线 */
    private void clearTrackHighlight() {
        if (highlightTrack != null) {
            highlightTrack.setWidth(5);
            highlightTrack = null;
        }
        highlightDeviceId = "";
    }

    private void removeStaleMarkers(Set<String> keepIds) {
        List<String> stale = new ArrayList<>();
        for (String id : markers.keySet()) {
            if (!id.equals(myDeviceId) && !keepIds.contains(id)) {
                stale.add(id);
            }
        }
        for (String id : stale) {
            Marker mk = markers.remove(id);
            if (mk != null) {
                mk.remove();
            }
            iconCache.remove(id);
            removeAccuracyCircle(id);
        }
    }

    private void refreshAdapter() {
        adapter.update(new ArrayList<>(members.values()), myDeviceId);
        updateCountAndEmpty();
        // 成员列表高度变化（如切换家庭后）会改变面板高度，重新定位刻度尺
        bottomPanel.post(this::updateScaleBarPosition);
    }

    /** 仅刷新成员数量/空状态（成员行用定点刷新，避免绿点闪烁） */
    private void updateCountAndEmpty() {
        int count = members.size();
        if (tvMemberCount != null) {
            tvMemberCount.setText(getString(R.string.member_count_title, count));
        }
        boolean empty = members.isEmpty();
        // 没有成员时用带「创建/加入家庭」按钮的引导卡片兜底（比一行灰字更容易上手）
        if (emptyCard != null) {
            emptyCard.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        tvEmpty.setVisibility(View.GONE); // 文案已由引导卡片承载
        memberList.setVisibility(empty ? View.GONE : View.VISIBLE);
        updateMemberListHeight();
        updateMoreMembersHint();
        updateFamilyTitle();
        updateFamilySwitchButton();
    }

    /**
     * 列表被裁掉时，只在顶部/底部显示透明渐变（被裁掉的那半行自然淡出），
     * 不再显示「还有 N 位家人」文字。
     */
    private void updateMoreMembersHint() {
        if (listTopMore == null || listBottomMore == null || memberList == null) {
            return;
        }
        if (memberList.getVisibility() != View.VISIBLE || memberList.getHeight() <= 0) {
            listTopMore.setVisibility(View.GONE);
            listBottomMore.setVisibility(View.GONE);
            return;
        }
        listBottomMore.setVisibility(memberList.canScrollVertically(1) ? View.VISIBLE : View.GONE);
        listTopMore.setVisibility(memberList.canScrollVertically(-1) ? View.VISIBLE : View.GONE);
    }

    /**
     * 成员列表高度限制：最多同时显示 MAX_VISIBLE_MEMBERS 行，并在底部多留出约半行的高度，
     * 让下一个家人露出半个卡片（便于辨认是谁），配合底部透明渐变提示还能下滑。
     * 人数不足时用自然高度（wrap_content）。
     */
    private void updateMemberListHeight() {
        if (memberList == null) {
            return;
        }
        int count = adapter.getItemCount();
        ViewGroup.LayoutParams lp = memberList.getLayoutParams();
        if (lp == null) {
            return;
        }
        if (count <= MAX_VISIBLE_MEMBERS) {
            if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                memberList.setLayoutParams(lp);
            }
            return;
        }
        if (memberRowHeightPx <= 0) {
            View sample = getLayoutInflater().inflate(R.layout.item_member, memberList, false);
            int w = memberList.getWidth() > 0 ? memberList.getWidth() : dp(320);
            sample.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            memberRowHeightPx = sample.getMeasuredHeight() > 0 ? sample.getMeasuredHeight() : dp(62);
        }
        // N 行完整高度 + 下一行露出约 40%（能看清昵称与头像，又明显是"被裁了一半"）
        int targetH = Math.round(memberRowHeightPx * (MAX_VISIBLE_MEMBERS + 0.4f));
        if (lp.height != targetH) {
            lp.height = targetH;
            memberList.setLayoutParams(lp);
        }
    }

    private void fitCameraToMembers() {
        if (!mapReady || aMap == null) {
            return;
        }
        LatLngBounds.Builder builder = LatLngBounds.builder();
        int count = 0;
        // 去重后的第一个点：仅 1 个定位点时不能用 newLatLngBounds
        LatLng only = null;
        for (Member m : members.values()) {
            if (m.hasLocation) {
                LatLng p = new LatLng(m.lat, m.lng);
                if (only == null || only.latitude != p.latitude || only.longitude != p.longitude) {
                    count++;
                }
                if (only == null) {
                    only = p;
                }
                builder.include(p);
            }
        }
        if (count == 0 && Prefs.get(this).hasLastLocation()) {
            only = new LatLng(Prefs.get(this).lastLat(), Prefs.get(this).lastLng());
            builder.include(only);
            count = 1;
        }
        if (count <= 0) {
            return;
        }
        // 只有 1 个（或全部重合的）定位点：LatLngBounds 退化为一个点，按边界适配在部分机型上会算出异常缩放，
        // 这里直接按固定级别定位到该点，避免视野跳到奇怪的位置。
        if (count == 1) {
            aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(only, 16f));
            return;
        }
        aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100));
    }

    private void moveCamera(double lat, double lng) {
        if (!mapReady || aMap == null) {
            return;
        }
        // 18 级缩放：定位到人/自己时更放大，便于看清位置
        aMap.animateCamera(CameraUpdateFactory
                .newLatLngZoom(new LatLng(lat, lng), 18f));
    }

    // ---------------- 交互 ----------------

    private void onMemberClick(Member m) {
        // 跳转到该成员当前的位置
        if (m.hasLocation) {
            moveCamera(m.lat, m.lng);
        }
        // 打开成员详情页
        showMemberDetail(m);
    }

    /** 家人主动查看 -> 请求服务器让目标设备实时上报一次 */
    private void requestFreshLocation(final Member m) {
        final String familyId = Prefs.get(this).familyId();
        if (familyId.isEmpty()) {
            toast(getString(R.string.toast_need_family_first));
            return;
        }
        Api.requestLocation(familyId, myDeviceId, m.deviceId, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    try {
                        JSONObject o = new JSONObject(body);
                        String status = o.optString("status");
                        if ("offline".equals(status)) {
                            if (pendingFocusDeviceId.equals(m.deviceId)) {
                                pendingFocusDeviceId = "";
                            }
                            toastTop(getString(R.string.toast_target_offline, m.name));
                        } else {
                            toastTop(getString(R.string.toast_report_requested, m.name));
                        }
                    } catch (Exception e) {
                        toastTop(getString(R.string.toast_report_requested, m.name));
                    }
                });
                // 兜底：几秒后静默重拉一次成员列表，确保拿到最新位置
                scheduleMembersRefresh();
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    if (pendingFocusDeviceId.equals(m.deviceId)) {
                        pendingFocusDeviceId = "";
                    }
                    toast(getString(R.string.toast_network_error, msg));
                });
            }
        });
    }

    private void onMemberLongClick(final Member m) {
        if (m.deviceId.equals(myDeviceId)) {
            return;
        }
        if (!Prefs.get(this).isOwner()) {
            toast(getString(R.string.member_action_remove_owner_only));
            return;
        }
        showRemoveChoiceDialog(m);
    }

    /** 创建者移出成员：可选择是否加入黑名单 */
    private void showRemoveChoiceDialog(final Member m) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_remove_choice_title)
                .setMessage(getString(R.string.member_action_remove_confirm, m.name))
                .setPositiveButton(R.string.option_remove_ban, (d, w) -> removeMember(m, true))
                .setNeutralButton(R.string.option_remove_only, (d, w) -> removeMember(m, false))
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    private void removeMember(final Member m, final boolean ban) {
        Api.removeMember(Prefs.get(this).familyId(), myDeviceId, m.deviceId, ban, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    loadMembers();
                    toast(getString(ban ? R.string.toast_removed_banned : R.string.toast_removed, m.name));
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
            }
        });
    }

    /** 从界面移除某成员（成员被移出时，服务器广播后调用） */
    private void removeMemberFromUi(String deviceId) {
        members.remove(deviceId);
        Marker mk = markers.remove(deviceId);
        if (mk != null) {
            mk.remove();
        }
        iconCache.remove(deviceId);
        removeAccuracyCircle(deviceId);
        Polyline pl = trackLines.remove(deviceId);
        if (pl != null) {
            pl.remove();
        }
        removeTrackStart(deviceId);
        refreshAdapter();
    }

    /**
     * 本机被移出某个家庭：只把该家庭从本地列表移除；
     * 若还属于其它家庭就自动切过去，否则停止共享并引导重新创建/加入。
     */
    private void handleSelfRemoved() {
        Prefs prefs = Prefs.get(this);
        String removedFamilyId = prefs.familyId();
        String removedName = prefs.familyCodeOf(removedFamilyId);
        prefs.isOwner(false);
        prefs.removeFamily(removedFamilyId);
        loadFamilyIds();
        closeDetail();
        members.clear();
        for (String id : new ArrayList<>(markers.keySet())) {
            Marker mk = markers.remove(id);
            if (mk != null) {
                mk.remove();
            }
            removeAccuracyCircle(id);
        }
        iconCache.clear();
        for (Polyline p : trackLines.values()) {
            p.remove();
        }
        trackLines.clear();
        for (Marker mk : trackStartMarkers.values()) {
            if (mk != null) {
                mk.remove();
            }
        }
        trackStartMarkers.clear();
        clearTrackHighlight();
        refreshAdapter();
        toast(getString(R.string.toast_removed_from_family,
                removedName.isEmpty() ? getString(R.string.title_family_short) : removedName));
        if (prefs.familyId().isEmpty()) {
            // 已不属于任何家庭：停止共享并引导重新创建/加入
            prefs.shareEnabled(false);
            prefs.offlineMode(false);
            sendToService(AppConfig.ACTION_STOP);
            updateFamilyTitle();
            showFamilySetup();
        } else {
            // 还有其它家庭：自动切过去继续使用
            switchingFamily = false;
            switchToFamily(prefs.familyId());
        }
    }

    /** 定位我：直接定位并显示自己的位置（带结果反馈，失败会提示原因） */
    private void onLocateMe() {
        if (!hasFineLocation()) {
            requestPermissionsIfNeeded();
            return;
        }
        toast(getString(R.string.toast_locating));
        final LocationHelper helper = new LocationHelper(this);
        // 定位我：用快速模式（拿到首个有效定位即返回），避免等待过久
        helper.requestOnce(new LocationHelper.Callback() {
            @Override
            public void onResult(double lat, double lng, float accuracy, long time, String address) {
                runOnUiThread(() -> {
                    Prefs prefs = Prefs.get(MainActivity.this);
                    prefs.saveLastLocation(lat, lng, time);
                    Member m = new Member();
                    m.deviceId = myDeviceId;
                    m.name = prefs.deviceName();
                    m.lat = lat;
                    m.lng = lng;
                    m.accuracy = accuracy;
                    m.ts = time;
                    m.address = address;
                    m.battery = DeviceInfo.battery(MainActivity.this);
                    m.network = DeviceInfo.network(MainActivity.this);
                    m.online = !prefs.offlineMode();
                    m.isOwner = prefs.isOwner();
                    m.hasLocation = true;
                    applyMember(m, true); // 画自己的标记并跳转过去
                    // 已加入家庭且未下线则上报，让家人实时看到
                    String familyId = prefs.familyId();
                    if (!familyId.isEmpty() && !prefs.offlineMode()) {
                        Api.reportLocation(myDeviceId, familyId, lat, lng, accuracy, time,
                                m.battery, m.network, address,
                                new Api.Callback() {
                                    @Override
                                    public void onSuccess(String body) {
                                    }

                                    @Override
                                    public void onError(String msg) {
                                    }
                                });
                    }
                    toast(getString(R.string.toast_my_location_updated));
                    helper.release();
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    toast(getString(R.string.toast_location_failed) + "（" + msg + "）");
                    helper.release();
                });
            }
        }, true);
    }

    private void showFamilySetup() {
        FamilySetupDialog.show(this, new FamilySetupDialog.Listener() {
            @Override
            public void onDone(boolean created, String familyId, String code) {
                // 切换前捕获原家庭成员（不含自己），供切换后选择邀请
                List<Member> oldMembers = new ArrayList<>();
                for (Member m : members.values()) {
                    if (!m.deviceId.equals(myDeviceId)) {
                        oldMembers.add(m);
                    }
                }
                sendToService(AppConfig.ACTION_RECONNECT);
                entryRefreshArmed = true;
                loadMembers();
                if (created) {
                    toast(getString(R.string.toast_create_family_success, code));
                } else {
                    toast(getString(R.string.toast_join_family_success));
                }
                // 进入家庭后：按顺序引导 隐私/运行时权限/后台定位/电池优化，一个个弹
                startFirstRunGuidedFlow();
                // 切换家庭后：询问是否邀请原家庭成员
                if (!oldMembers.isEmpty()) {
                    showInvitePrompt(oldMembers, code);
                }
            }

            @Override
            public void onError(String msg) {
                toast(getString(R.string.toast_network_error, msg));
            }

            @Override
            public void onPending(String requestId) {
                // 加入家庭需群主同意：进入等待审批流
                toast(getString(R.string.toast_join_pending));
                startPendingJoinFlow(requestId);
            }
        }, familyScanToken, cameraPermLauncher, scanLauncher);
    }

    // ---------------- 家庭邀请（切换家庭后邀请原成员 / 被邀请加入） ----------------

    /** 切换家庭后询问是否邀请原家庭成员 */
    private void showInvitePrompt(final List<Member> oldMembers, final String code) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.invite_prompt_title)
                .setMessage(R.string.invite_prompt_message)
                .setPositiveButton(R.string.btn_ok, (d, w) -> showInviteSelectDialog(oldMembers, code))
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    /** 选择要邀请的原家庭成员（多选） */
    private void showInviteSelectDialog(final List<Member> oldMembers, final String code) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(12), dp(4), dp(12), dp(4));
        final List<Member> checked = new ArrayList<>();
        for (final Member m : oldMembers) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            final CheckBox cb = new CheckBox(this);
            cb.setText(m.name);
            cb.setTextSize(15f);
            cb.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            cb.setOnCheckedChangeListener((b, isChecked) -> {
                if (isChecked) {
                    if (!checked.contains(m)) {
                        checked.add(m);
                    }
                } else {
                    checked.remove(m);
                }
            });
            row.addView(cb);
            ll.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(ll);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.invite_select_title)
                .setView(sv)
                .setPositiveButton(R.string.invite_send, (d, w) -> sendInvites(checked, code))
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    /** 向选中的原成员发送加入新家庭的邀请 */
    private void sendInvites(List<Member> targets, String code) {
        if (targets.isEmpty()) {
            toast(getString(R.string.invite_none_selected));
            return;
        }
        final String familyId = Prefs.get(this).familyId();
        final String fromName = Prefs.get(this).deviceName();
        final int[] ok = {0};
        final int[] offline = {0};
        for (final Member m : targets) {
            Api.invite(familyId, myDeviceId, m.deviceId, code, fromName, new Api.Callback() {
                @Override
                public void onSuccess(String body) {
                    runOnUiThread(() -> {
                        try {
                            if ("offline".equals(new JSONObject(body).optString("status"))) {
                                offline[0]++;
                            } else {
                                ok[0]++;
                            }
                        } catch (Exception e) {
                            ok[0]++;
                        }
                    });
                }

                @Override
                public void onError(String msg) {
                    runOnUiThread(() -> offline[0]++);
                }
            });
        }
        // 延迟汇总提示（给请求留时间）
        mainHandler.postDelayed(() -> {
            if (ok[0] > 0) {
                toast(getString(R.string.toast_invite_sent, ok[0]));
            }
            if (offline[0] > 0) {
                toast(getString(R.string.toast_invite_offline, offline[0]));
            }
        }, 800);
    }

    /** 收到邀请：弹窗提示加入/忽略 */
    private void showInviteDialog(final String code, final String fromName) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.invite_dialog_title)
                .setMessage(getString(R.string.invite_dialog_message, fromName, code))
                .setPositiveButton(R.string.invite_accept, (d, w) -> acceptInvite(code))
                .setNegativeButton(R.string.invite_ignore, null)
                .show();
    }

    /** 接受加入邀请：加入家庭（需群主同意，若返回 pending 则进入等待审批流） */
    private void acceptInvite(final String code) {
        Api.joinFamily(code, myDeviceId, Prefs.get(this).deviceName(), new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    try {
                        JSONObject o = new JSONObject(body);
                        if ("pending".equals(o.optString("status"))) {
                            startPendingJoinFlow(o.optString("requestId"));
                            return;
                        }
                        Prefs.get(MainActivity.this).familyId(o.optString("familyId"));
                        Prefs.get(MainActivity.this).familyCode(code);
                        Prefs.get(MainActivity.this).isOwner(false);
                        Prefs.get(MainActivity.this).shareEnabled(true);
                        sendToService(AppConfig.ACTION_RECONNECT);
                        loadMembers();
                        toast(getString(R.string.toast_join_family_success));
                    } catch (Exception e) {
                        toast(getString(R.string.toast_network_error, "响应解析失败"));
                    }
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
            }
        });
    }

    // ---------------- 消息中心（入群申请 / 加入邀请） ----------------

    /** 收到加入邀请：存入消息中心 */
    private void addInviteMessage(String code, String fromName) {
        if (code == null || code.isEmpty()) {
            return;
        }
        // 去重：同一家庭码的未处理邀请只保留最新一条
        for (java.util.Iterator<MessageItem> it = messages.iterator(); it.hasNext(); ) {
            MessageItem m = it.next();
            if ("invite".equals(m.type) && code.equals(m.code)) {
                it.remove();
            }
        }
        messages.add(new MessageItem("invite", "", fromName == null ? "家人" : fromName, "", code));
    }

    /** 收到入群申请：存入消息中心（仅群主有审批按钮）。
     *  已有同 requestId 的消息时直接忽略：服务器会在广播与「拉取待审批列表」两条路径上重复下发同一条申请，
     *  重复添加会出现两条一样的申请（点两次、第二次必定失败）。 */
    private void addJoinRequestMessage(String requestId, String deviceId, String name) {
        if (requestId == null || requestId.isEmpty() || deviceId == null || deviceId.isEmpty()) {
            return;
        }
        for (MessageItem m : messages) {
            if ("joinRequest".equals(m.type) && requestId.equals(m.requestId)) {
                return;
            }
        }
        messages.add(new MessageItem("joinRequest", deviceId, name == null ? "家人" : name, requestId, ""));
    }

    /** 打开消息中心：显示入群申请 + 加入邀请；群主审批入群申请 */
    private void showMessageListDialog() {
        // 群主先拉取服务器上的待审批申请，合并到本地消息中（防止离线时漏掉）
        if (Prefs.get(this).isOwner() && !Prefs.get(this).familyId().isEmpty()) {
            Api.listJoinRequests(Prefs.get(this).familyId(), myDeviceId, new Api.Callback() {
                @Override
                public void onSuccess(String body) {
                    runOnUiThread(() -> {
                        try {
                            JSONArray arr = new JSONArray(body);
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.getJSONObject(i);
                                addJoinRequestMessage(o.optString("requestId"),
                                        o.optString("deviceId"), o.optString("name"));
                            }
                        } catch (Exception ignored) {
                        }
                        showMessageListDialogInner();
                    });
                }

                @Override
                public void onError(String msg) {
                    runOnUiThread(() -> showMessageListDialogInner());
                }
            });
        } else {
            showMessageListDialogInner();
        }
    }

    private void showMessageListDialogInner() {
        if (!safeUi()) {
            return;
        }
        boolean isOwner = Prefs.get(this).isOwner();
        if (messages.isEmpty()) {
            toast(getString(R.string.messages_empty));
            return;
        }
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(12), dp(4), dp(12), dp(4));
        for (final MessageItem item : new ArrayList<>(messages)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            TextView msg = new TextView(this);
            msg.setTextSize(14f);
            msg.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            if ("joinRequest".equals(item.type)) {
                msg.setText(getString(R.string.join_request_message, item.name));
            } else {
                msg.setText(getString(R.string.invite_dialog_message, item.name, item.code));
            }
            row.addView(msg, new LinearLayout.LayoutParams(-1, -2, 1f));

            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.END);
            if ("joinRequest".equals(item.type)) {
                if (isOwner) {
                    Button approve = new Button(this);
                    approve.setText(R.string.join_approve);
                    approve.setTextSize(13f);
                    approve.setAllCaps(false);
                    approve.setOnClickListener(v -> handleJoinRequest(item, true));
                    btnRow.addView(approve);
                    Button reject = new Button(this);
                    reject.setText(R.string.join_reject);
                    reject.setTextSize(13f);
                    reject.setAllCaps(false);
                    reject.setOnClickListener(v -> handleJoinRequest(item, false));
                    btnRow.addView(reject);
                } else {
                    TextView hint = new TextView(this);
                    hint.setText(R.string.toast_join_pending);
                    hint.setTextSize(12f);
                    hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                    btnRow.addView(hint);
                }
            } else {
                Button join = new Button(this);
                join.setText(R.string.invite_accept);
                join.setTextSize(13f);
                join.setAllCaps(false);
                join.setOnClickListener(v -> {
                    messages.remove(item);
                    acceptInvite(item.code);
                });
                btnRow.addView(join);
                Button ignore = new Button(this);
                ignore.setText(R.string.invite_ignore);
                ignore.setTextSize(13f);
                ignore.setAllCaps(false);
                ignore.setOnClickListener(v -> {
                    messages.remove(item);
                    this.refreshMessagesBadge();
                    dismissMessageDialog();
                });
                btnRow.addView(ignore);
            }
            row.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));
            ll.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(ll);
        messageDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.messages_title)
                .setView(sv)
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    /** 群主处理加入申请 */
    private void handleJoinRequest(final MessageItem item, final boolean approve) {
        String familyId = Prefs.get(this).familyId();
        if (familyId.isEmpty()) {
            toast(getString(R.string.toast_need_family_first));
            return;
        }
        Api.handleJoinRequest(familyId, myDeviceId, item.requestId, approve, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    messages.remove(item);
                    toast(getString(approve ? R.string.toast_join_accept_success : R.string.toast_join_reject_success,
                            item.name));
                    dismissMessageDialog();
                    refreshMessagesBadge();
                    if (approve) {
                        loadMembers(); // 刷新成员列表
                    }
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
            }
        });
    }

    private void dismissMessageDialog() {
        if (messageDialog != null && messageDialog.isShowing()) {
            messageDialog.dismiss();
        }
        messageDialog = null;
    }

    private void refreshMessagesBadge() {
        // 消息菜单项上的数量提示（占位，若无未读则无角标）
    }

    // ---------------- 反馈 / Bug 上报 ----------------

    /** 弹出反馈输入框，提交后写服务器 bugs.json */
    private void showBugDialog() {
        final EditText et = new EditText(this);
        et.setHint(getString(R.string.bug_dialog_hint));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(3);
        et.setTextSize(14f);
        et.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        FrameLayout fl = new FrameLayout(this);
        fl.setPadding(dp(20), dp(12), dp(20), dp(4));
        fl.addView(et);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bug_dialog_title)
                .setView(fl)
                .setPositiveButton(R.string.bug_submit, (d, w) -> submitBug(et.getText().toString().trim()))
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    private void submitBug(String content) {
        if (content.isEmpty()) {
            toast(getString(R.string.bug_empty));
            return;
        }
        Api.reportBug(myDeviceId, Prefs.get(this).deviceName(), content, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> toast(getString(R.string.bug_sent)));
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
            }
        });
    }

    /** 启动/回到前台时，查询自己提交的 Bug 是否被开发者标记完成，若有则主动告知 */
    private void checkBugResolved() {
        Api.listBugs(new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONArray arr = new JSONArray(body);
                    java.util.Set<String> notified = Prefs.get(MainActivity.this).bugNotifiedIds();
                    java.util.List<String> newly = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        if (myDeviceId.equals(o.optString("deviceId"))
                                && o.optBoolean("resolved", false)) {
                            String id = o.optString("id", "");
                            if (!id.isEmpty() && !notified.contains(id)) {
                                newly.add(id);
                            }
                        }
                    }
                    if (!newly.isEmpty()) {
                        for (String id : newly) {
                            Prefs.get(MainActivity.this).addBugNotified(id);
                        }
                        runOnUiThread(() -> toast(getString(R.string.bug_resolved_notify)));
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onError(String msg) {
            }
        });
    }

    // ---------------- 加入家庭：等待群主同意 ----------------

    /** 提交加入申请后进入等待审批状态：持久化申请，上线时自动检查，群主同意后才真正加入 */
    private void startPendingJoinFlow(final String requestId) {
        // 持久化待审批申请：关掉弹窗/重开 App 后，上线时仍会继续检查审批结果
        Prefs.get(this).pendingJoinRequestId(requestId);
        final AlertDialog wait = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.join_wait_title)
                .setMessage(getString(R.string.toast_join_pending))
                .setCancelable(true)
                .setNegativeButton(R.string.btn_back, null)
                .create();
        // 关掉弹窗不清除申请：只要还没被处理，上线时仍会检查是否已被同意
        wait.setOnDismissListener(d -> {
            if (!pendingJoinRequestId.isEmpty()) {
                toast(getString(R.string.toast_join_still_pending));
            }
        });
        wait.show();

        pendingJoinRequestId = requestId;
        pendingJoinWaitDialog[0] = wait;
        pendingJoinAttempts = 0;
        mainHandler.removeCallbacks(pendingJoinRunnable);
        mainHandler.post(pendingJoinRunnable);
    }

    /** 重开/回到前台（上线）时：若还有未处理的入群申请且尚未加入家庭，则恢复审批检查 */
    private void resumePendingJoinIfAny() {
        if (!pendingJoinRequestId.isEmpty()) {
            return;
        }
        String saved = Prefs.get(this).pendingJoinRequestId();
        if (!saved.isEmpty() && Prefs.get(this).familyId().isEmpty()) {
            pendingJoinRequestId = saved;
            pendingJoinAttempts = 0;
            mainHandler.removeCallbacks(pendingJoinRunnable);
            mainHandler.post(pendingJoinRunnable);
        }
    }

    private final AlertDialog[] pendingJoinWaitDialog = {null};
    private String pendingJoinRequestId = "";
    private int pendingJoinAttempts = 0;

    private final Runnable pendingJoinRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingJoinRequestId.isEmpty()) {
                return;
            }
            if (pendingJoinAttempts >= 100) {
                // 长时间未通过：停止轮询，交由用户稍后到消息中心处理
                stopPendingJoinFlow(R.string.toast_join_waiting);
                return;
            }
            pendingJoinAttempts++;
            Api.joinStatus(pendingJoinRequestId, myDeviceId, new Api.Callback() {
                @Override
                public void onSuccess(String body) {
                    runOnUiThread(() -> {
                        try {
                            JSONObject o = new JSONObject(body);
                            String status = o.optString("status");
                            if ("approved".equals(status)) {
                                String familyId = o.optString("familyId");
                                String code = o.optString("code");
                                Prefs p = Prefs.get(MainActivity.this);
                                // 加入新家庭不退出原有家庭：记入家庭列表并切换为当前家庭
                                p.addFamily(familyId, code);
                                p.isOwner(false);
                                p.shareEnabled(true);
                                loadFamilyIds();
                                sendToService(AppConfig.ACTION_RECONNECT);
                                entryRefreshArmed = true;
                                loadMembers();
                                pendingJoinRequestId = "";
                                pendingJoinAttempts = 0;
                                Prefs.get(MainActivity.this).pendingJoinRequestId("");
                                if (pendingJoinWaitDialog[0] != null) {
                                    pendingJoinWaitDialog[0].dismiss();
                                    pendingJoinWaitDialog[0] = null;
                                }
                                toast(getString(R.string.toast_join_approved));
                            } else if ("rejected".equals(status)) {
                                pendingJoinRequestId = "";
                                pendingJoinAttempts = 0;
                                Prefs.get(MainActivity.this).pendingJoinRequestId("");
                                if (pendingJoinWaitDialog[0] != null) {
                                    pendingJoinWaitDialog[0].dismiss();
                                    pendingJoinWaitDialog[0] = null;
                                }
                                toast(getString(R.string.toast_join_rejected));
                            } else {
                                // 仍在等待：继续轮询
                                mainHandler.postDelayed(pendingJoinRunnable, 3000);
                            }
                        } catch (Exception e) {
                            mainHandler.postDelayed(pendingJoinRunnable, 3000);
                        }
                    });
                }

                @Override
                public void onError(String msg) {
                    if (msg != null && msg.contains("404")) {
                        // 申请已失效（服务器可能重启）：停止轮询并提示
                        runOnUiThread(() -> stopPendingJoinFlow(R.string.toast_join_expired));
                        return;
                    }
                    mainHandler.postDelayed(pendingJoinRunnable, 3000);
                }
            });
        }
    };

    /** 停止等待审批轮询并给出提示 */
    private void stopPendingJoinFlow(int toastRes) {
        pendingJoinRequestId = "";
        pendingJoinAttempts = 0;
        Prefs.get(this).pendingJoinRequestId("");
        if (pendingJoinWaitDialog[0] != null) {
            pendingJoinWaitDialog[0].dismiss();
            pendingJoinWaitDialog[0] = null;
        }
        toast(getString(toastRes));
    }

    /** 请求类操作（点击成员 / 一键刷新）后延迟几秒静默重拉一次，确保拿到最新位置（不移动视野） */
    private void scheduleMembersRefresh() {
        mainHandler.removeCallbacks(membersRefreshRunnable);
        mainHandler.postDelayed(membersRefreshRunnable, 4000);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable membersRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            String familyId = Prefs.get(MainActivity.this).familyId();
            if (familyId.isEmpty()) {
                return;
            }
            final int gen = familyGeneration;
            Api.listMembers(familyId, new Api.Callback() {
                @Override
                public void onSuccess(String body) {
                    runOnUiThread(() -> {
                        if (!isCurrentFamily(familyId, gen)) {
                            return; // 已切换家庭：丢弃旧家庭的响应
                        }
                        try {
                            applyMemberList(Member.listFromJson(body), false);
                        } catch (Exception ignored) {
                        }
                    });
                }

                @Override
                public void onError(String msg) {
                    // 静默
                }
            });
        }
    };

    // ---------------- 服务器连接状态（health 轮询，避免卡在“连接中”） ----------------

    private final Handler healthHandler = new Handler(Looper.getMainLooper());
    /** 连续健康检查失败计数：需连续多次失败才判定离线，避免单次网络抖动导致“已连接/服务器离线”来回跳 */
    private int healthFailures;
    private final Runnable healthPoller = new Runnable() {
        @Override
        public void run() {
            Api.health(new Api.Callback() {
                @Override
                public void onSuccess(String body) {
                    healthFailures = 0;
                    runOnUiThread(() -> updateStatusUi(AppConfig.STATUS_ONLINE));
                }

                @Override
                public void onError(String msg) {
                    healthFailures++;
                    runOnUiThread(() -> {
                        // 需连续 3 次失败才判定离线，避免偶发超时误报离线导致状态来回跳
                        if (healthFailures >= 3) {
                            updateStatusUi(AppConfig.STATUS_OFFLINE);
                        }
                    });
                }
            });
            healthHandler.postDelayed(this, 20000);
        }
    };

    private void startHealthPolling() {
        healthHandler.removeCallbacks(healthPoller);
        healthHandler.post(healthPoller);
    }

    private void stopHealthPolling() {
        healthHandler.removeCallbacks(healthPoller);
    }

    // ---------------- 周期刷新成员列表（保底，每 30 秒，保证在线/头像/群主等状态及时） ----------------

    private final Runnable periodicRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (Prefs.get(MainActivity.this).familyId().isEmpty()) {
                mainHandler.postDelayed(this, AppConfig.UI_REFRESH_INTERVAL_MS);
                return;
            }
            final String familyId = Prefs.get(MainActivity.this).familyId();
            final int gen = familyGeneration;
            Api.listMembers(familyId, new Api.Callback() {
                @Override
                public void onSuccess(String body) {
                    runOnUiThread(() -> {
                        if (!isCurrentFamily(familyId, gen)) {
                            return; // 已切换家庭：丢弃旧家庭的响应
                        }
                        try {
                            applyMemberList(Member.listFromJson(body), false); // 不移动视野
                        } catch (Exception ignored) {
                        }
                    });
                }

                @Override
                public void onError(String msg) {
                    // 静默，等下次
                }
            });
            mainHandler.postDelayed(this, AppConfig.UI_REFRESH_INTERVAL_MS);
        }
    };

    private void startPeriodicRefresh() {
        mainHandler.removeCallbacks(periodicRefreshRunnable);
        mainHandler.postDelayed(periodicRefreshRunnable, AppConfig.UI_REFRESH_INTERVAL_MS);
    }

    private void stopPeriodicRefresh() {
        mainHandler.removeCallbacks(periodicRefreshRunnable);
    }
    // ---------------- 首启引导（隐私/运行时权限/后台定位/电池优化 按顺序一个个弹） ----------------

    private final java.util.ArrayDeque<Runnable> firstRunQueue = new java.util.ArrayDeque<>();
    /** 引导流程是否在进行中（防止重复进入） */
    private boolean firstRunGuided;
    /** 当前步骤是否已开始但尚未完成（防止重复推进） */
    private boolean firstRunStepPending;

    /** 启动首启引导流程，把需要询问的项排成队列，按顺序逐个弹出，避免一次性弹太多权限弹窗 */
    private void startFirstRunGuidedFlow() {
        if (firstRunGuided) {
            return;
        }
        firstRunGuided = true;
        firstRunQueue.clear();
        firstRunQueue.add(this::maybeShowPrivacyDialog);
        firstRunQueue.add(this::requestPermissionsIfNeeded);
        firstRunQueue.add(this::maybeShowBackgroundLocation);
        firstRunQueue.add(this::requestBatteryOptimizationOnce);
        advanceFirstRunQueue();
    }

    /** 执行下一个引导步骤；若上一步已异步完成（firstRunStepPending=false）则继续 */
    private void advanceFirstRunQueue() {
        if (!firstRunGuided || firstRunStepPending) {
            return;
        }
        Runnable next = firstRunQueue.poll();
        if (next == null) {
            firstRunGuided = false;
            return;
        }
        firstRunStepPending = true;
        next.run();
    }

    /** 当前引导步骤完成：允许推进到下一个（非引导流程内的调用为 no-op） */
    private void firstRunStepDone() {
        if (!firstRunGuided) {
            return;
        }
        firstRunStepPending = false;
        advanceFirstRunQueue();
    }

    private void maybeShowPrivacyDialog() {
        Prefs prefs = Prefs.get(this);
        if (prefs.privacyPrompted()) {
            firstRunStepDone();
            return;
        }
        prefs.privacyPrompted(true);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_privacy_title)
                .setMessage(R.string.dialog_privacy_message)
                .setPositiveButton(R.string.btn_privacy_agree, (d, w) -> firstRunStepDone())
                .setCancelable(false)
                .show();
    }

    /** 底部「家庭码」按钮：查看并复制当前家庭的家庭码 */
    private void showCodeDialog() {
        Prefs prefs = Prefs.get(this);
        // 多家庭：必须取「当前家庭自己的家庭码」（family_codes 映射），
        // 不能用旧的单家庭 family_code 字段——切换家庭后它可能是空的，会误报"尚未加入家庭"
        String code = prefs.familyCodeOf(prefs.familyId());
        if (prefs.familyId().isEmpty() || code.isEmpty()) {
            toast(getString(R.string.toast_no_family_code));
            showFamilySetup();
            return;
        }
        View v = getLayoutInflater().inflate(R.layout.dialog_family_code, null);
        TextView tvCode = v.findViewById(R.id.tvCode);
        tvCode.setText(code);
        // 家庭码下方显示二维码：家人可用「扫码加入」直接识别
        ImageView ivQr = v.findViewById(R.id.ivQrCode);
        Bitmap qr = QrCode.generate(code, dp(200));
        if (qr != null) {
            ivQr.setImageBitmap(qr);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_code_title)
                .setView(v)
                .setPositiveButton(R.string.btn_copy, (d, w) -> copyCode(code))
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    private void copyCode(String code) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("family_code", code));
            toast(getString(R.string.toast_code_copied));
        }
    }

    // ---------------- 服务 ----------------

    private void startServiceIfNeeded() {
        if (!Prefs.get(this).shareEnabled() || Prefs.get(this).familyId().isEmpty()) {
            return;
        }
        if (!hasFineLocation()) {
            return;
        }
        sendToService(AppConfig.ACTION_START);
    }

    // ---------------- 服务器切换 ----------------

    /** 切换服务器对话框：官方(默认，不展示地址) + 自定义(展示地址)；可添加、可选择。默认官方，无确认弹窗。 */
    private void showServerDialog() {
        final Prefs prefs = Prefs.get(this);
        final java.util.List<String[]> servers = prefs.customServers();
        final int active = prefs.activeServerIndex();
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(20), dp(8), dp(20), dp(6));

        ll.addView(serverRow(getString(R.string.server_official), active < 0, () -> selectServer(-1), null));
        for (int i = 0; i < servers.size(); i++) {
            String[] s = servers.get(i);
            final int idx = i;
            ll.addView(serverRow(AppConfig.buildServerUrl(s[0], s[1], s[2]), active == i, () -> selectServer(idx), () -> deleteServer(idx)));
        }

        TextView addBtn = new TextView(this);
        addBtn.setText(getString(R.string.server_add));
        addBtn.setTextColor(ContextCompat.getColor(this, R.color.primary));
        addBtn.setTextSize(14f);
        addBtn.setPadding(0, dp(16), 0, dp(6));
        addBtn.setOnClickListener(v -> {
            dismissServerDialog();
            showAddServerDialog();
        });
        ll.addView(addBtn);

        serverDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_switch_server)
                .setView(ll)
                .setNegativeButton(R.string.btn_back, null)
                .create();
        serverDialog.show();
    }

    /** 服务器列表行：label + 选中标记 + 可选删除按钮 */
    private View serverRow(String label, boolean selected, Runnable onTap, Runnable onDelete) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) {
            TextView mark = new TextView(this);
            mark.setText("✓");
            mark.setTextSize(16f);
            mark.setTextColor(ContextCompat.getColor(this, R.color.primary));
            row.addView(mark);
        }
        if (onDelete != null) {
            TextView del = new TextView(this);
            del.setText(getString(R.string.server_delete));
            del.setTextSize(14f);
            del.setTextColor(0xFFE53935); // 红色
            del.setPadding(dp(14), 0, 0, 0);
            del.setOnClickListener(v -> onDelete.run());
            row.addView(del);
        }
        row.setOnClickListener(v -> {
            if (onTap != null) {
                onTap.run();
            }
        });
        return row;
    }

    /** 选择服务器：-1 = 官方；>=0 = customServers 下标。应用后重连服务并刷新。 */
    private void selectServer(int index) {
        Prefs p = Prefs.get(this);
        p.activeServerIndex(index);
        String url;
        if (index < 0) {
            url = AppConfig.OFFICIAL_URL;
        } else {
            java.util.List<String[]> list = p.customServers();
            if (index < list.size()) {
                String[] s = list.get(index);
                url = AppConfig.buildServerUrl(s[0], s[1], s[2]);
            } else {
                url = AppConfig.OFFICIAL_URL;
                p.activeServerIndex(-1);
            }
        }
        AppConfig.applyServer(url);
        dismissServerDialog();
        toast(getString(R.string.toast_server_switched));
        sendToService(AppConfig.ACTION_RECONNECT);
        serverSwitchAt = System.currentTimeMillis();
        loadMembers();
    }

    private void dismissServerDialog() {
        if (serverDialog != null && serverDialog.isShowing()) {
            serverDialog.dismiss();
            serverDialog = null;
        }
    }

    /** 删除自定义服务器（先确认）；若删除的是当前选中项则自动切回官方 */
    private void deleteServer(final int index) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.server_delete)
                .setMessage(R.string.server_delete_confirm)
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    d.dismiss();
                    doDeleteServer(index);
                })
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    private void doDeleteServer(int index) {
        Prefs p = Prefs.get(this);
        java.util.List<String[]> list = new java.util.ArrayList<>(p.customServers());
        if (index < 0 || index >= list.size()) {
            return;
        }
        list.remove(index);
        p.customServers(list);
        int active = p.activeServerIndex();
        if (active == index) {
            p.activeServerIndex(-1); // 回到官方
            AppConfig.applyServer(AppConfig.OFFICIAL_URL);
            toast(getString(R.string.toast_server_switched));
            sendToService(AppConfig.ACTION_RECONNECT);
            serverSwitchAt = System.currentTimeMillis();
            loadMembers();
        } else if (active > index) {
            p.activeServerIndex(active - 1); // 下标前移
        }
        dismissServerDialog();
        showServerDialog(); // 刷新列表
    }

    /** 添加服务器：域名或IP + 可选端口 + 协议(http/https) */
    private void showAddServerDialog() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.server_addr_label));
        hint.setTextSize(13f);
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        ll.addView(hint);

        EditText etHost = new EditText(this);
        etHost.setHint(getString(R.string.server_addr_hint));
        etHost.setSingleLine(true);
        etHost.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ll.addView(etHost);

        EditText etPort = new EditText(this);
        etPort.setHint(getString(R.string.server_port_hint));
        etPort.setSingleLine(true);
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        ll.addView(etPort);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbHttp = new RadioButton(this);
        rbHttp.setText(getString(R.string.server_scheme_http));
        RadioButton rbHttps = new RadioButton(this);
        rbHttps.setText(getString(R.string.server_scheme_https));
        rbHttps.setChecked(true);
        rg.addView(rbHttp);
        rg.addView(rbHttps);
        ll.addView(rg);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.server_add)
                .setView(ll)
                .setPositiveButton(R.string.btn_ok, null)
                .setNegativeButton(R.string.btn_back, (d, w) -> showServerDialog())
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            String host = etHost.getText().toString().trim();
            if (host.isEmpty()) {
                toast(getString(R.string.hint_server_host));
                return;
            }
            String port = etPort.getText().toString().trim();
            if (!port.isEmpty()) {
                try {
                    Integer.parseInt(port);
                } catch (Exception e) {
                    toast(getString(R.string.hint_server_port));
                    return;
                }
            }
            String scheme = rbHttps.isChecked() ? "https" : "http";
            dialog.dismiss();
            addServer(scheme, host, port);
        }));
        dialog.show();
    }

    /** 添加并选中新服务器 */
    private void addServer(String scheme, String host, String port) {
        Prefs p = Prefs.get(this);
        java.util.List<String[]> list = new java.util.ArrayList<>(p.customServers());
        list.add(new String[]{scheme, host, port});
        p.customServers(list);
        int idx = list.size() - 1;
        p.activeServerIndex(idx);
        AppConfig.applyServer(AppConfig.buildServerUrl(scheme, host, port));
        toast(getString(R.string.toast_server_switched));
        sendToService(AppConfig.ACTION_RECONNECT);
        serverSwitchAt = System.currentTimeMillis();
        loadMembers();
        showServerDialog(); // 刷新列表
    }

    private void sendToService(String action) {
        Intent i = new Intent(this, LocationReportService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    // ---------------- 权限与保活 ----------------

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsIfNeeded() {
        List<String> need = new ArrayList<>();
        if (!hasFineLocation()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) {
            // 无需申请：直接推进引导流程（外部调用如「定位我」时 firstRunStepDone 为 no-op）
            firstRunStepDone();
            return;
        }
        ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            if (hasFineLocation()) {
                startServiceIfNeeded();
            } else {
                toast(getString(R.string.toast_need_location_permission));
            }
            // 运行时权限步骤完成；后台定位在引导队列的下一步处理，不再在此弹，避免一次弹太多
            firstRunStepDone();
        } else if (requestCode == REQ_PERMISSION_BG) {
            if (hasFineLocation()) {
                startServiceIfNeeded();
            }
            firstRunStepDone();
        }
    }

    /** 引导步骤：按需请求「后台定位」权限（仅 Android 10+ 且未授予时），完成后推进到下一步 */
    private void maybeShowBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 30 || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            firstRunStepDone();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_permission_title)
                .setMessage(R.string.dialog_permission_message)
                .setPositiveButton("去开启", (d, w) ->
                        ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                REQ_PERMISSION_BG))
                .setNegativeButton(R.string.btn_back, (d, w) -> firstRunStepDone())
                .show();
    }

    private void requestBatteryOptimizationOnce() {
        if (Build.VERSION.SDK_INT < 23) {
            firstRunStepDone();
            return;
        }
        Prefs prefs = Prefs.get(this);
        if (prefs.batteryPrompted()) {
            firstRunStepDone();
            return;
        }
        prefs.batteryPrompted(true);
        // 首次引导也算一次提醒：避免刚引导完又被周期提醒重复打扰
        prefs.keepAlivePromptAt(System.currentTimeMillis());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            // 主动弹出系统「忽略电池优化」请求对话框（而非仅展示权限设置大对话框）
            requestIgnoreBattery();
        }
        firstRunStepDone();
    }

    /**
     * 保活提醒：若本应用不在电池优化白名单，每隔 3 天温和提醒一次去开启「自启动 + 忽略电池优化」。
     * （微信级保活依赖厂商系统级白名单，普通应用只能引导用户手动加入省电白名单，
     *   见 AppConfig 的看门狗/闹钟/JobScheduler 多路兜底。）
     */
    private void maybeRemindKeepAlive() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        Prefs prefs = Prefs.get(this);
        if (prefs.familyId().isEmpty()) {
            return; // 尚未加入家庭，无需保活
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return; // 已在白名单，不打扰
        }
        long now = System.currentTimeMillis();
        if (now - prefs.keepAlivePromptAt() < 3L * 24 * 60 * 60 * 1000) {
            return; // 3 天内已提醒过
        }
        prefs.keepAlivePromptAt(now);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.keepalive_title)
                .setMessage(R.string.keepalive_message)
                .setPositiveButton(R.string.keepalive_go, (d, w) -> showPermissionsDialog())
                .setNegativeButton(R.string.keepalive_later, null)
                .show();
    }

    /** 权限设置：集中展示并跳转各项权限（定位/通知/电池优化/自启动/安装未知应用） */
    private void showPermissionsDialog() {
        permStatusViews.clear();
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(16), dp(4), dp(16), dp(4));

        ll.addView(permRow(getString(R.string.perm_location),
                permStatusText(0), this::openAppDetails));
        ll.addView(permRow(getString(R.string.perm_notification),
                permStatusText(1), this::openAppDetails));
        ll.addView(permRow(getString(R.string.perm_battery),
                permStatusText(2), this::requestIgnoreBattery));
        ll.addView(permRow(getString(R.string.perm_autostart),
                getString(R.string.perm_autostart_hint), this::openAutoStartSettings));
        ll.addView(permRow(getString(R.string.perm_install),
                permStatusText(4), this::openInstallSettings));

        // 允许他人响铃（开关，非跳转项）
        LinearLayout ringRow = new LinearLayout(this);
        ringRow.setOrientation(LinearLayout.HORIZONTAL);
        ringRow.setGravity(Gravity.CENTER_VERTICAL);
        ringRow.setPadding(0, dp(8), 0, dp(8));
        TextView ringTv = new TextView(this);
        ringTv.setText(R.string.perm_ring);
        ringTv.setTextSize(14f);
        ringTv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        ringRow.addView(ringTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        SwitchCompat ringSwitch = new SwitchCompat(this);
        ringSwitch.setChecked(Prefs.get(this).ringEnabled());
        ringSwitch.setOnCheckedChangeListener((b, checked) -> Prefs.get(this).ringEnabled(checked));
        ringRow.addView(ringSwitch);
        ll.addView(ringRow);

        // 响铃时长（家人让本机响铃时持续多久，可自定义）
        LinearLayout ringDurRow = new LinearLayout(this);
        ringDurRow.setOrientation(LinearLayout.HORIZONTAL);
        ringDurRow.setGravity(Gravity.CENTER_VERTICAL);
        ringDurRow.setPadding(0, dp(8), 0, dp(8));
        TextView ringDurTv = new TextView(this);
        ringDurTv.setText(R.string.perm_ring_duration);
        ringDurTv.setTextSize(14f);
        ringDurTv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        ringDurRow.addView(ringDurTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView ringDurValue = new TextView(this);
        ringDurValue.setText(ringDurationText(Prefs.get(this).ringDurationMs()));
        ringDurValue.setTextSize(12f);
        ringDurValue.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        ringDurRow.addView(ringDurValue);
        ringDurRow.setOnClickListener(v -> showRingDurationDialog(ringDurValue));
        ll.addView(ringDurRow);

        permDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.btn_battery)
                .setView(ll)
                .setNegativeButton(R.string.btn_back, null)
                .create();
        permDialog.show();
    }

    /** 按行号实时计算权限状态文字（与 showPermissionsDialog 行序一致：0定位 1通知 2电池 3自启动 4安装） */
    private String permStatusText(int index) {
        switch (index) {
            case 0: {
                boolean locOk = hasFineLocation() && (Build.VERSION.SDK_INT < 30
                        || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED);
                return getString(locOk ? R.string.perm_status_allowed : R.string.perm_status_off);
            }
            case 1:
                return getString(NotificationManagerCompat.from(this).areNotificationsEnabled()
                        ? R.string.perm_status_allowed : R.string.perm_status_off);
            case 2: {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                boolean batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                return getString(batteryOk ? R.string.perm_status_allowed : R.string.perm_status_off);
            }
            case 4: {
                boolean installOk = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
                return getString(installOk ? R.string.perm_status_allowed : R.string.perm_status_off);
            }
            default:
                return getString(R.string.perm_autostart_hint);
        }
    }

    /** 从系统设置返回后刷新权限对话框内各状态文字 */
    private void refreshPermissionStatus() {
        if (permDialog == null || !permDialog.isShowing() || permStatusViews.isEmpty()) {
            return;
        }
        int i = 0;
        for (TextView tv : permStatusViews) {
            String text = (i == 3) ? getString(R.string.perm_autostart_hint) : permStatusText(i);
            tv.setText(text);
            i++;
        }
    }

    private View permRow(String name, String status, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rippleBackground());
        row.setPadding(0, dp(12), 0, dp(12));
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextSize(14f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView statusTv = new TextView(this);
        statusTv.setText(status);
        statusTv.setTextSize(12f);
        statusTv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        row.addView(statusTv);
        permStatusViews.add(statusTv); // 供从系统设置返回后刷新状态
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** 响铃时长展示文本 */
    private String ringDurationText(long ms) {
        if (ms <= 0) {
            return getString(R.string.ring_duration_default);
        }
        return (ms / 1000) + getString(R.string.ring_duration_unit);
    }

    /** 选择响铃时长（预设 + 自定义） */
    private void showRingDurationDialog(final TextView valueTv) {
        final long[] msValues = {10000, 30000, 60000, 90000, 120000};
        String[] opts = new String[msValues.length + 1];
        for (int i = 0; i < msValues.length; i++) {
            opts[i] = (msValues[i] / 1000) + getString(R.string.ring_duration_unit);
        }
        opts[msValues.length] = getString(R.string.btn_custom);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_ring_duration)
                .setItems(opts, (d, which) -> {
                    if (which < msValues.length) {
                        Prefs.get(this).ringDurationMs(msValues[which]);
                        valueTv.setText(ringDurationText(msValues[which]));
                    } else {
                        showRingDurationCustomDialog(valueTv);
                    }
                    d.dismiss();
                })
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    /** 自定义响铃时长（输入秒数） */
    private void showRingDurationCustomDialog(final TextView valueTv) {
        LinearLayout ll = new LinearLayout(this);
        ll.setPadding(dp(20), dp(8), dp(20), dp(8));
        final EditText et = new EditText(this);
        et.setHint(getString(R.string.hint_ring_duration_seconds));
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        ll.addView(et);
        AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_ring_duration)
                .setView(ll)
                .setPositiveButton(R.string.btn_ok, null)
                .setNegativeButton(R.string.btn_back, null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String s = et.getText().toString().trim();
            int sec;
            try {
                sec = Integer.parseInt(s);
            } catch (Exception e) {
                sec = 0;
            }
            if (sec <= 0 || sec > 300) {
                toast(getString(R.string.hint_ring_duration_invalid));
                return;
            }
            long ms = sec * 1000L;
            Prefs.get(this).ringDurationMs(ms);
            valueTv.setText(ringDurationText(ms));
            d.dismiss();
        }));
        d.show();
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    private void requestIgnoreBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    /** 自启动管理：先弹窗引导用户在系统设置顶部搜索「自启动」，再打开系统设置。
     *  各厂商自启动页的 Activity 名不稳定、易跳错，统一用系统设置 + 引导搜索更可靠。 */
    private void openAutoStartSettings() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_autostart)
                .setMessage(R.string.autostart_guide_message)
                .setPositiveButton(R.string.btn_go_settings, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception e) {
                        toast(getString(R.string.toast_open_url_failed));
                    }
                })
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    private void openInstallSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    /** 用系统浏览器打开一个网址 */
    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast(getString(R.string.toast_open_url_failed));
        }
    }

    /** 「关于」对话框：开发者 + 隐私政策 + 隐私权利 两个网址（可点开） */
    private void showAboutDialog() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(20), dp(12), dp(20), dp(6));
        ll.addView(aboutItem(getString(R.string.about_developer),
                getString(R.string.developer_name), null));
        ll.addView(aboutItem(getString(R.string.about_icp),
                getString(R.string.icp_number), null));
        ll.addView(aboutItem(getString(R.string.about_github),
                "github.com/yujianxi666/FamilyShare",
                "https://github.com/yujianxi666/FamilyShare"));
        ll.addView(aboutItem(getString(R.string.menu_privacy),
                AppConfig.SERVER_URL + "/privacy.html",
                AppConfig.SERVER_URL + "/privacy.html"));
        ll.addView(aboutItem(getString(R.string.menu_privacy_rights),
                AppConfig.SERVER_URL + "/rights.html",
                AppConfig.SERVER_URL + "/rights.html"));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_about)
                .setView(ll)
                .setNegativeButton(R.string.btn_ok, null)
                .show();
    }

    /** 「关于」信息行：左标签 + 右值；url 非空时右侧可点击打开 */
    private View aboutItem(String label, String value, final String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));
        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(14f);
        tvLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        row.addView(tvLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextSize(13f);
        tvValue.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        if (url != null) {
            tvValue.setTextColor(ContextCompat.getColor(this, R.color.primary));
            tvValue.setPaintFlags(tvValue.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            tvValue.setOnClickListener(v -> openUrl(url));
        }
        row.addView(tvValue);
        return row;
    }

    /** 右上角 ⋮：自绘圆角菜单（格子样式，与主面板风格统一） */
    private void showOverflowMenu(View anchor) {
        Prefs prefs = Prefs.get(this);
        java.util.List<Object[]> items = new ArrayList<>();
        // 消息中心（带未读数量角标）
        String msgTitle = getString(R.string.menu_messages)
                + (messages.isEmpty() ? "" : ("（" + messages.size() + "）"));
        items.add(new Object[]{R.drawable.ic_message, msgTitle, (Runnable) this::showMessageListDialog});
        items.add(new Object[]{R.drawable.ic_refresh, getString(R.string.menu_refresh), (Runnable) this::refreshEverything});
        items.add(new Object[]{R.drawable.ic_dialpad, getString(R.string.btn_code), (Runnable) this::showCodeDialog});
        items.add(new Object[]{R.drawable.ic_people, getString(R.string.btn_family_setup), (Runnable) this::switchFamilyFlow});
        items.add(new Object[]{R.drawable.ic_expand_more, getString(R.string.menu_switch_server), (Runnable) this::showServerDialog});
        items.add(new Object[]{R.drawable.ic_stat_location, getString(R.string.menu_avatar), (Runnable) this::pickAvatar});
        items.add(new Object[]{R.drawable.ic_battery, getString(R.string.btn_battery), (Runnable) this::showPermissionsDialog});
        items.add(new Object[]{R.drawable.ic_my_location,
                getString(prefs.offlineMode() ? R.string.menu_online : R.string.menu_offline), (Runnable) this::toggleOffline});
        if (prefs.isOwner()) {
            items.add(new Object[]{R.drawable.ic_stat_location, getString(R.string.menu_banlist), (Runnable) this::showBanListDialog});
            // 群主一键解散家庭
            items.add(new Object[]{R.drawable.ic_back, getString(R.string.menu_disband_family),
                    (Runnable) this::showDisbandFamilyDialog});
        }
        // 群主主动转让（家庭里有其他成员才显示）
        if (prefs.isOwner() && members.size() > 1) {
            items.add(new Object[]{R.drawable.ic_people, getString(R.string.menu_transfer_owner),
                    (Runnable) () -> showTransferOwnerDialog(false)});
        }
        items.add(new Object[]{R.drawable.ic_bug, getString(R.string.menu_report_bug), (Runnable) this::showBugDialog});
        items.add(new Object[]{R.drawable.ic_more_vert, getString(R.string.menu_update), (Runnable) () -> checkUpdate(false)});
        items.add(new Object[]{R.drawable.ic_people, getString(R.string.menu_about), (Runnable) this::showAboutDialog});

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(6), dp(8), dp(6));
        int i = 0;
        while (i < items.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // 每行最多 3 个按钮；同一行的按钮等分整行宽度（末行不满 3 个时自动加宽填满，不留空白）
            int cols = Math.min(3, items.size() - i);
            for (int c = 0; c < cols; c++) {
                Object[] it = items.get(i + c);
                row.addView(menuCard((int) it[0], (String) it[1], (Runnable) it[2]), cellLp(1f));
            }
            container.addView(row);
            i += cols;
        }
        overflowMenuDialog = new MaterialAlertDialogBuilder(this)
                .setView(container)
                .create();
        overflowMenuDialog.show();
    }

    /** 菜单格子：图标在上、文字在下，圆角卡片、可点击 */
    private View menuCard(int iconRes, String title, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(6), dp(12), dp(6), dp(12));
        card.setBackgroundResource(R.drawable.bg_menu_card);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            card.setForeground(rippleBackground());
        }

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(ContextCompat.getColor(this, R.color.primary));
        card.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(11f);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.setMargins(0, dp(6), 0, 0);
        card.addView(tv, tlp);

        card.setOnClickListener(v -> {
            dismissOverflowMenu();
            action.run();
        });
        return card;
    }

    /** 三列格子的通用布局参数：每格权重 1 均分宽度，留少量间距 */
    private LinearLayout.LayoutParams cellLp(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        int m = dp(3);
        lp.setMargins(m, dp(5), m, dp(5));
        return lp;
    }

    /** 菜单项点击后先关闭菜单再执行动作 */
    private void dismissOverflowMenu() {
        if (overflowMenuDialog != null && overflowMenuDialog.isShowing()) {
            overflowMenuDialog.dismiss();
        }
    }

    /**
     * 解析主题里的 selectableItemBackground 为可用背景。
     * 不能直接 setBackgroundResource(android.R.attr.selectableItemBackground)：
     * attr 不是 drawable 资源，部分设备（如部分华为/荣耀）会抛 Resources.NotFoundException 崩溃。
     */
    private android.graphics.drawable.Drawable rippleBackground() {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                && tv.resourceId != 0) {
            return getDrawable(tv.resourceId);
        }
        return null;
    }

    /** 黑名单管理（仅创建者）：查看已拉黑设备并解除拉黑 */
    private void showBanListDialog() {
        if (!safeUi()) {
            return;
        }
        Prefs prefs = Prefs.get(this);
        String familyId = prefs.familyId();
        if (familyId.isEmpty()) {
            toast(getString(R.string.toast_need_family_first));
            return;
        }
        if (!prefs.isOwner()) {
            toast(getString(R.string.banlist_owner_only));
            return;
        }
        Api.getBanned(familyId, myDeviceId, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    try {
                        JSONArray arr = new JSONArray(body);
                        if (arr.length() == 0) {
                            // 解除拉黑后可能为空：关掉旧对话框并提示
                            dismissBanDialog();
                            toast(getString(R.string.banlist_empty));
                            return;
                        }
                        // 重新展示前先关掉上一个黑名单对话框（避免新旧列表叠加）
                        dismissBanDialog();
                        LinearLayout ll = new LinearLayout(MainActivity.this);
                        ll.setOrientation(LinearLayout.VERTICAL);
                        ll.setPadding(dp(12), dp(4), dp(12), dp(4));
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            final String deviceId = o.optString("deviceId");
                            String name = o.optString("name", deviceId);
                            LinearLayout row = new LinearLayout(MainActivity.this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            row.setPadding(0, dp(8), 0, dp(8));
                            TextView tv = new TextView(MainActivity.this);
                            tv.setText(name + "（" + deviceId + "）");
                            tv.setTextSize(14f);
                            tv.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text_primary));
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                            row.addView(tv, lp);
                            Button btn = new Button(MainActivity.this);
                            btn.setText(R.string.btn_unban);
                            btn.setTextSize(12f);
                            btn.setAllCaps(false);
                            btn.setOnClickListener(v -> unban(familyId, deviceId));
                            row.addView(btn);
                            ll.addView(row);
                        }
                        ScrollView sv = new ScrollView(MainActivity.this);
                        sv.addView(ll);
                        banDialog = new MaterialAlertDialogBuilder(MainActivity.this)
                                .setTitle(R.string.banlist_title)
                                .setView(sv)
                                .setNegativeButton(R.string.btn_back, (d, w) -> {
                                    if (banDialog != null) {
                                        banDialog = null;
                                    }
                                })
                                .show();
                    } catch (Exception e) {
                        toast(getString(R.string.toast_network_error, "数据解析失败"));
                    }
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
            }
        });
    }

    /** 关闭当前黑名单对话框（解除拉黑后刷新用） */
    private void dismissBanDialog() {
        if (banDialog != null && banDialog.isShowing()) {
            banDialog.dismiss();
        }
        banDialog = null;
    }

    private void unban(String familyId, String deviceId) {
        Api.unbanMember(familyId, myDeviceId, deviceId, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    toast(getString(R.string.toast_unban));
                    showBanListDialog();
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
            }
        });
    }

    /** 下线模式开关：开启后可查看别人但不更新自己位置，对自己/别人都显示灰色离线 */
    private void toggleOffline() {
        Prefs prefs = Prefs.get(this);
        String familyId = prefs.familyId();
        if (familyId.isEmpty()) {
            toast(getString(R.string.toast_need_family_first));
            return;
        }
        final boolean now = !prefs.offlineMode();
        Api.setMemberOffline(familyId, myDeviceId, now, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    prefs.offlineMode(now);
                    // 重连 WS 携带下线参数，让服务器标记为离线
                    sendToService(AppConfig.ACTION_RECONNECT);
                    loadMembers();
                    toast(getString(now ? R.string.toast_offline_on : R.string.toast_offline_off));
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
            }
        });
    }

    // ---------------- 面板收缩（灰色小横条：点击切换 + 跟随手指线性收缩/展开） ----------------

    private void togglePanel() {
        if (isDetailOpen()) {
            // 详情页打开：灰色小横条用于“收起/展开”详情本身（带动画），不切换到成员列表，避免两个界面叠加。
            panelCollapsed = !panelCollapsed;
            if (panelCollapsed) {
                collapseDetailAnimated();
            } else {
                expandDetailAnimated();
            }
            return;
        }
        if (panelCollapsed) {
            expandPanel();
        } else {
            collapsePanel();
        }
    }

    /** 详情页收起动画：向下滑出 + 渐隐 */
    private void collapseDetailAnimated() {
        final int h = detailArea.getHeight() > 0 ? detailArea.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        detailArea.animate().translationY(h).alpha(0f).setDuration(180)
                .withEndAction(() -> {
                    detailArea.setVisibility(View.GONE);
                    detailArea.setTranslationY(0);
                    detailArea.setAlpha(1f);
                    updateScaleBarPosition();
                }).start();
    }

    /** 详情页展开动画：从下方滑入 + 渐显 */
    private void expandDetailAnimated() {
        final int h = detailArea.getHeight() > 0 ? detailArea.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        detailArea.setVisibility(View.VISIBLE);
        detailArea.setTranslationY(h);
        detailArea.setAlpha(0f);
        detailArea.animate().translationY(0).alpha(1f).setDuration(180).start();
    }

    private void collapsePanel() {
        if (panelCollapsed) {
            return;
        }
        panelCollapsed = true;
        panelBody.setVisibility(View.GONE);
        bottomPanel.setTranslationY(0);
        updateScaleBarPosition();
    }

    private void expandPanel() {
        if (!panelCollapsed) {
            return;
        }
        panelCollapsed = false;
        panelBody.setVisibility(View.VISIBLE);
        bottomPanel.setTranslationY(0);
        updateScaleBarPosition();
    }

    /** 面板完整高度（头部 + 主体自然高度，按需测量——成员列表高度会变化） */
    private int panelFullHeight() {
        if (bottomPanel == null || panelBody == null) {
            return 0; // 视图未就绪（如 onCreate 早期）：返回 0，调用方会跳过
        }
        int w = bottomPanel.getWidth();
        if (w <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
        }
        panelBody.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return panelHeaderHeight() + panelBody.getMeasuredHeight();
    }

    /** 面板头部高度（拖拽条 + 头部行 + 面板上下内边距）——收缩状态下的完整卡片高度 */
    private int panelHeaderHeight() {
        if (panelHeader == null || bottomPanel == null) {
            return dp(132); // 视图未就绪：用默认值兜底，避免空指针
        }
        View dragHandle = findViewById(R.id.dragHandle);
        int handleH = dragHandle == null ? 0 : dragHandle.getHeight();
        int h = handleH + panelHeader.getHeight()
                + bottomPanel.getPaddingTop() + bottomPanel.getPaddingBottom();
        return h > 0 ? h : dp(132);
    }

    /** 拖拽可滑动的最大位移：面板完全收起时整体下移的距离（纯位移，不触发布局，拖拽流畅） */
    private int panelMaxTranslate() {
        return Math.max(0, panelFullHeight() - panelHeaderHeight());
    }

    private void setPanelTranslation(float t) {
        bottomPanel.setTranslationY(t);
    }

    /** 拖拽结束（无速度信息）：按距离阈值判定 */
    private void settlePanel(float currentTranslation) {
        settlePanelWithVelocity(currentTranslation, 0f);
    }

    /** 甩动速度阈值（像素/秒）：超过它就按手势方向直接决定，不再看拉出距离 */
    private static final float FLING_VELOCITY = 700f;

    /**
     * 手势结束时的判定：先看甩动速度，再看拉出的绝对距离（不再按"占最大位移的比例"，
     * 因为收起态的总位移很大，按比例会让用户觉得"怎么拉都不动"/"拉一点就弹回"）。
     */
    private void settlePanelWithVelocity(float currentTranslation, float velocityY) {
        float max = panelMaxTranslate();
        if (max <= 0f) {
            animatePanelTranslation(currentTranslation, 0f, false);
            return;
        }
        // 本次手势实际拉出的距离（收起态是从"完全收起"往上拉，展开态是从 0 往下拉）
        float traveled = lastDragStartCollapsed ? (max - currentTranslation) : currentTranslation;
        boolean collapse;
        if (velocityY < -FLING_VELOCITY) {
            collapse = false;               // 向上快甩：展开
        } else if (velocityY > FLING_VELOCITY) {
            collapse = true;                // 向下快甩：收起
        } else if (lastDragStartCollapsed) {
            collapse = traveled < dp(DRAG_EXPAND_DP);   // 上拉不到 4dp 才弹回收起
        } else {
            collapse = traveled >= dp(DRAG_COLLAPSE_DP); // 下拉超过 16dp 才收起
        }
        animatePanelTranslation(currentTranslation, collapse ? max : 0f, collapse);
    }

    private void animatePanelTranslation(float from, float to, boolean collapse) {
        ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        if (collapse) {
            // 收起：匀速减速，干净利落
            anim.setDuration(180);
            anim.setInterpolator(new DecelerateInterpolator());
        } else {
            // 展开：轻微回弹（overshoot），手感更自然
            anim.setDuration(240);
            anim.setInterpolator(new OvershootInterpolator(0.9f));
        }
        // 展开过程中面板内容渐显，收起时渐隐：层次感更强
        panelBody.setAlpha(collapse ? 1f : 0f);
        panelBody.animate().cancel();
        panelBody.animate().alpha(1f).setDuration(collapse ? 90 : 180).start();
        anim.addUpdateListener(a -> setPanelTranslation((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                panelCollapsed = collapse;
                panelBody.setVisibility(collapse ? View.GONE : View.VISIBLE);
                panelBody.setAlpha(1f);
                bottomPanel.setTranslationY(0);
                updateScaleBarPosition();
                // 面板展开/收起改变了列表可视高度，「还有更多家人」提示需要重新判断
                memberList.post(MainActivity.this::updateMoreMembersHint);
            }
        });
        anim.start();
    }

    /** 成员详情是否为当前视图（详情被收起时也为 true，用于区分“收起详情”与“回到列表”） */
    private boolean isDetailOpen() {
        return detailOpen;
    }

    /**
     * 面板拖拽监听：在灰色横条/标题行/详情区按下后跟随手指线性位移（纯 translationY，不重排布局）。
     * 收起态上拉展开、展开态下拉收起；**判定只在松手时做**（按拉出的绝对距离 + 甩动速度），
     * 拖动过程中绝不提交，否则手指还在屏幕上面板就会弹回去。
     */
    /** 松手判定用：本次手势开始时面板是否处于收起态 */
    private boolean lastDragStartCollapsed;

    private class PanelDragTouchListener implements View.OnTouchListener {
        private float downRawY;
        private float downTranslation;
        private int maxTranslate;
        private boolean dragging;
        private boolean startCollapsed;
        private android.view.VelocityTracker velocity;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawY = ev.getRawY();
                    downTranslation = bottomPanel.getTranslationY();
                    dragging = false;
                    startCollapsed = panelCollapsed;
                    lastDragStartCollapsed = startCollapsed; // 供松手判定使用
                    maxTranslate = panelMaxTranslate();
                    if (velocity != null) {
                        velocity.recycle();
                    }
                    velocity = android.view.VelocityTracker.obtain();
                    velocity.addMovement(ev);
                    return false; // 不消费 DOWN，子控件仍可点击
                case MotionEvent.ACTION_MOVE: {
                    if (velocity != null) {
                        velocity.addMovement(ev);
                    }
                    float dy = ev.getRawY() - downRawY;
                    if (!dragging) {
                        if (Math.abs(dy) <= slop) {
                            return false;
                        }
                        dragging = true;
                        if (startCollapsed) {
                            // 从收起态开始拖：先把主体显示出来但位移仍为「完全收起」，视觉不变，
                            // 手指继续上移时主体从屏幕下方滑入
                            downTranslation = maxTranslate;
                            setPanelBodyShown(true);
                            panelCollapsed = false;
                            setPanelTranslation(maxTranslate);
                        }
                        // 拖动中可以随时反向：若按下时是展开态、手指先下后上，从这里开始就跟随手指
                    }
                    // 跟随手指（带范围限制）：收起态往上 = 位移减小（展开），展开态往下 = 位移增大（收起）；
                    // 反向拖动会被 Math.min/max 夹住，只能回到原位，不会越界
                    float target = Math.max(0f, Math.min(maxTranslate, downTranslation + dy));
                    setPanelTranslation(target);
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float velocityY = 0f;
                    if (velocity != null) {
                        velocity.addMovement(ev);
                        velocity.computeCurrentVelocity(1000);
                        velocityY = velocity.getYVelocity();
                        velocity.recycle();
                        velocity = null;
                    }
                    if (!dragging) {
                        return false;
                    }
                    dragging = false;
                    settlePanelWithVelocity(bottomPanel.getTranslationY(), velocityY);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    if (velocity != null) {
                        velocity.recycle();
                        velocity = null;
                    }
                    if (!dragging) {
                        return false;
                    }
                    dragging = false;
                    // 手势被系统打断：还原到手势开始时的状态，避免状态错乱导致后续手势失效
                    panelCollapsed = startCollapsed;
                    if (startCollapsed) {
                        setPanelBodyShown(false);
                    }
                    setPanelTranslation(0f);
                    updateScaleBarPosition();
                    return true;
                default:
                    return false;
            }
        }
    }

    /** 收起态上拉多少 dp 就展开（松手判定；取得很小，避免"拉了一点又弹回去"） */
    private static final int DRAG_EXPAND_DP = 4;
    /** 展开态下拉多少 dp 才收起（需要一点意图，避免误触） */
    private static final int DRAG_COLLAPSE_DP = 16;

    /** 显示/隐藏面板主体（统一收口，避免各处漏掉 alpha/动画取消） */
    private void setPanelBodyShown(boolean shown) {
        if (panelBody == null) {
            return;
        }
        panelBody.animate().cancel();
        panelBody.setAlpha(1f);
        panelBody.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    // ---------------- 自绘刻度尺（默认刻度尺被底部面板遮挡，改为自绘并跟随面板高度） ----------------

    private View scaleBar;
    private TextView tvScaleText;
    private View scaleLine;

    private void initScaleBar() {
        scaleBar = findViewById(R.id.scaleBar);
        tvScaleText = findViewById(R.id.tvScaleText);
        scaleLine = findViewById(R.id.scaleLine);
        // |___| 样式深色刻度线（地图底图固定为浅色，深色线保证清晰）
        scaleLine.setBackground(new ScaleLineDrawable(0xFF1A1A2E, dp(2), dp(5)));
        aMap.setOnCameraChangeListener(new AMap.OnCameraChangeListener() {
            @Override
            public void onCameraChange(CameraPosition cameraPosition) {
            }

            @Override
            public void onCameraChangeFinish(CameraPosition cameraPosition) {
                // 缩放结束后切换标点样式（卡片 <-> 圆点）与精度圈显隐，并刷新刻度尺
                if (cameraPosition != null) {
                    refreshMarkerStyleIfNeeded(cameraPosition.zoom);
                }
                updateScaleBar();
            }
        });
        mapView.post(this::updateScaleBarPosition);
    }

    /** 刻度尺位置：面板展开时位于面板上方，收起时下移到头部上方；详情页也保持显示（位于详情面板上方） */
    private void updateScaleBarPosition() {
        // 视图未就绪时直接跳过（onCreate 早期/极端时序），避免空指针崩溃
        if (scaleBar == null || !panelViewsReady()) {
            return;
        }
        scaleBar.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scaleBar.getLayoutParams();
        int panelH;
        if (detailOpen && !panelCollapsed) {
            // 详情页展开：面板高度 = 头部 + 详情内容高度
            panelH = panelHeaderHeight() + (detailArea != null ? detailArea.getHeight() : 0);
        } else if (panelCollapsed) {
            panelH = panelHeaderHeight();
        } else {
            panelH = panelFullHeight();
        }
        // 详情内容很高时把刻度尺顶出屏幕，这里限制其最高位置
        int maxBottom = getResources().getDisplayMetrics().heightPixels - dp(140);
        lp.bottomMargin = Math.min(panelH + dp(26), maxBottom);
        scaleBar.setLayoutParams(lp);
        updateScaleBar();
    }

    /** 根据当前缩放级别刷新刻度尺长度与文字（手动计算地面分辨率，不依赖已废弃的 getScalePerPixel） */
    private void updateScaleBar() {
        if (scaleBar == null || aMap == null) {
            return;
        }
        try {
            CameraPosition cam = aMap.getCameraPosition();
            if (cam == null) {
                return;
            }
            double zoom = cam.zoom;
            double lat = cam.target.latitude;
            if (zoom <= 0) {
                return;
            }
            // Web 墨卡托地面分辨率：米/像素 = 156543.03392 * cos(纬度) / 2^zoom
            double metersPerPixel = 156543.03392 * Math.cos(lat * Math.PI / 180.0)
                    / Math.pow(2.0, zoom);
            if (metersPerPixel <= 0) {
                return;
            }
            int targetPx = dp(56);
            double niceMeters = niceScale(metersPerPixel * targetPx);
            int linePx = Math.max(dp(20), (int) Math.round(niceMeters / metersPerPixel));
            String label;
            if (niceMeters >= 1000) {
                double km = niceMeters / 1000;
                label = (km == Math.floor(km) ? String.valueOf((long) km) : String.valueOf(km)) + "公里";
            } else {
                label = Math.round(niceMeters) + "米";
            }
            tvScaleText.setText(label);
            ViewGroup.LayoutParams lpl = scaleLine.getLayoutParams();
            lpl.width = linePx;
            scaleLine.setLayoutParams(lpl);
        } catch (Exception ignored) {
        }
    }

    /** 取最接近的 1/2/5×10^n 的刻度长度 */
    private static double niceScale(double meters) {
        if (meters <= 0) {
            return 1;
        }
        double exp = Math.floor(Math.log10(meters));
        double base = Math.pow(10, exp);
        double[] nice = {1, 2, 5};
        double best = nice[0] * base;
        double bestDiff = Math.abs(meters - best);
        for (double n : nice) {
            double cand = n * base;
            double diff = Math.abs(meters - cand);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = cand;
            }
        }
        double next = base * 10;
        if (Math.abs(meters - next) < bestDiff) {
            best = next;
        }
        return best;
    }

    // ---------------- 成员详情页 ----------------

    /** 点击成员行 -> 在底部悬浮窗口内展示详情（电量/导航/地址/网络 + 创建者操作） */
    private void showMemberDetail(final Member m) {
        if (!safeUi()) {
            return; // 活动已销毁：不再操作视图（异步回调可能晚到）
        }
        View v = getLayoutInflater().inflate(R.layout.dialog_member_detail, null);
        TextView dAvatar = v.findViewById(R.id.dAvatar);
        TextView dName = v.findViewById(R.id.dName);
        TextView dStatus = v.findViewById(R.id.dStatus);
        TextView dBattery = v.findViewById(R.id.dBattery);
        TextView dNetwork = v.findViewById(R.id.dNetwork);
        TextView dAddress = v.findViewById(R.id.dAddress);
        TextView dAccuracy = v.findViewById(R.id.dAccuracy);
        View dOwnerArea = v.findViewById(R.id.dOwnerArea);

        boolean isSelf = m.deviceId.equals(myDeviceId);
        dName.setText(isSelf ? (m.name + "（我）") : m.name);
        // 群主标识
        View dOwnerBadge = v.findViewById(R.id.dOwnerBadge);
        if (dOwnerBadge != null) {
            dOwnerBadge.setVisibility(m.isOwner ? View.VISIBLE : View.GONE);
        }
        String initial = (m.name != null && !m.name.isEmpty()) ? m.name.substring(0, 1) : "?";
        // 头像：已上传显示图片，否则首字圆形（与地图标点同色）
        if (m.avatar != null && !m.avatar.isEmpty()) {
            dAvatar.setTag(m.deviceId);
            AvatarLoader.load(m.avatar, bmp -> {
                if (!m.deviceId.equals(dAvatar.getTag())) {
                    return;
                }
                if (bmp != null) {
                    dAvatar.setText("");
                    dAvatar.setBackgroundTintList(null);
                    dAvatar.setBackground(new BitmapDrawable(getResources(), AvatarLoader.circleCrop(bmp)));
                } else {
                    // 加载失败：回退为首字彩圈
                    dAvatar.setText(initial);
                    dAvatar.setBackgroundResource(R.drawable.bg_avatar);
                    dAvatar.setBackgroundTintList(ColorStateList.valueOf(
                            isSelf ? MemberColors.selfColor() : MemberColors.colorFor(m.deviceId)));
                }
            });
        } else {
            dAvatar.setText(initial);
            dAvatar.setBackgroundResource(R.drawable.bg_avatar);
            dAvatar.setBackgroundTintList(ColorStateList.valueOf(
                    isSelf ? MemberColors.selfColor() : MemberColors.colorFor(m.deviceId)));
        }

        // 状态
        if (m.offlineMode) {
            dStatus.setText(R.string.detail_offline_mode);
            dStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        } else if (m.online) {
            dStatus.setText(R.string.detail_online);
            dStatus.setTextColor(ContextCompat.getColor(this, R.color.dot_online));
        } else {
            dStatus.setText(R.string.detail_offline);
            dStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
        // 电量
        dBattery.setText(m.battery >= 0 ? m.battery + "%" : getString(R.string.detail_unknown));
        // 网络
        dNetwork.setText(m.network == null || m.network.isEmpty()
                ? getString(R.string.detail_unknown) : m.network);
        // 地址
        dAddress.setText(m.address == null || m.address.isEmpty()
                ? getString(R.string.detail_no_address) : m.address);
        // 精度
        dAccuracy.setText(m.hasLocation
                ? ("±" + Math.round(m.accuracy) + "m") : getString(R.string.detail_no_location));

        // 内嵌到悬浮窗口（替换列表内容），右滑进入动画
        detailArea.removeAllViews();
        detailArea.addView(v);
        panelBody.setVisibility(View.GONE);
        detailArea.setVisibility(View.VISIBLE);
        detailOpen = true;
        // 打开详情即认为面板“展开”，保证灰色小横条收起/展开行为一致
        panelCollapsed = false;
        int width = getResources().getDisplayMetrics().widthPixels;
        detailArea.setTranslationX(width);
        detailArea.animate().translationX(0).setDuration(250).start();
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setOnClickListener(x -> closeDetail());

        v.findViewById(R.id.dRefresh).setOnClickListener(x -> {
            if (!isSelf) {
                pendingFocusDeviceId = m.deviceId;
                requestFreshLocation(m);
            }
        });
        v.findViewById(R.id.dRing).setOnClickListener(x -> requestRing(m));
        v.findViewById(R.id.dNavi).setOnClickListener(x -> {
            if (m.hasLocation) {
                openNavigation(m.lat, m.lng, m.name);
            } else {
                toast(getString(R.string.detail_no_location));
            }
        });

        // 操作区：移出家庭仅创建者可见；轨迹开关所有人可用（含自己，服务端已放开）
        boolean owner = Prefs.get(this).isOwner() && !isSelf;
        View dRemove = v.findViewById(R.id.dRemove);
        dRemove.setVisibility(owner ? View.VISIBLE : View.GONE);
        if (owner) {
            dRemove.setOnClickListener(x -> {
                closeDetail();
                showRemoveChoiceDialog(m);
            });
        }
        final TextView dTrack = v.findViewById(R.id.dTrack);
        dTrack.setVisibility(View.VISIBLE);
        dTrack.setText(m.track ? R.string.track_off : R.string.track_on);
        dTrack.setOnClickListener(x -> toggleTrack(m));
        dOwnerArea.setVisibility(View.VISIBLE);
        // 加粗高亮该成员的轨迹线
        highlightTrack(m.deviceId);
        // 详情页也保留刻度尺：详情内容布局完成后重新定位到详情面板上方
        detailArea.post(this::updateScaleBarPosition);
    }

    /** 关闭详情，右滑退出回到成员列表视图 */
    private void closeDetail() {
        clearTrackHighlight();
        int width = getResources().getDisplayMetrics().widthPixels;
        detailArea.animate().translationX(width).setDuration(200).withEndAction(() -> {
            detailOpen = false;
            detailArea.removeAllViews();
            detailArea.setVisibility(View.GONE);
            detailArea.setTranslationX(0);
            // 关闭详情后回到“展开”的成员列表（若期间通过灰色小横条收起过详情，这里重置为展开）
            panelCollapsed = false;
            panelBody.setVisibility(View.VISIBLE);
            btnBack.setVisibility(View.GONE);
            updateScaleBarPosition();
        }).start();
    }

    /** 开关轨迹：开启前询问更新间隔（1/3/5 分钟或自定义）；关闭直接提交 */
    private void toggleTrack(final Member m) {
        if (m.track) {
            submitTrack(m, false, 0);
        } else {
            showTrackIntervalDialog(m);
        }
    }

    /** 轨迹更新间隔选择框：1/3/5 分钟固定选项 + 自定义分钟 */
    private void showTrackIntervalDialog(final Member m) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(16), dp(8), dp(16), dp(4));

        final long[] intervals = {60_000L, 180_000L, 300_000L};
        String[] labels = {
                getString(R.string.track_interval_1m),
                getString(R.string.track_interval_3m),
                getString(R.string.track_interval_5m)
        };
        final RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setId(1000 + i);
            rb.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            rb.setPadding(0, dp(6), 0, dp(6));
            rg.addView(rb);
        }
        rg.check(1002); // 默认 5 分钟
        ll.addView(rg);

        // 自定义分钟数
        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        final RadioButton rbCustom = new RadioButton(this);
        rbCustom.setId(1100);
        rbCustom.setText(getString(R.string.track_interval_custom));
        rbCustom.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        customRow.addView(rbCustom);
        final EditText etCustom = new EditText(this);
        etCustom.setHint(R.string.track_interval_custom_hint);
        etCustom.setInputType(InputType.TYPE_CLASS_NUMBER);
        etCustom.setTextSize(14f);
        etCustom.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(110), dp(42));
        elp.setMarginStart(dp(8));
        customRow.addView(etCustom, elp);
        rbCustom.setOnClickListener(v -> {
            rg.clearCheck();
            rbCustom.setChecked(true);
            etCustom.requestFocus();
        });
        ll.addView(customRow);
        // 选中固定项时清空自定义输入
        rg.setOnCheckedChangeListener((g, id) -> {
            if (id >= 1000 && id < 1100) {
                etCustom.setText("");
            }
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.track_interval_title)
                .setView(ll)
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    long interval;
                    if (rbCustom.isChecked()) {
                        String s = etCustom.getText().toString().trim();
                        long minutes;
                        try {
                            minutes = Long.parseLong(s);
                        } catch (Exception e) {
                            toast(getString(R.string.track_interval_invalid));
                            return;
                        }
                        if (minutes < 1 || minutes > 1440) {
                            toast(getString(R.string.track_interval_invalid));
                            return;
                        }
                        interval = minutes * 60_000L;
                    } else {
                        int id = rg.getCheckedRadioButtonId();
                        int idx = id - 1000;
                        interval = (idx >= 0 && idx < intervals.length) ? intervals[idx] : 300_000L;
                    }
                    submitTrack(m, true, interval);
                })
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    private void submitTrack(final Member m, boolean track, long intervalMs) {
        Api.setMemberTrack(Prefs.get(this).familyId(), myDeviceId, m.deviceId, track, intervalMs,
                new Api.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        runOnUiThread(() -> {
                            closeDetail();
                            loadMembers();
                            if (track) {
                                toast(getString(R.string.toast_track_on, m.name, intervalMs / 60_000L));
                            } else {
                                toast(getString(R.string.toast_track_off, m.name));
                            }
                        });
                    }

                    @Override
                    public void onError(String msg) {
                        runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
                    }
                });
    }

    /** 请求目标成员手机响铃（查找手机） */
    private void requestRing(final Member m) {
        String familyId = Prefs.get(this).familyId();
        if (familyId.isEmpty()) {
            toast(getString(R.string.toast_need_family_first));
            return;
        }
        Api.requestRing(familyId, myDeviceId, m.deviceId, Prefs.get(this).deviceName(), new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    try {
                        JSONObject o = new JSONObject(body);
                        if ("offline".equals(o.optString("status"))) {
                            toast(getString(R.string.toast_ring_offline, m.name));
                        } else {
                            toast(getString(R.string.toast_ring_sent, m.name));
                        }
                    } catch (Exception e) {
                        toast(getString(R.string.toast_ring_sent, m.name));
                    }
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
            }
        });
    }

    /** 跳转导航：优先高德 App，未安装则回退系统地图 */
    private void openNavigation(double lat, double lng, String name) {
        try {
            Intent amap = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("androidamap://navi?sourceApplication=familyshare&lat=" + lat
                            + "&lon=" + lng + "&dev=0&style=2"));
            amap.setPackage("com.autonavi.minimap");
            if (amap.resolveActivity(getPackageManager()) != null) {
                startActivity(amap);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:" + lat + "," + lng + "?q=" + lat + "," + lng)));
        } catch (Exception ignored) {
            toast(getString(R.string.toast_navi_failed));
        }
    }

    /** 一键刷新：向所有家人请求实时位置（各自上报后通过广播自动更新标记） */
    private void refreshAll() {
        String familyId = Prefs.get(this).familyId();
        if (familyId.isEmpty()) {
            toast(getString(R.string.toast_need_family_first));
            return;
        }
        int count = 0;
        for (Member m : new ArrayList<>(members.values())) {
            if (!m.deviceId.equals(myDeviceId)) {
                count++;
                Api.requestLocation(familyId, myDeviceId, m.deviceId, new Api.Callback() {
                    @Override
                    public void onSuccess(String body) {
                    }

                    @Override
                    public void onError(String msg) {
                    }
                });
            }
        }
        if (count == 0) {
            toast(getString(R.string.toast_no_other_members));
            return;
        }
        toastTop(getString(R.string.toast_refresh_all_sent));
        // 兜底：几秒后静默重拉一次成员列表
        scheduleMembersRefresh();
    }

    /** ⋮ 一键刷新：刷新所有不会即时更新的内容（家庭列表/电量/服务器连接/响铃状态/实时位置） */
    private void refreshEverything() {
        String familyId = Prefs.get(this).familyId();
        if (familyId.isEmpty()) {
            toast(getString(R.string.toast_need_family_first));
            return;
        }
        // 1. 刷新家庭成员列表（位置/轨迹/在线状态/头像）
        loadMembers();
        // 2. 刷新服务器连接状态
        Api.health(new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> updateStatusUi(AppConfig.STATUS_ONLINE));
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> updateStatusUi(AppConfig.STATUS_OFFLINE));
            }
        });
        // 3. 让本机立即上报一次位置（含最新电量/网络/地址）
        sendToService(AppConfig.ACTION_REPORT_NOW);
        // 4. 刷新响铃状态（服务回广播当前是否正在响铃）
        sendToService(AppConfig.ACTION_QUERY_RING);
        // 5. 向所有家人请求实时位置
        refreshAll();
    }

    // ---------------- 头像（选图 -> 1:1 裁切 -> 压缩 -> 上传） ----------------

    private void pickAvatar() {
        try {
            avatarPicker.launch("image/*");
        } catch (Exception e) {
            toast(getString(R.string.toast_no_gallery_app));
        }
    }

    /** 按最大边采样解码图片，避免大图 OOM */
    private Bitmap decodeSampled(Uri uri, int maxSide) {
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opt);
            int sample = 1;
            while (Math.max(opt.outWidth, opt.outHeight) / sample > maxSide) {
                sample *= 2;
            }
            try (java.io.InputStream is2 = getContentResolver().openInputStream(uri)) {
                opt.inJustDecodeBounds = false;
                opt.inSampleSize = sample;
                return BitmapFactory.decodeStream(is2, null, opt);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 1:1 裁切界面：中间方形取景框（黑色描边 + 外部压暗），确定后上传 */
    private void showCropDialog(final Bitmap src) {
        final CropView crop = new CropView(this);
        int side = dp(300);
        crop.setLayoutParams(new LinearLayout.LayoutParams(side, side));
        crop.setImage(src);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(20), dp(12), dp(20), dp(4));
        ll.addView(crop);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.avatar_crop_title)
                .setView(ll)
                .setPositiveButton(R.string.btn_ok, (d, w) -> uploadAvatar(crop.crop()))
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    private void uploadAvatar(Bitmap cropped) {
        if (cropped == null) {
            toast(getString(R.string.toast_avatar_decode_failed));
            return;
        }
        String familyId = Prefs.get(this).familyId();
        if (familyId.isEmpty()) {
            toast(getString(R.string.toast_need_family_first));
            return;
        }
        byte[] bytes = compressUnder50Kb(cropped);
        Api.uploadAvatar(myDeviceId, familyId, bytes, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    toast(getString(R.string.toast_avatar_uploaded));
                    invalidateMemberIconAndAvatar(myDeviceId); // 头像地址不变但内容已变：强制重绘标点
                    loadMembers(); // 重新拉取成员列表以拿到带新头像 URL 的数据
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
            }
        });
    }

    /** JPEG 压缩到 50KB 以下：先降质量，再缩小尺寸 */
    private byte[] compressUnder50Kb(Bitmap bmp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Bitmap working = bmp;
        for (int round = 0; round < 3; round++) {
            int quality = 90;
            while (quality >= 20) {
                bos.reset();
                working.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                if (bos.size() <= 50 * 1024) {
                    return bos.toByteArray();
                }
                quality -= 10;
            }
            if (working.getWidth() <= 64) {
                break;
            }
            working = Bitmap.createScaledBitmap(working, working.getWidth() / 2,
                    working.getHeight() / 2, true);
        }
        return bos.toByteArray();
    }

    // ---------------- 切换家庭（创建者需先转让群主） ----------------

    /**
     * 「家庭设置」入口：新增/加入一个家庭。
     * 现在支持同一设备同时属于多个家庭，所以**不再需要**为了换家庭而转让群主或退出原家庭；
     * 想切换当前查看的家庭，直接在主页面成员列表上左右滑动即可。
     */
    private void switchFamilyFlow() {
        showFamilySetup();
    }

    /** 主动转让群主：选择新的群主。thenSwitch=true 表示转让后进入「切换家庭」流程；=false 表示仅转让 */
    private void showTransferOwnerDialog(final boolean thenSwitch) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(12), dp(4), dp(12), dp(4));
        for (Member m : members.values()) {
            if (m.deviceId.equals(myDeviceId)) {
                continue;
            }
            TextView row = new TextView(this);
            row.setText(m.name + "（" + (m.online ? getString(R.string.detail_online)
                    : getString(R.string.detail_offline)) + "）");
            row.setTextSize(15f);
            row.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            row.setPadding(0, dp(12), 0, dp(12));
            row.setOnClickListener(v -> transferOwnerTo(m, thenSwitch));
            ll.addView(row);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.transfer_owner_select_title)
                .setMessage(R.string.transfer_owner_message)
                .setView(ll)
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    /** 调用服务端转让群主；thenSwitch=true 转让后进入切换家庭流程，=false 仅转让并刷新列表 */
    private void transferOwnerTo(final Member newOwner, final boolean thenSwitch) {
        Api.transferOwner(Prefs.get(this).familyId(), myDeviceId, newOwner.deviceId, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    Prefs.get(MainActivity.this).isOwner(false);
                    toast(getString(R.string.toast_transfer_owner_done, newOwner.name));
                    if (thenSwitch) {
                        showFamilySetup();
                    } else {
                        loadMembers(); // 主动转让：刷新列表更新群主标识；新群主端通过 WS owner-changed 感知
                    }
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> toast(getString(R.string.toast_network_error, msg)));
            }
        });
    }

    /** 检查更新；silent=true 用于启动自动检查（无新版时不打扰） */
    private void checkUpdate(boolean silent) {
        Api.getUpdateInfo(new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    try {
                        JSONObject o = new JSONObject(body);
                        String versionName = o.optString("versionName", "");
                        String note = o.optString("note", "");
                        String url = o.optString("url", "");
                        String serverMd5 = o.optString("md5", "");
                        boolean hasApk = o.optBoolean("hasApk", false);
                        if (!hasApk || url.isEmpty() || serverMd5.isEmpty()) {
                            // 服务器缺少安装包或未返回 MD5
                            if (!silent) {
                                toast(getString(R.string.toast_update_missing_apk));
                            }
                            return;
                        }
                        // MD5 比对：云端文件与本地已安装 APK 不同则更新
                        String localMd5 = installedApkMd5();
                        if (localMd5.isEmpty()) {
                            if (!silent) {
                                toast(getString(R.string.toast_update_check_failed));
                            }
                            return;
                        }
                        if (serverMd5.equalsIgnoreCase(localMd5)) {
                            if (!silent) {
                                toast(getString(R.string.toast_update_latest));
                            }
                            return;
                        }
                        showUpdateDialog(versionName, note, url, serverMd5);
                    } catch (Exception e) {
                        if (!silent) {
                            toast(getString(R.string.toast_update_check_failed));
                        }
                    }
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    if (!silent) {
                        toast(getString(R.string.toast_network_error, msg));
                    }
                });
            }
        });
    }

    private void showUpdateDialog(String versionName, String note, String url, String serverMd5) {
        if (!safeUi()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_update_title)
                .setMessage(getString(R.string.dialog_update_message, versionName, note))
                .setPositiveButton(R.string.btn_update_now, (d, w) -> downloadAndInstall(url, serverMd5))
                .setNegativeButton(R.string.btn_back, null)
                .show();
    }

    private void downloadAndInstall(String url, String serverMd5) {
        // Android 8+ 需允许安装未知来源
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.toast_update_install_permission)
                    .setPositiveButton(R.string.btn_go_settings, (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton(R.string.btn_back, null)
                    .show();
            return;
        }
        showDownloadDialog();
        File dir = new File(getFilesDir(), "downloads");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        final File apk = new File(dir, "app-release.apk");
        downloadCancelled = false;
        downloadCall = Api.downloadFile(url, apk, (read, total) -> runOnUiThread(() -> {
                    if (downloadCancelled) {
                        return;
                    }
                    if (total > 0) {
                        downloadProgress.setIndeterminate(false);
                        int percent = (int) (read * 100 / total);
                        downloadProgress.setProgress(percent);
                        tvDownloadPercent.setText(percent + "%");
                    } else {
                        downloadProgress.setIndeterminate(true);
                        tvDownloadPercent.setText(getString(R.string.toast_update_downloading));
                    }
                }),
                new Api.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        runOnUiThread(() -> {
                            dismissDownloadDialog();
                            // MD5 校验：下载的文件必须与服务器声明一致，否则视为损坏
                            if (serverMd5 != null && !serverMd5.isEmpty()
                                    && !serverMd5.equalsIgnoreCase(md5File(apk))) {
                                apk.delete();
                                toast(getString(R.string.toast_update_md5_mismatch));
                                return;
                            }
                            installApk(apk);
                        });
                    }

                    @Override
                    public void onError(String msg) {
                        runOnUiThread(() -> {
                            dismissDownloadDialog();
                            if (!downloadCancelled) {
                                toast(getString(R.string.toast_update_download_failed, msg));
                            }
                        });
                    }
                });
    }

    private void showDownloadDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_download_progress, null);
        downloadProgress = v.findViewById(R.id.downloadProgress);
        tvDownloadPercent = v.findViewById(R.id.tvDownloadPercent);
        downloadDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_update_title)
                .setView(v)
                .setNegativeButton(R.string.btn_back, (d, w) -> {
                    downloadCancelled = true;
                    if (downloadCall != null) {
                        downloadCall.cancel();
                    }
                    dismissDownloadDialog();
                })
                .setCancelable(false)
                .create();
        downloadDialog.show();
    }

    private void dismissDownloadDialog() {
        if (downloadDialog != null && downloadDialog.isShowing()) {
            downloadDialog.dismiss();
        }
        downloadDialog = null;
    }

    /** 本地已安装 APK 的 MD5 */
    private String installedApkMd5() {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(getPackageName(), 0);
            return md5File(new File(ai.sourceDir));
        } catch (Exception e) {
            return "";
        }
    }

    /** 计算文件 MD5（小写十六进制） */
    private String md5File(File file) {
        try (FileInputStream is = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void installApk(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            toast(getString(R.string.toast_update_install_failed));
        }
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void updateStatusUi(int status) {
        if (!safeUi() || statusDot == null || tvStatus == null || statusPill == null
                || statusPill.getBackground() == null) {
            return; // 异步回调可能在 Activity 已销毁后到达：直接跳过，避免空指针/崩溃
        }
        int pillColor;
        int textColor;
        int dotRes;
        String text;
        if (status == AppConfig.STATUS_ONLINE) {
            pillColor = ContextCompat.getColor(this, R.color.status_pill_online);
            textColor = ContextCompat.getColor(this, R.color.status_pill_online_text);
            dotRes = R.drawable.dot_online;
            text = getString(R.string.status_online);
        } else if (status == AppConfig.STATUS_OFFLINE) {
            pillColor = ContextCompat.getColor(this, R.color.status_pill_offline);
            textColor = ContextCompat.getColor(this, R.color.status_pill_offline_text);
            dotRes = R.drawable.dot_offline;
            text = getString(R.string.status_offline);
        } else {
            pillColor = ContextCompat.getColor(this, R.color.status_pill_offline);
            textColor = ContextCompat.getColor(this, R.color.status_pill_offline_text);
            dotRes = R.drawable.dot_offline;
            text = getString(R.string.status_connecting);
        }
        statusDot.setBackgroundResource(dotRes);
        tvStatus.setText(text);
        tvStatus.setTextColor(textColor);
        statusPill.getBackground().setTint(pillColor);
    }

    /** 活动是否仍可安全弹窗/操作 UI（防止异步网络回调在活动已销毁时弹窗导致的偶发闪退） */
    private boolean safeUi() {
        return !isFinishing() && !isDestroyed();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 顶部提示：位于屏幕上方，不会被底部面板/详情遮挡（用于刷新位置等反馈） */
    private void toastTop(String msg) {
        Toast t = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        t.setGravity(Gravity.TOP, 0, dp(120));
        t.show();
    }
}
