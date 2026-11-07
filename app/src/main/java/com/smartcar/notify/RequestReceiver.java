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

/**
 * The door client apps knock on. An explicit broadcast reaches this app even when it is not
 * running, from a client that is itself in the background — which a client starting the service
 * directly could not count on (Android 12 refuses a foreground-service start from the background;
 * this app targets 30, so the start from here is allowed on the units it runs on).
 */
public class RequestReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (NotifyContract.ACTION_SHOW.equals(action) || NotifyContract.ACTION_CANCEL.equals(action)) {
            NotificationService.deliver(context, intent);
        }
    }
}
