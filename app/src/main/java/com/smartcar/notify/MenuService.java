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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the cluster menu ({@link ClusterMenu}) for as long as the unit is on: the subscription
 * to the cluster must outlive any card, so this is a service of its own, started at boot and by
 * the first list an app sends. The apps' lists live here in memory with where their picks go;
 * after a restart the apps are asked for them again ({@link NotifyContract#ACTION_MENU_WANTED}).
 */
public class MenuService extends Service implements ClusterMenu.Listener {
    static final String ACTION_START = "com.smartcar.notify.MENU_START";
    static final String ACTION_SETTINGS = "com.smartcar.notify.MENU_SETTINGS";
    /** From the app's screen: tell the cluster a control-centre state (extra {@code type}). */
    static final String ACTION_PROBE = "com.smartcar.notify.MENU_PROBE";
    private static final String TAG = "MenuService";
    private static final String CHANNEL_ID = "menu";
    private static final int NOTIFICATION_ID = 3;

    private static final class Client {
        final String pkg;
        final int dimTab;
        final PendingIntent pick;

        Client(String pkg, int dimTab, PendingIntent pick) {
            this.pkg = pkg;
            this.dimTab = dimTab;
            this.pick = pick;
        }
    }

    private final Map<String, Client> clients = new HashMap<>();
    private ClusterMenu menu;

    static void send(Context c, Intent request) {
        if (!AppSettings.menu(c) && !ACTION_SETTINGS.equals(request.getAction())) return;
        Intent i = new Intent(request);
        i.setClass(c, MenuService.class);
        c.startForegroundService(i);
    }

    static void start(Context c) {
        send(c, new Intent(ACTION_START));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, notification());
        menu = new ClusterMenu(this, this);
        if (AppSettings.menu(this)) {
            menu.start();
            if (AppSettings.menuThirdTab(this)) menu.controlCenter(DimTabs.ACTIVE_MEDIA_PLAYING);
            for (String pkg : AppSettings.menuClients(this)) ask(pkg);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        if (!AppSettings.menu(this)) {
            ClusterMenu.log("меню выключено в приложении");
            stopSelf();
            return START_NOT_STICKY;
        }
        String a = intent == null ? null : intent.getAction();
        if (NotifyContract.ACTION_MENU_TAB.equals(a)) put(intent);
        else if (NotifyContract.ACTION_MENU_REMOVE.equals(a)) remove(intent);
        else if (NotifyContract.ACTION_MENU_TRIAL.equals(a)) trial(intent);
        else if (ACTION_PROBE.equals(a)) menu.controlCenter(intent.getIntExtra("type", 0));
        else if (ACTION_SETTINGS.equals(a)) {
            menu.start();
            for (String pkg : AppSettings.menuClients(this)) ask(pkg);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (menu != null) menu.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ requests

    private void put(Intent i) {
        String id = i.getStringExtra(NotifyContract.EXTRA_TAB);
        String title = i.getStringExtra(NotifyContract.EXTRA_TITLE);
        PendingIntent pick = i.getParcelableExtra(NotifyContract.EXTRA_PICK);
        int dimTab = i.getIntExtra(NotifyContract.EXTRA_DIM_TAB, DimTabs.NAVIGATION);
        String[] rows = i.getStringArrayExtra(NotifyContract.EXTRA_OPTIONS);
        if (id == null || id.isEmpty() || title == null || pick == null
                || (dimTab != DimTabs.NAVIGATION && dimTab != DimTabs.PHONE)) {
            ClusterMenu.log("список отклонён: нет tab, title, pick или вкладка не 1/2 (" + id + ")");
            return;
        }
        if (rows != null && rows.length > NotifyContract.MAX_MENU_ROWS) {
            rows = java.util.Arrays.copyOf(rows, NotifyContract.MAX_MENU_ROWS);
        }
        String[] keys = i.getStringArrayExtra(NotifyContract.EXTRA_KEYS);
        if (keys != null && rows != null && keys.length > rows.length) {
            keys = java.util.Arrays.copyOf(keys, rows.length);
        }
        String pkg = pick.getCreatorPackage();
        Client was = clients.put(id, new Client(pkg, dimTab, pick));
        if (pkg != null) AppSettings.addMenuClient(this, pkg);
        if (was == null) {
            ClusterMenu.log("список «" + title + "» от " + pkg + " на вкладке «"
                    + DimTabs.name(dimTab) + "»: строк " + (rows == null ? 0 : rows.length));
        }
        menu.put(dimTab, new MenuModel.Tab(id, title, i.getIntExtra(NotifyContract.EXTRA_ORDER, 0),
                rows, keys, i.getStringExtra(NotifyContract.EXTRA_EMPTY)));
    }

    private void remove(Intent i) {
        String id = i.getStringExtra(NotifyContract.EXTRA_TAB);
        if (id == null || clients.remove(id) == null) return;
        ClusterMenu.log("список " + id + " убран");
        menu.remove(id);
    }

    private void trial(Intent i) {
        String id = i.getStringExtra(NotifyContract.EXTRA_TAB);
        Client cl = id == null ? null : clients.get(id);
        if (cl == null) {
            ClusterMenu.log("проба: списка " + id + " нет");
            return;
        }
        ClusterMenu.log("проба: " + id);
        menu.trial(cl.dimTab, id);
    }

    /** Asks an app for its list: by package, to the manifest receiver it declares. */
    private void ask(String pkg) {
        sendBroadcast(new Intent(NotifyContract.ACTION_MENU_WANTED).setPackage(pkg));
    }

    // ------------------------------------------------------------------ the menu's side

    @Override
    public void onShown(int dimTab) {
        java.util.Set<String> asked = new java.util.HashSet<>();
        for (Client cl : clients.values()) {
            if (cl.dimTab == dimTab && cl.pkg != null && asked.add(cl.pkg)) ask(cl.pkg);
        }
    }

    @Override
    public void onPick(MenuModel.Pick p) {
        Client cl = clients.get(p.tab);
        if (cl == null) return;
        Intent extras = new Intent()
                .putExtra(NotifyContract.EXTRA_TAB, p.tab)
                .putExtra(NotifyContract.EXTRA_KEY, p.key)
                .putExtra(NotifyContract.EXTRA_INDEX, p.index)
                .putExtra(NotifyContract.EXTRA_OPTION, p.row);
        try {
            cl.pick.send(this, 0, extras);
        } catch (PendingIntent.CanceledException e) {
            ClusterMenu.log("выбор не доставлен: " + cl.pkg + " больше не слушает");
            clients.remove(p.tab);
            menu.remove(p.tab);
        }
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.channel_menu), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_menu))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
