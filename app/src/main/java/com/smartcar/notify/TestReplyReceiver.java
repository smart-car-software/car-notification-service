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
import android.widget.Toast;

/** Where the settings screen's test cards answer: a toast with what came back, as a client sees it. */
public class TestReplyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String result = intent.getStringExtra(NotifyContract.EXTRA_RESULT);
        String option = intent.getStringExtra(NotifyContract.EXTRA_OPTION);
        String s = context.getString(R.string.test_reply, result, option == null ? "—" : option);
        DebugLog.i("TestReply", s);
        Toast.makeText(context, s, Toast.LENGTH_LONG).show();
    }
}
