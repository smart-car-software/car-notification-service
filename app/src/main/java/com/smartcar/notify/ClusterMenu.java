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
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client apps' lists in the cluster's own menu: route-suggest's places on its navigation tab,
 * barrier-opener's gates on its phone tab — whichever app took the tab the driver opened. The
 * left joystick of the wheel scrolls and its press picks; the pick goes back to the app.
 *
 * <p>How the unit lets an app in there (learned from mNavi's APK, carried over from route-suggest,
 * where it worked in the car): ECarX's {@code DimMenuInteraction} (in the unit's framework,
 * reached by reflection) calls back {@code onTabChanged(tab)} when the driver switches the
 * cluster menu's tab ({@link DimTabs}); the joystick comes to Android as the broadcasts
 * {@link #KEY_UP} / {@link #KEY_DOWN} / {@link #KEY_OK}. The list is our overlay on the cluster
 * display where the tab's panel is ({@link #X}, {@link #Y}). The window stays after a pick: gone,
 * it left the tab's empty panel (route-suggest v0.6.0 in the car).
 *
 * <p>The menu opens on the phone tab; {@code requestDimSwitchTabWindow} takes a menu just opened
 * to navigation ({@link DimTabs#jumpToNavigation}) when the setting says so. What the unit said
 * and did is kept in {@link #journal()} for the app's screen: in the car there is no adb.
 */
final class ClusterMenu {
    static final String DIM_CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction";
    static final String DIM_CALLBACK =
            "com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction$IDimMenuInteractionCallback";
    static final String KEY_UP = "ecarx.intent.action.ECARX_KEY_DIMSCROLLUP_EVENT";
    static final String KEY_DOWN = "ecarx.intent.action.ECARX_KEY_DIMSCROLLDOWN_EVENT";
    static final String KEY_OK = "ecarx.intent.action.ECARX_KEY_DIMCONFIRM_EVENT";

    /** The tab's panel on the driver's display, px (route-suggest's, measured in the car). */
    static final int X = 740;
    static final int Y = 720;
    static final int W = 414;
    static final int H = 284;
    static final int HEAD_H = 44;
    static final int VISIBLE_ROWS = 5;
    /** The test from an app shows the list for this long. */
    static final long TRIAL_MS = 30_000L;
    private static final long FLASH_MS = 600L;
    private static final int JOURNAL = 30;

    interface Listener {
        /** A tab came on screen: its apps may want to bring their rows up to date. */
        void onShown(int dimTab);

        void onPick(MenuModel.Pick pick);
    }

    private static final String TAG = "Menu";
    /** For the app's screen: what the unit said, if anything. */
    static volatile String state = "";
    private static final Deque<String> journal = new ArrayDeque<>();

    private final Context c;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, MenuModel> models = new HashMap<>();
    private Object dim;
    private Object callback;
    private boolean keysRegistered;
    private int dimTab = DimTabs.UNKNOWN;
    private long dimTabAt;

    private WindowManager wm;
    private LinearLayout view;
    private LinearLayout head;
    private LinearLayout list;
    /** The cluster tab the window shows, {@link DimTabs#UNKNOWN} when it is not up. */
    private int shown = DimTabs.UNKNOWN;
    /** Bumped on each show and hide: a trial's timer for an old show does nothing. */
    private int generation;
    private long flashUntil;

    ClusterMenu(Context c, Listener listener) {
        this.c = c;
        this.listener = listener;
    }

    static boolean granted(Context c) {
        return Settings.canDrawOverlays(c);
    }

    static synchronized void log(String line) {
        DebugLog.i(TAG, line);
        String t = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                .toString();
        journal.addFirst(t + " " + line);
        while (journal.size() > JOURNAL) journal.removeLast();
    }

    /** The latest first. */
    static synchronized List<String> journal() {
        return new ArrayList<>(journal);
    }

    // ------------------------------------------------------------------ the unit's side

    void start() {
        main.post(() -> {
            if (!keysRegistered) {
                IntentFilter f = new IntentFilter();
                f.addAction(KEY_UP);
                f.addAction(KEY_DOWN);
                f.addAction(KEY_OK);
                c.registerReceiver(keys, f);
                keysRegistered = true;
            }
            if (dim != null) return;
            try {
                Class<?> cls = Class.forName(DIM_CLASS);
                Class<?> cb = Class.forName(DIM_CALLBACK);
                dim = cls.getConstructor(Context.class).newInstance(c);
                callback = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb},
                        (proxy, m, args) -> {
                            // The unit keeps callbacks in lists: identity for these.
                            switch (m.getName()) {
                                case "equals":
                                    return args != null && args.length == 1 && proxy == args[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "toString":
                                    return "car-notification-service DIM callback";
                                default:
                                    break;
                            }
                            onUnitCall(m, args);
                            return defaultValue(m.getReturnType());
                        });
                cls.getMethod("registerDimMenuInteractionCallback", cb).invoke(dim, callback);
                state = "подключено";
                log("подписка на меню приборки есть");
            } catch (ClassNotFoundException e) {
                dim = null;
                state = "нет ECarX (не голова Monjaro?)";
                log("нет ECarX DimMenuInteraction");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                dim = null;
                state = "ошибка: " + text(e);
                log("подписка не удалась: " + state);
            }
        });
    }

    void stop() {
        main.post(() -> {
            hide();
            if (keysRegistered) {
                try {
                    c.unregisterReceiver(keys);
                } catch (IllegalArgumentException ignored) {
                }
                keysRegistered = false;
            }
            if (dim != null && callback != null) {
                try {
                    Class<?> cb = Class.forName(DIM_CALLBACK);
                    dim.getClass().getMethod("unregisterDimMenuInteractionCallback", cb)
                            .invoke(dim, callback);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    DebugLog.w(TAG, "unregister: " + e);
                }
            }
            dim = null;
            callback = null;
            state = "выключено";
        });
    }

    /** Any call from the unit, on whatever thread it comes. */
    private void onUnitCall(Method m, Object[] args) {
        String name = m.getName();
        if (m.getDeclaringClass() == Object.class) return;
        if (!"onTabChanged".equals(name) || args == null || args.length != 1
                || !(args[0] instanceof Integer)) {
            log("приборка: " + name + (args == null ? "()" : java.util.Arrays.toString(args)));
            return;
        }
        int tab = (Integer) args[0];
        main.post(() -> onTab(tab));
    }

    private void onTab(int tab) {
        long now = SystemClock.elapsedRealtime();
        int previous = dimTab;
        long since = dimTabAt == 0 ? Long.MAX_VALUE : now - dimTabAt;
        dimTab = tab;
        dimTabAt = now;
        state = "подключено, " + DimTabs.name(tab);
        if (AppSettings.menuNavigationFirst(c) && DimTabs.jumpToNavigation(previous, tab, since)) {
            log("вкладка: " + DimTabs.name(tab) + " — переключаю на навигацию");
            switchTo(DimTabs.NAVIGATION);
            show(tab, 0); // until the unit answers with the navigation tab
            return;
        }
        log("вкладка: " + DimTabs.name(tab));
        show(tab, 0);
    }

    /** Asks the cluster for {@code tab}; it answers with {@code onTabChanged} if it obeys. */
    private void switchTo(int tab) {
        if (dim == null) {
            log("переключить вкладку нечем: нет подписки");
            return;
        }
        try {
            dim.getClass().getMethod("requestDimSwitchTabWindow", int.class).invoke(dim, tab);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log("requestDimSwitchTabWindow(" + tab + "): " + text(e));
        }
    }

    private static Object defaultValue(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class || t == short.class || t == byte.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == char.class) return '\0';
        return null;
    }

    private final BroadcastReceiver keys = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            MenuModel m = shown == DimTabs.UNKNOWN ? null : models.get(shown);
            if (m == null || view == null) {
                log("джойстик " + a.replace("ecarx.intent.action.ECARX_KEY_", "")
                        + " на вкладке «" + DimTabs.name(dimTab) + "» — меню не на экране");
                return;
            }
            if (KEY_UP.equals(a)) {
                m.move(-1);
                draw();
            } else if (KEY_DOWN.equals(a)) {
                m.move(1);
                draw();
            } else if (KEY_OK.equals(a)) {
                MenuModel.Pick p = m.press();
                if (p != null) {
                    flashUntil = SystemClock.elapsedRealtime() + FLASH_MS;
                    main.postDelayed(ClusterMenu.this::draw, FLASH_MS);
                    log("выбрано: " + p.tab + ", строка " + (p.index + 1));
                    listener.onPick(p);
                }
                draw();
            }
        }
    };

    // ------------------------------------------------------------------ the lists

    /** An app's tab, new or with new rows. On the main thread. */
    void put(int dimTab, MenuModel.Tab t) {
        for (Map.Entry<Integer, MenuModel> e : models.entrySet()) {
            if (e.getKey() != dimTab) e.getValue().remove(t.id);
        }
        MenuModel m = models.get(dimTab);
        if (m == null) {
            m = new MenuModel(VISIBLE_ROWS);
            models.put(dimTab, m);
        }
        m.put(t);
        if (shown == dimTab) draw();
        else if (this.dimTab == dimTab && shown == DimTabs.UNKNOWN) show(dimTab, 0);
    }

    /** On the main thread. */
    void remove(String id) {
        for (MenuModel m : models.values()) m.remove(id);
        if (shown != DimTabs.UNKNOWN) {
            MenuModel m = models.get(shown);
            if (m == null || m.current() == null) hide();
            else draw();
        }
    }

    /** From an app: its tab for {@link #TRIAL_MS}, as on the cluster. */
    void trial(int dimTab, String tabId) {
        main.post(() -> {
            show(dimTab, TRIAL_MS);
            MenuModel m = models.get(dimTab);
            if (m != null && tabId != null) {
                m.open(tabId);
                draw();
            }
        });
    }

    void hideNow() {
        main.post(this::hide);
    }

    /** On the main thread. {@code forMs} > 0: hide by itself after that long. */
    private void show(int tab, long forMs) {
        MenuModel m = models.get(tab);
        if (m == null || m.current() == null) {
            hide();
            return;
        }
        if (!granted(c)) {
            state = "нет разрешения показывать поверх окон";
            log("нет разрешения показывать поверх окон");
            return;
        }
        int gen = ++generation;
        if (shown != tab) m.reset();
        if (!attach()) return;
        shown = tab;
        draw();
        listener.onShown(tab);
        if (forMs > 0) main.postDelayed(() -> {
            if (gen == generation) hide();
        }, forMs);
    }

    private void hide() {
        generation++;
        shown = DimTabs.UNKNOWN;
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (RuntimeException ignored) {
            }
        }
        view = null;
        head = null;
        list = null;
    }

    private boolean attach() {
        if (view != null) return true;
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        Display d = dm == null ? null : dm.getDisplay(AppSettings.CLUSTER_DISPLAY);
        boolean onCluster = d != null;
        if (d == null && dm != null) d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return false;
        Context dc = c.createDisplayContext(d);
        wm = dc.getSystemService(WindowManager.class);
        view = new LinearLayout(dc);
        view.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.getColor(R.color.cluster_panel));
        bg.setCornerRadius(12);
        bg.setStroke(1, c.getColor(R.color.cluster_panel_rim));
        view.setBackground(bg);
        head = new LinearLayout(dc);
        head.setOrientation(LinearLayout.HORIZONTAL);
        view.addView(head, new LinearLayout.LayoutParams(W, HEAD_H));
        list = new LinearLayout(dc);
        list.setOrientation(LinearLayout.VERTICAL);
        view.addView(list, new LinearLayout.LayoutParams(W, H - HEAD_H));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(W, H,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = onCluster ? X : 40;
        lp.y = onCluster ? Y : 40;
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            state = "окно не открылось: " + text(e);
            log(state);
            view = null;
            head = null;
            list = null;
            return false;
        }
        return true;
    }

    private void draw() {
        if (list == null || shown == DimTabs.UNKNOWN) return;
        MenuModel m = models.get(shown);
        if (m == null || m.current() == null) return;
        head.removeAllViews();
        List<MenuModel.Tab> tabs = m.tabs();
        if (tabs.size() < 2) {
            TextView t = text(m.current().title, false);
            t.setTextColor(c.getColor(R.color.cluster_text_dim));
            head.addView(t, new LinearLayout.LayoutParams(W, HEAD_H));
        } else {
            boolean onStrip = m.selected() == MenuModel.STRIP;
            for (int n = 0; n < tabs.size(); n++) {
                boolean cur = n == m.currentIndex();
                TextView t = text(tabs.get(n).title, cur);
                t.setGravity(Gravity.CENTER);
                t.setTextColor(c.getColor(cur ? R.color.cluster_accent : R.color.cluster_text_dim));
                if (cur && onStrip) t.setBackground(fill(R.color.cluster_accent_fill));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, HEAD_H - 6, 1f);
                lp.topMargin = 3;
                lp.leftMargin = 3;
                lp.rightMargin = 3;
                head.addView(t, lp);
            }
        }
        list.removeAllViews();
        MenuModel.Tab t = m.current();
        int rowH = (H - HEAD_H) / VISIBLE_ROWS;
        if (t.rows.length == 0) {
            list.addView(row(t.empty, false, false, rowH));
            return;
        }
        boolean flash = SystemClock.elapsedRealtime() < flashUntil;
        for (int n = m.first(); n < Math.min(t.rows.length, m.first() + VISIBLE_ROWS); n++) {
            boolean sel = n == m.selected();
            list.addView(row(t.rows[n], sel, sel && flash, rowH));
        }
    }

    private TextView text(String s, boolean bold) {
        TextView t = new TextView(list.getContext());
        t.setText(s);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(18, 0, 18, 0);
        return t;
    }

    private View row(String s, boolean selected, boolean flash, int h) {
        TextView t = text(s, selected);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24);
        t.setTextColor(c.getColor(flash ? R.color.on_accent
                : selected ? R.color.cluster_text : R.color.cluster_text_dim));
        if (selected) t.setBackground(fill(flash ? R.color.cluster_accent : R.color.cluster_accent_fill));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(W - 12, h);
        lp.leftMargin = 6;
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable fill(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.getColor(color));
        bg.setCornerRadius(8);
        return bg;
    }

    private static String text(Throwable e) {
        Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                ? e.getCause() : e;
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
