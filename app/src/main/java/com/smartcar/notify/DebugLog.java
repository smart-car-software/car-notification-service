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
import android.util.Log;

import java.io.File;

/**
 * What the app decided and why, to logcat. (Test builds also kept it on the unit as a journal for
 * "Send logs"; the release has neither, and drops the file those builds left behind.)
 */
final class DebugLog {
    private static final String OLD_JOURNAL = "journal.txt";

    private DebugLog() {
    }

    /** From {@code NotifyApp}. */
    static void init(Context context) {
        File old = new File(context.getApplicationContext().getFilesDir(), OLD_JOURNAL);
        if (old.exists() && !old.delete()) {
            Log.w("DebugLog", "Old journal not deleted");
        }
    }

    static void i(String tag, String message) {
        Log.i(tag, message);
    }

    /** Every occurrence counts (a wheel press). */
    static void each(String tag, String message) {
        Log.i(tag, message);
    }

    static void w(String tag, String message, Throwable error) {
        Log.w(tag, message, error);
    }
}
