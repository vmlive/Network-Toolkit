package com.vmlive.networktoolkit;

import android.test.ActivityInstrumentationTestCase2;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Actual device UI regressions. These tests never start external network requests. */
@SuppressWarnings("deprecation")
public final class AppSmokeTest extends ActivityInstrumentationTestCase2<MainActivity> {
    private MainActivity activity;
    public AppSmokeTest() { super(MainActivity.class); }

    @Override protected void setUp() throws Exception {
        super.setUp();
        getInstrumentation().getTargetContext().getSharedPreferences("toolkit", 0).edit().clear().commit();
        activity = getActivity();
        getInstrumentation().waitForIdleSync();
    }

    public void testReferenceHomeAndFiveNavigationTabs() {
        assertText("网络工具箱"); assertText("一键诊断");
        for (String item : new String[]{"Ping", "Traceroute", "DNS", "TCP", "UDP", "局域网扫描", "HTTP", "连接测试", "延迟测试", "本机信息"}) assertText(item);
        click("测速"); assertText("网速测试");
        click("扫描"); assertText("Bonjour"); assertText("SSDP");
        click("IP/MAC"); assertText("IP / MAC 查询");
        click("我的"); assertText("深色模式");
        click("本地测"); assertText("网络层工具");
    }

    public void testSearchBase64AndHistory() throws Exception {
        allTools(); input(0, "Base64"); assertText("找到 2 个工具");
        click("Base64 编码"); input(0, "Hello"); click("开始计算");
        awaitText("SGVsbG8="); click("我的"); click("检测历史");
        assertText("Base64 编码"); assertText("Hello");
    }

    public void testSubnetBoundaryAndFavorites() throws Exception {
        allTools(); input(0, "子网"); click("子网计算");
        input(0, "192.0.2.10/31"); click("开始计算");
        awaitText("可用主机：2"); clickDescription("收藏工具");
        click("我的"); click("我的收藏"); assertText("找到 1 个工具"); assertText("子网计算");
    }

    public void testInvalidInputReturnsFailure() throws Exception {
        allTools(); input(0, "子网"); click("子网计算");
        input(0, "192.0.2.10/33"); click("开始计算");
        awaitText("执行失败"); assertText("重新开始");
    }

    public void testDeviceNetworkInformation() throws Exception {
        click("本机信息"); click("▶ 开始测试"); awaitText("本机网络接口"); assertText("重新开始");
    }

    public void testDiagnosticRequiresTargetAndCompletesRealLoopbackStages() throws Exception {
        click("一键诊断");
        getInstrumentation().runOnMainSync(() -> {
            View start = findClickableText(activity.getWindow().getDecorView(), "▶ 开始诊断");
            assertNotNull(start); assertFalse("Empty target must not start diagnosis", start.isEnabled());
        });
        input(0, "127.0.0.1"); click("▶ 开始诊断"); awaitText("诊断结束");
        assertText("1 / 5  DNS 查询"); assertText("2 / 5  ICMP Ping");
        assertText("3 / 5  TCP 80"); assertText("4 / 5  TCP 443"); assertText("5 / 5  路由追踪");
    }

    public void testDnsRecordAndCustomProviderBindingWithoutExternalTraffic() throws Exception {
        click("DNS"); assertText("DNS 查询"); click("AAAA"); click("更多");
        activeWindowText("DNS 记录类型", false); activeWindowText("TXT", true);
        assertText("TXT"); click("自定义"); input(1, "not-an-ip"); click("⌕ 查询");
        // Numeric-address validation runs before any socket or DNS lookup.
        awaitText("DNS 服务器必须是 IPv4 / IPv6 数字地址"); assertText("失败");
    }

    public void testSpeedTrafficConfirmationCanCancelWithoutStartingTest() throws Exception {
        click("测速");
        assertText("↓ 下载"); assertText("↑ 上传"); assertText("◷ 延迟"); assertText("〰 抖动");
        assertText("预估流量消耗"); assertText("--"); click("▶ 开始测速");
        activeWindowText("开始网速测试？", false); activeWindowText("取消", true);
        assertText("▶ 开始测速"); assertText("--");
        click("我的"); click("检测历史"); assertText("还没有检测记录");
    }

    public void testLanLandingDoesNotInventResults() {
        click("扫描"); assertText("网络信息"); assertText("Bonjour"); assertText("SSDP");
        assertText("发现的设备"); assertText("点击开始扫描以获取真实结果"); assertText("在线设备"); assertText("已扫描");
    }

    public void testMacInspectionActuallyRunsOffline() throws Exception {
        click("IP/MAC"); input(0, "02:11:22:33:44:55"); click("⌕ 查询");
        awaitText("MAC 地址信息"); assertText("标准格式：02:11:22:33:44:55");
        assertText("本地管理位"); assertText("未内置厂商数据库");
    }

    public void testIpSourcesStartIdleAndBothWebsiteEntriesLaunch() {
        click("IP/MAC"); assertText("原生公网 IP 诊断");
        assertText("ipify · IPv4"); assertText("ipify · IPv6"); assertText("Cloudflare Trace"); assertText("IP.SB"); assertText("尚未查询");
        // Intercept the actual Activity launch so the UI regression never queries live sites.
        android.app.Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(
                WebToolsActivity.class.getName(), new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true);
        try {
            click("IP.SKK.MOE · 完整网站   ↗");
            assertEquals("SKK entry must launch the embedded website Activity", 1, monitor.getHits());
            click("PING0 · IP 画像与检测   ↗");
            assertEquals("Ping0 entry must launch the embedded website Activity", 2, monitor.getHits());
        } finally { getInstrumentation().removeMonitor(monitor); }
        click("我的"); click("检测历史"); assertText("还没有检测记录");
    }

