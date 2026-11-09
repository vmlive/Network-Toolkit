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
import android.net.ConnectivityManager;
import android.net.LinkProperties;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.vmlive.networktoolkit.core.AdvancedDiagnostics;
import com.vmlive.networktoolkit.core.AddressTools;
import com.vmlive.networktoolkit.core.DnsClient;
import com.vmlive.networktoolkit.core.NetworkTools;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Native phone/tablet layout based on the user-provided reference screens. */
public final class MainActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout root, navigation;
    private FrameLayout content;
    private int tab;
    private boolean dark, tablet;
    private int bg, card, ink, muted, line;
    private final int blue = Color.rgb(0, 151, 255), cyan = Color.rgb(48, 205, 235);
    private final int purple = Color.rgb(170, 83, 235), green = Color.rgb(67, 199, 103);
    private String detailId, query = "", lastResult = "", dnsType = "A", family = "auto";
    private int packets = 3, dnsProvider, downloadMb = 5;
    private EditText input, customDns;
    private TextView output, state, runButton, prompt;
    private LinearLayout dnsDeck, deviceDeck, resultPanel;
    private TextView scanFound, scanCount, scanPercent;
    private final Map<String, TextView> siteLabels = new HashMap<>();
    private final Map<String, TextView> speedLabels = new HashMap<>();
    private Gauge gauge;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private NetworkTools.Cancellation cancellation;
    private Future<?> future;
    private int generation;
    private boolean busy;
    private String speedDownload = "", speedUpload = "https://speed.cloudflare.com/__up";
    private static final String[] DNS_NAMES = {"系统默认", "腾讯 DNS", "阿里 DNS", "114 DNS", "Google", "Cloudflare", "自定义"};
    private static final String[] DNS_IPS = {"", "119.29.29.29", "223.5.5.5", "114.114.114.114", "8.8.8.8", "1.1.1.1", ""};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("toolkit", MODE_PRIVATE);
        dark = prefs.getBoolean("dark", true);
        speedDownload = prefs.getString("speed_download", ""); speedUpload = prefs.getString("speed_upload", "https://speed.cloudflare.com/__up"); downloadMb = prefs.getInt("speed_mb", 5);
        if (saved != null) {
            tab = saved.getInt("tab"); detailId = saved.getString("detail"); lastResult = saved.getString("result", "");
            dnsType = saved.getString("dns_type", "A"); dnsProvider = saved.getInt("dns_provider");
            family = saved.getString("family", "auto"); packets = saved.getInt("packets", 3);
        }
        buildShell();
        if ("history".equals(detailId)) history();
        else if ("all_tools".equals(detailId) || "favorites".equals(detailId)) allTools("favorites".equals(detailId));
        else if (detailId != null) showTool(Tool.find(detailId), saved == null ? null : saved.getString("input"), lastResult);
        else renderTab();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putInt("tab", tab); out.putString("detail", detailId); out.putString("result", lastResult);
        out.putString("dns_type", dnsType); out.putInt("dns_provider", dnsProvider); out.putString("family", family); out.putInt("packets", packets);
        if (input != null) out.putString("input", input.getText().toString());
    }
    @Override protected void onDestroy() { cancelTask(); worker.shutdownNow(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (detailId != null) { cancelTask(); detailId = null; renderTab(); }
        else if (tab != 0) { cancelTask(); tab = 0; renderTab(); }
        else super.onBackPressed();
    }
    private void palette() {
        bg = color(dark ? "#000000" : "#FFFFFF"); card = color(dark ? "#1C1C1E" : "#F2F2F7");
        ink = color(dark ? "#F5F5F7" : "#141416"); muted = color(dark ? "#939397" : "#818186"); line = color(dark ? "#343436" : "#DDDDDF");
    }
    private void buildShell() {
        setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
        palette(); tablet = getResources().getConfiguration().screenWidthDp >= 600;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(dark || Build.VERSION.SDK_INT < 27 ? Color.BLACK : Color.WHITE);
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 27) light |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : light);
        root = column(); root.setBackgroundColor(bg);
        // We provide both palettes; Android Force Dark must not recolor the reference palette.
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().getDecorView().setForceDarkAllowed(false);
            root.setForceDarkAllowed(false);
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        FrameLayout frame = new FrameLayout(this); root.addView(frame, new LinearLayout.LayoutParams(-1, -1));
        content = new FrameLayout(this); FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -1);
        if (tablet) cp.topMargin = dp(58); frame.addView(content, cp);
        navigation = row(); navigation.setPadding(dp(4), dp(4), dp(4), dp(4)); navigation.setBackground(outline(card, line, 34)); navigation.setElevation(dp(8));
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(tablet ? dp(400) : -1, dp(tablet ? 44 : 62), tablet ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.BOTTOM);
        if (tablet) np.topMargin = dp(8); else { np.leftMargin = dp(18); np.rightMargin = dp(18); np.bottomMargin = dp(10); }
        frame.addView(navigation, np); setContentView(root); root.requestApplyInsets();
    }
    private void drawNav() {
        navigation.removeAllViews(); String[] labels = {"本地测", "测速", "扫描", "IP/MAC", "我的"}; int[] icons = {15, 1, 6, 16, 17};
        for (int i = 0; i < labels.length; i++) {
            final int index = i; LinearLayout item = tablet ? row() : column(); item.setGravity(Gravity.CENTER);
            if (tab == i) item.setBackground(round(dark ? color("#39393B") : color("#E5E5EA"), 28));
            if (!tablet) item.addView(new Symbol(icons[i], tab == i ? blue : ink), new LinearLayout.LayoutParams(dp(23), dp(23)));
            TextView t = text(labels[i], tablet ? 14 : 10, tab == i ? blue : ink, tab == i); t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2); if (!tablet) tl.topMargin = dp(3); item.addView(t, tl);
            item.setContentDescription(labels[i]); item.setClickable(true); item.setFocusable(true);
            item.setOnClickListener(v -> { cancelTask(); tab = index; detailId = null; query = ""; lastResult = ""; renderTab(); });
            navigation.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
    }
    private void resetViews() {
        input = null; customDns = null; output = null; state = null; runButton = null; prompt = null;
        dnsDeck = null; deviceDeck = null; resultPanel = null; gauge = null; siteLabels.clear(); speedLabels.clear();
    }
    private void renderTab() {
        detailId = null; resetViews(); content.removeAllViews(); drawNav();
        if (tab == 0) home(); else if (tab == 1) speedPage(); else if (tab == 2) scanPage(); else if (tab == 3) addressPage(); else mine();
    }
    private LinearLayout page() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout body = column(); body.setPadding(dp(16), dp(14), dp(16), dp(tablet ? 28 : 96));
        scroll.addView(body); content.addView(scroll); return body;
    }
    private void pageHeading(LinearLayout body, String title) {
        TextView t = text(title, 19, ink, true); t.setGravity(Gravity.CENTER); t.setMinHeight(dp(42)); body.addView(t);
    }
    private void section(LinearLayout body, String title) { add(body, text(title, 14, muted, false), 24); }
    private void home() {
        LinearLayout body = page(); LinearLayout brand = row(); brand.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout logo = new FrameLayout(this); logo.setBackground(gradient(22)); FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER);
        logo.addView(new Symbol(10, Color.WHITE), iconLp); brand.addView(logo, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView name = text("网络工具箱", 15, color("#B29BFF"), true); LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1); nameLp.leftMargin = dp(9); brand.addView(name, nameLp);
        TextView language = text("◎ 中", 12, color("#B29BFF"), false); language.setPadding(dp(12), dp(7), dp(12), dp(7)); language.setBackground(round(dark ? color("#261D35") : color("#F0E9FA"), 18));
        language.setOnClickListener(v -> toast("当前版本使用简体中文")); brand.addView(language); body.addView(brand);
        LinearLayout hero = column(); hero.setGravity(Gravity.CENTER); hero.setBackground(gradient(16)); hero.setContentDescription("一键诊断");
        hero.addView(new Symbol(10, Color.WHITE), new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView heroTitle = text("一键诊断", 21, Color.WHITE, true); addCentered(hero, heroTitle, 10);
        addCentered(hero, text("输入目标地址，快速定位网络问题", 11, color("#E3D6FF"), false), 8);
        hero.setOnClickListener(v -> showTool(Tool.find("diagnose"), null, "")); LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(tablet ? 150 : 146)); hp.topMargin = dp(20); body.addView(hero, hp);
        section(body, "网络层工具"); toolGrid(body, Arrays.asList("ping", "trace", "dns", "tcp", "udp", "lan"));
        section(body, "应用层工具"); toolGrid(body, Arrays.asList("http", "connection", "sites"));
        section(body, "系统工具"); toolGrid(body, Collections.singletonList("network"));
    }
    private void toolGrid(LinearLayout body, List<String> ids) {
        for (int i = 0; i < ids.size(); i += 3) {
            LinearLayout r = row();
            for (int j = 0; j < 3; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(102), 1); if (j < 2) lp.rightMargin = dp(8);
                if (i + j >= ids.size()) { r.addView(new View(this), lp); continue; }
                Tool tool = Tool.find(ids.get(i + j)); LinearLayout tile = column(); tile.setGravity(Gravity.CENTER); tile.setBackground(outline(card, line, 15));
                int tint = toolColor(tool.id); FrameLayout circle = new FrameLayout(this); circle.setBackground(round(withAlpha(tint, dark ? 45 : 27), 24));
                circle.addView(new Symbol(tool.icon, tint), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)); tile.addView(circle, new LinearLayout.LayoutParams(dp(43), dp(43)));
                TextView label = text(displayName(tool), 14, ink, true); label.setGravity(Gravity.CENTER); addCentered(tile, label, 10);
                tile.setContentDescription(displayName(tool)); tile.setOnClickListener(v -> { if (tool.id.equals("lan")) { cancelTask(); tab = 2; renderTab(); } else showTool(tool, null, ""); });
                r.addView(tile, lp);
            }
            add(body, r, 8);
        }
    }
    private String displayName(Tool t) { if (t.id.equals("tcp")) return "TCP"; if (t.id.equals("dns")) return "DNS"; if (t.id.equals("http")) return "HTTP"; if (t.id.equals("network")) return "本机信息"; return t.name; }
    private int toolColor(String id) {
        switch (id) { case "trace": return purple; case "tcp": return color("#E8912F"); case "udp": return green; case "lan": return color("#7772ED"); case "dns": return cyan; case "http": return cyan; case "connection": return color("#05C8AF"); case "sites": return green; case "network": return color("#807CF7"); default: return blue; }
    }
    private void showTool(Tool tool, String priorInput, String priorResult) {
        cancelTask(); detailId = tool.id; lastResult = priorResult == null ? "" : priorResult; resetViews(); drawNav(); content.removeAllViews();
        if (tool.id.equals("sites")) { sitesPage(); return; }
        if (tool.id.equals("speed")) { tab = 1; renderTab(); restoreResult(priorResult); return; }
        if (tool.id.equals("lan")) { tab = 2; renderTab(); restoreResult(priorResult); return; }
        LinearLayout body = page(); detailHeader(body, tool);
        if (tool.id.equals("diagnose")) diagnosticIntro(body);
        input = edit(tool.placeholder); input.setText(priorInput == null ? tool.initial : priorInput);
        if (tool.category.equals("编码工具")) { input.setSingleLine(false); input.setMaxLines(5); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); }
        LinearLayout field = row(); field.setGravity(Gravity.CENTER_VERTICAL); field.addView(input, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView clear = smallAction("×", muted); clear.setContentDescription("清除输入"); clear.setTextSize(23); clear.setOnClickListener(v -> input.setText("")); field.addView(clear, new LinearLayout.LayoutParams(dp(36), dp(44)));
        TextView recent = smallAction("◷", toolColor(tool.id)); recent.setContentDescription("最近输入"); recent.setOnClickListener(v -> recentDialog(tool)); field.addView(recent, new LinearLayout.LayoutParams(dp(36), dp(44)));
        boolean noInput = Arrays.asList("network", "public_ip", "bonjour", "ssdp").contains(tool.id);
        boolean inlineRun = !noInput && !tool.id.equals("diagnose") && !tool.category.equals("编码工具") && !Arrays.asList("subnet", "wol", "lookup").contains(tool.id);
        runButton = button(inlineRun ? tool.id.equals("dns") ? "⌕" : "▶" : runLabel(tool), tool.id.equals("diagnose") ? purple : toolColor(tool.id));
        runButton.setContentDescription(runLabel(tool));
        if (inlineRun) field.addView(runButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        if (!noInput) { add(body, field, tool.id.equals("diagnose") ? 24 : 22); if (hostTool(tool.id)) suffixes(body); }
        if (tool.id.equals("dns")) dnsControls(body);
        if (tool.id.equals("trace") || tool.id.equals("ping")) {
            optionRow(body, tool.id.equals("trace") ? "每跳发包数" : "发包次数", new String[]{"3", "5", "10", "20"}, packets == 5 ? 1 : packets == 10 ? 2 : packets == 20 ? 3 : 0, n -> packets = new int[]{3, 5, 10, 20}[n]);
            optionRow(body, "IP 协议", new String[]{"默认", "IPv4", "IPv6"}, family.equals("4") ? 1 : family.equals("6") ? 2 : 0, n -> family = new String[]{"auto", "4", "6"}[n]);
        }
        if (!inlineRun) add(body, runButton, 18);
        if (tool.id.equals("diagnose")) {
            runButton.setBackground(gradient(12)); updateDiagnosticButton();
            input.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void onTextChanged(CharSequence s, int start, int before, int count) { updateDiagnosticButton(); } public void afterTextChanged(Editable e) {} });
        }
        runButton.setOnClickListener(v -> { if (busy) cancelDisplayed(); else execute(tool, input == null ? "" : input.getText().toString()); });
        if (tool.id.equals("diagnose")) {
            recentChips(body); LinearLayout info = panel(); info.addView(text("ⓘ 功能说明", 15, ink, true)); add(info, text(tool.help, 13, muted, false), 12); add(body, info, 24);
        }
        terminal(body, tool, lastResult);
        if (tool.id.equals("dns")) loadDnsHistory();
        if (!tool.id.equals("diagnose")) recentChips(body);
        if (!tool.id.equals("diagnose")) add(body, text(tool.help, 11, muted, false), 20);
    }
    private void detailHeader(LinearLayout body, Tool tool) {
        LinearLayout h = row(); h.setGravity(Gravity.CENTER_VERTICAL); TextView back = smallAction("‹", ink); back.setTextSize(34); back.setContentDescription("返回"); back.setBackground(round(card, 24)); back.setOnClickListener(v -> onBackPressed()); h.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = text(tool.id.equals("dns") ? "DNS 查询" : displayName(tool), 18, ink, true); title.setGravity(Gravity.CENTER); h.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView star = smallAction(favorites().contains(tool.id) ? "★" : "☆", toolColor(tool.id)); star.setContentDescription("收藏工具"); star.setTextSize(23); h.addView(star, new LinearLayout.LayoutParams(dp(42), dp(42)));
        star.setOnClickListener(v -> { Set<String> f = favorites(); if (!f.add(tool.id)) f.remove(tool.id); prefs.edit().putStringSet("favorites", f).apply(); star.setText(f.contains(tool.id) ? "★" : "☆"); }); body.addView(h);
    }
    private void diagnosticIntro(LinearLayout body) {
        FrameLayout orb = new FrameLayout(this); orb.setBackground(gradient(60)); orb.setElevation(dp(8)); orb.addView(new Symbol(10, Color.WHITE), new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(dp(88), dp(88)); op.gravity = Gravity.CENTER_HORIZONTAL; op.topMargin = dp(34); body.addView(orb, op);
        addCentered(body, text("一键诊断", 28, color("#8063D9"), true), 28); addCentered(body, text("请输入目标地址", 17, muted, false), 12);
    }
    private String runLabel(Tool tool) {
        if (tool.id.equals("diagnose")) return "▶ 开始诊断";
        if (tool.id.equals("dns")) return "⌕ 查询";
        if (tool.id.equals("wol")) return "发送魔术包";
        if (tool.category.equals("编码工具") || tool.id.equals("subnet")) return "开始计算";
        return "▶ 开始测试";
    }
    private boolean hostTool(String id) { return Arrays.asList("diagnose", "ping", "trace", "dns", "tcp", "connection", "http", "tls").contains(id); }
    private void suffixes(LinearLayout body) {
        LinearLayout chips = row();
        for (String suffix : new String[]{"www.", ".com", ".cn", ".net"}) {
            TextView t = chip(suffix, withAlpha(toolColor(detailId), dark ? 32 : 22), toolColor(detailId)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(28)); p.rightMargin = dp(6); chips.addView(t, p);
            t.setOnClickListener(v -> { String value = input.getText().toString(); input.setText(suffix.equals("www.") ? suffix + value : value + suffix); input.setSelection(input.length()); });
        }
        add(body, chips, 8);
    }
    private void optionRow(LinearLayout body, String label, String[] options, int selected, Consumer<Integer> change) {
        LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL); TextView l = text(label + "：", 12, muted, false); row.addView(l, new LinearLayout.LayoutParams(dp(86), -2));
        LinearLayout segments = row(); segments.setBackground(round(card, 20)); segments.setPadding(dp(3), dp(3), dp(3), dp(3)); row.addView(segments, new LinearLayout.LayoutParams(0, dp(36), 1));
        for (int i = 0; i < options.length; i++) {
            final int index = i; TextView t = text(options[i], 12, ink, i == selected); t.setGravity(Gravity.CENTER); t.setBackground(round(i == selected ? (dark ? color("#5B5B60") : Color.WHITE) : Color.TRANSPARENT, 18)); segments.addView(t, new LinearLayout.LayoutParams(0, -1, 1));
            t.setOnClickListener(v -> { change.accept(index); for (int k = 0; k < segments.getChildCount(); k++) segments.getChildAt(k).setBackground(round(k == index ? (dark ? color("#5B5B60") : Color.WHITE) : Color.TRANSPARENT, 18)); });
        }
        add(body, row, 14);
    }
    private void dnsControls(LinearLayout body) {
        LinearLayout recordTypes = row(); recordTypes.setBackground(round(card, 20));
        for (String type : new String[]{"A", "AAAA", "CNAME", "更多"}) {
            TextView t = text(type, 12, ink, type.equals(dnsType)); t.setGravity(Gravity.CENTER); if (type.equals(dnsType)) t.setBackground(round(dark ? color("#5B5B60") : color("#E3E3E9"), 20));
            recordTypes.addView(t, new LinearLayout.LayoutParams(0, dp(34), 1)); t.setOnClickListener(v -> {
                if (type.equals("更多")) new AlertDialog.Builder(this).setTitle("DNS 记录类型").setItems(new String[]{"MX", "TXT", "NS", "SOA", "PTR"}, (d, which) -> { dnsType = new String[]{"MX", "TXT", "NS", "SOA", "PTR"}[which]; refreshType(recordTypes, dnsType); }).show();
                else { dnsType = type; refreshType(recordTypes, type); }
            });
        }
        add(body, recordTypes, 14); add(body, text("DNS 服务器", 11, muted, false), 12);
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); LinearLayout providers = row(); scroll.addView(providers);
        for (int i = 0; i < DNS_NAMES.length; i++) {
            final int index = i; TextView t = chip(DNS_NAMES[i], i == dnsProvider ? cyan : card, i == dnsProvider ? Color.BLACK : ink); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(30)); lp.rightMargin = dp(6); providers.addView(t, lp);
            t.setOnClickListener(v -> { dnsProvider = index; for (int j = 0; j < providers.getChildCount(); j++) { TextView c = (TextView) providers.getChildAt(j); c.setBackground(round(j == index ? cyan : card, 6)); c.setTextColor(j == index ? Color.BLACK : ink); } customDns.setVisibility(index == 6 ? View.VISIBLE : View.GONE); });
        }
        add(body, scroll, 8); customDns = edit("自定义 DNS IP，例如 1.1.1.1"); customDns.setText(prefs.getString("custom_dns", "")); customDns.setVisibility(dnsProvider == 6 ? View.VISIBLE : View.GONE); add(body, customDns, 8);
        add(body, text("系统 A/AAAA 遵循设备解析；其他记录直接询问所选 DNS（UDP 53）。", 10, muted, false), 8);
    }
    private void refreshType(LinearLayout row, String selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView t = (TextView) row.getChildAt(i); boolean match = t.getText().toString().equals(selected) || i == 3 && !Arrays.asList("A", "AAAA", "CNAME").contains(selected);
            if (i == 3) t.setText(match ? selected : "更多"); t.setBackground(round(match ? (dark ? color("#5B5B60") : color("#E3E3E9")) : Color.TRANSPARENT, 20));
        }
    }
    private String selectedDns() {
        if (dnsProvider == 6) { String value = customDns.getText().toString().trim(); prefs.edit().putString("custom_dns", value).apply(); if (value.isEmpty()) throw new IllegalArgumentException("请输入自定义 DNS 服务器 IP"); return value; }
        if (dnsProvider != 0) return DNS_IPS[dnsProvider];
        if (dnsType.equals("A") || dnsType.equals("AAAA")) return "";
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE); LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp != null && !lp.getDnsServers().isEmpty()) return lp.getDnsServers().get(0).getHostAddress();
        throw new IllegalArgumentException("当前网络未提供 DNS 服务器，请选择公共或自定义 DNS");
    }
    private void restoreResult(String result) { if (result != null && !result.isEmpty()) { lastResult = result; if (resultPanel != null) resultPanel.setVisibility(View.VISIBLE); if (output != null) output.setText(result); if (state != null) state.setText("历史结果"); } }
    private void terminal(LinearLayout body, Tool tool, String prior) {
        LinearLayout terminal = column(); terminal.setPadding(dp(10), dp(10), dp(10), dp(14)); terminal.setBackground(outline(color("#030303"), line, 10));
        LinearLayout header = row(); prompt = text("$ " + tool.id + (input == null ? "" : " " + input.getText()), 11, green, false); prompt.setTypeface(Typeface.MONOSPACE); header.addView(prompt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = smallAction("清空", color("#D96568")); clear.setTextSize(11); header.addView(clear); terminal.addView(header);
        state = text(prior.isEmpty() ? "等待开始" : "历史结果", 11, cyan, false); add(terminal, state, 12);
        output = text(prior.isEmpty() ? (tool.id.equals("trace") ? "点击开始按钮追踪路由路径" : "点击开始按钮，查看实际检测结果") : prior, 12, prior.isEmpty() ? muted : green, false);
        output.setTypeface(Typeface.MONOSPACE); output.setTextIsSelectable(true); output.setLineSpacing(dp(3), 1); add(terminal, output, 16);
        if (tool.id.equals("dns")) { dnsDeck = column(); add(terminal, dnsDeck, 10); }
        int terminalHeight = tablet ? Math.max(400, getResources().getConfiguration().screenHeightDp - 330) : tool.id.equals("trace") ? 390 : 330;
        terminal.setMinimumHeight(dp(tool.id.equals("diagnose") ? 160 : terminalHeight));
        LinearLayout actions = row(); TextView copy = smallAction("复制", cyan); copy.setOnClickListener(v -> copyText(lastResult)); actions.addView(copy);
        TextView export = smallAction(tool.id.equals("dns") ? "dig 输出 / 分享" : "分享结果", cyan); export.setOnClickListener(v -> shareResult(tool.name)); actions.addView(export); add(terminal, actions, 12);
        clear.setOnClickListener(v -> { if (busy) cancelTask(); lastResult = ""; output.setVisibility(View.VISIBLE); output.setText("终端已清空"); state.setText("等待开始"); if (dnsDeck != null) dnsDeck.removeAllViews(); setIdleButton(runLabel(tool)); });
        resultPanel = terminal;
        if (tool.id.equals("diagnose") && prior.isEmpty()) terminal.setVisibility(View.GONE);
        add(body, terminal, 20);
    }
    private void loadDnsHistory() {
        JSONArray history = records(); int added = 0;
        for (int i = 0; i < history.length() && added < 4; i++) { JSONObject entry = history.optJSONObject(i); if (entry != null && entry.optString("id").equals("dns")) { addDnsCard(entry.optString("input"), entry.optString("result"), entry.optLong("time"), false); added++; } }
        if (added > 0 && lastResult.isEmpty()) { output.setVisibility(View.GONE); state.setText("最近查询记录"); }
    }
    private void addDnsCard(String host, String result, long time, boolean first) {
        if (dnsDeck == null) return; LinearLayout item = column(); item.setPadding(dp(10), dp(10), dp(10), dp(10)); item.setBackground(round(color("#101010"), 7));
        String[] command = result.split("\n", 2)[0].split("\\s+");
        String record = command.length >= 4 && command[0].equals("$") && Arrays.asList("A", "AAAA", "CNAME", "MX", "TXT", "NS", "SOA", "PTR", "SRV").contains(command[3]) ? command[3] : "DNS";
        LinearLayout top = row(); TextView tag = chip(record, cyan, Color.BLACK); top.addView(tag);
        TextView target = text("  " + host, 11, Color.LTGRAY, false); top.addView(target, new LinearLayout.LayoutParams(0, -2, 1)); top.addView(text(new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new java.util.Date(time)), 10, muted, false)); item.addView(top);
        TextView text = text(result, 11, green, false); text.setTypeface(Typeface.MONOSPACE); text.setTextIsSelectable(true); add(item, text, 8);
        TextView copy = smallAction("dig 输出   复制", cyan); copy.setTextSize(11); copy.setOnClickListener(v -> copyText(result)); add(item, copy, 6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(8); dnsDeck.addView(item, first ? 0 : dnsDeck.getChildCount(), lp);
        while (dnsDeck.getChildCount() > 6) dnsDeck.removeViewAt(dnsDeck.getChildCount() - 1);
    }
    private void recentDialog(Tool tool) {
        List<String> recent = recentHosts(); new AlertDialog.Builder(this).setTitle("最近输入").setItems(recent.toArray(new String[0]), (d, which) -> input.setText(recent.get(which))).setNegativeButton("取消", null).show();
    }
    private List<String> recentHosts() {
        List<String> hosts = new ArrayList<>(); JSONArray records = records();
        for (int i = 0; i < records.length() && hosts.size() < 6; i++) { JSONObject e = records.optJSONObject(i); if (e == null) continue; String s = e.optString("input").trim(); if (!s.isEmpty() && !s.contains(" ") && s.length() < 120 && !hosts.contains(s)) hosts.add(s); }
        for (String s : Arrays.asList("www.qq.com", "www.baidu.com", "www.google.com", "github.com")) if (!hosts.contains(s)) hosts.add(s);
        return hosts;
    }
    private void recentChips(LinearLayout body) {
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); LinearLayout chips = row(); scroll.addView(chips);
        for (String host : recentHosts()) { TextView t = chip("◷ " + host, card, ink); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(30)); lp.rightMargin = dp(7); chips.addView(t, lp); t.setOnClickListener(v -> { if (input != null) input.setText(host); }); }
        add(body, scroll, 16);
    }
    private interface Operation { String run(NetworkTools.Cancellation c, NetworkTools.Progress progress) throws Exception; }
    private void execute(Tool tool, String value) {
        if (prompt != null) prompt.setText(getString(R.string.command_prompt, tool.id.equals("trace") ? "traceroute" : tool.id.equals("dns") ? "dig" : tool.id, value, tool.id.equals("dns") ? " " + dnsType : ""));
        final String type = dnsType; final int rounds = packets; final String ipFamily = family; final String server;
        try { server = tool.id.equals("dns") ? selectedDns() : ""; }
        catch (Exception e) { showError(e); return; }
        runOperation(tool.id, value, (c, progress) -> {
            switch (tool.id) {
                case "network": return DeviceTools.network(getApplicationContext()) + "\n设备：" + Build.MANUFACTURER + " " + Build.MODEL + "\nAndroid " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT;
                case "public_ip": return DeviceTools.publicIp(c);
                case "dns": return DnsClient.query(value.trim(), type, server, c);
                case "ping": case "trace": return AdvancedDiagnostics.execute(tool.id, value + " count=" + rounds + " family=" + ipFamily, c, progress);
                case "udp": case "diagnose": return AdvancedDiagnostics.execute(tool.id, value, c, progress);
                case "connection": return NetworkTools.execute("tcp", value, c, progress);
                case "lookup": return AddressTools.inspect(value, c);
                case "bonjour": return LanDiscovery.bonjour(getApplicationContext(), c, progress);
                case "ssdp": return LanDiscovery.ssdp(getApplicationContext(), c, progress);
                default: return NetworkTools.execute(tool.id, value, c, progress);
            }
        });
    }
    private void runOperation(String id, String value, Operation operation) {
        cancelTask(); int token = ++generation; cancellation = new NetworkTools.Cancellation(); NetworkTools.Cancellation cancel = cancellation; busy = true;
        lastResult = ""; if (resultPanel != null) resultPanel.setVisibility(View.VISIBLE); if (state != null) state.setText("进行中"); if (output != null) { output.setVisibility(View.VISIBLE); output.setText("正在执行…"); } if (runButton != null) runButton.setText(runButton.getLayoutParams().width == dp(44) ? "■" : "■ 停止");
        NetworkTools.Progress progress = message -> runOnUiThread(() -> { if (generation == token) onProgress(message); });
        future = worker.submit(() -> {
            long started = System.nanoTime(); String result; boolean success;
            try { cancel.check(); result = operation.run(cancel, progress); cancel.check(); success = true; }
            catch (CancellationException e) { return; }
            catch (Exception e) { result = "执行失败\n" + e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "未知错误" : e.getMessage()) + "\n请检查输入、权限、网络和目标服务。"; success = false; }
            String completed = result; boolean ok = success; long ms = (System.nanoTime() - started) / 1_000_000;
            runOnUiThread(() -> {
                if (generation != token) return; busy = false; lastResult = completed;
                if (state != null) state.setText(getString(R.string.result_status, ok ? "已完成" : "失败", ms));
                setIdleButton(getString(R.string.run_again));
                if (id.equals("dns") && dnsDeck != null) { output.setVisibility(View.GONE); addDnsCard(value, completed, System.currentTimeMillis(), true); }
                else if (output != null) { output.setText(completed); output.setTextColor(ok ? green : color("#EF7C7D")); }
                saveHistory(Tool.find(id), value, completed, ok);
            });
        });
    }
    private void onProgress(String message) {
        String[] parts = message.split("\\|", -1);
        if (parts.length >= 2 && parts[0].equals("phase")) { if (state != null) state.setText(parts[1].equals("download") ? "正在下载" : parts[1].equals("upload") ? "正在上传" : "正在测量延迟"); return; }
        if (parts.length >= 2 && (parts[0].equals("download_error") || parts[0].equals("upload_error"))) { TextView metric = speedLabels.get(parts[0].startsWith("download") ? "download" : "upload"); if (metric != null) metric.setText("失败"); return; }
        if (parts.length >= 3 && (parts[0].equals("download") || parts[0].equals("upload"))) {
            TextView value = speedLabels.get(parts[0]); if (value != null) value.setText(parts[1]);
            TextView byteRate = speedLabels.get(parts[0] + "_bytes"); if (byteRate != null) byteRate.setText(String.format(Locale.CHINA, "%.2f MB/s", parseDouble(parts[1]) / 8));
            if (gauge != null) { gauge.value = parts[1]; gauge.setContentDescription("网速仪表盘 " + parts[1] + " Mbps"); gauge.invalidate(); } if (state != null) state.setText(parts[0].equals("download") ? "正在下载" : "正在上传"); return;
        }
        if (parts.length >= 3 && parts[0].equals("latency")) { if (speedLabels.containsKey("latency")) speedLabels.get("latency").setText(parts[1]); if (speedLabels.containsKey("jitter")) speedLabels.get("jitter").setText(parts[2]); return; }
        if (parts.length >= 4 && parts[0].equals("site")) {
            TextView label = siteLabels.get(parts[1]); if (label != null) { boolean failed = parts[2].equals("-1"); label.setText(failed ? "未响应" : parts[2] + "ms"); label.setTextColor(failed ? muted : parseDouble(parts[2]) >= 600 ? color("#E89B43") : green); } return;
        }
        if (parts.length >= 4 && parts[0].equals("scan")) {
            if (scanFound != null) scanFound.setText(parts[1]); if (scanCount != null) scanCount.setText(parts[2]);
            if (scanPercent != null) scanPercent.setText(String.format(Locale.CHINA, "%.0f%%", 100 * parseDouble(parts[2]) / Math.max(1, parseDouble(parts[3])))); return;
        }
        if (parts.length >= 6 && parts[0].equals("device")) { addDevice(parts); return; }
        if (output != null) output.setText(message);
    }
    private static double parseDouble(String value) { try { return Double.parseDouble(value); } catch (Exception e) { return 0; } }
    private void cancelTask() { generation++; busy = false; if (cancellation != null) cancellation.cancel(); if (future != null) future.cancel(true); future = null; cancellation = null; }
    private void updateDiagnosticButton() { if (runButton != null && "diagnose".equals(detailId)) { boolean ready = busy || input != null && !input.getText().toString().trim().isEmpty(); runButton.setEnabled(ready); runButton.setAlpha(ready ? 1f : .45f); } }
    private void setIdleButton(String label) { if (runButton != null) { runButton.setText(runButton.getLayoutParams().width == dp(44) ? "dns".equals(detailId) ? "⌕" : "▶" : label); updateDiagnosticButton(); } }
    private void cancelDisplayed() { cancelTask(); if (state != null) state.setText("已停止"); setIdleButton(getString(R.string.run_again)); }
    private void showError(Exception e) { new AlertDialog.Builder(this).setTitle("参数有误").setMessage(e.getMessage()).setPositiveButton("知道了", null).show(); }
    private void speedPage() {
        LinearLayout body = page(); pageHeading(body, "网速测试");
        gauge = new Gauge(); LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(200), dp(200)); gp.gravity = Gravity.CENTER_HORIZONTAL; gp.topMargin = dp(10); body.addView(gauge, gp);
        LinearLayout metrics = row(); metrics.addView(metric("download", "↓ 下载", "Mbps", blue), new LinearLayout.LayoutParams(0, -2, 1)); metrics.addView(metric("upload", "↑ 上传", "Mbps", green), new LinearLayout.LayoutParams(0, -2, 1)); add(body, metrics, 12);
        LinearLayout secondary = row(); secondary.addView(metric("latency", "◷ 延迟", "ms", color("#DB913C")), new LinearLayout.LayoutParams(0, -2, 1)); secondary.addView(metric("jitter", "〰 抖动", "ms", purple), new LinearLayout.LayoutParams(0, -2, 1)); add(body, secondary, 18);
        LinearLayout traffic = panel(); traffic.setBackground(outline(dark ? color("#211A15") : color("#FFF6EC"), color("#D9AE88"), 14)); traffic.addView(text("⚠ 预估流量消耗", 16, ink, true));
        TextView estimate = text(getString(R.string.traffic_estimate, downloadMb, downloadMb + 1), 15, color("#D89553"), true); add(traffic, estimate, 16);
        add(traffic, text("默认采用较小样本，结果受服务端与线路影响。", 11, muted, false), 12); add(body, traffic, 18);
        runButton = button("▶ 开始测速", blue); add(body, runButton, 20);
        runButton.setOnClickListener(v -> {
            if (busy) { cancelDisplayed(); return; }
            new AlertDialog.Builder(this).setTitle("开始网速测试？").setMessage("将进行实际下载、上传和 HEAD 请求，预计消耗约 " + (downloadMb + 1) + " MB 加协议开销。当前：" + DeviceTools.connection(this)).setNegativeButton("取消", null).setPositiveButton("开始测速", (d, which) -> {
                for (TextView label : speedLabels.values()) label.setText("--"); gauge.value = "--"; gauge.invalidate();
                final int mb = downloadMb; final String down = speedDownload, up = speedUpload;
                runOperation("speed", down.isEmpty() ? "Cloudflare · " + mb + " MB" : down, (c, p) -> Measurements.speed(down, up, mb, c, p));
            }).show();
        });
        add(body, text("ⓘ 延迟采用 HTTPS HEAD，包含 DNS/TCP/TLS 与服务端响应；抖动为相邻样本延迟差的平均绝对值。", 11, muted, false), 14);
        terminal(body, Tool.find("speed"), "");
        resultPanel.setVisibility(View.GONE);
        LinearLayout siteHeader = row(); siteHeader.setGravity(Gravity.CENTER_VERTICAL); siteHeader.addView(text("腾讯系", 18, ink, true), new LinearLayout.LayoutParams(0, -2, 1)); TextView refresh = smallAction("↻", blue); refresh.setContentDescription("测试腾讯系站点延迟"); siteHeader.addView(refresh); add(body, siteHeader, 20);
        List<Measurements.Site> sampleSites = new ArrayList<>(); for (Measurements.Site site : Measurements.CATALOG) if (Arrays.asList("wechat", "tencentvideo", "tencentgames").contains(site.id)) sampleSites.add(site);
        refresh.setOnClickListener(v -> runOperation("sites", "腾讯系 HEAD", (c, p) -> Measurements.sites(sampleSites, c, p))); siteGrid(body, sampleSites);
        TextView sites = button("站点连接延迟   →", purple); sites.setOnClickListener(v -> showTool(Tool.find("sites"), null, "")); add(body, sites, 24);
    }
    private LinearLayout metric(String id, String label, String unit, int tint) {
        boolean transfer = id.equals("download") || id.equals("upload");
        LinearLayout result = column(); result.setGravity(Gravity.CENTER); TextView heading = text(label, 14, tint, false); heading.setGravity(Gravity.CENTER); result.addView(heading);
        TextView value = text(transfer ? "0.0" : "--", transfer ? 28 : 22, ink, true); LinearLayout reading = row(); reading.setGravity(Gravity.CENTER_VERTICAL); reading.addView(value); TextView units = text(" " + unit, 11, muted, false); reading.addView(units); addCentered(result, reading, 10);
        if (transfer) { TextView byteRate = text("0.00 MB/s", 11, muted, false); addCentered(result, byteRate, 5); speedLabels.put(id + "_bytes", byteRate); }
        speedLabels.put(id, value); return result;
    }
    private void speedSettings() {
        LinearLayout fields = column(); fields.setPadding(dp(20), dp(12), dp(20), 0);
        EditText down = edit("留空使用 Cloudflare 默认下载端点"); down.setText(speedDownload); fields.addView(down);
        EditText up = edit("HTTPS 上传端点"); up.setText(speedUpload); add(fields, up, 12);
        int[] sample = {downloadMb}; optionRow(fields, "下载样本", new String[]{"5 MB", "10 MB", "20 MB"}, downloadMb == 10 ? 1 : downloadMb == 20 ? 2 : 0, n -> sample[0] = new int[]{5, 10, 20}[n]);
        new AlertDialog.Builder(this).setTitle("测速设置").setMessage("下载使用 GET，上传使用 POST。端点必须支持 HTTPS 和相应方法，TLS 校验保持启用。").setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> { downloadMb = sample[0]; speedDownload = down.getText().toString().trim(); speedUpload = up.getText().toString().trim(); prefs.edit().putInt("speed_mb", downloadMb).putString("speed_download", speedDownload).putString("speed_upload", speedUpload).apply(); }).show();
    }
    private List<Measurements.Site> allSites() {
        List<Measurements.Site> list = new ArrayList<>(Measurements.CATALOG);
        try { JSONArray custom = new JSONArray(prefs.getString("sites", "[]")); for (int i = 0; i < custom.length(); i++) { JSONObject e = custom.getJSONObject(i); list.add(new Measurements.Site("custom_" + i, "自定义", e.getString("name"), e.getString("host"))); } } catch (Exception ignored) { /* preserve catalog */ }
        return list;
    }
    private void sitesPage() {
        LinearLayout body = page(); detailHeader(body, Tool.find("sites"));
        LinearLayout actions = row(); TextView addSite = smallAction("⊕ 添加站点", green); actions.addView(addSite, new LinearLayout.LayoutParams(0, dp(44), 1));
        runButton = button("▶ 开始", blue); actions.addView(runButton, new LinearLayout.LayoutParams(0, dp(44), 1)); add(body, actions, 14);
        addSite.setOnClickListener(v -> addSiteDialog());
        List<Measurements.Site> sites = allSites();
        runButton.setOnClickListener(v -> { if (busy) cancelDisplayed(); else runOperation("sites", "分类站点 HEAD", (c, p) -> Measurements.sites(sites, c, p)); });
        add(body, text("HTTPS HEAD 响应耗时，包含连接和服务端处理；点击卡片可单独测试。", 11, muted, false), 12);
        String previous = ""; List<Measurements.Site> group = new ArrayList<>();
        for (Measurements.Site s : sites) {
            if (!s.category.equals(previous)) { if (!group.isEmpty()) siteGrid(body, group); section(body, s.category); group = new ArrayList<>(); previous = s.category; } group.add(s);
        }
        if (!group.isEmpty()) siteGrid(body, group); terminal(body, Tool.find("sites"), "");
    }
    private void siteGrid(LinearLayout body, List<Measurements.Site> sites) {
        for (int i = 0; i < sites.size(); i += 2) {
            LinearLayout row = row();
            for (int j = 0; j < 2; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); if (j == 0) lp.rightMargin = dp(8);
                if (i + j >= sites.size()) { row.addView(new View(this), lp); continue; }
                Measurements.Site site = sites.get(i + j); LinearLayout tile = panel(); tile.setPadding(dp(12), dp(13), dp(10), dp(13)); tile.setMinimumHeight(dp(76));
                LinearLayout h = row(); TextView title = text(site.name, 13, ink, true); h.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); TextView delay = text("—", 12, muted, true); h.addView(delay); siteLabels.put(site.id, delay); tile.addView(h);
                TextView host = text(site.host, 10, muted, false); host.setSingleLine(true); host.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); add(tile, host, 7);
                tile.setOnClickListener(v -> runOperation("sites", site.host, (c, p) -> Measurements.sites(Collections.singletonList(site), c, p)));
                if (site.category.equals("自定义")) tile.setOnLongClickListener(v -> { new AlertDialog.Builder(this).setTitle("删除自定义站点？").setMessage(site.name).setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> removeSite(site)).show(); return true; }); row.addView(tile, lp);
            }
            add(body, row, 8);
        }
    }
    private void addSiteDialog() {
        LinearLayout fields = column(); fields.setPadding(dp(18), dp(8), dp(18), 0); EditText name = edit("站点名称"); fields.addView(name); EditText host = edit("域名，例如 example.com"); add(fields, host, 10);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("添加站点").setView(fields).setNegativeButton("取消", null).setPositiveButton("添加", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                String hostname = host.getText().toString().trim(); String label = name.getText().toString().trim(); NetworkTools.HostPort target = NetworkTools.HostPort.parse(hostname, 443);
                if (hostname.contains(":") || hostname.contains("/") || label.isEmpty() || label.length() > 30) throw new IllegalArgumentException("请输入简短名称与不含协议、端口或路径的域名");
                JSONArray sites = new JSONArray(prefs.getString("sites", "[]")); if (sites.length() >= 10) throw new IllegalArgumentException("最多保存 10 个自定义站点");
                sites.put(new JSONObject().put("name", label).put("host", target.host)); prefs.edit().putString("sites", sites.toString()).apply(); dialog.dismiss(); showTool(Tool.find("sites"), null, "");
            } catch (Exception e) { toast(e.getMessage()); }
        })); dialog.show();
    }
    private void removeSite(Measurements.Site site) {
        try { JSONArray all = new JSONArray(prefs.getString("sites", "[]")), next = new JSONArray(); for (int i = 0; i < all.length(); i++) if (!("custom_" + i).equals(site.id)) next.put(all.get(i)); prefs.edit().putString("sites", next.toString()).apply(); showTool(Tool.find("sites"), null, ""); } catch (Exception e) { showError(e); }
    }
    private void scanPage() {
        LinearLayout body = page(); pageHeading(body, "局域网扫描");
        LinearLayout info = panel(); info.addView(text("◉  网络信息", 15, ink, true)); add(info, text(LanDiscovery.networkSummary(this), 12, muted, false), 14); add(body, info, 16);
        input = edit("输入 IPv4/CIDR，例如 192.168.1.0/24"); input.setText(LanDiscovery.defaultCidr(this)); add(body, input, 14);
        runButton = button("◉ 开始扫描", purple); runButton.setBackground(gradient(12)); add(body, runButton, 14);
        runButton.setOnClickListener(v -> {
            if (busy) { cancelDisplayed(); return; }
            String cidr = input.getText().toString().trim();
            new AlertDialog.Builder(this).setTitle("扫描局域网").setMessage("仅扫描你管理或获授权的网络。本次目标：" + cidr + "，最多 256 个地址。防火墙或系统限制可能导致漏报。").setNegativeButton("取消", null).setPositiveButton("开始扫描", (d, w) -> {
                deviceDeck.removeAllViews(); scanFound.setText("0"); scanCount.setText("0"); scanPercent.setText("0%");
                runOperation("lan", cidr, (c, p) -> LanDiscovery.scan(getApplicationContext(), cidr, c, p));
            }).show();
        });
        LinearLayout discovery = panel(); discovery.addView(text("♧ 服务发现", 13, muted, false)); LinearLayout serviceRow = row();
        TextView bonjour = discoveryButton("Bonjour", "Apple / 打印机 / AirPlay", purple); TextView ssdp = discoveryButton("SSDP", "智能电视 / 路由器 / NAS", color("#D88E35"));
        LinearLayout.LayoutParams serviceLp = new LinearLayout.LayoutParams(0, dp(78), 1); serviceLp.rightMargin = dp(8); serviceRow.addView(bonjour, serviceLp); serviceRow.addView(ssdp, new LinearLayout.LayoutParams(0, dp(78), 1)); add(discovery, serviceRow, 12); add(body, discovery, 16);
        bonjour.setOnClickListener(v -> runOperation("bonjour", "局域网 NSD", (c, p) -> LanDiscovery.bonjour(getApplicationContext(), c, p)));
        ssdp.setOnClickListener(v -> runOperation("ssdp", "局域网 SSDP", (c, p) -> LanDiscovery.ssdp(getApplicationContext(), c, p)));
        LinearLayout stats = panel(); stats.setOrientation(LinearLayout.HORIZONTAL); scanFound = counter(stats, "0", "在线设备", green); scanCount = counter(stats, "0", "已扫描", blue); scanPercent = counter(stats, "—", "进度", color("#E6A345")); add(body, stats, 16);
        LinearLayout devices = panel(); devices.addView(text("▣ 发现的设备", 16, ink, true)); deviceDeck = column(); add(devices, deviceDeck, 12); add(body, devices, 16);
        add(devices, text("点击开始扫描以获取真实结果。未响应的设备不会被虚构为在线。", 11, muted, false), 10);
        terminal(body, Tool.find("lan"), "");
    }
    private TextView discoveryButton(String name, String hint, int tint) {
        TextView t = text(name + "\n" + hint + "\n点击扫描", 11, ink, true); t.setGravity(Gravity.CENTER); t.setLineSpacing(dp(4), 1); t.setBackground(outline(withAlpha(tint, dark ? 18 : 12), withAlpha(tint, 100), 10)); return t;
    }
    private TextView counter(LinearLayout parent, String number, String label, int tint) {
        LinearLayout c = column(); c.setGravity(Gravity.CENTER); TextView value = text(number, 17, tint, true); value.setGravity(Gravity.CENTER); c.addView(value); addCentered(c, text(label, 10, muted, false), 6); parent.addView(c, new LinearLayout.LayoutParams(0, -2, 1)); return value;
    }
    private void addDevice(String[] parts) {
        if (deviceDeck == null) return; String ip = parts[1], name = parts[2], ms = parts[3], kind = parts[4], mac = parts[5];
        LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(14), 0, dp(14)); int tint = kind.equals("local") ? blue : kind.equals("gateway") ? color("#DE943A") : green;
        row.addView(new Symbol(kind.equals("local") ? 15 : kind.equals("gateway") ? 6 : 3, tint), new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout details = column(); TextView title = text(name.isEmpty() ? ip : name, 14, ink, true); details.addView(title);
        add(details, text((kind.equals("local") ? "本机" : kind.equals("gateway") ? "网关" : "响应设备") + "  " + ip, 11, tint, false), 5);
        add(details, text((ms.isEmpty() ? "" : ms + " ms  ") + (mac.isEmpty() ? "MAC 未提供" : mac), 10, muted, false), 5);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.leftMargin = dp(12); row.addView(details, lp); row.addView(text("● ›", 13, green, true));
        row.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(name.isEmpty() ? ip : name).setMessage(ip + "\n" + (mac.isEmpty() ? "系统未提供 MAC 地址" : mac)).setNegativeButton("关闭", null).setNeutralButton("Ping", (d, w) -> showTool(Tool.find("ping"), ip, "")).setPositiveButton("TCP", (d, w) -> showTool(Tool.find("tcp"), ip + ":80", "")).show()); deviceDeck.addView(row);
    }
    private void addressPage() {
        LinearLayout body = page(); pageHeading(body, "IP / MAC 查询"); addCentered(body, text("⌕", 54, blue, true), 24); addCentered(body, text("地址信息，一目了然", 17, ink, true), 12);
        input = edit("输入 IP、域名或 MAC 地址"); input.setText(R.string.default_ip); add(body, input, 24);
        runButton = button("⌕ 查询", blue); add(body, runButton, 14); runButton.setOnClickListener(v -> { if (busy) cancelDisplayed(); else { String value = input.getText().toString(); runOperation("lookup", value, (c, p) -> AddressTools.inspect(value, c)); } });
        TextView publicIp = smallAction("查询当前公网 IPv4", blue); add(body, publicIp, 12); publicIp.setOnClickListener(v -> runOperation("public_ip", "api.ipify.org", (c, p) -> DeviceTools.publicIp(c)));
        add(body, text("IP 显示实际解析、地址分类与可用的反向 DNS；MAC 显示格式、OUI 前缀和地址标志。不推测运营商、地理位置或厂商。", 12, muted, false), 14); terminal(body, Tool.find("lookup"), "");
        TextView subnet = button("子网计算   →", purple); subnet.setOnClickListener(v -> showTool(Tool.find("subnet"), null, "")); add(body, subnet, 20);
    }
    private void mine() {
        LinearLayout body = page(); pageHeading(body, "我的"); addCentered(body, text("网络工具箱", 25, ink, true), 24); addCentered(body, text("1.1.0 · 原生 Android · 无广告", 12, muted, false), 10);
        LinearLayout list = panel(); menu(list, "全部工具", "搜索全部工具与离线实用工具", () -> allTools(false)); menu(list, "我的收藏", favorites().size() + " 个工具", () -> allTools(true)); menu(list, "检测历史", "最近 50 条，仅保存在本机", () -> history()); menu(list, "测速设置", "服务器与下载样本", () -> speedSettings()); add(body, list, 24);
        LinearLayout appearance = panel(); appearance.addView(text("外观与记录", 15, ink, true)); Switch theme = new Switch(this); theme.setText("深色模式"); theme.setTextColor(ink); theme.setChecked(dark); theme.setPadding(0, dp(16), 0, dp(16)); appearance.addView(theme);
        theme.setOnCheckedChangeListener((v, checked) -> { dark = checked; prefs.edit().putBoolean("dark", checked).apply(); buildShell(); renderTab(); });
        Switch save = new Switch(this); save.setText("保存检测历史"); save.setTextColor(ink); save.setChecked(prefs.getBoolean("save_history", true)); save.setPadding(0, dp(12), 0, dp(12)); appearance.addView(save); save.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("save_history", checked).apply());
        TextView clear = smallAction("清空历史记录", color("#EC6869")); clear.setOnClickListener(v -> clearHistory()); add(appearance, clear, 12); add(body, appearance, 18);
        LinearLayout privacy = panel(); privacy.addView(text("隐私与权限", 15, ink, true)); add(privacy, text("无需注册，无分析 SDK。收藏与历史仅存本机，可能包含网址、目标和输入文本；可关闭记录并清空。\n\n测速默认连接 Cloudflare，公网 IPv4 使用 api.ipify.org。站点延迟仅在你启动后访问所列站点；其他工具连接输入目标或在本地计算。局域网服务发现使用多播权限，不请求位置或存储权限。\n\nHTTPS 保持系统验证；HTTP 诊断支持明文请求。路由、ICMP、多播、MAC 读取可能受 Android 和网络限制，结果将如实显示。", 12, muted, false), 14); add(body, privacy, 18);
        TextView github = smallAction("GitHub 源代码  ↗", blue); github.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/vmlive/Network-Toolkit")))); add(body, github, 18);
    }
    private void menu(LinearLayout parent, String title, String subtitle, Runnable action) {
        LinearLayout item = column(); item.setPadding(0, dp(12), 0, dp(12)); item.addView(text(title + "   ›", 15, ink, true)); add(item, text(subtitle, 11, muted, false), 7); item.setOnClickListener(v -> action.run()); parent.addView(item);
    }
    private void allTools(boolean onlyFavorites) {
        cancelTask(); detailId = onlyFavorites ? "favorites" : "all_tools"; resetViews(); content.removeAllViews(); LinearLayout body = page(); simpleHeader(body, onlyFavorites ? "我的收藏" : "全部工具");
        EditText search = edit("搜索工具、DNS、HTTP、Base64…"); input = search; add(body, search, 18); LinearLayout found = column(); add(body, found, 10);
        Consumer<String> fill = value -> {
            found.removeAllViews(); List<Tool> matched = new ArrayList<>(); Set<String> f = favorites(); String q = value.toLowerCase(Locale.ROOT);
            for (Tool t : Tool.ALL) if ((!onlyFavorites || f.contains(t.id)) && (t.id + t.name + t.description).toLowerCase(Locale.ROOT).contains(q)) matched.add(t);
            add(found, text("找到 " + matched.size() + " 个工具", 11, muted, false), 8);
            for (Tool t : matched) { LinearLayout item = panel(); item.addView(text(t.name, 15, ink, true)); add(item, text(t.description, 11, muted, false), 8); item.setOnClickListener(v -> showTool(t, null, "")); add(found, item, 10); }
        };
        fill.accept(""); search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void onTextChanged(CharSequence s, int start, int before, int count) { fill.accept(s.toString()); } public void afterTextChanged(Editable e) {} });
    }
    private void simpleHeader(LinearLayout body, String title) {
        LinearLayout h = row(); h.setGravity(Gravity.CENTER_VERTICAL); TextView back = smallAction("‹", ink); back.setContentDescription("返回"); back.setTextSize(30); back.setOnClickListener(v -> onBackPressed()); h.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42))); TextView name = text(title, 18, ink, true); name.setGravity(Gravity.CENTER); h.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); h.addView(new View(this), new LinearLayout.LayoutParams(dp(42), dp(42))); body.addView(h);
    }
    private Set<String> favorites() { return new HashSet<>(prefs.getStringSet("favorites", new HashSet<>())); }
    private JSONArray records() { try { return new JSONArray(prefs.getString("history", "[]")); } catch (Exception e) { return new JSONArray(); } }
    private void saveHistory(Tool tool, String value, String result, boolean ok) {
        if (!prefs.getBoolean("save_history", true)) return;
        try { JSONObject entry = new JSONObject().put("id", tool.id).put("input", limit(value, 2048)).put("result", limit(result, 16384)).put("time", System.currentTimeMillis()).put("ok", ok); JSONArray next = new JSONArray().put(entry), old = records(); for (int i = 0; i < Math.min(49, old.length()); i++) next.put(old.get(i)); prefs.edit().putString("history", next.toString()).apply(); } catch (Exception e) { toast("历史保存失败"); }
    }
    private static String limit(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "\n[记录已截断]"; }
    private void history() {
        cancelTask(); detailId = "history"; resetViews(); content.removeAllViews(); LinearLayout body = page(); simpleHeader(body, "检测历史");
        TextView clear = smallAction("清空历史", color("#E97072")); clear.setOnClickListener(v -> clearHistory()); add(body, clear, 12); JSONArray records = records();
        if (records.length() == 0) { addCentered(body, text("还没有检测记录", 17, muted, false), 60); return; }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
        for (int i = 0; i < records.length(); i++) { JSONObject entry = records.optJSONObject(i); if (entry == null) continue; Tool t = Tool.find(entry.optString("id")); LinearLayout item = panel(); item.addView(text(t.name + "  ·  " + (entry.optBoolean("ok") ? "已完成" : "失败"), 15, ink, true)); add(item, text(entry.optString("input"), 12, muted, false), 8); add(item, text(format.format(new java.util.Date(entry.optLong("time"))), 10, muted, false), 8); item.setOnClickListener(v -> showTool(t, entry.optString("input"), entry.optString("result"))); add(body, item, 10); }
    }
    private void clearHistory() {
        new AlertDialog.Builder(this).setTitle("清空本机历史？").setMessage("删除所有输入和结果，无法恢复。").setNegativeButton("取消", null).setPositiveButton("清空", (d, w) -> { prefs.edit().remove("history").apply(); if ("history".equals(detailId)) history(); else renderTab(); }).show();
    }
    private void copyText(String value) { if (value == null || value.isEmpty()) { toast("尚无结果"); return; } ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("网络工具箱结果", value)); toast("已复制"); }
    private void shareResult(String title) { if (lastResult.isEmpty()) { toast("尚无结果"); return; } startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, title + "\n\n" + lastResult), "分享结果")); }
    private TextView button(String title, int tint) {
        TextView t = text(title, 16, Color.WHITE, true); t.setGravity(Gravity.CENTER); t.setMinHeight(dp(48)); t.setPadding(dp(12), dp(10), dp(12), dp(10)); t.setBackground(round(tint, 12)); accessibleButton(t); return t;
    }
    private TextView smallAction(String title, int tint) { TextView t = text(title, 13, tint, false); t.setGravity(Gravity.CENTER); t.setPadding(dp(8), dp(6), dp(8), dp(6)); t.setMinHeight(dp(40)); accessibleButton(t); return t; }
    private void accessibleButton(TextView t) {
        t.setClickable(true); t.setFocusable(true); t.setAccessibilityDelegate(new View.AccessibilityDelegate() { @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); } });
    }
    private TextView chip(String value, int background, int foreground) { TextView t = text(value, 11, foreground, false); t.setGravity(Gravity.CENTER); t.setPadding(dp(10), dp(4), dp(10), dp(4)); t.setBackground(round(background, 14)); return t; }
    private EditText edit(String hint) {
        EditText e = new EditText(this); e.setTextSize(14); e.setTextColor(ink); e.setHintTextColor(muted); e.setHint(hint); e.setSingleLine(true); e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); e.setPadding(dp(12), dp(9), dp(12), dp(9)); e.setBackground(outline(bg, line, 8)); e.setMinHeight(dp(44)); return e;
    }
    private LinearLayout panel() { LinearLayout p = column(); p.setPadding(dp(14), dp(14), dp(14), dp(14)); p.setBackground(round(card, 13)); return p; }
    private TextView text(String value, float size, int tint, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(tint); t.setIncludeFontPadding(false); if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return t; }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private void add(LinearLayout parent, View child, int top) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(top); parent.addView(child, lp); }
    private void addCentered(LinearLayout parent, View child, int top) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.gravity = Gravity.CENTER_HORIZONTAL; lp.topMargin = dp(top); parent.addView(child, lp); }
    private GradientDrawable round(int tint, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(tint); d.setCornerRadius(dp(radius)); return d; }
    private GradientDrawable outline(int tint, int stroke, int radius) { GradientDrawable d = round(tint, radius); d.setStroke(dp(1), stroke); return d; }
    private GradientDrawable gradient(int radius) { GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{color("#326BEA"), color("#A444E2")}); d.setCornerRadius(dp(radius)); return d; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static int color(String value) { return Color.parseColor(value); }
    private static int withAlpha(int value, int alpha) { return (value & 0x00FFFFFF) | (alpha << 24); }
    private void toast(String value) { Toast.makeText(this, value == null ? "操作失败" : value, Toast.LENGTH_SHORT).show(); }
    private final class Gauge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); String value = "--";
        Gauge() { super(MainActivity.this); setContentDescription("网速仪表盘，尚未开始测速"); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float x = getWidth() / 2f, y = getHeight() / 2f, radius = Math.min(x, y) - dp(14); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(15)); paint.setColor(dark ? color("#28282B") : color("#E5E5E9")); canvas.drawCircle(x, y, radius, paint);
            if (!value.equals("--")) { paint.setColor(blue); canvas.drawArc(x - radius, y - radius, x + radius, y + radius, -90, (float) Math.min(360, parseDouble(value) * 3.6), false, paint); }
            paint.setStyle(Paint.Style.FILL); paint.setColor(ink); paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); paint.setTextSize(dp(value.length() > 6 ? 28 : 38)); canvas.drawText(value, x, y + dp(10), paint);
            if (!value.equals("--")) { paint.setColor(muted); paint.setTextSize(dp(12)); canvas.drawText("Mbps", x, y + dp(35), paint); }
        }
    }
    private final class Symbol extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final int kind, tint;
        Symbol(int kind, int tint) { super(MainActivity.this); this.kind = kind; this.tint = tint; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); c.save(); c.scale(getWidth() / 24f, getHeight() / 24f); p.setColor(tint); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            switch (kind) {
                case 0: c.drawArc(2, 4, 22, 24, 220, 100, false, p); c.drawArc(6, 9, 18, 21, 220, 100, false, p); c.drawCircle(12, 19, 1, p); break;
                case 1: c.drawCircle(12, 12, 9, p); c.drawLine(12, 12, 17, 7, p); c.drawLine(6, 17, 18, 17, p); break;
                case 2: c.drawCircle(12, 12, 9, p); c.drawOval(8, 3, 16, 21, p); c.drawLine(3, 12, 21, 12, p); break;
                case 3: case 8: for (int x = 4; x <= 14; x += 10) for (int y = 4; y <= 14; y += 10) c.drawRoundRect(x, y, x + 6, y + 6, 1.5f, 1.5f, p); break;
                case 4: c.drawRoundRect(3, 4, 21, 20, 2, 2, p); c.drawLine(3, 8, 21, 8, p); c.drawLine(6, 12, 17, 12, p); c.drawLine(6, 16, 14, 16, p); break;
                case 5: c.drawRoundRect(5, 10, 19, 21, 2, 2, p); c.drawArc(8, 3, 16, 17, 180, 180, false, p); break;
                case 6: c.drawRoundRect(2, 12, 22, 20, 3, 3, p); c.drawLine(6, 16, 6, 16, p); c.drawLine(10, 16, 10, 16, p); c.drawArc(5, 1, 19, 15, 220, 100, false, p); c.drawArc(8, 5, 16, 13, 220, 100, false, p); break;
                case 7: c.drawArc(3, 3, 21, 21, 305, 290, false, p); c.drawLine(12, 2, 12, 12, p); break;
                case 10: c.drawLine(7, 8, 18, 19, p); c.drawLine(9, 6, 20, 17, p); c.drawLine(7, 8, 9, 6, p); c.drawLine(18, 19, 20, 17, p); c.drawLine(17, 2, 17, 8, p); c.drawLine(14, 5, 20, 5, p); c.drawLine(4, 12, 4, 18, p); c.drawLine(1, 15, 7, 15, p); break;
                case 11: c.drawCircle(6, 4, 2, p); c.drawCircle(18, 20, 2, p); c.drawLine(6, 6, 6, 9, p); c.drawLine(6, 9, 18, 15, p); c.drawLine(18, 15, 18, 18, p); break;
                case 12: c.drawLine(3, 11, 21, 3, p); c.drawLine(21, 3, 14, 21, p); c.drawLine(14, 21, 10, 14, p); c.drawLine(10, 14, 3, 11, p); c.drawLine(10, 14, 21, 3, p); break;
                case 13: c.drawLine(3, 21, 22, 21, p); c.drawLine(6, 17, 6, 10, p); c.drawLine(11, 17, 11, 3, p); c.drawLine(16, 17, 16, 7, p); c.drawLine(21, 17, 21, 12, p); break;
                case 14: c.drawLine(2, 15, 8, 8, p); c.drawLine(8, 8, 14, 14, p); c.drawLine(14, 14, 22, 5, p); break;
                case 15: c.drawRoundRect(6, 2, 18, 22, 2, 2, p); c.drawLine(10, 19, 14, 19, p); break;
                case 16: c.drawCircle(10, 10, 7, p); c.drawLine(15, 15, 22, 22, p); break;
                case 17: c.drawCircle(12, 6, 4, p); c.drawRoundRect(4, 13, 20, 22, 7, 7, p); break;
                case 18: c.drawLine(3, 7, 21, 7, p); c.drawLine(3, 7, 7, 3, p); c.drawLine(3, 7, 7, 11, p); c.drawLine(3, 17, 21, 17, p); c.drawLine(21, 17, 17, 13, p); c.drawLine(21, 17, 17, 21, p); break;
                default: c.drawLine(9, 3, 6, 21, p); c.drawLine(18, 3, 15, 21, p); c.drawLine(3, 9, 22, 9, p); c.drawLine(2, 16, 21, 16, p);
            }
            c.restore();
        }
    }
}
