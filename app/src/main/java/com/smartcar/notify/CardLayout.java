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
 * The decisions about a card's layout that are worth a test: which rows of a list are in view, and
 * whether the head unit has to show the card as well as the cluster.
 */
final class CardLayout {

    /**
     * Rows of a list in view at once on the cluster. The visible panel ends at row 720 and the
     * card starts at 288; five rows plus the title fit with room to spare for the arrows.
     */
    static final int VISIBLE_ROWS = 5;

    private CardLayout() {
    }

    /**
     * The first row in view so that {@code selected} is: the window stays put while the selection
     * moves inside it and slides by the least needed when it leaves — a list that recentres on
     * every click of the roller cannot be read while scrolling.
     */
    static int firstVisible(int selected, int count, int visible, int previousFirst) {
        if (count <= visible) {
            return 0;
        }
        int first = previousFirst;
        if (selected < first) {
            first = selected;
        } else if (selected >= first + visible) {
            first = selected - visible + 1;
        }
        return Math.max(0, Math.min(first, count - visible));
    }

    /**
     * Whether the head unit must show the card too. The driver wants the cluster alone, but the
     * cluster has no touch: hidden from the head unit, a card nobody can answer would sit there
     * until it gives up — which happened in the car the day the key log did not connect.
     *
     * @param onCluster    the cluster copy is up
     * @param keyLog       the wheel's key log is being followed (⏮ ⏭ ⏯ and the volume roller)
     * @param mediaHeld    the media keys are held (⏮ ⏭ ⏯ reach the service; the roller does not)
     */
    static boolean needsHeadUnit(Card.Type type, boolean onCluster, boolean keyLog, boolean mediaHeld) {
        if (!onCluster) {
            return true;
        }
        if (keyLog) {
            return false;
        }
        // Without the log a list has no scroll, so only a touch can pick a row other than the first.
        return type == Card.Type.LIST || !mediaHeld;
    }
}
