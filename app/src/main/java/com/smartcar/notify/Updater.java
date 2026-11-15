/*
 * Copyright © 2026 smart-car-software contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.smartcar.notify;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates from the app itself, by the button only (as route-suggest does): the latest GitHub
 * Release of this (public) repository, its APK downloaded and installed — silently as root
 * through the unit's adb when it already trusts us (the wheel's keys are on), else by Android's
 * installer, which asks. The same signing key, so the settings stay.
 */
final class Updater {
    private static final String TAG = "Updater";
    static final String LATEST =
            "https://api.github.com/repos/smart-car-software/car-notification-service/releases/latest";
    private static final int CONNECT_MS = 10_000;

    static final class Release {
        final String tag;
        final String apkUrl;
        final String notes;

        Release(String tag, String apkUrl, String notes) {
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.notes = notes;
        }
    }

    private Updater() {
    }

    /** «v0.8.1» → {0, 8, 1}; anything else → null. Pure. */
    static int[] version(String v) {
        if (v == null) return null;
        Matcher m = Pattern.compile("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(v.trim());
        if (!m.find()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
    }

    /** Whether {@code latest} is newer than {@code current}. Pure. */
    static boolean newer(String latest, String current) {
        int[] a = version(latest), b = version(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }

    /** GitHub's «latest release» answer → the release with its APK, or null. Pure. */
    static Release parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        String tag = o.optString("tag_name", "");
        JSONArray assets = o.optJSONArray("assets");
        if (tag.isEmpty() || assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) {
                return new Release(tag, a.optString("browser_download_url"), o.optString("body", ""));
            }
        }
        return null;
    }

    /** The latest release, or null when it carries no APK. Blocking. */
    static Release latest() throws IOException {
        HttpURLConnection h = open(LATEST, 15_000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = h.getResponseCode();
            if (code == 404) throw new IOException("GitHub: no releases visible");
            if (code / 100 != 2) throw new IOException("GitHub: HTTP " + code);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                copy(in, body);
            }
            try {
                return parse(new String(body.toByteArray(), StandardCharsets.UTF_8));
            } catch (JSONException e) {
                throw new IOException("GitHub: unexpected answer");
            }
        } finally {
            h.disconnect();
        }
    }

    /** Downloads the APK into the app's cache. Blocking. */
    static File download(Context c, Release rel) throws IOException {
        File out = new File(c.getCacheDir(), "update.apk");
        HttpURLConnection h = open(rel.apkUrl, 60_000);
        try (InputStream in = h.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
        } finally {
            h.disconnect();
        }
        if (out.length() < 100_000) throw new IOException("the download is not an APK");
        return out;
    }

    /**
     * As root through the unit's adb, silent — only when the wheel's keys are on, i.e. the unit
     * has already been asked "allow debugging?" for us; an update is no reason to ask it. The
     * app's process goes with the install. Blocking; false when the unit is not ours to use.
     */
    static boolean installAsRoot(Context c, File apk) throws IOException {
        if (!AppSettings.wheelKeys(c)) return false;
        String tmp = "/data/local/tmp/car-notification-service-update.apk";
        StringBuilder out = new StringBuilder();
        // One command, waited for: in route-suggest a second shell, or a detached install, did
        // not survive the adb session. The system ends this process only once the install is
        // done, so «Success» may never reach us — then the update is in.
        DebugLog.i(TAG, "installing as root");
        try (LocalAdb adb = LocalAdb.connect(c, new StringBuilder())) {
            adb.shell("su 0 sh -c 'cp " + apk.getAbsolutePath() + " " + tmp + " && chmod 644 " + tmp
                    + " && echo COPIED && pm install -r " + tmp + "; rm -f " + tmp + "'", out::append);
        }
        String o = out.toString();
        if (!o.contains("COPIED")) throw new IOException("no root: " + o.trim());
        if (!o.contains("Success")) {
            String err = o.substring(o.indexOf("COPIED") + 6).trim();
            throw new IOException("not installed: " + (err.isEmpty() ? "?" : err));
        }
        return true;
    }

    /** Android's own installer: it asks the driver to confirm, {@link UpdateReceiver} hears the end. */
    static void installWithPrompt(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream os = s.openWrite("car-notification-service.apk", 0, apk.length())) {
                copy(in, os);
                s.fsync(os);
            }
            Intent done = new Intent(c, UpdateReceiver.class);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, done,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pend.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url, int readMs) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        // GitHub hands the asset out through a redirect to its CDN (https → https).
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(CONNECT_MS);
        h.setReadTimeout(readMs);
        h.setRequestProperty("User-Agent", "car-notification-service/" + BuildConfig.VERSION_NAME);
        return h;
    }

    private static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
    }
}
