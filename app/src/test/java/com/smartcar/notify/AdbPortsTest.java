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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.smartcar.notify.AdbPorts.Failure;

import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

public class AdbPortsTest {
    private static final int[] PORTS = {5555, 5556, 5557, 5037};

    @Test
    public void groupsPortsByReason() {
        // The emulator: 5555–5557 refuse, the adb server on 5037 closes on CNXN.
        assertEquals("no adb on 127.0.0.1 (5555/5556/5557 refused, 5037 not adbd);"
                        + " switch on adb over the network, as for MConfig+",
                AdbPorts.message(PORTS, new Failure[]{
                        Failure.REFUSED, Failure.REFUSED, Failure.REFUSED, Failure.NOT_ADBD}));
    }

    @Test
    public void reasonsInPortOrder() {
        assertEquals("no adb on 127.0.0.1 (5555/5037 no answer, 5556/5557 refused);"
                        + " switch on adb over the network, as for MConfig+",
                AdbPorts.message(PORTS, new Failure[]{
                        Failure.NO_ANSWER, Failure.REFUSED, Failure.REFUSED, Failure.NO_ANSWER}));
    }

    @Test
    public void notAllowedAsksToAllowDebugging() {
        assertEquals("no adb on 127.0.0.1 (5555 debugging not allowed, 5556/5557/5037 refused);"
                        + " allow debugging for this app on the screen, with \"always\"",
                AdbPorts.message(PORTS, new Failure[]{
                        Failure.NOT_ALLOWED, Failure.REFUSED, Failure.REFUSED, Failure.REFUSED}));
    }

    @Test
    public void classifiesFailures() {
        assertEquals(Failure.REFUSED, AdbPorts.ofConnect(new ConnectException("refused")));
        assertEquals(Failure.NO_ANSWER, AdbPorts.ofConnect(new SocketTimeoutException()));
        assertEquals(Failure.NO_ANSWER, AdbPorts.ofConnect(new SocketException()));
        assertEquals(Failure.NOT_ADBD, AdbPorts.ofHandshake(false));
        assertEquals(Failure.NOT_ALLOWED, AdbPorts.ofHandshake(true));
    }

    @Test
    public void describeWithoutMessageHasNoNull() {
        assertEquals("EOFException", AdbPorts.describe(new EOFException()));
        assertEquals("SocketException", AdbPorts.describe(new SocketException()));
        assertEquals("IOException: broken", AdbPorts.describe(new IOException("broken")));
    }

    @Test
    public void rootCauseWithoutMessageHasNoNull() {
        String s = WheelKeys.rootCause(new IOException("adb", new EOFException()));
        assertEquals("EOFException", s);
        assertFalse(s.contains("null"));
    }
}
