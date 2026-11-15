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

/**
 * The wire contract with client apps — the one thing they and this service must agree on, and
 * the reason it is all plain strings: a client copies these values (see docs/API.md), it does not
 * link against this app.
 *
 * <p>A client sends an <em>explicit</em> broadcast to {@link #RECEIVER} (explicit broadcasts reach
 * a stopped app on every Android version, which neither an implicit one nor a service start from
 * the background does), and gets the answer back through the {@code PendingIntent} it put in
 * {@link #EXTRA_REPLY}, filled in with {@link #EXTRA_ID}, {@link #EXTRA_RESULT} and, for an
 * answer, {@link #EXTRA_INDEX} / {@link #EXTRA_OPTION}.
 */
final class NotifyContract {
    static final String PACKAGE = "com.smartcar.notify";
    static final String RECEIVER = "com.smartcar.notify.RequestReceiver";

    /** Queue a card (or update the one with the same id). */
    static final String ACTION_SHOW = "com.smartcar.notify.action.SHOW";
    /** Take the card with that id away, shown or waiting. */
    static final String ACTION_CANCEL = "com.smartcar.notify.action.CANCEL";

    // Request extras.
    /** String, required. Unique for what is being asked; prefix it with your package. */
    static final String EXTRA_ID = "id";
    /** String: {@link #TYPE_INFO}, {@link #TYPE_QUESTION} or {@link #TYPE_LIST}. */
    static final String EXTRA_TYPE = "type";
    static final String EXTRA_TITLE = "title";
    /** String, optional: a second line under the title. */
    static final String EXTRA_TEXT = "text";
    /** String[]: a question's answers (1–4) or a list's rows (1–50); none for info. */
    static final String EXTRA_OPTIONS = "options";
    /** int, optional: the answer or row selected at first (default 0). */
    static final String EXTRA_SELECTED = "selected";
    /** long ms, optional: how long it stays up once shown (default: info 8 s, else 5 min). */
    static final String EXTRA_TIMEOUT_MS = "timeout_ms";
    /**
     * long ms, optional: how long it may wait in the line before it is no longer worth showing
     * (default: no limit). Counted from when the service receives it.
     */
    static final String EXTRA_EXPIRE_MS = "expire_ms";
    /** boolean, optional: chime when it comes on screen. */
    static final String EXTRA_SOUND = "sound";
    /** PendingIntent, optional for info: where the result goes. Make it FLAG_MUTABLE on 31+. */
    static final String EXTRA_REPLY = "reply";

    static final String TYPE_INFO = "info";
    static final String TYPE_QUESTION = "question";
    static final String TYPE_LIST = "list";

    // Reply extras (EXTRA_ID comes back too).
    static final String EXTRA_RESULT = "result";
    /** int: the chosen answer / row; -1 for anything but {@link #RESULT_ANSWERED}. */
    static final String EXTRA_INDEX = "index";
    /** String: its text. */
    static final String EXTRA_OPTION = "option";
    /** String: why a request was refused ({@link #RESULT_INVALID}). */
    static final String EXTRA_ERROR = "error";

    /** The driver picked {@link #EXTRA_INDEX}. */
    static final String RESULT_ANSWERED = "answered";
    /** An information card was seen to the end (its time, ⏯ or a tap). */
    static final String RESULT_DISMISSED = "dismissed";
    /** Shown, never answered within its time. */
    static final String RESULT_TIMEOUT = "timeout";
    /** Never shown: it waited past {@link #EXTRA_EXPIRE_MS}. */
    static final String RESULT_EXPIRED = "expired";
    /** Taken away with {@link #ACTION_CANCEL}. */
    static final String RESULT_CANCELLED = "cancelled";
    /** Refused as malformed; see {@link #EXTRA_ERROR}. */
    static final String RESULT_INVALID = "invalid";
    /**
     * Nothing can be shown at all (the overlay permission is not granted): ask in your own way.
     * Sent at once, before the card is queued.
     */
    static final String RESULT_UNAVAILABLE = "unavailable";

    // ------------------------------------------------------------------ the cluster menu

    /**
     * Put the app's list in the cluster menu (or replace its rows): {@link #EXTRA_TAB},
     * {@link #EXTRA_DIM_TAB}, {@link #EXTRA_TITLE}, {@link #EXTRA_OPTIONS} (0–50 rows),
     * optionally {@link #EXTRA_KEYS}, {@link #EXTRA_EMPTY}, {@link #EXTRA_ORDER}, and
     * {@link #EXTRA_PICK}, where a pressed row goes.
     */
    static final String ACTION_MENU_TAB = "com.smartcar.notify.action.MENU_TAB";
    /** Take the app's list ({@link #EXTRA_TAB}) out of the menu. */
    static final String ACTION_MENU_REMOVE = "com.smartcar.notify.action.MENU_REMOVE";
    /** Show the app's list ({@link #EXTRA_TAB}) on the cluster for 30 s, as a test. */
    static final String ACTION_MENU_TRIAL = "com.smartcar.notify.action.MENU_TRIAL";
    /**
     * Sent to each app that has put a list (by package, to its manifest receiver) when its tab
     * comes on screen and when this service starts: send your list again, it may be stale.
     */
    static final String ACTION_MENU_WANTED = "com.smartcar.notify.action.MENU_WANTED";

    /** String: the list's id, unique per app (your package, or package + ":" + name). */
    static final String EXTRA_TAB = "tab";
    /** int: on which of the cluster's tabs: {@link #DIM_TAB_NAVIGATION} or {@link #DIM_TAB_PHONE}. */
    static final String EXTRA_DIM_TAB = "dim_tab";
    /** String[], optional: what the app calls each row, sent back with the pick. */
    static final String EXTRA_KEYS = "keys";
    /** String, optional: shown when there are no rows. */
    static final String EXTRA_EMPTY = "empty";
    /** int, optional: lists on the same cluster tab are ordered by it. */
    static final String EXTRA_ORDER = "order";
    /** PendingIntent: gets {@link #EXTRA_TAB}, {@link #EXTRA_KEY}, {@link #EXTRA_INDEX}, {@link #EXTRA_OPTION}. */
    static final String EXTRA_PICK = "pick";
    /** String: the pressed row's key. */
    static final String EXTRA_KEY = "key";

    static final int DIM_TAB_PHONE = DimTabs.PHONE;
    static final int DIM_TAB_NAVIGATION = DimTabs.NAVIGATION;
    static final int MAX_MENU_ROWS = 50;

    private NotifyContract() {
    }
}
