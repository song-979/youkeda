package com.youkeda.project.wechatproject.bot.tool.amap;

import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

public final class AmapSignUtil {

    private static final Logger log = LoggerFactory.getLogger(AmapSignUtil.class);

    private AmapSignUtil() {
    }

    public static UriComponentsBuilder appendSign(UriComponentsBuilder builder, String privateKey) {
        if (privateKey == null || privateKey.isBlank()) {
            log.warn("AmapSignUtil: privateKey is null or blank, no sig will be added");
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
        log.info("AmapSignUtil signature computed: rawString={}, sig={}",
                raw.substring(0, Math.min(200, raw.length())) + "...",
                sig);
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
