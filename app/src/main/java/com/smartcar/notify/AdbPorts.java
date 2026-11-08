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

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Why no port of {@link LocalAdb#PORTS} gave a shell, in a line the settings screen can show:
 * the ports grouped by plain reason, then what to do. The raw exceptions go to logcat only.
 */
final class AdbPorts {

    enum Failure {
        /** Nothing listens there. */
        REFUSED("refused"),
        /** The connection or the answer timed out, or the socket failed otherwise. */
        NO_ANSWER("no answer"),
        /** It accepted the connection but did not speak adb (closed on CNXN, garbage). */
        NOT_ADBD("not adbd"),
        /** adbd asked for the key and the connection ended: debugging not allowed on the screen. */
        NOT_ALLOWED("debugging not allowed");

        final String text;

        Failure(String text) {
            this.text = text;
        }
    }

    private AdbPorts() {
    }

    /** How a TCP connect to a port failed. */
    static Failure ofConnect(Exception e) {
        return e instanceof ConnectException ? Failure.REFUSED : Failure.NO_ANSWER;
    }

    /** How the adb handshake on an accepted connection failed. */
    static Failure ofHandshake(boolean askedForKey) {
        return askedForKey ? Failure.NOT_ALLOWED : Failure.NOT_ADBD;
    }

    /**
     * E.g. "no adb on 127.0.0.1 (5555/5556/5557 refused, 5037 not adbd); switch on adb over the
     * network, as for MConfig+". {@code failures[i]} is for {@code ports[i]}; a null entry is
     * skipped.
     */
    static String message(int[] ports, Failure[] failures) {
        Map<Failure, List<String>> byReason = new LinkedHashMap<>();
        boolean notAllowed = false;
        for (int i = 0; i < ports.length && i < failures.length; i++) {
            Failure f = failures[i];
            if (f == null) {
                continue;
            }
            notAllowed |= f == Failure.NOT_ALLOWED;
            List<String> list = byReason.get(f);
            if (list == null) {
                list = new ArrayList<>();
                byReason.put(f, list);
            }
            list.add(String.valueOf(ports[i]));
        }
        StringBuilder s = new StringBuilder("no adb on 127.0.0.1");
        if (!byReason.isEmpty()) {
            s.append(" (");
            boolean first = true;
            for (Map.Entry<Failure, List<String>> e : byReason.entrySet()) {
                if (!first) {
                    s.append(", ");
                }
                first = false;
                s.append(String.join("/", e.getValue())).append(' ').append(e.getKey().text);
            }
            s.append(')');
        }
        s.append(notAllowed
                ? "; allow debugging for this app on the screen, with \"always\""
                : "; switch on adb over the network, as for MConfig+");
        return s.toString();
    }

    /** An exception as "Name: message", or just "Name" when it has no message (EOFException). */
    static String describe(Throwable t) {
        String m = t.getMessage();
        String name = t.getClass().getSimpleName();
        return m == null || m.trim().isEmpty() ? name : name + ": " + m;
    }
}
