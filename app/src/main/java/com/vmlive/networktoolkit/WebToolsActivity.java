package com.vmlive.networktoolkit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;

/** Isolated website view with standard HTTPS browser behavior and no native JavaScript bridge. */
public final class WebToolsActivity extends Activity {
    private WebSitePolicy.Site site;
    private WebView web;
    private FrameLayout viewport;
    private ScrollView message;
    private TextView address, status, refresh;
    private ProgressBar progress;
    private boolean dark, loading, failed, opened, destroyed;
    private int bg, ink, muted, surface;
    private String lastUrl;
    private int httpStatus;
    private final int blue = Color.rgb(0, 151, 255);

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        dark = getSharedPreferences("toolkit", MODE_PRIVATE).getBoolean("dark", true);
        setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
        try { site = WebSitePolicy.forId(getIntent().getStringExtra("site")); }
        catch (IllegalArgumentException invalid) { toast("未知的网站工具"); finish(); return; }
        lastUrl = site.url;
        build();
        if (web == null) return;
        if (saved != null && saved.getBoolean("opened")) {
            opened = true;
            String previous = saved.getString("url", site.url);
            if (WebSitePolicy.classify(site, previous) == WebSitePolicy.Navigation.INTERNAL) lastUrl = previous;
            Bundle history = saved.getBundle("web");
            try {
                if (history != null && web.restoreState(history) != null) {
                    message.setVisibility(View.GONE); web.setVisibility(View.VISIBLE); address.setText(WebSitePolicy.host(lastUrl));
                } else load(lastUrl);
            } catch (RuntimeException unavailable) { showWebViewUnavailable(); }
        } else if (getIntent().getBooleanExtra("open", false)) load(site.url);
        else showMessage(site.name, site.description + "\n\n网页与数据由原站实时提供。打开后，网站及其数据服务会收到你的出口 IP。网站账户、API Key 和隐私规则由原站管理。", "打开完整网站", () -> load(site.url));
    }

    private void build() {
        bg = Color.parseColor(dark ? "#000000" : "#FFFFFF"); ink = Color.parseColor(dark ? "#F5F5F7" : "#141416");
        muted = Color.parseColor(dark ? "#A0A0A5" : "#73737B"); surface = Color.parseColor(dark ? "#1C1C1E" : "#F2F2F7");
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(dark || Build.VERSION.SDK_INT < 27 ? Color.BLACK : Color.WHITE);
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; if (Build.VERSION.SDK_INT >= 27) light |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : light);
        if (Build.VERSION.SDK_INT >= 29) getWindow().getDecorView().setForceDarkAllowed(false);
        LinearLayout root = column(); root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout bar = row(); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(5), dp(4), dp(5), dp(4));
        TextView back = action("‹", "返回"); back.setTextSize(28); back.setOnClickListener(v -> onBackPressed()); bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(44)));
        LinearLayout heading = column(); TextView title = text(site.name, 16, ink, true); heading.addView(title);
        address = text(site.host, 10, muted, false); address.setSingleLine(true); heading.addView(address); bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        refresh = action("↻", "刷新网页"); refresh.setOnClickListener(v -> { if (loading && web != null) { try { web.stopLoading(); loading = false; progress.setVisibility(View.GONE); refresh.setText("↻"); status.setText("已停止加载"); } catch (RuntimeException unavailable) { showWebViewUnavailable(); } } else load(lastUrl); });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(42), dp(44)));
        TextView menu = action("⋮", "网站菜单"); menu.setTextSize(25); menu.setOnClickListener(v -> menu(menu)); bar.addView(menu, new LinearLayout.LayoutParams(dp(42), dp(44))); root.addView(bar);
        status = text("由原站提供完整功能", 10, muted, false); status.setPadding(dp(16), dp(3), dp(16), dp(6)); root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setVisibility(View.GONE); root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        viewport = new FrameLayout(this); root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        message = new ScrollView(this); message.setFillViewport(true); message.setBackgroundColor(bg); viewport.addView(message, new FrameLayout.LayoutParams(-1, -1));
        createWebView();
        setContentView(root); root.requestApplyInsets();
    }

    private boolean createWebView() {
        try { configureWebView(); return true; }
        catch (RuntimeException unavailable) { showWebViewUnavailable(); return false; }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        web = new WebView(this); web.setBackgroundColor(bg); web.setVisibility(View.INVISIBLE);
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setSafeBrowsingEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        settings.setGeolocationEnabled(false); settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { if (view == web && !destroyed) { progress.setProgress(value); if (value >= 100) progress.setVisibility(View.GONE); } }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) { callback.invoke(origin, false, false); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return !"https".equalsIgnoreCase(request.getUrl().getScheme());
                return navigate(request.getUrl().toString(), request.hasGesture());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return navigate(url, false); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme) || "content".equalsIgnoreCase(scheme) || "intent".equalsIgnoreCase(scheme))
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                return null;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (view != web || destroyed) return;
                if (WebSitePolicy.classify(site, url) != WebSitePolicy.Navigation.INTERNAL) { view.stopLoading(); showFailure("已阻止站外跳转，请使用菜单在浏览器打开。"); return; }
                opened = true; loading = true; failed = false; httpStatus = 0; lastUrl = url; address.setText(WebSitePolicy.host(url)); status.setText("正在连接原站…"); progress.setVisibility(View.VISIBLE); refresh.setText("■");
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != web || destroyed) return; loading = false; progress.setVisibility(View.GONE); refresh.setText("↻");
                if (!failed) { message.setVisibility(View.GONE); web.setVisibility(View.VISIBLE); status.setText(httpStatus >= 400 ? getString(R.string.web_http_status, httpStatus) : "HTTPS · 原站实时页面"); }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == web && request.isForMainFrame()) showFailure("无法连接原站。请检查网络后重试，也可在系统浏览器打开。");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                // Keep the original error/challenge page interactive instead of hiding it.
                if (view == web && request.isForMainFrame()) { httpStatus = response.getStatusCode(); status.setText(getString(R.string.web_http_status, httpStatus)); }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) { handler.cancel(); if (view == web) showFailure("网站证书验证失败，连接已停止。不会绕过 HTTPS 校验。"); }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view == web) { destroyWebView(); showFailure("网页进程已停止。请重试以重新打开原站。"); } return true;
            }
        });
        viewport.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    private boolean navigate(String value, boolean userGesture) {
        WebSitePolicy.Navigation policy = WebSitePolicy.classify(site, value);
        if (policy == WebSitePolicy.Navigation.INTERNAL) return false;
        if (policy == WebSitePolicy.Navigation.EXTERNAL && userGesture) new AlertDialog.Builder(this).setTitle("打开外部网站？").setMessage(WebSitePolicy.host(value)).setNegativeButton("取消", null).setPositiveButton("浏览器打开", (d, w) -> openBrowser(value)).show();
        else if (policy == WebSitePolicy.Navigation.EXTERNAL) toast("已阻止自动站外跳转，可使用菜单在浏览器打开");
        else toast("仅允许 HTTPS 网站导航");
        return true;
    }

    private void load(String value) {
        if (destroyed) return;
        if (WebSitePolicy.classify(site, value) != WebSitePolicy.Navigation.INTERNAL) value = site.url;
        if (web == null && !createWebView()) return;
        opened = true; loading = true; failed = false; httpStatus = 0; lastUrl = value;
        try { message.setVisibility(View.GONE); web.setVisibility(View.VISIBLE); progress.setVisibility(View.VISIBLE); refresh.setText("■"); status.setText("正在连接原站…"); web.loadUrl(value); }
        catch (RuntimeException unavailable) { showWebViewUnavailable(); }
    }

    private void showWebViewUnavailable() {
        destroyWebView(); failed = true; loading = false; progress.setVisibility(View.GONE); refresh.setText("↻"); status.setText("网页组件不可用");
        showMessage("网页组件不可用", "请在系统设置中启用或更新 Android System WebView 或 Chrome 后重试。原生 IP 诊断仍可使用，也可在系统浏览器中打开原站。", "重试网页组件", () -> load(lastUrl));
    }
    private void showFailure(String text) { failed = true; loading = false; progress.setVisibility(View.GONE); refresh.setText("↻"); status.setText("未能完成加载"); showMessage("加载未完成", text, "重试", () -> load(lastUrl)); }
    private void showMessage(String title, String body, String label, Runnable action) {
        message.removeAllViews(); LinearLayout box = column(); box.setPadding(dp(24), dp(42), dp(24), dp(30)); box.addView(text(title, 25, ink, true));
        TextView detail = text(body, 14, muted, false); detail.setLineSpacing(dp(5), 1); LinearLayout.LayoutParams paragraph = new LinearLayout.LayoutParams(-1, -2); paragraph.topMargin = dp(20); box.addView(detail, paragraph);
        TextView button = action(label, label); button.setTextColor(Color.WHITE); GradientDrawable background = new GradientDrawable(); background.setColor(blue); background.setCornerRadius(dp(12)); button.setBackground(background);
        button.setOnClickListener(v -> action.run()); LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(48)); bp.topMargin = dp(28); box.addView(button, bp);
        TextView browser = action("在浏览器打开", "在浏览器打开"); browser.setOnClickListener(v -> openBrowser(lastUrl)); LinearLayout.LayoutParams external = new LinearLayout.LayoutParams(-1, dp(44)); external.topMargin = dp(12); box.addView(browser, external);
        message.addView(box); message.setVisibility(View.VISIBLE); if (web != null) web.setVisibility(View.INVISIBLE);
    }
    private void menu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor); menu.getMenu().add("分享当前链接"); menu.getMenu().add("在浏览器打开"); menu.getMenu().add("返回网站首页"); menu.getMenu().add("清除网站数据");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("分享当前链接")) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, safeCurrentUrl()), "分享网站链接"));
            else if (title.equals("在浏览器打开")) openBrowser(safeCurrentUrl());
            else if (title.equals("返回网站首页")) load(site.url);
            else new AlertDialog.Builder(this).setTitle("清除网站数据？").setMessage("将清除应用内两个网站的 Cookie、登录状态、网页存储、缓存与浏览记录。不会删除原生工具的收藏和检测历史。").setNegativeButton("取消", null).setPositiveButton("清除", (d, w) -> clearSiteData()).show();
            return true;
        }); menu.show();
    }
    private String safeCurrentUrl() { try { String url = web == null ? lastUrl : web.getUrl(); return WebSitePolicy.classify(site, url) == WebSitePolicy.Navigation.INTERNAL ? url : site.url; } catch (RuntimeException unavailable) { return site.url; } }
    private void clearSiteData() {
        try {
            if (web != null) { web.stopLoading(); web.clearCache(true); web.clearHistory(); destroyWebView(); }
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(value -> {
                if (destroyed) return;
                try { CookieManager.getInstance().flush(); toast("网站数据已清除"); load(site.url); }
                catch (RuntimeException unavailable) { showWebViewUnavailable(); }
            });
        } catch (RuntimeException unavailable) { showWebViewUnavailable(); }
    }
    private void openBrowser(String value) {
        if (WebSitePolicy.classify(site, value) == WebSitePolicy.Navigation.BLOCKED) { toast("链接不可用"); return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException missing) { toast("未找到可用的浏览器"); }
    }
    @Override public void onBackPressed() { try { if (web != null && opened && web.canGoBack()) { web.goBack(); return; } } catch (RuntimeException unavailable) { destroyWebView(); } super.onBackPressed(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putBoolean("opened", opened); state.putString("url", lastUrl); if (web != null && opened) { try { Bundle history = new Bundle(); web.saveState(history); state.putBundle("web", history); } catch (RuntimeException unavailable) { showWebViewUnavailable(); } } }
    @Override protected void onPause() { if (web != null) { try { web.onPause(); } catch (RuntimeException unavailable) { showWebViewUnavailable(); } } super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) { try { web.onResume(); } catch (RuntimeException unavailable) { showWebViewUnavailable(); } } }
    @Override protected void onDestroy() { destroyed = true; destroyWebView(); super.onDestroy(); }
    private void destroyWebView() {
        if (web == null) return; WebView old = web; web = null;
        if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        try { old.stopLoading(); old.setWebChromeClient(null); old.setWebViewClient(null); old.destroy(); }
        catch (RuntimeException unavailable) { /* A missing/dead provider cannot be cleaned up through its API. */ }
    }
    WebView getWebViewForTest() { return web; }

    private TextView action(String label, String description) { TextView value = text(label, 14, blue, true); value.setContentDescription(description); value.setGravity(Gravity.CENTER); value.setPadding(dp(8), dp(6), dp(8), dp(6)); value.setMinHeight(dp(44)); value.setClickable(true); value.setFocusable(true); value.setAccessibilityDelegate(new View.AccessibilityDelegate() { @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); } }); return value; }
    private TextView text(String value, int size, int color, boolean bold) { TextView result = new TextView(this); result.setText(value); result.setTextSize(size); result.setTextColor(color); if (bold) result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return result; }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
