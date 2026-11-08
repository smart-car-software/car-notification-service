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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Keeps the media volume where it was while a list is being scrolled with the volume roller.
 *
 * <p>A stopgap, not a cure: the held media session takes the roller's step, but the car's audio
 * policy ({@code com.android.car}) still sets the media stream on every click, so this puts it
 * back.
 */
final class VolumeGuard {
    private static final String TAG = "VolumeGuard";
    /** The log line comes as the key is pressed; the volume moves a beat later. */
    private static final long RESTORE_DELAY_MS = 150L;

    /** Hidden in the SDK, stable since Android 4: the stream a volume change was for. */
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";

    private final Context context;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int held = -1;
    private final Runnable restore = this::restoreNow;
    private boolean listening;

    /**
     * Puts the volume back the moment the car has changed it (the car sets it some 10 ms after
     * the click), rather than {@link #RESTORE_DELAY_MS} later: the jump is then too short to hear
     * as more than a flicker. The delayed restore stays for a change that sent no broadcast.
     */
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (held >= 0 && intent.getIntExtra(EXTRA_STREAM, -1) == AudioManager.STREAM_MUSIC) {
                restoreNow();
            }
        }
    };

    VolumeGuard(Context context) {
        this.context = context.getApplicationContext();
        audio = this.context.getSystemService(AudioManager.class);
    }

    void start() {
        if (audio == null || held >= 0) {
            return;
        }
        held = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        try {
            context.registerReceiver(changed, new IntentFilter(VOLUME_CHANGED), null, main);
            listening = true;
        } catch (RuntimeException e) {
            DebugLog.w(TAG, "Volume changes not followed", e);
        }
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
        if (listening) {
            context.unregisterReceiver(changed);
            listening = false;
        }
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
