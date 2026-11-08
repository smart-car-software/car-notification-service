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
import android.os.Handler;
import android.os.Looper;

/**
 * A diagnostic capture for the one open question about the volume roller: how to keep it from
 * changing the volume while a list is being scrolled. Its presses can be read (InputImpl) but not
 * taken, and what to try next depends on where the volume change is made — so for
 * {@link #SECONDS} seconds, while the driver rolls it, this records as root:
 *
 * <ul>
 *   <li>every log buffer, unfiltered — which service (ECarX input, car audio, AudioService) acts
 *       on the click;</li>
 *   <li>the media stream's volume and the media sessions before and after — whether it is the
 *       Android stream that moves, which {@link VolumeGuard} can put back, or the car's own.</li>
 * </ul>
 *
 * The result goes to the log server as {@code source=volumetrace}.
 */
final class VolumeTrace {
    static final int SECONDS = 15;
    /** No single quotes: it runs inside {@code su 0 sh -c '…'}. */
    private static final String COMMAND = "echo == before; dumpsys audio | grep -A 8 STREAM_MUSIC;"
            + " dumpsys media_session | head -n 200;"
            + " echo == trace; timeout " + SECONDS + " logcat -b all -v threadtime -T 1;"
            + " echo == after; dumpsys audio | grep -A 8 STREAM_MUSIC; echo == end";
    private static final String KILL = "pkill -f \"logcat -b all\"";

    interface Done {
        /** Main thread: everything captured, or what went wrong. */
        void onDone(String capture);
    }

    private final RootStream stream;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder out = new StringBuilder();
    private boolean running;

    VolumeTrace(Context context) {
        stream = new RootStream(context);
    }

    boolean isRunning() {
        return running;
    }

    void start(Done done) {
        if (running) {
            return;
        }
        running = true;
        out.setLength(0);
        // A unit without toybox's timeout would never end on its own.
        Runnable guard = stream::stop;
        main.postDelayed(guard, (SECONDS + 20) * 1000L);
        stream.start(COMMAND, KILL, line -> {
            if (line != null) {
                out.append(line).append('\n');
                return;
            }
            main.removeCallbacks(guard);
            running = false;
            done.onDone(out.toString());
        });
    }

    void stop() {
        stream.stop();
    }
}
