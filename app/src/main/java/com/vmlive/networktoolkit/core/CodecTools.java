package com.vmlive.networktoolkit.core;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Local-only UTF-8 conversions. Hashes are checksums, not password storage. */
public final class CodecTools {
    private CodecTools() {}
    public static String execute(String id, String input) throws Exception {
        switch (id) {
            case "base64_encode": return Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
            case "base64_decode": return new String(Base64.getDecoder().decode(input.trim()), StandardCharsets.UTF_8);
            case "url_encode": return URLEncoder.encode(input, "UTF-8");
            case "url_decode": return URLDecoder.decode(input, "UTF-8");
            case "sha256": return NetworkTools.hex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
            case "md5": return NetworkTools.hex(MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8)));
            default: throw new IllegalArgumentException("未知工具：" + id);
        }
    }
}
