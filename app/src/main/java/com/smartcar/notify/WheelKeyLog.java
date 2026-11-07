/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
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
import android.os.Handler;
import android.os.Looper;

/**
 * The Monjaro's wheel as Android can see it at all: the ECarX input library logs every press of
 * the right-hand keys and the volume roller ({@code InputImpl: onKeyPressed cookie=0
 * keyCode=200087}), and this follows that log as root through the unit's own adb daemon
 * ({@link LocalAdb}). Key events, the input service's broadcasts, ECarX key interception and
 * Geely OneOS were all tried in the car and deliver nothing; the left joystick belongs to the
 * cluster and never reaches Android by any route. Moved here from barrier-opener (MonjaroWheel).
 */
final class WheelKeyLog {
    private static final String TAG = "WheelKeyLog";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LocalAdb logStream;
    private volatile boolean running;
    private volatile int generation;

    WheelKeyLog(Context context) {
        this.context = context.getApplicationContext();
    }

    interface StateListener {
        /** Main thread: the key log is being followed (true), or is not / no longer (false). */
        void onWheelState(boolean connected);
    }

    boolean isRunning() {
        return running;
    }

    /** Starts following the key log; harmless on units without adb or the service. */
    void start(WheelKeys.Listener listener, StateListener state) {
        if (running) {
            return;
        }
        running = true;
        // A reader stopped and restarted at once (two cards in a row) must not have the old
        // thread's ending switch the new one off.
        final int gen = ++generation;
        Thread reader = new Thread(() -> {
            StringBuilder pending = new StringBuilder();
            try (LocalAdb adb = LocalAdb.connect(context, new StringBuilder())) {
                if (!running || gen != generation) {
                    return;
                }
                logStream = adb;
                main.post(() -> state.onWheelState(true));
                // -T 1: from now on, not the presses already in the log buffer.
                adb.shell("su 0 sh -c 'logcat -b main -v raw -T 1 InputImpl:V *:S'", text -> {
                    pending.append(text);
                    int nl;
                    while ((nl = pending.indexOf("\n")) >= 0) {
                        int code = WheelNavigator.pressedKeyFromInputImplLog(pending.substring(0, nl));
                        pending.delete(0, nl + 1);
                        if (code > 0 && running && gen == generation) {
                            main.post(() -> listener.onWheelKey(code, true));
                        }
                    }
                });
            } catch (Exception e) {
                DebugLog.w(TAG, "Wheel log ended", e);
            }
            if (gen != generation) {
                return; // Superseded: the new reader reports for itself.
            }
            logStream = null;
            running = false;
            // Ended on its own (adb gone, root refused) or because it was stopped: either way the
            // wheel no longer answers, and the screen must not rely on it.
            main.post(() -> state.onWheelState(false));
        }, "WheelKeyLog");
        reader.setDaemon(true);
        reader.start();
    }

    void stop() {
        if (!running) {
            return;
        }
        running = false;
        LocalAdb adb = logStream;
        logStream = null;
        if (adb != null) {
            adb.close();
        }
    }
}
