package com.vmlive.networktoolkit;

import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicReference;

/** Runs entirely offline on a device: verifies navigation and real calculator execution. */
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

    public void testLaunchAndNavigation() {
        assertText("网络工具箱");
        click("全部工具");
        assertText("找到 16 个工具");
        click("设置");
        assertText("深色模式");
        click("历史");
        assertText("还没有检测记录");
    }

    public void testSearchBase64AndHistory() throws Exception {
        click("全部工具");
        input("Base64");
        assertText("找到 2 个工具");
        click("Base64 编码");
        input("Hello");
        click("开始计算");
        awaitText("SGVsbG8=");
        click("‹ 返回");
        click("历史");
        assertText("Base64 编码");
        assertText("Hello");
    }

    public void testSubnetBoundaryAndFavorites() throws Exception {
        click("全部工具");
        input("子网");
        click("子网计算");
        input("192.0.2.10/31");
        click("开始计算");
        awaitText("可用主机：2");
        click("☆");
        click("‹ 返回");
        click("首页");
        assertText("子网计算");
    }

    public void testInvalidInputReturnsFailure() throws Exception {
        click("全部工具");
        input("子网");
        click("子网计算");
        input("192.0.2.10/33");
        click("开始计算");
        awaitText("执行失败");
        assertText("重新开始");
    }

    public void testDeviceNetworkInformation() throws Exception {
        click("查看网络详情   →");
        click("开始检测");
        awaitText("本机网络接口");
        assertText("重新开始");
    }

    private void click(String label) {
        getInstrumentation().runOnMainSync(() -> {
            View view = findText(activity.getWindow().getDecorView(), label, false);
            assertNotNull("Missing UI: " + label, view);
            while (!view.isClickable() && view.getParent() instanceof View) view = (View) view.getParent();
            assertTrue("UI is not clickable: " + label, view.performClick());
        });
        getInstrumentation().waitForIdleSync();
    }

    private void input(String value) {
        getInstrumentation().runOnMainSync(() -> {
            EditText edit = findInput(activity.getWindow().getDecorView());
            assertNotNull("Expected an input field", edit);
            edit.setText(value);
        });
        getInstrumentation().waitForIdleSync();
    }

    private void assertText(String value) {
        getInstrumentation().runOnMainSync(() -> assertNotNull("Missing text: " + value,
                findText(activity.getWindow().getDecorView(), value, true)));
    }

    private void awaitText(String value) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            AtomicReference<View> found = new AtomicReference<>();
            getInstrumentation().runOnMainSync(() -> found.set(findText(activity.getWindow().getDecorView(), value, true)));
            if (found.get() != null) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for text: " + value);
    }

    private static View findText(View view, String value, boolean partial) {
        if (view instanceof TextView && !(view instanceof EditText)) {
            String text = ((TextView) view).getText().toString();
            if (partial ? text.contains(value) : text.equals(value)) return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), value, partial);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static EditText findInput(View view) {
        if (view instanceof EditText && view.getVisibility() == View.VISIBLE) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findInput(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
