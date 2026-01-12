package org.unmojang.loki.util;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class Base64 {
    private static final char[] base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] base64Inv = new int[256];

    static {
        Arrays.fill(base64Inv, -1);
        for (int i = 0; i < base64Chars.length; i++) base64Inv[base64Chars[i]] = i;
        base64Inv['='] = 0;
    }

    public static byte[] decode(String s) {
        s = s.replaceAll("\\s", "");
        int len = s.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream((len * 3) / 4);
        int i = 0;
        while (i < len) {
            int b0 = base64Inv[s.charAt(i++) & 0xFF];
            int b1 = base64Inv[s.charAt(i++) & 0xFF];
            int b2 = i < len ? base64Inv[s.charAt(i++) & 0xFF] : 0;
            int b3 = i < len ? base64Inv[s.charAt(i++) & 0xFF] : 0;

            out.write((b0 << 2) | (b1 >> 4));
            if (s.charAt(i - 2) != '=') out.write(((b1 & 0xF) << 4) | (b2 >> 2));
            if (s.charAt(i - 1) != '=') out.write(((b2 & 0x3) << 6) | b3);
        }
        return out.toByteArray();
    }
}
