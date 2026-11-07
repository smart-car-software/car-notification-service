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
import java.util.Iterator;
import java.util.List;

/**
 * The single line every card waits in: one on screen, the rest in arrival order. This is the
 * whole point of the service — two apps asking at once get two cards one after another, never two
 * overlays fighting over the same panel and the same wheel.
 *
 * <p>Ids are the client's own and identify a request across sends: sending the same id again
 * updates that request where it stands (on screen, or at its place in the line) instead of asking
 * twice — what a client re-detecting the same gate every second needs.
 */
final class CardQueue {

    enum Offer {
        /** The card on screen was this id: it now shows the new content. */
        UPDATED_CURRENT,
        /** A waiting card was this id: replaced at its place in the line. */
        REPLACED_WAITING,
        /** New: at the end of the line (or straight on screen, once {@link #advance} runs). */
        QUEUED
    }

    private Card current;
    private final List<Card> waiting = new ArrayList<>();

    Offer offer(Card card) {
        if (current != null && current.id.equals(card.id)) {
            current = card;
            return Offer.UPDATED_CURRENT;
        }
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).id.equals(card.id)) {
                waiting.set(i, card);
                return Offer.REPLACED_WAITING;
            }
        }
        waiting.add(card);
        return Offer.QUEUED;
    }

    Card current() {
        return current;
    }

    /**
     * Puts the next card on screen when there is none, skipping the ones whose moment has passed
     * (a gate the car drove past while a list was being read). Those go into {@code expired} so
     * their clients still hear back.
     *
     * @return the card now on screen, or null when the line is empty or one is already up
     */
    Card advance(long nowMs, List<Card> expired) {
        if (current != null) {
            return null;
        }
        while (!waiting.isEmpty()) {
            Card next = waiting.remove(0);
            if (next.expired(nowMs)) {
                expired.add(next);
                continue;
            }
            current = next;
            return next;
        }
        return null;
    }

    /** The card on screen is done with (answered, dismissed, timed out). */
    Card finishCurrent() {
        Card c = current;
        current = null;
        return c;
    }

    /** Takes the id out wherever it is; null when it is not there. */
    Card cancel(String id) {
        if (current != null && current.id.equals(id)) {
            return finishCurrent();
        }
        for (Iterator<Card> it = waiting.iterator(); it.hasNext(); ) {
            Card c = it.next();
            if (c.id.equals(id)) {
                it.remove();
                return c;
            }
        }
        return null;
    }

    /** Waiting cards past their deadline, taken out of the line. */
    List<Card> dropExpired(long nowMs) {
        List<Card> out = new ArrayList<>();
        for (Iterator<Card> it = waiting.iterator(); it.hasNext(); ) {
            Card c = it.next();
            if (c.expired(nowMs)) {
                it.remove();
                out.add(c);
            }
        }
        return out;
    }

    int waitingCount() {
        return waiting.size();
    }

    boolean isEmpty() {
        return current == null && waiting.isEmpty();
    }
}
