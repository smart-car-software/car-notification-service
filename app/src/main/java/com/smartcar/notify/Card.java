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
 * One request from a client app, as the queue and the screen see it. Android-free: the reply
 * target (a {@code PendingIntent}) rides along as an opaque object, so queueing and validation are
 * under test on the JVM.
 */
final class Card {

    enum Type {
        /** Something to read: no answers, goes by itself or on ⏯. */
        INFO,
        /** A few answers side by side, chosen with ⏮ ⏭, pressed with ⏯. */
        QUESTION,
        /** A column of rows scrolled with the volume roller, chosen with ⏯. */
        LIST;

        static Type parse(String s) {
            if (s == null) {
                return null;
            }
            switch (s) {
                case NotifyContract.TYPE_INFO:
                    return INFO;
                case NotifyContract.TYPE_QUESTION:
                    return QUESTION;
                case NotifyContract.TYPE_LIST:
                    return LIST;
                default:
                    return null;
            }
        }
    }

    /** Side by side on a 460 px panel: four short answers are the most that stay readable. */
    static final int MAX_ANSWERS = 4;
    static final int MAX_ROWS = 50;
    static final long INFO_DEFAULT_MS = 8_000L;
    /**
     * A question or a list stays until answered — the driver may be busy for a while — but one
     * nobody can answer (the wheel lost, the driver gone) must still leave room for the next.
     */
    static final long ANSWER_GIVE_UP_MS = 5 * 60_000L;

    final String id;
    final Type type;
    final String title;
    final String text;
    final String[] options;
    final int initial;
    /** How long it stays up once shown. */
    final long showForMs;
    /** Wall-clock deadline for still being shown at all; 0 means it may wait as long as it takes. */
    final long expiresAtMs;
    final boolean sound;
    /** Where the answer goes; a {@code PendingIntent} on the device, anything in tests. */
    final Object reply;

    Card(String id, Type type, String title, String text, String[] options, int initial,
         long showForMs, long expiresAtMs, boolean sound, Object reply) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.text = text == null ? "" : text;
        this.options = options == null ? new String[0] : options;
        this.initial = Math.max(0, Math.min(initial, Math.max(0, this.options.length - 1)));
        this.showForMs = showForMs > 0 ? Math.min(showForMs, ANSWER_GIVE_UP_MS)
                : (type == Type.INFO ? INFO_DEFAULT_MS : ANSWER_GIVE_UP_MS);
        this.expiresAtMs = expiresAtMs;
        this.sound = sound;
        this.reply = reply;
    }

    /**
     * Why a request cannot be shown, or null when it can. Checked before it is queued, so a
     * broken request is answered at once instead of blocking the queue.
     */
    static String invalid(String id, Type type, String title, String[] options) {
        if (id == null || id.trim().isEmpty()) {
            return "no id";
        }
        if (type == null) {
            return "unknown type";
        }
        if (title == null || title.trim().isEmpty()) {
            return "no title";
        }
        int n = options == null ? 0 : options.length;
        if (options != null) {
            for (String o : options) {
                if (o == null || o.trim().isEmpty()) {
                    return "an empty option";
                }
            }
        }
        switch (type) {
            case QUESTION:
                return n < 1 || n > MAX_ANSWERS ? "a question takes 1–" + MAX_ANSWERS + " answers" : null;
            case LIST:
                return n < 1 || n > MAX_ROWS ? "a list takes 1–" + MAX_ROWS + " rows" : null;
            case INFO:
            default:
                return n > 0 ? "an information card takes no options" : null;
        }
    }

    boolean expired(long nowMs) {
        return expiresAtMs > 0 && nowMs >= expiresAtMs;
    }
}
