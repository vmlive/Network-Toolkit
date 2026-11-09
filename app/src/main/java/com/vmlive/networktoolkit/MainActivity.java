package com.vmlive.networktoolkit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.vmlive.networktoolkit.core.NetworkTools;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native Android UI. No WebView, analytics, account, or background service. */
public final class MainActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout root, navigation;
    private FrameLayout content;
    private int tab;
    private boolean dark;
    private int bg, card, ink, muted, line, accent, tint;
    private String detailId, category = "全部", query = "", lastResult = "";
    private EditText input;
    private TextView output, state, runButton;
    private NetworkTools.Cancellation cancellation;
    private Future<?> future;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int generation;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("toolkit", MODE_PRIVATE);
        dark = prefs.getBoolean("dark", false);
        if (saved != null) { tab = saved.getInt("tab"); detailId = saved.getString("detail"); lastResult = saved.getString("result", ""); }
        buildShell();
        if (detailId != null) {
            showTool(Tool.find(detailId), saved == null ? null : saved.getString("input"), lastResult);
        } else renderTab();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putInt("tab", tab); out.putString("detail", detailId);
        out.putString("result", lastResult);
        if (input != null) out.putString("input", input.getText().toString());
    }
    @Override protected void onDestroy() { cancelTask(); worker.shutdownNow(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (detailId != null) { cancelTask(); detailId = null; renderTab(); }
        else if (tab != 0) { tab = 0; renderTab(); }
        else super.onBackPressed();
    }
    @Override protected void onResume() {
        super.onResume();
        if (root != null && tab == 0 && detailId == null) renderTab();
    }
    private void palette() {
        bg = color(dark ? "#10191E" : "#F5F7FA"); card = color(dark ? "#1B272E" : "#FFFFFF");
        ink = color(dark ? "#EDF6F6" : "#172A34"); muted = color(dark ? "#9CACB5" : "#788891");
        line = color(dark ? "#2B3C44" : "#E8EDF0"); accent = color(dark ? "#69DCC7" : "#008E83");
        tint = color(dark ? "#173F3C" : "#E7F6F2");
    }
    private void buildShell() {
        palette();
        getWindow().setNavigationBarColor(card);
        getWindow().setStatusBarColor(bg);
        int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 27) lightFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : lightFlags);
        root = column(); root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(system.left, system.top, system.right, system.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = row(); navigation.setBackgroundColor(card); navigation.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, dp(72)));
        setContentView(root); root.requestApplyInsets();
    }
    private void drawNav() {
        navigation.removeAllViews(); navigation.setVisibility(detailId == null ? View.VISIBLE : View.GONE);
        String[] names = {"首页", "全部工具", "历史", "设置"}; int[] icons = {6, 3, 1, 9};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setContentDescription(names[i]);
            item.addView(new Symbol(icons[i], tab == i ? accent : muted), new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView label = text(names[i], 11, tab == i ? accent : muted, tab == i);
            label.setGravity(Gravity.CENTER); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(4); item.addView(label, lp);
            navigation.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
            item.setOnClickListener(v -> { cancelTask(); tab = index; detailId = null; category = "全部"; query = ""; renderTab(); });
        }
    }
    private void renderTab() {
        detailId = null; input = null; output = null; drawNav(); content.removeAllViews();
        if (tab == 0) home(); else if (tab == 1) tools(); else if (tab == 2) history(); else settings();
    }
    private LinearLayout page() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout body = column(); body.setPadding(dp(22), dp(16), dp(22), dp(28));
        scroll.addView(body); content.addView(scroll); return body;
    }
    private void title(LinearLayout body, String eyebrow, String title, String subtitle) {
        TextView small = text(eyebrow, 11, accent, true); small.setLetterSpacing(.12f); body.addView(small);
        TextView heading = text(title, 30, ink, true); add(body, heading, 6);
        add(body, text(subtitle, 13, muted, false), 8);
    }
    private void home() {
        LinearLayout body = page(); title(body, "NETWORK TOOLKIT", "网络工具箱", "把复杂的网络问题，变得简单。");
        LinearLayout banner = column(); banner.setPadding(dp(20), dp(20), dp(20), dp(20));
        banner.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, dark ? new int[]{color("#13554F"), color("#193E4A")} : new int[]{color("#DDF5EC"), color("#E2F1F9")}));
        ((GradientDrawable) banner.getBackground()).setCornerRadius(dp(22));
        LinearLayout statusRow = row(); statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.addView(new Symbol(0, accent), new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout statusText = column(); TextView connected = text(DeviceTools.connection(this), 17, ink, true); statusText.addView(connected);
        add(statusText, text("当前设备 · 实时网络状态", 11, muted, false), 5);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1); sp.leftMargin = dp(12); statusRow.addView(statusText, sp); banner.addView(statusRow);
        TextView check = text("查看网络详情   →", 13, accent, true); add(banner, check, 22); banner.setOnClickListener(v -> showTool(Tool.find("network"), null, "")); add(body, banner, 24);
        LinearLayout numbers = row(); numbers.setGravity(Gravity.CENTER_VERTICAL);
        numbers.addView(stat(String.valueOf(Tool.ALL.size()), "实用工具"), new LinearLayout.LayoutParams(0, -2, 1));
        numbers.addView(stat("0", "广告与追踪"), new LinearLayout.LayoutParams(0, -2, 1));
        numbers.addView(stat("本地", "历史记录"), new LinearLayout.LayoutParams(0, -2, 1)); add(body, numbers, 22);
        section(body, "常用工具", "全部工具 →", () -> { tab = 1; renderTab(); });
        List<Tool> frequent = new ArrayList<>(); for (String id : Arrays.asList("tcp", "dns", "http", "ports")) frequent.add(Tool.find(id)); grid(body, frequent);
        Set<String> favorites = favorites(); section(body, "我的收藏", favorites.size() + " 个", null);
        if (favorites.isEmpty()) {
            LinearLayout empty = panel(); empty.addView(text("让常用工具触手可及", 14, ink, true));
            add(empty, text("打开任意工具，点击右上角 ☆ 即可收藏。", 12, muted, false), 8); add(body, empty, 12);
        } else { List<Tool> list = new ArrayList<>(); for (Tool t : Tool.ALL) if (favorites.contains(t.id)) list.add(t); grid(body, list); }
        add(body, text("为每一次连接，找到答案。", 11, muted, false), 26);
    }
    private LinearLayout stat(String number, String label) {
        LinearLayout s = column(); s.setGravity(Gravity.CENTER); s.addView(text(number, 21, ink, true)); add(s, text(label, 11, muted, false), 5); return s;
    }
    private void tools() {
        LinearLayout body = page(); title(body, "EXPLORE", "全部工具", "诊断、开发、计算，一站完成。");
        EditText search = edit("搜索工具、DNS、HTTP…"); search.setSingleLine(true); search.setText(query); add(body, search, 22);
        android.widget.HorizontalScrollView categories = new android.widget.HorizontalScrollView(this); categories.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row(); categories.addView(chips); add(body, categories, 16);
        LinearLayout results = column(); add(body, results, 6);
        for (String c : Arrays.asList("全部", "网络诊断", "开发工具", "地址工具", "编码工具", "收藏")) {
            TextView chip = text(c, 12, c.equals(category) ? card : muted, c.equals(category)); chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(10), dp(14), dp(10)); chip.setBackground(round(c.equals(category) ? accent : card, 18));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.rightMargin = dp(8); chips.addView(chip, cp);
            chip.setOnClickListener(v -> { query = search.getText().toString(); category = c; content.removeAllViews(); tools(); });
        }
        fillTools(results);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { query = s.toString(); fillTools(results); }
            public void afterTextChanged(Editable e) {}
        });
    }
    private void fillTools(LinearLayout results) {
        results.removeAllViews(); String q = query.trim().toLowerCase(Locale.ROOT); Set<String> fav = favorites();
        List<Tool> found = new ArrayList<>();
        for (Tool t : Tool.ALL) if ((category.equals("全部") || category.equals(t.category) || category.equals("收藏") && fav.contains(t.id)) &&
            (t.name + t.id + t.description).toLowerCase(Locale.ROOT).contains(q)) found.add(t);
        add(results, text("找到 " + found.size() + " 个工具", 11, muted, false), 12);
        if (found.isEmpty()) add(results, text("没有匹配的工具，试试其他关键词。", 14, muted, false), 30); else grid(results, found);
    }
    private void section(LinearLayout body, String heading, String action, Runnable click) {
        LinearLayout r = row(); r.setGravity(Gravity.CENTER_VERTICAL); r.addView(text(heading, 18, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView a = text(action, 11, click == null ? muted : accent, false); r.addView(a); if (click != null) a.setOnClickListener(v -> click.run()); add(body, r, 28);
    }
    private void grid(LinearLayout body, List<Tool> tools) {
        for (int i = 0; i < tools.size(); i += 2) {
            LinearLayout r = row();
            for (int j = 0; j < 2; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(136), 1); if (j == 0) lp.rightMargin = dp(10);
                if (i + j >= tools.size()) { r.addView(new View(this), lp); continue; }
                Tool t = tools.get(i + j); LinearLayout tile = panel(); tile.setPadding(dp(16), dp(16), dp(12), dp(12));
                FrameLayout icon = new FrameLayout(this); icon.setBackground(round(tint, 12)); Symbol symbol = new Symbol(t.icon, accent);
                FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER); icon.addView(symbol, ip); tile.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
                add(tile, text(t.name, 15, ink, true), 12); add(tile, text(t.description, 10, muted, false), 5);
                tile.setContentDescription(t.name + "，" + t.description); tile.setOnClickListener(v -> showTool(t, null, "")); r.addView(tile, lp);
            }
            add(body, r, 10);
        }
    }
    private void showTool(Tool tool, String priorInput, String priorResult) {
        cancelTask(); detailId = tool.id; lastResult = priorResult == null ? "" : priorResult; drawNav(); content.removeAllViews();
        LinearLayout body = page(); LinearLayout top = row(); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = action("‹ 返回", false); top.addView(back); back.setOnClickListener(v -> onBackPressed());
        TextView label = text(tool.category, 12, muted, false); label.setGravity(Gravity.CENTER); top.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView star = action(favorites().contains(tool.id) ? "★" : "☆", false); star.setTextSize(25); star.setContentDescription("收藏工具"); top.addView(star);
        star.setOnClickListener(v -> { Set<String> set = favorites(); if (!set.add(tool.id)) set.remove(tool.id); prefs.edit().putStringSet("favorites", set).apply(); star.setText(set.contains(tool.id) ? "★" : "☆"); }); body.addView(top);
        TextView heading = text(tool.name, 29, ink, true); add(body, heading, 24); add(body, text(tool.description, 13, muted, false), 8);
        LinearLayout form = panel(); add(form, text("输入参数", 12, muted, true), 0);
        input = edit(tool.placeholder); input.setText(priorInput == null ? tool.initial : priorInput); input.setMinLines(2); input.setMaxLines(6); input.setGravity(Gravity.TOP); input.setTextSize(14);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(false); add(form, input, 12);
        if (tool.id.equals("network") || tool.id.equals("public_ip")) { form.removeAllViews(); form.addView(text("此工具无需输入参数", 13, muted, false)); input.setVisibility(View.GONE); }
        runButton = action("开始检测", true);
        if (tool.category.equals("编码工具") || tool.id.equals("subnet")) runButton.setText("开始计算");
        if (tool.id.equals("wol")) runButton.setText("发送魔术包");
        add(form, runButton, 16); runButton.setOnClickListener(v -> {
            if (future != null && !future.isDone()) { cancelTask(); state.setText("已取消"); runButton.setText("重新开始"); return; }
            String value = input.getText().toString();
            if (tool.id.equals("speed")) new AlertDialog.Builder(this).setTitle("下载测速").setMessage("会消耗网络流量，单次最多 10 MiB。继续测速？").setNegativeButton("取消", null).setPositiveButton("开始测速", (d, w) -> execute(tool, value)).show();
            else execute(tool, value);
        }); add(body, form, 24);
        LinearLayout hint = column(); hint.setPadding(dp(4), 0, dp(4), 0); hint.addView(text(tool.help, 12, muted, false)); add(body, hint, 16);
        LinearLayout result = panel(); LinearLayout resultHeader = row(); resultHeader.setGravity(Gravity.CENTER_VERTICAL);
        resultHeader.addView(text("检测结果", 16, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView copy = action("复制", false); resultHeader.addView(copy); copy.setOnClickListener(v -> copyResult());
        TextView share = action("分享", false); resultHeader.addView(share); share.setOnClickListener(v -> shareResult(tool.name)); result.addView(resultHeader);
        state = text(lastResult.isEmpty() ? "等待开始" : "历史结果", 11, accent, true); add(result, state, 12);
        output = text(lastResult.isEmpty() ? "点击上方按钮，查看实际检测结果。" : lastResult, 12, muted, false);
        output.setTypeface(Typeface.MONOSPACE); output.setTextIsSelectable(true); output.setLineSpacing(dp(4), 1); add(result, output, 14); add(body, result, 24);
    }
    private void execute(Tool tool, String value) {
        cancelTask(); int token = ++generation; cancellation = new NetworkTools.Cancellation(); NetworkTools.Cancellation cancel = cancellation;
        lastResult = ""; output.setText("正在执行…"); state.setText("进行中"); runButton.setText("取消");
        NetworkTools.Progress progress = message -> runOnUiThread(() -> { if (generation == token && output != null) output.setText(message); });
        future = worker.submit(() -> {
            long started = System.nanoTime(); String result; boolean success;
            try {
                cancel.check();
                switch (tool.id) {
                    case "network": result = DeviceTools.network(getApplicationContext()); break;
                    case "public_ip": result = DeviceTools.publicIp(cancel); break;
                    case "speed": result = DeviceTools.speed(value, cancel, progress); break;
                    default: result = NetworkTools.execute(tool.id, value, cancel, progress);
                }
                cancel.check(); success = true;
            } catch (CancellationException e) { return; }
            catch (Exception e) { result = "执行失败\n\n" + e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "未知错误" : e.getMessage()) + "\n\n请检查输入、网络连接及目标服务状态后重试。"; success = false; }
            String completed = result; boolean ok = success; long ms = (System.nanoTime() - started) / 1_000_000;
            runOnUiThread(() -> {
                if (generation != token || output == null) return;
                lastResult = completed; output.setText(completed); output.setTextColor(ink); state.setText(getString(R.string.result_status, ok ? "已完成" : "失败", ms)); runButton.setText(R.string.run_again);
                saveHistory(tool, value, completed, ok);
            });
        });
    }
    private void cancelTask() {
        generation++;
        if (cancellation != null) cancellation.cancel();
        if (future != null) future.cancel(true);
        future = null; cancellation = null;
    }
    private void copyResult() {
        if (lastResult.isEmpty()) { toast("尚无结果"); return; }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("网络工具箱结果", lastResult)); toast("结果已复制");
    }
    private void shareResult(String name) {
        if (lastResult.isEmpty()) { toast("尚无结果"); return; }
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, name + "\n\n" + lastResult);
        startActivity(Intent.createChooser(send, "分享检测结果"));
    }
    private Set<String> favorites() { return new HashSet<>(prefs.getStringSet("favorites", new HashSet<>())); }
    private JSONArray records() { try { return new JSONArray(prefs.getString("history", "[]")); } catch (Exception e) { return new JSONArray(); } }
    private void saveHistory(Tool tool, String value, String result, boolean ok) {
        if (!prefs.getBoolean("save_history", true)) return;
        try {
            JSONObject entry = new JSONObject().put("id", tool.id).put("input", limit(value, 2048)).put("result", limit(result, 16384)).put("time", System.currentTimeMillis()).put("ok", ok);
            JSONArray next = new JSONArray().put(entry), old = records();
            for (int i = 0; i < Math.min(old.length(), 49); i++) next.put(old.get(i));
            prefs.edit().putString("history", next.toString()).apply();
        } catch (Exception ignored) { toast("历史记录保存失败"); }
    }
    private static String limit(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "\n[记录已截断]"; }
    private void history() {
        LinearLayout body = page(); title(body, "ACTIVITY", "检测历史", "最近 50 条记录，仅保存在此设备。");
        TextView clear = action("清空历史", false); add(body, clear, 16); clear.setOnClickListener(v -> clearHistory());
        JSONArray records = records();
        if (records.length() == 0) { LinearLayout empty = panel(); empty.addView(text("还没有检测记录", 17, ink, true)); add(empty, text("完成一次检测后，你可以在这里回看和分享结果。", 13, muted, false), 12); add(body, empty, 28); return; }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
        for (int i = 0; i < records.length(); i++) {
            JSONObject entry = records.optJSONObject(i); if (entry == null) continue; Tool t = Tool.find(entry.optString("id"));
            LinearLayout item = panel(); LinearLayout h = row(); h.addView(text(t.name, 16, ink, true), new LinearLayout.LayoutParams(0, -2, 1)); h.addView(text(entry.optBoolean("ok") ? "已完成" : "失败", 11, entry.optBoolean("ok") ? accent : color("#C96451"), false)); item.addView(h);
            String target = entry.optString("input").replace('\n', ' '); if (!target.isEmpty()) add(item, text(target, 12, muted, false), 8);
            add(item, text(format.format(new java.util.Date(entry.optLong("time"))), 11, muted, false), 10);
            item.setOnClickListener(v -> showTool(t, entry.optString("input"), entry.optString("result"))); add(body, item, 12);
        }
    }
    private void clearHistory() {
        new AlertDialog.Builder(this).setTitle("清空本机历史？").setMessage("删除所有检测输入和结果，无法恢复。").setNegativeButton("取消", null).setPositiveButton("清空", (d, w) -> { prefs.edit().remove("history").apply(); renderTab(); toast("已清空"); }).show();
    }
    private void settings() {
        LinearLayout body = page(); title(body, "PREFERENCES", "设置", "简单、透明，由你掌控。");
        LinearLayout appearance = panel(); appearance.addView(text("外观与记录", 16, ink, true));
        Switch theme = new Switch(this); theme.setText("深色模式"); theme.setTextColor(ink); theme.setTextSize(14); theme.setChecked(dark); theme.setPadding(0, dp(16), 0, dp(16)); appearance.addView(theme);
        theme.setOnCheckedChangeListener((v, checked) -> { dark = checked; prefs.edit().putBoolean("dark", checked).apply(); buildShell(); renderTab(); });
        Switch historySwitch = new Switch(this); historySwitch.setText("保存检测历史"); historySwitch.setTextColor(ink); historySwitch.setTextSize(14); historySwitch.setChecked(prefs.getBoolean("save_history", true)); historySwitch.setPadding(0, dp(16), 0, dp(16)); appearance.addView(historySwitch);
        historySwitch.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("save_history", checked).apply());
        add(appearance, text("历史可能包含网址参数和输入文本。关闭后不再新增；已有记录可单独清空。", 12, muted, false), 4);
        TextView clear = action("清空全部历史记录", false); add(appearance, clear, 16); clear.setOnClickListener(v -> clearHistory()); add(body, appearance, 24);
        LinearLayout privacy = panel(); privacy.addView(text("隐私与网络", 16, ink, true));
        add(privacy, text("无需注册，无广告，无分析 SDK。收藏与历史仅保存在本机，卸载后删除。\n\n只有你启动检测时才访问目标服务。公网 IP 使用 api.ipify.org；默认测速使用 speed.cloudflare.com。DNS 查询使用设备解析器。其余工具仅访问你输入的目标或在本机计算。\n\nHTTP 工具允许明文 HTTP 以诊断旧服务；请勿发送敏感内容。HTTPS 保持系统证书和主机名验证。", 13, muted, false), 14); add(body, privacy, 16);
        LinearLayout about = panel(); about.addView(text("关于网络工具箱", 16, ink, true));
        add(about, text("版本 1.0.0 · Android 8.0+\n原生 Android 开发 · 开源项目", 13, muted, false), 12);
        TextView source = action("查看 GitHub 源代码 ↗", false); add(about, source, 16); source.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/vmlive/Network-Toolkit")))); add(body, about, 16);
    }
    private TextView action(String label, boolean primary) {
        TextView t = text(label, 14, primary ? (dark ? color("#102A26") : Color.WHITE) : accent, true);
        t.setGravity(Gravity.CENTER); t.setMinHeight(dp(48)); t.setPadding(dp(14), dp(8), dp(14), dp(8));
        if (primary) t.setBackground(round(accent, 14)); t.setClickable(true); t.setFocusable(true);
        t.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); }
        });
        return t;
    }
    private EditText edit(String hint) {
        EditText e = new EditText(this); e.setTextSize(14); e.setTextColor(ink); e.setHintTextColor(muted); e.setHint(hint);
        e.setPadding(dp(14), dp(14), dp(14), dp(14)); e.setBackground(round(bg, 12)); e.setSelectAllOnFocus(false); e.setMinHeight(dp(52)); return e;
    }
    private LinearLayout panel() { LinearLayout p = column(); p.setPadding(dp(18), dp(18), dp(18), dp(18)); p.setBackground(round(card, 18)); return p; }
    private TextView text(String value, float size, int c, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(c); t.setFontFeatureSettings("kern");
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setIncludeFontPadding(false); return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private void add(LinearLayout parent, View child, int margin) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(margin); parent.addView(child, lp); }
    private GradientDrawable round(int c, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(c); d.setCornerRadius(dp(radius)); return d; }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private static int color(String c) { return Color.parseColor(c); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }

    private final class Symbol extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final int kind, c;
        Symbol(int kind, int c) { super(MainActivity.this); this.kind = kind; this.c = c; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save(); canvas.scale(getWidth() / 24f, getHeight() / 24f);
            p.setColor(c); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.65f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            switch (kind) {
                case 0: canvas.drawArc(2, 4, 22, 24, 220, 100, false, p); canvas.drawArc(6, 9, 18, 21, 220, 100, false, p); canvas.drawCircle(12, 19, 1, p); break;
                case 1: canvas.drawCircle(12, 12, 9, p); canvas.drawLine(12, 12, 17, 7, p); canvas.drawLine(6, 16, 18, 16, p); break;
                case 2: canvas.drawCircle(12, 12, 9, p); canvas.drawOval(8, 3, 16, 21, p); canvas.drawLine(3, 12, 21, 12, p); break;
                case 3: for (int x = 4; x <= 14; x += 10) for (int y = 4; y <= 14; y += 10) canvas.drawRoundRect(x, y, x + 6, y + 6, 1.5f, 1.5f, p); break;
                case 4: canvas.drawLine(8, 6, 3, 12, p); canvas.drawLine(3, 12, 8, 18, p); canvas.drawLine(16, 6, 21, 12, p); canvas.drawLine(21, 12, 16, 18, p); canvas.drawLine(14, 4, 10, 20, p); break;
                case 5: canvas.drawRoundRect(5, 10, 19, 21, 2, 2, p); canvas.drawArc(8, 3, 16, 17, 180, 180, false, p); canvas.drawPoint(12, 15, p); break;
                case 6: canvas.drawRoundRect(8, 3, 16, 9, 1, 1, p); canvas.drawLine(12, 9, 12, 15, p); canvas.drawLine(4, 15, 20, 15, p); canvas.drawLine(4, 15, 4, 18, p); canvas.drawLine(20, 15, 20, 18, p); canvas.drawRoundRect(1, 18, 7, 23, 1, 1, p); canvas.drawRoundRect(17, 18, 23, 23, 1, 1, p); break;
                case 7: canvas.drawArc(3, 3, 21, 21, 305, 290, false, p); canvas.drawLine(12, 2, 12, 12, p); break;
                case 8: canvas.drawRoundRect(2, 4, 22, 20, 3, 3, p); p.setStyle(Paint.Style.FILL); p.setTypeface(Typeface.MONOSPACE); p.setTextSize(9); canvas.drawText("64", 6, 15, p); break;
                default: canvas.drawLine(9, 3, 6, 21, p); canvas.drawLine(18, 3, 15, 21, p); canvas.drawLine(3, 9, 22, 9, p); canvas.drawLine(2, 16, 21, 16, p);
            }
            canvas.restore();
        }
    }
}
