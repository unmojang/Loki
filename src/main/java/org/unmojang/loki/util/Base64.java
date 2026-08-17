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

    private static final int MIME_LINE = 76;

    public static String encodeMime(byte[] data) {
        StringBuilder out = new StringBuilder();
        int lineLength = 0;
        for (int i = 0; i < data.length; i += 3) {
            int remaining = data.length - i;
            int chunk = (data[i] & 0xFF) << 16
                    | (remaining > 1 ? (data[i + 1] & 0xFF) << 8 : 0)
                    | (remaining > 2 ? (data[i + 2] & 0xFF) : 0);

            if (lineLength == MIME_LINE) {
                out.append('\n');
                lineLength = 0;
            }
            out.append(base64Chars[(chunk >> 18) & 0x3F]);
            out.append(base64Chars[(chunk >> 12) & 0x3F]);
            out.append(remaining > 1 ? base64Chars[(chunk >> 6) & 0x3F] : '=');
            out.append(remaining > 2 ? base64Chars[chunk & 0x3F] : '=');
            lineLength += 4;
        }
        return out.toString();
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
