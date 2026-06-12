package org.unmojang.loki.util;

import org.unmojang.loki.hooks.Hooks;
import org.unmojang.loki.util.logger.NilLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// Loosely based on https://github.com/PrismarineJS/prismarine-auth
public final class MicrosoftAuth {
    private static final NilLogger log = NilLogger.get("Loki");
    private static final String CLIENT_ID = "00000000402b5328"; // Minecraft Java's client ID
    public static final Object LOCK = new Object();
    public static List<String[]> accounts; // { uuid, accessToken, refreshToken }

    // Single-account login for the applet launcher: replays a stored account silently if there is one,
    // otherwise prompts.
    public static Json.JSONObject authenticate() {
        synchronized (LOCK) {
            loadAccounts();
            String[] account = accounts.isEmpty() ? null : accounts.get(0);
            if (account != null) {
                Json.JSONObject session = refreshAccount(account);
                if (session != null) return session;
            }
            return interactiveLogin();
        }
    }

    // Session building
    public static Json.JSONObject interactiveLogin() {
        try {
            String body = "client_id=" + CLIENT_ID + "&scope=" + URLEncoder.encode("service::user.auth.xboxlive.com::MBI_SSL", "UTF-8") + "&response_type=device_code";
            Json.JSONObject dc = httpJson("POST", "https://login.live.com/oauth20_connect.srf",
                    "application/x-www-form-urlencoded", body.getBytes("UTF-8"), null);
            if (!dc.has("device_code")) {
                log.error("Device authorization failed: " + dc.optString("error_description", dc.optString("error", "unknown error")));
                return null;
            }

            final String deviceCode = dc.getString("device_code");
            String userCode = dc.getString("user_code");
            String verificationUri = dc.getString("verification_uri");
            // Append ?otc= so the Microsoft sign-in page prefills the code
            String browseUri = verificationUri + (verificationUri.indexOf('?') >= 0 ? "&" : "?") + "otc=" + URLEncoder.encode(userCode, "UTF-8");
            final int pollingInterval = dc.optInt("interval", 5);
            final long deadline = System.currentTimeMillis() + dc.optInt("expires_in", 900) * 1000L;

            // Poll on a worker while the modal prompt pumps the event queue, so EDT-driven launchers (Alpha) don't freeze.
            final MicrosoftSignInDialog.Handle prompt = MicrosoftSignInDialog.create(verificationUri, browseUri, userCode);
            final String[][] tokens = new String[1][];
            Thread poller = new Thread("MSA-poll") {
                public void run() {
                    try {
                        tokens[0] = pollForToken(deviceCode, pollingInterval, deadline, prompt);
                    } catch (Throwable t) {
                        log.error("Microsoft device code polling failed", t);
                    } finally {
                        MicrosoftSignInDialog.dismiss(prompt);
                    }
                }
            };
            poller.start();
            MicrosoftSignInDialog.showAndWait(prompt);
            try {
                poller.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            String[] msa = tokens[0]; // { msaAccessToken, msaRefreshToken }
            return msa == null ? null : buildSession(msa[0], msa[1]);
        } catch (Throwable t) {
            log.error("Microsoft login failed", t);
            return null;
        }
    }

    public static Json.JSONObject refreshAccount(String[] account) {
        try {
            String body = "client_id=" + CLIENT_ID
                    + "&grant_type=refresh_token"
                    + "&refresh_token=" + URLEncoder.encode(account[2], "UTF-8")
                    + "&scope=" + URLEncoder.encode("service::user.auth.xboxlive.com::MBI_SSL", "UTF-8");
            Json.JSONObject refreshed = httpJson("POST", "https://login.live.com/oauth20_token.srf",
                    "application/x-www-form-urlencoded", body.getBytes("UTF-8"), null);
            if (!refreshed.has("access_token")) {
                log.info("Stored Microsoft account is no longer valid; dropping it");
                removeAccountByUuid(account[0]);
                saveAccounts();
                return null;
            }
            return buildSession(refreshed.getString("access_token"),
                    refreshed.optString("refresh_token", account[2]));
        } catch (Throwable t) {
            log.error("Microsoft refresh failed", t);
            return null;
        }
    }

    private static Json.JSONObject buildSession(String msaToken, String refreshToken) throws Exception {
        // Xbox Live user authentication
        String xboxBody = "{"
                + "\"Properties\":{"
                + "\"AuthMethod\":\"RPS\","
                + "\"SiteName\":\"user.auth.xboxlive.com\","
                + "\"RpsTicket\":\"" + msaToken + "\""
                + "},"
                + "\"RelyingParty\":\"http://auth.xboxlive.com\","
                + "\"TokenType\":\"JWT\""
                + "}";
        Json.JSONObject xbox = httpJson("POST", "https://user.auth.xboxlive.com/user/authenticate", "application/json", xboxBody.getBytes("UTF-8"), null);
        String xboxToken = xbox.getString("Token");
        String uhs = xbox.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");

        // XSTS authorization
        String xstsBody = "{"
                + "\"Properties\":{"
                + "\"SandboxId\":\"RETAIL\","
                + "\"UserTokens\":[\"" + xboxToken + "\"]"
                + "},"
                + "\"RelyingParty\":\"rp://api.minecraftservices.com/\","
                + "\"TokenType\":\"JWT\""
                + "}";
        String xstsToken = httpJson("POST", "https://xsts.auth.xboxlive.com/xsts/authorize", "application/json", xstsBody.getBytes("UTF-8"), null).getString("Token");

        // Minecraft services launcher login
        String loginBody = "{"
                + "\"xtoken\":\"XBL3.0 x=" + uhs + ";" + xstsToken + "\","
                + "\"platform\":\"PC_LAUNCHER\""
                + "}";
        String mcToken = httpJson("POST", "https://api.minecraftservices.com/launcher/login", "application/json", loginBody.getBytes("UTF-8"), null).getString("access_token");

        Json.JSONObject profile = httpJson("GET", "https://api.minecraftservices.com/minecraft/profile", null, null, "Bearer " + mcToken);
        String uuid = profile.getString("id");
        String name = profile.getString("name");

        loadAccounts();
        removeAccountByUuid(uuid);
        accounts.add(0, new String[]{ uuid, mcToken, refreshToken }); // most recent first
        saveAccounts();

        Json.JSONObject selectedProfile = new Json.JSONObject();
        selectedProfile.put("id", uuid);
        selectedProfile.put("name", name);
        Json.JSONObject result = new Json.JSONObject();
        result.put("accessToken", mcToken);
        result.put("selectedProfile", selectedProfile);
        log.info("Microsoft login succeeded for " + name);
        return result;
    }

    private static File minecraftDirectory() {
        String userHome = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return new File(appData != null ? appData : userHome, ".minecraft");
        }
        if (os.contains("mac")) {
            return new File(userHome, "Library/Application Support/minecraft");
        }
        return new File(userHome, ".minecraft");
    }

