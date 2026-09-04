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
    private RecyclerView memberList;
    private MemberAdapter adapter;
    private TextView tvStatus;
    private TextView tvEmpty;
    private View statusDot;
    private View statusPill;
    private String pendingFocusDeviceId = "";
    /** 成员列表按下时的 Y 坐标（用于“列表到顶后下拉收缩面板”） */
    private float memberDownY;
    /** 本次列表下拉是否已触发过收缩（防止一次拖动重复触发） */
    private boolean listPullHandled;
    /** 按下瞬间成员列表是否已在顶部：只有“按下时已在顶部”的下拉才触发收缩；
     *  下拉过程中滚到顶部不收缩，需“到顶后再往下拉一次”才收缩（避免滚到顶部就误收起）。 */
    private boolean memberListAtTop;
    /** 成员人数简洁标签（如“家庭成员（3）”） */
    private TextView tvMemberCount;

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
    /** 扫码结果：识别到二维码后把家庭码写入加入对话框的输入框 */
    private final ActivityResultLauncher<ScanOptions> scanLauncher = registerForActivityResult(
            new ScanContract(), result -> {
                String code = result.getContents();
                if (code != null && familyScanToken.joinCode != null) {
                    familyScanToken.joinCode.setText(code.trim());
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
    private String myDeviceId = "";
    /** 打开/回到前台时，若在多人家庭则自动向全员请求一次实时位置（只在一次成员列表渲染后消费，用底部 toast 提示、不弹窗） */
    private boolean entryRefreshArmed;
    /** 最近一次切换服务器的时间戳：用于防止切换后成员列表被清空导致绿点消失 */
    private long serverSwitchAt;

    /** 家人列表最多同时显示的成员行数：超过则可上下滚动，滚到顶部后下拉收起面板 */
    private static final int MAX_VISIBLE_MEMBERS = 4;
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
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        myDeviceId = Prefs.get(this).deviceId();

        // 地图
        mapView = findViewById(R.id.mapView);
        mapView.onCreate(savedInstanceState);
        aMap = mapView.getMap();
        aMap.setMapType(AMap.MAP_TYPE_NORMAL);
        aMap.getUiSettings().setCompassEnabled(true);   // 指南针
        aMap.getUiSettings().setZoomControlsEnabled(false); // 隐藏默认缩放按钮，保留双指缩放
        initScaleBar(); // 自绘刻度尺（默认刻度尺会被底部面板遮挡）

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
        statusDot = findViewById(R.id.statusDot);
        statusPill = findViewById(R.id.statusPill);
        findViewById(R.id.btnLocateMe).setOnClickListener(v -> onLocateMe());

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
        // 成员列表：已在顶部时继续向下拉 -> 平滑收缩面板（否则列表正常滚动）
        memberList.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    memberDownY = ev.getRawY();
                    listPullHandled = false;
                    // 记录按下瞬间列表是否已在顶部：收缩只在“本就已在顶部，再下拉”时触发
                    memberListAtTop = !memberList.canScrollVertically(-1);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (listPullHandled) {
                        // 已触发收缩：本次手势的后续 move 一律消费，避免污染 RecyclerView 手势状态导致下次失效
                        return true;
                    }
                    if (!isDetailOpen() && memberListAtTop
                            && ev.getRawY() - memberDownY > ViewConfiguration.get(MainActivity.this).getScaledTouchSlop()) {
                        listPullHandled = true;
                        // 平滑下拉收缩（带动画，避免生硬）
                        if (!panelCollapsed) {
                            animatePanelTranslation(bottomPanel.getTranslationY(), panelMaxTranslate(), true);
                        }
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    listPullHandled = false;
                    return false;
                default:
                    return false;
            }
        });
        // 成员人数标签
        tvMemberCount = findViewById(R.id.tvMemberCount);

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
        // 广播均来自本应用自身，声明 NOT_EXPORTED 兼容 Android 13+
        ContextCompat.registerReceiver(this, uiReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
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
        unregisterReceiver(uiReceiver);
        stopHealthPolling();
        stopPeriodicRefresh();
    }

    @Override
    protected void onDestroy() {
        mapView.onDestroy();
        super.onDestroy();
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

    private void updateMarker(Member m) {
        if (!m.hasLocation) {
            removeAccuracyCircle(m.deviceId);
            return;
        }
        BitmapDescriptor icon = buildMemberIcon(m);
        Marker marker = markers.get(m.deviceId);
        if (marker == null) {
            MarkerOptions opt = new MarkerOptions()
                    .position(new LatLng(m.lat, m.lng))
                    .icon(icon)
                    .title(m.name)
                    .anchor(0.5f, 1f);
            marker = aMap.addMarker(opt);
            markers.put(m.deviceId, marker);
        } else {
            marker.setIcon(icon);
            marker.setPosition(new LatLng(m.lat, m.lng));
        }
        marker.setTitle(m.name);
        loadMarkerAvatar(m);
        updateAccuracyCircle(m);
    }

    /** 扁平风标点：成员颜色圆角卡片 + 头像/首字 + 名字 + 底部三角尾巴（锚点=尾尖） */
    private BitmapDescriptor buildMemberIcon(Member m) {
        float d = getResources().getDisplayMetrics().density;
        int avatarSize = Math.round(30 * d);
        int pad = Math.round(6 * d);
        int gap = Math.round(6 * d);
        int tailH = Math.round(9 * d);
        int nameSize = Math.round(12 * d);
        int maxNameW = Math.round(96 * d);
        boolean self = m.deviceId.equals(myDeviceId);
        int color = self ? MemberColors.selfColor() : MemberColors.colorFor(m.deviceId);

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

        Bitmap bmp = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);

        android.graphics.Paint cardPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        cardPaint.setColor(color);
        float radius = 9 * d;
        c.drawRoundRect(0, 0, cardW, cardH, radius, radius, cardPaint);

        // 头像（白色圆底 + 头像/首字）
        float circleR = avatarSize / 2f;
        float cx = pad + circleR, cy = pad + circleR;
        android.graphics.Paint white = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        white.setColor(android.graphics.Color.WHITE);
        c.drawCircle(cx, cy, circleR, white);
        Bitmap av = m.avatarBitmap;
        if (av != null && m.avatar != null && m.avatar.equals(m.avatarBitmapFor)) {
            float inner = circleR - 1.5f * d;
            android.graphics.Path circleClip = new android.graphics.Path();
            circleClip.addCircle(cx, cy, inner, android.graphics.Path.Direction.CW);
            int sc = c.save();
            c.clipPath(circleClip);
            c.drawBitmap(Bitmap.createScaledBitmap(av, avatarSize, avatarSize, true),
                    cx - avatarSize / 2f, cy - avatarSize / 2f, null);
            c.restoreToCount(sc);
        } else {
            String initial = name.substring(0, 1);
            android.graphics.Paint ip = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            ip.setColor(color);
            ip.setTextSize(avatarSize * 0.55f);
            ip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            ip.setTextAlign(android.graphics.Paint.Align.CENTER);
            float ib = cy - (ip.descent() + ip.ascent()) / 2f;
            c.drawText(initial, cx, ib, ip);
        }

        // 名字
        float tx = pad + avatarSize + gap;
        float tb = cardH / 2f - (tp.descent() + tp.ascent()) / 2f;
        c.drawText(shownName, tx, tb, tp);

        // 底部三角尾巴（指向定位点）
        android.graphics.Path tail = new android.graphics.Path();
        float tcx = cardW / 2f, tw = 8 * d;
        tail.moveTo(tcx - tw, cardH);
        tail.lineTo(tcx + tw, cardH);
        tail.lineTo(tcx, cardH + tailH);
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
        double radius = m.accuracy > 0 ? m.accuracy : 0;
        if (radius <= 0 || !m.hasLocation) {
            removeAccuracyCircle(m.deviceId);
            return;
        }
        LatLng center = new LatLng(m.lat, m.lng);
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
        Api.listMembers(familyId, new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                runOnUiThread(() -> {
                    try {
                        applyMemberList(Member.listFromJson(body), true);
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
            Polyline p = trackLines.get(m.deviceId);
            if (p != null) {
                p.setPoints(m.trajectory);
            } else {
                p = aMap.addPolyline(new PolylineOptions()
                        .addAll(m.trajectory)
                        .width(5)
                        .color(MemberColors.colorFor(m.deviceId)));
                trackLines.put(m.deviceId, p);
            }
            updateTrackStart(m);
        }
        // 列表刷新后若详情页仍打开，重新加粗该成员轨迹
        if (highlightDeviceId != null && !highlightDeviceId.isEmpty() && isDetailOpen()) {
            highlightTrack(highlightDeviceId);
        }
    }

    /** 添加/更新某成员轨迹的绿色起点标记（单独的一个绿点） */
    private void updateTrackStart(Member m) {
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
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        memberList.setVisibility(empty ? View.GONE : View.VISIBLE);
        updateMemberListHeight();
    }

    /**
     * 成员列表高度限制：最多同时显示 MAX_VISIBLE_MEMBERS 行；超过时固定为
     * 「4 行 + 下一行露出一半」的高度，让第 5 个成员被裁掉一半露出来作为「还有更多」的滚动提示，
     * 同时保持可上下滚动（并在「滚到顶部后继续下拉」时触发面板收起，见 memberList 的 onTouch 监听）。
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
        // 4 行完整高度 + 第 5 行露出一半（半行 peek）：既提示可滚动，也让底部始终有被裁一半的行
        int targetH = memberRowHeightPx * MAX_VISIBLE_MEMBERS + memberRowHeightPx / 2;
        if (lp.height != targetH) {
            lp.height = targetH;
            memberList.setLayoutParams(lp);
        }
    }

    private void fitCameraToMembers() {
        LatLngBounds.Builder builder = LatLngBounds.builder();
        boolean any = false;
        for (Member m : members.values()) {
            if (m.hasLocation) {
                builder.include(new LatLng(m.lat, m.lng));
                any = true;
            }
        }
        if (!any && Prefs.get(this).hasLastLocation()) {
            builder.include(new LatLng(Prefs.get(this).lastLat(), Prefs.get(this).lastLng()));
            any = true;
        }
        if (any) {
            aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100));
        }
    }

    private void moveCamera(double lat, double lng) {
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
        removeAccuracyCircle(deviceId);
        Polyline pl = trackLines.remove(deviceId);
        if (pl != null) {
            pl.remove();
        }
        removeTrackStart(deviceId);
        refreshAdapter();
    }

    /** 本机被移出家庭：清理本地家庭状态，并引导重新创建/加入家庭 */
    private void handleSelfRemoved() {
        Prefs prefs = Prefs.get(this);
        prefs.familyId("");
        prefs.familyCode("");
        prefs.isOwner(false);
        prefs.shareEnabled(false);
        prefs.offlineMode(false);
        sendToService(AppConfig.ACTION_STOP);
        closeDetail();
        members.clear();
        for (String id : new ArrayList<>(markers.keySet())) {
            Marker mk = markers.remove(id);
            if (mk != null) {
                mk.remove();
            }
            removeAccuracyCircle(id);
        }
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
        toast(getString(R.string.toast_removed_self));
        showFamilySetup();
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

    /** 收到入群申请：存入消息中心（仅群主有审批按钮） */
    private void addJoinRequestMessage(String requestId, String deviceId, String name) {
        if (requestId == null || requestId.isEmpty() || deviceId == null || deviceId.isEmpty()) {
            return;
        }
        for (java.util.Iterator<MessageItem> it = messages.iterator(); it.hasNext(); ) {
            MessageItem m = it.next();
            if ("joinRequest".equals(m.type) && requestId.equals(m.requestId)) {
                it.remove();
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
                                p.familyId(familyId);
                                p.familyCode(code);
                                p.isOwner(false);
                                p.shareEnabled(true);
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
            Api.listMembers(familyId, new Api.Callback() {
                @Override
                public void onSuccess(String body) {
                    runOnUiThread(() -> {
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
            Api.listMembers(familyId, new Api.Callback() {
                @Override
                public void onSuccess(String body) {
                    runOnUiThread(() -> {
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

    /** 底部「家庭码」按钮：查看并复制当前家庭码 */
    private void showCodeDialog() {
        Prefs prefs = Prefs.get(this);
        if (prefs.familyId().isEmpty() || prefs.familyCode().isEmpty()) {
            toast(getString(R.string.toast_no_family_code));
            showFamilySetup();
            return;
        }
        View v = getLayoutInflater().inflate(R.layout.dialog_family_code, null);
        TextView tvCode = v.findViewById(R.id.tvCode);
        tvCode.setText(prefs.familyCode());
        // 家庭码下方显示二维码：家人可用「扫码加入」直接识别
        ImageView ivQr = v.findViewById(R.id.ivQrCode);
        Bitmap qr = QrCode.generate(prefs.familyCode(), dp(200));
        if (qr != null) {
            ivQr.setImageBitmap(qr);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_code_title)
                .setView(v)
                .setPositiveButton(R.string.btn_copy, (d, w) -> copyCode(prefs.familyCode()))
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
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            // 主动弹出系统「忽略电池优化」请求对话框（而非仅展示权限设置大对话框）
            requestIgnoreBattery();
        }
        firstRunStepDone();
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
        View dragHandle = findViewById(R.id.dragHandle);
        int h = dragHandle.getHeight() + panelHeader.getHeight()
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

    /** 拖拽结束：位移过半则收起，否则展开（位移动画，无布局开销） */
    private void settlePanel(float currentTranslation) {
        float max = panelMaxTranslate();
        boolean collapse = currentTranslation >= max / 2f;
        animatePanelTranslation(currentTranslation, collapse ? max : 0f, collapse);
    }

    private void animatePanelTranslation(float from, float to, boolean collapse) {
        ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> setPanelTranslation((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                panelCollapsed = collapse;
                panelBody.setVisibility(collapse ? View.GONE : View.VISIBLE);
                bottomPanel.setTranslationY(0);
                updateScaleBarPosition();
            }
        });
        anim.start();
    }

    /** 成员详情是否为当前视图（详情被收起时也为 true，用于区分“收起详情”与“回到列表”） */
    private boolean isDetailOpen() {
        return detailOpen;
    }

    /**
     * 面板拖拽监听：在灰色横条/头部区域按下后，跟随手指移动线性滑动面板（纯位移，不重排布局）。
     * 下拉收缩、上拉展开，松手后按位移过半决定去向（带动画）；
     * 未拖动时不消费事件，保证头部返回按钮等子控件仍可点击。
     */
    private class PanelDragTouchListener implements View.OnTouchListener {
        private float downRawY;
        private float downTranslation;
        private float dragMaxTranslate;
        private boolean dragging;
        private boolean dragStartCollapsed;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawY = ev.getRawY();
                    downTranslation = bottomPanel.getTranslationY();
                    dragging = false;
                    return false; // 不消费 DOWN，子控件仍可点击
                case MotionEvent.ACTION_MOVE:
                    if (isDetailOpen()) {
                        // 详情页：展开态向下拉收起、收起态向上拉展开（方向匹配才触发）
                        float ddy = ev.getRawY() - downRawY;
                        int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
                        if (!dragging) {
                            if (panelCollapsed && ddy < -slop) {
                                dragging = true;   // 收起态上拉 -> 展开
                            } else if (!panelCollapsed && ddy > slop) {
                                dragging = true;   // 展开态下拉 -> 收起
                            }
                        }
                        return dragging;
                    }
                    float dy = ev.getRawY() - downRawY;
                    if (!dragging && Math.abs(dy) > ViewConfiguration
                            .get(MainActivity.this).getScaledTouchSlop()) {
                        // 方向匹配才拖拽：收起态上拉展开、展开态下拉收缩；展开态上拉不触发（避免误收缩）
                        boolean dirOk = panelCollapsed ? dy < 0 : dy > 0;
                        if (!dirOk) {
                            return false;
                        }
                        dragging = true;
                        dragStartCollapsed = panelCollapsed;
                        dragMaxTranslate = panelMaxTranslate();
                        if (panelCollapsed) {
                            // 从收起态开始拖：展开主体但整体保持"完全收起"位移，视觉不变，
                            // 上拉时主体从屏幕下方滑入
                            panelCollapsed = false;
                            panelBody.setVisibility(View.VISIBLE);
                            setPanelTranslation(dragMaxTranslate);
                            downTranslation = dragMaxTranslate;
                        }
                    }
                    if (dragging) {
                        float target = downTranslation + dy;
                        setPanelTranslation(Math.max(0f, Math.min(dragMaxTranslate, target)));
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                    if (isDetailOpen()) {
                        if (dragging) {
                            dragging = false;
                            // 详情页：下拉收缩详情（不返回列表；要返回用返回键/返回按钮）
                            togglePanel();
                            return true;
                        }
                        return false;
                    }
                    if (dragging) {
                        dragging = false;
                        settlePanel(bottomPanel.getTranslationY());
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_CANCEL:
                    if (isDetailOpen()) {
                        dragging = false;
                        return true;
                    }
                    if (dragging) {
                        dragging = false;
                        panelCollapsed = dragStartCollapsed;
                        panelBody.setVisibility(dragStartCollapsed ? View.GONE : View.VISIBLE);
                        bottomPanel.setTranslationY(0);
                        return true;
                    }
                    return false;
            }
            return false;
        }
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
        // 关闭 SDK 默认刻度尺，改用自绘
        aMap.getUiSettings().setScaleControlsEnabled(false);
        aMap.setOnCameraChangeListener(new AMap.OnCameraChangeListener() {
            @Override
            public void onCameraChange(CameraPosition cameraPosition) {
            }

            @Override
            public void onCameraChangeFinish(CameraPosition cameraPosition) {
                updateScaleBar();
            }
        });
        mapView.post(this::updateScaleBarPosition);
    }

    /** 刻度尺位置：面板展开时位于面板上方，收起时下移到头部上方，详情页隐藏 */
    private void updateScaleBarPosition() {
        if (scaleBar == null) {
            return;
        }
        // 仅当详情完整展开时隐藏刻度尺；详情被灰色小横条收起后按面板头部高度定位
        if (detailOpen && !panelCollapsed) {
            scaleBar.setVisibility(View.GONE);
            return;
        }
        scaleBar.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scaleBar.getLayoutParams();
        int panelH = panelCollapsed ? panelHeaderHeight() : panelFullHeight();
        lp.bottomMargin = panelH + dp(26);
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
        // 详情页打开时隐藏刻度尺（详情内容可能很高，避免遮挡）
        if (scaleBar != null) {
            scaleBar.setVisibility(View.GONE);
        }
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

    /** 切换家庭：创建者且家庭里有其他成员时，先选择转让群主；否则直接切换 */
    private void switchFamilyFlow() {
        Prefs prefs = Prefs.get(this);
        if (prefs.familyId().isEmpty()) {
            showFamilySetup();
            return;
        }
        if (!prefs.isOwner() || members.size() <= 1) {
            showFamilySetup();
            return;
        }
        // 群主且家庭里有其他人：先选新群主（转让后进入切换家庭流程）
        showTransferOwnerDialog(true);
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

    private void installApk(File apk) {        try {
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
