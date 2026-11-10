package com.vmlive.networktoolkit;

import java.net.URI;
import java.util.Locale;

/** Fixed destinations for embedded tools. No user-controlled URL is used as a start page. */
public final class WebSitePolicy {
    private WebSitePolicy() {}
    public enum Navigation { INTERNAL, EXTERNAL, BLOCKED }
    public static final class Site {
        public final String id, name, host, url, description;
        private Site(String id, String name, String host, String description) {
            this.id = id; this.name = name; this.host = host; this.url = "https://" + host + "/"; this.description = description;
        }
    }
    public static final Site SKK = new Site("skk", "IP.SKK.MOE", "ip.skk.moe", "IP 与网络连通性检测");
    public static final Site PING0 = new Site("ping0", "PING0", "ping0.cc", "IP 查询与风险信息");

    public static Site forId(String id) {
        if ("skk".equals(id)) return SKK;
        if ("ping0".equals(id)) return PING0;
        throw new IllegalArgumentException("未知的网站工具");
    }

    public static Navigation classify(Site site, String value) {
        URI uri = secureUri(value);
        if (uri == null || site == null) return Navigation.BLOCKED;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals(site.host) || host.equals("www." + site.host)) return Navigation.INTERNAL;
        return Navigation.EXTERNAL;
    }

    /** Display only the verified URL host, never an untrusted page title or credentials. */
    public static String host(String value) {
        URI uri = secureUri(value);
        return uri == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static URI secureUri(String value) {
        if (value == null || value.length() > 8192 || value.indexOf('\\') >= 0) return null;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getHost() == null ||
                uri.getHost().isEmpty() || uri.getHost().endsWith(".") || uri.getPort() != -1 && uri.getPort() != 443) return null;
            return uri;
        } catch (Exception invalid) { return null; }
    }
}
