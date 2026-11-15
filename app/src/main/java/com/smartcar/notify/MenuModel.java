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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the cluster menu shows and where the joystick is in it: the client apps' tabs («Маршруты»,
 * «Шлагбаумы»), the current one's rows, the selection. Pure, so the joystick's rules are tested.
 *
 * <p>The joystick gives only up, down and a press (left and right switch the cluster's own tabs
 * and never reach Android). So the tab strip is a row of its own above the list: up from the
 * first row selects it ({@link #STRIP}), a press there moves to the next tab, down goes back to
 * the rows. With one tab there is no strip to select.
 */
final class MenuModel {
    /** The selection is on the tab strip, not on a row. */
    static final int STRIP = -1;

    static final class Tab {
        final String id;
        final String title;
        final int order;
        final String[] rows;
        /** What the client calls each row; the row's text when it gave none. */
        final String[] keys;
        /** Shown in place of the rows when there are none. */
        final String empty;

        Tab(String id, String title, int order, String[] rows, String[] keys, String empty) {
            this.id = id;
            this.title = title;
            this.order = order;
            this.rows = rows == null ? new String[0] : rows;
            this.keys = keys != null && keys.length == this.rows.length ? keys : this.rows;
            this.empty = empty == null ? "" : empty;
        }
    }

    /** A row the driver pressed. */
    static final class Pick {
        final String tab;
        final int index;
        final String key;
        final String row;

        Pick(String tab, int index, String key, String row) {
            this.tab = tab;
            this.index = index;
            this.key = key;
            this.row = row;
        }
    }

    private final int visible;
    private final List<Tab> tabs = new ArrayList<>();
    private int current;
    private int selected;
    private int first;

    MenuModel(int visibleRows) {
        this.visible = visibleRows;
    }

    /**
     * A tab is new or has new rows. The driver stays where they were: on the same tab, on the row
     * with the same key if it is still there.
     */
    void put(Tab t) {
        String was = currentId();
        String wasKey = selected >= 0 && current() != null && selected < current().rows.length
                ? current().keys[selected] : null;
        boolean shown = current() != null && t.id.equals(was);
        for (int n = 0; n < tabs.size(); n++) {
            if (tabs.get(n).id.equals(t.id)) {
                tabs.remove(n);
                break;
            }
        }
        tabs.add(t);
        Collections.sort(tabs, (a, b) -> a.order != b.order ? Integer.compare(a.order, b.order)
                : a.id.compareTo(b.id));
        current = Math.max(0, indexOf(was));
        if (!shown) {
            clamp();
            return;
        }
        if (selected != STRIP) {
            int at = wasKey == null ? -1 : Arrays.asList(t.keys).indexOf(wasKey);
            selected = at >= 0 ? at : 0;
        }
        clamp();
    }

    /** The client is gone or switched its tab off. */
    boolean remove(String id) {
        String was = currentId();
        for (int n = 0; n < tabs.size(); n++) {
            if (tabs.get(n).id.equals(id)) {
                tabs.remove(n);
                if (id.equals(was)) {
                    current = 0;
                    selected = 0;
                    first = 0;
                } else {
                    current = Math.max(0, indexOf(was));
                }
                clamp();
                return true;
            }
        }
        return false;
    }

    /** The menu comes on screen: the first tab, its first row. */
    void reset() {
        current = 0;
        selected = 0;
        first = 0;
    }

    /** Shows {@code id}'s tab, if there is one (the test from a client's app). */
    void open(String id) {
        int at = indexOf(id);
        if (at < 0) return;
        current = at;
        selected = 0;
        first = 0;
    }

    void move(int by) {
        Tab t = current();
        if (t == null) return;
        if (selected == STRIP) {
            if (by > 0) selected = 0;
        } else {
            int n = selected + by;
            if (n < 0) selected = tabs.size() > 1 ? STRIP : 0;
            else selected = Math.min(n, Math.max(0, t.rows.length - 1));
        }
        clamp();
    }

    /** The joystick's press: the next tab on the strip, else the row (null when there is none). */
    Pick press() {
        Tab t = current();
        if (t == null) return null;
        if (selected == STRIP) {
            current = (current + 1) % tabs.size();
            first = 0;
            return null;
        }
        if (selected >= t.rows.length) return null;
        return new Pick(t.id, selected, t.keys[selected], t.rows[selected]);
    }

    List<Tab> tabs() {
        return Collections.unmodifiableList(tabs);
    }

    Tab current() {
        return tabs.isEmpty() ? null : tabs.get(Math.min(current, tabs.size() - 1));
    }

    int currentIndex() {
        return current;
    }

    int selected() {
        return selected;
    }

    /** The first row on screen. */
    int first() {
        return first;
    }

    private String currentId() {
        Tab t = current();
        return t == null ? null : t.id;
    }

    private int indexOf(String id) {
        if (id == null) return -1;
        for (int n = 0; n < tabs.size(); n++) if (tabs.get(n).id.equals(id)) return n;
        return -1;
    }

    private void clamp() {
        Tab t = current();
        if (t == null) {
            current = 0;
            selected = 0;
            first = 0;
            return;
        }
        if (selected == STRIP && tabs.size() < 2) selected = 0;
        if (selected != STRIP) selected = Math.max(0, Math.min(selected, t.rows.length - 1));
        int row = Math.max(0, selected);
        if (row < first) first = row;
        if (row >= first + visible) first = row - visible + 1;
        first = Math.max(0, Math.min(first, Math.max(0, t.rows.length - visible)));
    }
}
