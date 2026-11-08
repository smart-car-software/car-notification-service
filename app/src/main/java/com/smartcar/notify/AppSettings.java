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

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    private static final String PREFS = "app_settings";
    private static final String KEY_CLUSTER = "cluster";
    private static final String KEY_WHEEL = "wheel_keys";
    private static final String KEY_MUTE = "mute_media";
    private static final String KEY_HOLD_VOLUME = "hold_volume";
    private static final String KEY_LOG_URL = "log_url";
    private static final String KEY_LOG_USER = "log_user";
    private static final String KEY_LOG_PASSWORD = "log_password";
    /**
     * The Monjaro's instrument cluster: display #2, the 1920x1080 one of its three "HDMI
     * screens". Fixed rather than chosen — that is the car this app is for.
     */
    static final int CLUSTER_DISPLAY = 2;

    private AppSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Cards go to the cluster; on by default, since that is what this app is for. */
    static boolean cluster(Context c) {
        return prefs(c).getBoolean(KEY_CLUSTER, true);
    }

    static void setCluster(Context c, boolean on) {
        set(c, KEY_CLUSTER, on);
    }

    /** Answer with the wheel, read as root through adb. Off until the driver has allowed debugging. */
    static boolean wheelKeys(Context c) {
        return prefs(c).getBoolean(KEY_WHEEL, false);
    }

    static void setWheelKeys(Context c, boolean on) {
        set(c, KEY_WHEEL, on);
    }

    /** While a card is up, keep ⏮ ⏭ ⏯ from also working the player. */
    static boolean muteMedia(Context c) {
        return prefs(c).getBoolean(KEY_MUTE, true);
    }

    static void setMuteMedia(Context c, boolean on) {
        set(c, KEY_MUTE, on);
    }

    /**
     * While a list is scrolled, put the media volume back after each click of the roller. The
     * roller changes the volume whatever the app does (its key cannot be taken, only read), so
     * this is the stopgap until a way to take it is found; see {@link VolumeGuard}.
     */
    static boolean holdVolume(Context c) {
        return prefs(c).getBoolean(KEY_HOLD_VOLUME, true);
    }

    static void setHoldVolume(Context c, boolean on) {
        set(c, KEY_HOLD_VOLUME, on);
    }

    private static void set(Context c, String key, boolean on) {
        DebugLog.i("AppSettings", key + " = " + on);
        // commit, not apply: a head unit's process can be killed seconds after a tap (the engine
        // off), and a switch that silently reverts is worse than a millisecond on the main thread.
        prefs(c).edit().putBoolean(key, on).commit();
    }

    static String describe(Context c) {
        return "cluster " + cluster(c) + ", wheel " + wheelKeys(c) + ", keep from music "
                + muteMedia(c) + ", hold volume " + holdVolume(c);
    }

    /** Test builds kept a log server's address and password here; the release has no use for them. */
    static void forgetLogServer(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains(KEY_LOG_URL) || p.contains(KEY_LOG_USER) || p.contains(KEY_LOG_PASSWORD)) {
            p.edit().remove(KEY_LOG_URL).remove(KEY_LOG_USER).remove(KEY_LOG_PASSWORD).apply();
        }
    }
}
