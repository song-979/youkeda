package com.youkeda.project.wechatproject.bot.tool;

import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

final class AmapSignUtil {

    private AmapSignUtil() {
    }

    /**
     * Appends the Amap digital signature (sig) to the UriComponentsBuilder.
     * Must be called before build() / encode() to sign raw parameter values.
     */
    static UriComponentsBuilder appendSign(UriComponentsBuilder builder, String privateKey) {
        if (privateKey == null || privateKey.isBlank()) {
            return builder;
        }

        UriComponents uc = builder.build();
        Map<String, String> params = new TreeMap<>();
        for (Map.Entry<String, String> entry : uc.getQueryParams().toSingleValueMap().entrySet()) {
            if (!"sig".equals(entry.getKey())) {
                params.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
            }
        }

        StringBuilder raw = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!raw.isEmpty()) {
                raw.append('&');
            }
            raw.append(entry.getKey()).append('=').append(entry.getValue());
        }
        raw.append(privateKey);

        String sig = md5(raw.toString());
        return builder.queryParam("sig", sig);
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }
}
