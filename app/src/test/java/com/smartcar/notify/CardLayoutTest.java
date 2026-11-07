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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CardLayoutTest {

    @Test
    public void aShortListNeverScrolls() {
        assertEquals(0, CardLayout.firstVisible(3, 4, 5, 0));
    }

    @Test
    public void theWindowStaysWhileTheSelectionMovesInsideIt() {
        assertEquals(0, CardLayout.firstVisible(4, 10, 5, 0));
        assertEquals(3, CardLayout.firstVisible(5, 10, 5, 3));
    }

    @Test
    public void theWindowSlidesByTheLeastNeeded() {
        assertEquals(1, CardLayout.firstVisible(5, 10, 5, 0));
        assertEquals(2, CardLayout.firstVisible(2, 10, 5, 4));
    }

    @Test
    public void theWindowNeverRunsPastTheEnd() {
        assertEquals(5, CardLayout.firstVisible(9, 10, 5, 9));
        assertEquals(0, CardLayout.firstVisible(0, 10, 5, -3));
    }

    @Test
    public void withoutTheClusterTheHeadUnitShowsIt() {
        for (Card.Type t : Card.Type.values()) {
            assertTrue(CardLayout.needsHeadUnit(t, false, true, true));
        }
    }

    @Test
    public void theKeyLogAnswersEverythingOnTheCluster() {
        for (Card.Type t : Card.Type.values()) {
            assertFalse(CardLayout.needsHeadUnit(t, true, true, false));
        }
    }

    @Test
    public void heldMediaKeysAnswerAQuestionButCannotScrollAList() {
        assertFalse(CardLayout.needsHeadUnit(Card.Type.QUESTION, true, false, true));
        assertFalse(CardLayout.needsHeadUnit(Card.Type.INFO, true, false, true));
        assertTrue(CardLayout.needsHeadUnit(Card.Type.LIST, true, false, true));
    }

    @Test
    public void nothingToAnswerWithMeansTheHeadUnit() {
        assertTrue(CardLayout.needsHeadUnit(Card.Type.QUESTION, true, false, false));
    }
}