    public void testThemePersistsAcrossNavigation() {
        click("我的");
        getInstrumentation().runOnMainSync(() -> {
            Switch theme = findSwitch(activity.getWindow().getDecorView(), "深色模式");
            assertNotNull(theme); assertTrue("Reference default is dark", theme.isChecked()); theme.performClick();
        });
        getInstrumentation().waitForIdleSync(); click("本地测"); click("我的");
        getInstrumentation().runOnMainSync(() -> {
            Switch theme = findSwitch(activity.getWindow().getDecorView(), "深色模式"); assertNotNull(theme); assertFalse(theme.isChecked());
        });
        assertFalse(getInstrumentation().getTargetContext().getSharedPreferences("toolkit", 0).getBoolean("dark", true));
    }

    private void allTools() { click("我的"); click("全部工具"); }
    private void click(String label) {
        getInstrumentation().runOnMainSync(() -> {
            View target = findClickableText(activity.getWindow().getDecorView(), label);
            assertNotNull("Missing clickable UI: " + label, target); assertTrue("UI action failed: " + label, target.performClick());
        });
        getInstrumentation().waitForIdleSync();
    }
    private void clickDescription(String description) {
        getInstrumentation().runOnMainSync(() -> {
            View view = findDescription(activity.getWindow().getDecorView(), description); assertNotNull("Missing action: " + description, view);
            View target = clickableAncestor(view); assertNotNull("Not clickable: " + description, target); assertTrue(target.performClick());
        });
        getInstrumentation().waitForIdleSync();
    }
    private void input(int index, String value) {
        getInstrumentation().runOnMainSync(() -> {
            List<EditText> fields = new ArrayList<>(); findInputs(activity.getWindow().getDecorView(), fields);
            assertTrue("Expected input field " + index + ", found " + fields.size(), fields.size() > index); fields.get(index).setText(value);
        });
        getInstrumentation().waitForIdleSync();
    }
    private void assertText(String value) {
        getInstrumentation().runOnMainSync(() -> assertNotNull("Missing text: " + value, findText(activity.getWindow().getDecorView(), value)));
    }
    private void awaitText(String value) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            AtomicReference<View> found = new AtomicReference<>();
            getInstrumentation().runOnMainSync(() -> found.set(findText(activity.getWindow().getDecorView(), value)));
            if (found.get() != null) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for actual result: " + value);
    }
    /** Dialogs occupy a separate window from Activity.getWindow().getDecorView(). */
    private void activeWindowText(String value, boolean click) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo match : root.findAccessibilityNodeInfosByText(value)) {
                    if (match.getText() == null || !match.getText().toString().equals(value)) continue;
                    if (!click) return;
                    // AbsListView rows can implement ACTION_CLICK through an accessibility
                    // delegate even when isClickable() is false on the row itself.
                    if (match.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        getInstrumentation().waitForIdleSync(); return;
                    }
                    // Some platform dialogs expose only ListView's touch-item selection.
                    // Tap the actual matched row, rather than clicking the whole ListView.
                    Rect bounds = new Rect(); match.getBoundsInScreen(bounds);
                    if (!match.isVisibleToUser() || bounds.isEmpty()) continue;
                    long when = SystemClock.uptimeMillis();
                    MotionEvent down = MotionEvent.obtain(when, when, MotionEvent.ACTION_DOWN,
                            bounds.exactCenterX(), bounds.exactCenterY(), 0);
                    MotionEvent up = MotionEvent.obtain(when, when + 16, MotionEvent.ACTION_UP,
                            bounds.exactCenterX(), bounds.exactCenterY(), 0);
                    try {
                        getInstrumentation().sendPointerSync(down);
                        getInstrumentation().sendPointerSync(up);
                    } finally { down.recycle(); up.recycle(); }
                    getInstrumentation().waitForIdleSync(); return;
                }
            }
            Thread.sleep(50);
        }
        fail("Missing active dialog action: " + value);
    }
    private static View findClickableText(View view, String label) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) {
            View target = clickableAncestor(view); if (target != null) return target;
        }
        if (view instanceof TextView && !(view instanceof EditText)) {
            String value = ((TextView) view).getText().toString();
            if (value.equals(label) || value.startsWith(label + "   ›")) { View target = clickableAncestor(view); if (target != null) return target; }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { View found = findClickableText(group.getChildAt(i), label); if (found != null) return found; }
        }
        return null;
    }
    private static View clickableAncestor(View view) {
        while (view != null) { if (view.isClickable()) return view; if (!(view.getParent() instanceof View)) return null; view = (View) view.getParent(); }
        return null;
    }
    private static View findText(View view, String value) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && !(view instanceof EditText) && ((TextView) view).getText().toString().contains(value)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { View found = findText(group.getChildAt(i), value); if (found != null) return found; }
        }
        return null;
    }
    private static View findDescription(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { View found = findDescription(group.getChildAt(i), description); if (found != null) return found; }
        }
        return null;
    }
    private static void findInputs(View view, List<EditText> found) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof EditText) found.add((EditText) view);
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) findInputs(group.getChildAt(i), found); }
    }
    private static Switch findSwitch(View view, String title) {
        if (view instanceof Switch && ((Switch) view).getText().toString().equals(title)) return (Switch) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { Switch found = findSwitch(group.getChildAt(i), title); if (found != null) return found; }
        }
        return null;
    }
}
