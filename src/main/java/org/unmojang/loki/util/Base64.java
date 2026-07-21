package org.unmojang.loki.util;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class Base64 {
    private static final char[] base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] base64Inv = new int[256];

    static {
        Arrays.fill(base64Inv, -1);
        for (int i = 0; i < base64Chars.length; i++) base64Inv[base64Chars[i]] = i;
        // base64url alphabet
        base64Inv['-'] = 62;
        base64Inv['_'] = 63;
    }

    public static byte[] decode(String s) {
        s = s.replaceAll("\\s", "");
        int len = s.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream((len * 3) / 4);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            int v = c < 256 ? base64Inv[c] : -1;
            if (v < 0) throw new IllegalArgumentException("Illegal base64 character: " + c);
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
