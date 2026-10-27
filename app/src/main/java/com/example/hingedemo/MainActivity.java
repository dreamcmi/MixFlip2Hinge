// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The MixFlip2Hinge Authors
package com.example.hingedemo;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.util.Consumer;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 小米 MIX Flip 2 小折叠 —— 转轴角度测试主界面。
 *
 * 本工程通过两条互补的路径获取折叠状态：
 *
 * 路径一：转轴角度（数值 —— 标准的 {@link Sensor#TYPE_HINGE_ANGLE}
 * 传感器（Android 11 / API 30 引入），通过 {@link SensorManager} 注册监听，
 * 回调里 {@code event.values[0]} 即为 0–180° 的实时开合角度。
 * 这条路径与窗口无关：合盖（外屏）状态下角度依然实时上报
 *
 * 路径二：折叠姿态（状态 —— Jetpack WindowManager
 * （androidx.window）的 {@link FoldingFeature}，只报告 FLAT / HALF_OPENED
 * 等姿态状态和转轴位置，不提供具体角度。窗口必须跨越转轴（内屏展开/
 * 半开状态）才会出现 FoldingFeature；合盖后外屏窗口没有该特性
 *
 * 两条路径叠加，即可覆盖小折叠的全部形态：内屏完全展开、悬停半开、
 * 合盖外屏。详见 README.md「转轴角度获取路径」一节。
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final String TAG = "HingeDemo";
    /** 日志时间戳格式：时:分:秒.毫秒 */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ---- 角度区视图 ----
    private TextView tvAngle;          // 大字号实时角度
    private TextView tvMin;            // 本次会话最小角度
    private TextView tvMax;            // 本次会话最大角度
    private TextView tvEvents;         // 传感器事件计数
    private ProgressBar pbAngle;       // 0-180° 进度条
    // ---- 姿态区视图 ----
    private TextView tvState;          // 当前折叠姿态
    private TextView tvStateDetail;    // 姿态详情（方向/遮挡/物理区域）
    private TextView tvPostureTitle;   // 姿态卡片标题（内屏/外屏切换文案）
    private TextView tvSensorInfo;     // 传感器硬件信息
    private TextView tvLog;            // 最近若干条原始事件流

    // ---- 路径一：转轴角度传感器 ----
    private SensorManager sensorManager;
    private Sensor hingeSensor;

    /** 当前角度（NaN 表示尚未收到任何事件） */
    private float angle = Float.NaN;
    /** 最小/最大角度，NaN 表示尚无样本 */
    private float minAngle = Float.NaN;
    private float maxAngle = Float.NaN;
    /** 上一次写入 logcat 的角度，变化超过 1° 才记一条，避免刷屏 */
    private float lastLoggedAngle = Float.NaN;
    private int eventCount;

    /** 最近 6 条事件的时间戳 + 角度，用于界面上的事件流回放 */
    private final Deque<String> recent = new ArrayDeque<>();

    // ---- 路径二：Jetpack WindowManager 折叠特性 ----
    private WindowInfoTrackerCallbackAdapter windowAdapter;
    private final Consumer<WindowLayoutInfo> layoutConsumer = this::onWindowLayout;
    /** 当前窗口的 FoldingFeature；合盖（外屏）时为 null */
    private FoldingFeature foldingFeature;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 沉浸式边衬距：内容避开状态栏 / 导航栏 / 挖孔
        applyWindowInsets();

        tvAngle = findViewById(R.id.tv_angle);
        tvMin = findViewById(R.id.tv_min);
        tvMax = findViewById(R.id.tv_max);
        tvEvents = findViewById(R.id.tv_events);
        pbAngle = findViewById(R.id.pb_angle);
        tvState = findViewById(R.id.tv_state);
        tvStateDetail = findViewById(R.id.tv_state_detail);
        tvPostureTitle = findViewById(R.id.tv_posture_title);
        tvSensorInfo = findViewById(R.id.tv_sensor_info);
        tvLog = findViewById(R.id.tv_log);

        pbAngle.setMax(180);

        // ---- 获取转轴角度传感器 ----
        // 路径一的入口：TYPE_HINGE_ANGLE 是标准传感器类型，厂商 HAL 负责把
        // 铰链霍尔/电位器读数换算成 0-180° 上报。wakeUp=false 表示只要
        // 非唤醒传感器（合盖休眠时可能停报，亮屏即恢复）。
        sensorManager = getSystemService(SensorManager.class);
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, false);
        if (hingeSensor == null) {
            // 设备不支持（非折叠机，或厂商未实现该类型）时的兜底提示
            tvAngle.setText("N/A");
            tvSensorInfo.setText(R.string.sensor_missing);
        } else {
            // 打印传感器硬件参数，便于确认拿到的是小米哪个 HAL 节点
            tvSensorInfo.setText(String.format(Locale.US,
                    "%s  (type %d)\nvendor: %s\nmax: %.0f°   resolution: %.2f°   wake-up: %s\nminDelay: %d µs",
                    hingeSensor.getName(),
                    hingeSensor.getType(),
                    hingeSensor.getVendor(),
                    hingeSensor.getMaximumRange(),
                    hingeSensor.getResolution(),
                    hingeSensor.isWakeUpSensor() ? "yes" : "no",
                    hingeSensor.getMinDelay()));
        }

        // ---- 初始化 Jetpack WindowManager ----
        // 路径二的入口：WindowInfoTracker 负责监听窗口与转轴的相对位置，
        // 输出 FoldingFeature（姿态状态 + 转轴矩形）。
        windowAdapter = new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this));
        // 先按“暂无数据”渲染一次，避免首帧空白
        renderPosture(null);
    }

    /** 处理窗口边衬距（状态栏/导航栏/挖孔），实现全屏沉浸布局 */
    private void applyWindowInsets() {
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 注册转轴角度监听。SENSOR_DELAY_GAME（约 50Hz）足以观察开合过程；
        // 注意：注册时机放在 onResume，onPause 统一注销，避免后台耗电。
        if (hingeSensor != null) {
            sensorManager.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        // 注册窗口布局监听（FoldingFeature 来自这里，而非传感器）
        windowAdapter.addWindowLayoutInfoListener(this, getMainExecutor(), layoutConsumer);
        renderPosture(foldingFeature);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 屏幕尺寸/方向变化（例如展开↔合盖切屏）时刷新一次姿态显示。
        // manifest 里 configChanges 已声明相关项，避免重建 Activity 丢状态。
        renderPosture(foldingFeature);
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        windowAdapter.removeWindowLayoutInfoListener(layoutConsumer);
        super.onPause();
    }

    /**
     * 路径一回调：转轴角度事件。
     * values[0] 即当前开合角（0° = 完全合上，180° = 完全展开平放）。
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HINGE_ANGLE) {
            return; // 只关心转轴角度这一种传感器
        }

        angle = event.values[0];
        eventCount++;
        // 维护本次会话的最小/最大值（NaN 时代表首个样本，直接初始化）
        minAngle = Float.isNaN(minAngle) ? angle : Math.min(minAngle, angle);
        maxAngle = Float.isNaN(maxAngle) ? angle : Math.max(maxAngle, angle);

        // 压入“时间 + 角度”事件流，只保留最近 6 条
        recent.addFirst(CLOCK.format(LocalTime.now())
                + "   " + String.format(Locale.US, "%.1f°", angle));
        while (recent.size() > 6) {
            recent.removeLast();
        }

        // 角度变化 ≥1° 时才写 logcat，方便 adb logcat -s HingeDemo 抓数据
        if (Float.isNaN(lastLoggedAngle) || Math.abs(angle - lastLoggedAngle) >= 1f) {
            Log.d(TAG, String.format(Locale.US, "hinge=%.1f° state=%s", angle, describeState()));
            lastLoggedAngle = angle;
        }

        renderAngle();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 转轴角度传感器没有精度变化语义，无需处理
    }

    /**
     * 路径二回调：窗口布局信息变化。
     * 从 DisplayFeature 列表中取出第一个 FoldingFeature；
     * 合盖（外屏）时窗口不跨越转轴，列表里不会有任何 FoldingFeature。
     */
    private void onWindowLayout(WindowLayoutInfo info) {
        FoldingFeature feature = null;
        List<DisplayFeature> features = info.getDisplayFeatures();
        for (DisplayFeature displayFeature : features) {
            if (displayFeature instanceof FoldingFeature) {
                feature = (FoldingFeature) displayFeature;
                break;
            }
        }
        Log.d(TAG, "windowLayout features=" + features.size()
                + " state=" + (feature == null ? "none" : feature.getState()));
        renderPosture(feature);
    }

    /** 把最新角度/最值/事件数/事件流刷到界面上 */
    private void renderAngle() {
        if (Float.isNaN(angle)) {
            return;
        }
        tvAngle.setText(String.format(Locale.US, "%.0f°", angle));
        pbAngle.setProgress(Math.round(angle));
        tvMin.setText(String.format(Locale.US, "MIN  %.1f°", minAngle));
        tvMax.setText(String.format(Locale.US, "MAX  %.1f°", maxAngle));
        tvEvents.setText(String.format(Locale.US, "EVENTS  %d", eventCount));

        StringBuilder builder = new StringBuilder();
        for (String line : recent) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        tvLog.setText(builder);
    }

    /**
     * 渲染当前折叠姿态卡片。
     *
     * <p>优先用 {@link #isCoverWindow} 判定是否处于外屏（合盖）模式：
     * 外屏窗口没有 FoldingFeature，所以不能只依赖 WindowManager，
     * 必须结合 Display ID 与屏幕尺寸综合判断（详见该方法注释）。</p>
     */
    private void renderPosture(FoldingFeature feature) {
        foldingFeature = feature;

        // ---- 外屏（合盖）模式：整张卡片换成外屏专属文案 ----
        if (isCoverWindow(feature)) {
            tvPostureTitle.setText(R.string.posture_title_cover);
            tvState.setText(R.string.posture_cover);
            tvStateDetail.setText(R.string.posture_cover_detail);
            return;
        }

        tvPostureTitle.setText(R.string.posture_title);

        // ---- 无 FoldingFeature 且不在外屏：等待或异常状态 ----
        if (feature == null) {
            tvState.setText(R.string.posture_none);
            tvStateDetail.setText(R.string.posture_none_detail);
            return;
        }

        // ---- 内屏且有 FoldingFeature：翻译姿态状态 ----
        String state;
        if (FoldingFeature.State.FLAT.equals(feature.getState())) {
            state = "完全展开 · FLAT";
        } else if (FoldingFeature.State.HALF_OPENED.equals(feature.getState())) {
            state = "半折叠 · HALF_OPENED";   // 小折叠的悬停态
        } else {
            state = "未知 · " + feature.getState();
        }
        tvState.setText(state);

        // 转轴的空间属性：方向、是否分隔窗口、是否遮挡内容
        String orientation = FoldingFeature.Orientation.HORIZONTAL.equals(feature.getOrientation())
                ? "转轴水平（上下分区）"
                : "转轴垂直（左右分区）";
        String separating = feature.isSeparating() ? "转轴分隔窗口" : "转轴未分隔窗口";
        String occlusion = FoldingFeature.OcclusionType.FULL.equals(feature.getOcclusionType())
                ? "铰链区域被遮挡"
                : "铰链区域不遮挡";

        // 转轴的物理位置矩形（窗口坐标系），可用来做悬停分屏布局
        Rect bounds = feature.getBounds();
        tvStateDetail.setText(String.format(Locale.US,
                "%s\n%s · %s\n物理折叠区域: [%d, %d] – [%d, %d]",
                orientation, separating, occlusion,
                bounds.left, bounds.top, bounds.right, bounds.bottom));
    }

    /** logcat 用：当前 FoldingFeature 状态的简短描述 */
    private String describeState() {
        return foldingFeature == null ? "none" : String.valueOf(foldingFeature.getState());
    }

    /**
     * 判定当前窗口是否运行在【外屏】（合盖状态）。
     *
     * <p>外屏窗口没有 FoldingFeature，所以判定逻辑分三层：</p>
     * <ol>
     *   <li>窗口所在 Display 不是默认显示器 → 一定是外屏（内屏永远是
     *       DEFAULT_DISPLAY，外屏是 MIUI 新建的副显示器）；</li>
     *   <li>有 FoldingFeature → 一定在内屏（窗口跨越了转轴）；</li>
     *   <li>兜底：外屏物理尺寸很小（此处以高度 < 600dp 为界），
     *       以此区分“内屏但窗口未跨轴”与“外屏”两种无特性情况。</li>
     * </ol>
     *
     * <p>注意：能在副屏（外屏）显示本身依赖 manifest 中的
     * {@code miui.supportFlipFullScreen} 元数据与 {@code miui.fliphome.default}
     * intent-filter，并且通常受小米外屏白名单限制，见 README.md。</p>
     */
    private boolean isCoverWindow(FoldingFeature feature) {
        Display display = getDisplay();
        if (display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY) {
            return true;   // 副显示器 = 外屏
        }
        if (feature != null) {
            return false;  // 有折叠特性 = 内屏
        }
        // 兜底：按屏幕高度粗判（MIX Flip 2 外屏高度远小于 600dp）
        int heightDp = getResources().getConfiguration().screenHeightDp;
        Log.d(TAG, "display=" + (display == null ? "null" : display.getDisplayId())
                + " heightDp=" + heightDp);
        return heightDp > 0 && heightDp < 600;
    }
}
