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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "Update" block of the screen, after route-suggest's: the latest release looked at on
 * opening (every few hours, the answer kept in between) and installed by the button only. The
 * pill in the header says a newer one exists; the block itself is a panel of its own.
 */
final class UpdateSection {
    private static final String TAG = "Updater";
    private static final String PREFS = "updater";
    private static final long CHECK_EVERY_MS = 6 * 3600_000L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Updater");
        t.setDaemon(true);
        return t;
    });

    private final Activity activity;
    private final TextView status;
    private final TextView notes;
    private final Button button;
    private final TextView pill;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Updater.Release available;
    private boolean busy;

    UpdateSection(Activity activity, TextView status, TextView notes, Button button, TextView pill) {
        this.activity = activity;
        this.status = status;
        this.notes = notes;
        this.button = button;
        this.pill = pill;
        button.setOnClickListener(v -> {
            if (available != null) install();
            else check(true);
        });
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** {@code now}: the driver asked; otherwise the kept answer while it is fresh. */
    void check(boolean now) {
        SharedPreferences p = prefs(activity);
        if (!now && System.currentTimeMillis() - p.getLong("checked", 0) < CHECK_EVERY_MS) {
            String tag = p.getString("tag", "");
            available = Updater.newer(tag, BuildConfig.VERSION_NAME)
                    ? new Updater.Release(tag, p.getString("url", ""), p.getString("notes", ""))
                    : null;
            draw();
            return;
        }
        if (now) {
            busy = true;
            draw();
        }
        WORKER.execute(() -> {
            String err = "";
            Updater.Release found = null;
            try {
                Updater.Release r = Updater.latest();
                p.edit().putLong("checked", System.currentTimeMillis())
                        .putString("tag", r == null ? "" : r.tag)
                        .putString("url", r == null ? "" : r.apkUrl)
                        .putString("notes", r == null ? "" : r.notes).apply();
                found = r != null && Updater.newer(r.tag, BuildConfig.VERSION_NAME) ? r : null;
            } catch (IOException | RuntimeException e) {
                err = text(e);
            }
            String error = err;
            Updater.Release result = found;
            main.post(() -> {
                busy = false;
                // A failed look keeps what was known: the network may just not be up yet.
                if (error.isEmpty()) available = result;
                p.edit().putString("error", error).apply();
                draw();
                if (now && error.isEmpty() && result == null) toast(activity.getString(R.string.upd_latest));
            });
        });
    }

    private void draw() {
        Updater.Release r = available;
        pill.setVisibility(r != null ? View.VISIBLE : View.GONE);
        if (r != null) pill.setText(activity.getString(R.string.upd_pill, r.tag));
        status.setText(r != null ? activity.getString(R.string.upd_available, r.tag)
                : activity.getString(R.string.upd_current, BuildConfig.VERSION_NAME));
        String err = prefs(activity).getString("error", "");
        String n = r != null ? r.notes.trim() : err.isEmpty() ? "" : activity.getString(R.string.upd_error, err);
        notes.setText(n);
        notes.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
        button.setEnabled(!busy);
        // «Обновить до vX» is the screen's one strong action; «Проверить» a quiet one.
        boolean strong = r != null && !busy;
        button.setBackgroundTintList(ColorStateList.valueOf(activity.getColor(
                strong ? R.color.accent : R.color.accent_dim)));
        button.setTextColor(activity.getColor(strong ? R.color.on_accent : R.color.accent));
        button.setText(busy ? activity.getString(R.string.upd_checking)
                : r != null ? activity.getString(R.string.upd_install, r.tag)
                : activity.getString(R.string.upd_check));
    }

    private void install() {
        Updater.Release r = available;
        if (r == null || r.apkUrl.isEmpty()) return;
        busy = true;
        draw();
        button.setText(activity.getString(R.string.upd_downloading, r.tag));
        Context app = activity.getApplicationContext();
        WORKER.execute(() -> {
            String err = "";
            boolean needPrompt = false;
            File apk = null;
            try {
                apk = Updater.download(app, r);
                DebugLog.i(TAG, "downloaded " + r.tag);
                needPrompt = !Updater.installAsRoot(app, apk);
            } catch (IOException | RuntimeException e) {
                DebugLog.w(TAG, "update to " + r.tag + " failed", e);
                err = text(e);
            }
            String error = err;
            File file = apk;
            boolean prompt = needPrompt;
            main.post(() -> {
                busy = false;
                draw();
                if (!error.isEmpty()) toast(activity.getString(R.string.upd_error, error));
                else if (prompt) installWithPrompt(file);
            });
        });
    }

    /** Android's installer reads our own "install unknown apps" setting: asked for first. */
    private void installWithPrompt(File apk) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            toast(activity.getString(R.string.upd_allow_unknown));
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (RuntimeException ignored) {
                // A firmware without that screen: nothing more to open.
            }
            return;
        }
        Context app = activity.getApplicationContext();
        WORKER.execute(() -> {
            try {
                Updater.installWithPrompt(app, apk);
            } catch (IOException | RuntimeException e) {
                DebugLog.w(TAG, "installer failed", e);
                main.post(() -> toast(activity.getString(R.string.upd_error, text(e))));
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
    }

    /** An exception as a line for the screen: its message, or its kind when it has none. */
    private static String text(Throwable e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
