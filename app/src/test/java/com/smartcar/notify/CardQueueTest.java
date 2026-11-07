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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CardQueueTest {

    private static Card card(String id) {
        return card(id, 0);
    }

    private static Card card(String id, long expiresAt) {
        return new Card(id, Card.Type.QUESTION, "t " + id, null, new String[]{"Yes", "No"}, 0,
                0, expiresAt, false, null);
    }

    @Test
    public void showsOneAtATimeInArrivalOrder() {
        CardQueue q = new CardQueue();
        Card a = card("a");
        Card b = card("b");
        q.offer(a);
        q.offer(b);
        List<Card> expired = new ArrayList<>();
        assertSame(a, q.advance(0, expired));
        // One is up: nothing else comes on screen until it is done with.
        assertNull(q.advance(0, expired));
        assertSame(a, q.finishCurrent());
        assertSame(b, q.advance(0, expired));
        assertSame(b, q.finishCurrent());
        assertTrue(q.isEmpty());
        assertTrue(expired.isEmpty());
    }

    @Test
    public void theSameIdUpdatesTheCardOnScreen() {
        CardQueue q = new CardQueue();
        q.offer(card("gate"));
        q.advance(0, new ArrayList<>());
        Card again = card("gate");
        assertEquals(CardQueue.Offer.UPDATED_CURRENT, q.offer(again));
        assertSame(again, q.current());
        assertEquals(0, q.waitingCount());
    }

    @Test
    public void theSameIdKeepsItsPlaceInTheLine() {
        CardQueue q = new CardQueue();
        q.offer(card("first"));
        q.offer(card("gate"));
        q.offer(card("last"));
        Card again = card("gate");
        assertEquals(CardQueue.Offer.REPLACED_WAITING, q.offer(again));
        List<Card> expired = new ArrayList<>();
        q.advance(0, expired);
        q.finishCurrent();
        assertSame(again, q.advance(0, expired));
    }

    @Test
    public void aCardWhoseMomentPassedIsSkippedAndReported() {
        CardQueue q = new CardQueue();
        Card stale = card("stale", 1_000);
        Card fresh = card("fresh", 0);
        q.offer(stale);
        q.offer(fresh);
        List<Card> expired = new ArrayList<>();
        assertSame(fresh, q.advance(1_000, expired));
        assertEquals(1, expired.size());
        assertSame(stale, expired.get(0));
    }

    @Test
    public void expiredWaitingCardsAreDroppedWhileAnotherIsUp() {
        CardQueue q = new CardQueue();
        q.offer(card("up"));
        q.advance(0, new ArrayList<>());
        q.offer(card("stale", 500));
        q.offer(card("fresh"));
        List<Card> dropped = q.dropExpired(600);
        assertEquals(1, dropped.size());
        assertEquals("stale", dropped.get(0).id);
        assertEquals(1, q.waitingCount());
    }

    @Test
    public void cancelFindsTheCardWhereverItIs() {
        CardQueue q = new CardQueue();
        q.offer(card("up"));
        q.offer(card("waiting"));
        q.advance(0, new ArrayList<>());
        assertEquals("waiting", q.cancel("waiting").id);
        assertEquals("up", q.cancel("up").id);
        assertNull(q.current());
        assertNull(q.cancel("nothing"));
        assertTrue(q.isEmpty());
    }

    @Test
    public void validation() {
        String[] two = {"Yes", "No"};
        assertNull(Card.invalid("x", Card.Type.QUESTION, "Open?", two));
        assertNull(Card.invalid("x", Card.Type.INFO, "Hello", null));
        assertNull(Card.invalid("x", Card.Type.LIST, "Pick", new String[]{"a"}));
        assertEquals("no id", Card.invalid(" ", Card.Type.INFO, "Hello", null));
        assertEquals("unknown type", Card.invalid("x", null, "Hello", null));
        assertEquals("no title", Card.invalid("x", Card.Type.INFO, "", null));
        assertEquals("an empty option", Card.invalid("x", Card.Type.LIST, "Pick", new String[]{"a", ""}));
        assertEquals("an information card takes no options",
                Card.invalid("x", Card.Type.INFO, "Hello", two));
        assertEquals("a question takes 1–4 answers",
                Card.invalid("x", Card.Type.QUESTION, "Open?", new String[]{"1", "2", "3", "4", "5"}));
        assertEquals("a list takes 1–50 rows", Card.invalid("x", Card.Type.LIST, "Pick", new String[0]));
    }

    @Test
    public void defaultsAndClamps() {
        Card info = new Card("i", Card.Type.INFO, "t", null, null, 0, 0, 0, false, null);
        assertEquals(Card.INFO_DEFAULT_MS, info.showForMs);
        Card q = new Card("q", Card.Type.QUESTION, "t", null, new String[]{"a", "b"}, 7,
                60 * 60_000L, 0, false, null);
        assertEquals(1, q.initial);
        assertEquals(Card.ANSWER_GIVE_UP_MS, q.showForMs);
        assertEquals(Card.Type.LIST, Card.Type.parse("list"));
        assertNull(Card.Type.parse("poll"));
    }
}
