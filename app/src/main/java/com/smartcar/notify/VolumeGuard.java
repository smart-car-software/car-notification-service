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
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Keeps the media volume where it was while a list is being scrolled with the volume roller.
 *
 * <p>A stopgap, not a cure: the roller's key cannot be taken from the car, only read out of the
 * ECarX log (see {@link WheelKeyLog}), so each click still changes the volume and this puts it
 * back a moment later. Whether that even reaches the car's own volume — rather than an Android
 * stream the car ignores — is exactly what the volume trace on the main screen is there to find
 * out; until then it is a switch in the settings.
 */
final class VolumeGuard {
    private static final String TAG = "VolumeGuard";
    /** The log line comes as the key is pressed; the volume moves a beat later. */
    private static final long RESTORE_DELAY_MS = 150L;

    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int held = -1;
    private final Runnable restore = this::restoreNow;

    VolumeGuard(Context context) {
        audio = context.getApplicationContext().getSystemService(AudioManager.class);
    }

    void start() {
        if (audio == null || held >= 0) {
            return;
        }
        held = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        DebugLog.i(TAG, "Holding the media volume at " + held);
    }

    /** One click of the roller went by: put the volume back once it has moved. */
    void onRoll() {
        if (held < 0) {
            return;
        }
        main.removeCallbacks(restore);
        main.postDelayed(restore, RESTORE_DELAY_MS);
    }

    void stop() {
        if (held < 0) {
            return;
        }
        main.removeCallbacks(restore);
        restoreNow();
        held = -1;
    }

    private void restoreNow() {
        if (audio == null || held < 0) {
            return;
        }
        int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (now != held) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, held, 0);
                DebugLog.each(TAG, "Volume " + now + " put back to " + held);
            } catch (SecurityException e) {
                DebugLog.w(TAG, "Volume not put back", e);
            }
        }
    }
}