    public static void loadAccounts() {
        if (accounts != null) return;
        accounts = new ArrayList<String[]>();
        try {
            File f = new File(minecraftDirectory(), "msa_accounts.json");
            if (!f.isFile()) return;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            } finally {
                in.close();
            }
            String s = out.toString("UTF-8").trim();
            if (s.length() == 0) return;
            Json.JSONArray arr = new Json.JSONObject(s).optJSONArray("accounts");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                Json.JSONObject o = arr.getJSONObject(i);
                String refresh = o.optString("refreshToken", "");
                if (refresh.length() == 0) continue;
                accounts.add(new String[]{ o.optString("uuid", ""), o.optString("accessToken", ""), refresh });
            }
        } catch (Throwable ignored) {}
    }

    public static void saveAccounts() {
        try {
            Json.JSONArray arr = new Json.JSONArray();
            for (String[] a : accounts) {
                Json.JSONObject o = new Json.JSONObject();
                o.put("uuid", a[0]);
                o.put("accessToken", a[1]);
                o.put("refreshToken", a[2]);
                arr.put(o);
            }
            Json.JSONObject root = new Json.JSONObject();
            root.put("accounts", arr);

            File f = new File(minecraftDirectory(), "msa_accounts.json");
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                log.error("Couldn't create directory for " + f);
                return;
            }
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(root.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {}
    }

    public static String[] findByAccessToken(String accessToken) {
        loadAccounts();
        for (String[] a : accounts) {
            if (a[1].equals(accessToken)) return a;
        }
        return null;
    }

    public static boolean removeAccountByUuid(String uuid) {
        loadAccounts();
        boolean removed = false;
        for (Iterator<String[]> it = accounts.iterator(); it.hasNext(); ) {
            if (it.next()[0].equals(uuid)) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    private static String[] pollForToken(String deviceCode, int interval, long deadline,
                                         MicrosoftSignInDialog.Handle prompt) throws Exception {
        while (System.currentTimeMillis() < deadline) {
            // Wait out the poll interval, but wake immediately if the user cancels.
            synchronized (prompt.lock) {
                if (prompt.cancelled) break;
                prompt.lock.wait(interval * 1000L);
                if (prompt.cancelled) break;
            }

            String pollBody = "client_id=" + CLIENT_ID
                    + "&grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", "UTF-8")
                    + "&device_code=" + URLEncoder.encode(deviceCode, "UTF-8")
                    + "&response_type=device_code"; // login.live.com only returns a refresh_token with this
            Json.JSONObject rsp = httpJson("POST", "https://login.live.com/oauth20_token.srf",
                    "application/x-www-form-urlencoded", pollBody.getBytes("UTF-8"), null);

            String error = rsp.optString("error", "");
            if (error.length() == 0 && rsp.has("access_token")) {
                return new String[]{ rsp.getString("access_token"), rsp.optString("refresh_token", "") };
            }
            if ("authorization_pending".equals(error)) continue;
            if ("slow_down".equals(error)) { interval += 5; continue; }
            if (error.length() != 0) {
                log.error("Device code sign-in failed: " + rsp.optString("error_description", error));
                return null;
            }
        }
        if (prompt.cancelled) log.info("Microsoft sign-in cancelled by user");
        else log.error("Microsoft device code sign-in timed out");
        return null;
    }

    private static Json.JSONObject httpJson(String method, String urlStr, String contentType,
                                            byte[] body, String authHeader) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Accept", "application/json");
        if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
        if (body != null) {
            conn.setDoOutput(true);
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
        }

        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String text = in != null ? Hooks.readStream(in) : "";
        return text.length() == 0 ? new Json.JSONObject() : new Json.JSONObject(text);
    }
}
