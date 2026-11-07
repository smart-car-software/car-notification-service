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

    private NotifyContract() {
    }
}
