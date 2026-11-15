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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MenuModelTest {

    private static MenuModel.Tab tab(String id, int order, String... rows) {
        return new MenuModel.Tab(id, id, order, rows, null, "");
    }

    @Test
    public void downAndUp_stayWithinTheRows() {
        MenuModel m = new MenuModel(5);
        m.put(tab("routes", 10, "a", "b", "c"));
        m.move(-1);
        assertEquals(0, m.selected());
        m.move(1);
        m.move(1);
        m.move(1);
        assertEquals(2, m.selected());
    }

    @Test
    public void press_picksTheSelectedRowWithItsKey() {
        MenuModel m = new MenuModel(5);
        m.put(new MenuModel.Tab("gates", "Шлагбаумы", 20, new String[]{"Открыть: Дом", "Закрыть: Дом"},
                new String[]{"open:1", "close:1"}, ""));
        m.move(1);
        MenuModel.Pick p = m.press();
        assertEquals("gates", p.tab);
        assertEquals(1, p.index);
        assertEquals("close:1", p.key);
        assertEquals("Закрыть: Дом", p.row);
    }

    @Test
    public void press_onAnEmptyTab_picksNothing() {
        MenuModel m = new MenuModel(5);
        m.put(tab("routes", 10));
        assertNull(m.press());
    }

    @Test
    public void oneTab_hasNoStripToSelect() {
        MenuModel m = new MenuModel(5);
        m.put(tab("routes", 10, "a", "b"));
        m.move(-1);
        assertEquals(0, m.selected());
    }

    @Test
    public void twoTabs_upFromTheFirstRowSelectsTheStrip_andPressSwitchesTab() {
        MenuModel m = new MenuModel(5);
        m.put(tab("gates", 20, "g1"));
        m.put(tab("routes", 10, "r1", "r2"));
        m.reset();
        assertEquals("routes", m.current().id);
        m.move(-1);
        assertEquals(MenuModel.STRIP, m.selected());
        assertNull(m.press());
        assertEquals("gates", m.current().id);
        assertEquals(MenuModel.STRIP, m.selected());
        assertNull(m.press());
        assertEquals("routes", m.current().id);
        m.move(1);
        assertEquals(0, m.selected());
        assertEquals("r1", m.press().key);
    }

    @Test
    public void newRows_keepTheSelectedKey() {
        MenuModel m = new MenuModel(5);
        m.put(tab("routes", 10, "a", "b", "c"));
        m.move(1);
        m.move(1);
        m.put(tab("routes", 10, "c", "a", "b"));
        assertEquals(0, m.selected());
        assertEquals("c", m.press().key);
    }

    @Test
    public void newRows_withoutTheSelectedKey_goToTheFirst() {
        MenuModel m = new MenuModel(5);
        m.put(tab("routes", 10, "a", "b"));
        m.move(1);
        m.put(tab("routes", 10, "x", "y"));
        assertEquals(0, m.selected());
    }

    @Test
    public void anotherTabsRows_doNotMoveTheDriver() {
        MenuModel m = new MenuModel(5);
        m.put(tab("routes", 10, "a", "b"));
        m.move(1);
        m.put(tab("gates", 5, "g"));
        assertEquals("routes", m.current().id);
        assertEquals(1, m.selected());
    }

    @Test
    public void scrolling_keepsTheSelectionOnScreen() {
        MenuModel m = new MenuModel(3);
        m.put(tab("routes", 10, "a", "b", "c", "d", "e"));
        for (int n = 0; n < 4; n++) m.move(1);
        assertEquals(4, m.selected());
        assertEquals(2, m.first());
        m.move(-1);
        m.move(-1);
        m.move(-1);
        assertEquals(1, m.selected());
        assertEquals(1, m.first());
    }

    @Test
    public void removingTheShownTab_showsTheFirstOther() {
        MenuModel m = new MenuModel(5);
        m.put(tab("routes", 10, "a"));
        m.put(tab("gates", 20, "g"));
        m.open("gates");
        assertTrue(m.remove("gates"));
        assertEquals("routes", m.current().id);
        assertFalse(m.remove("gates"));
        assertTrue(m.remove("routes"));
        assertNull(m.current());
        assertNull(m.press());
    }

    @Test
    public void reset_showsTheFirstTabsFirstRow() {
        MenuModel m = new MenuModel(5);
        m.put(tab("routes", 10, "a", "b"));
        m.put(tab("gates", 20, "g"));
        m.open("gates");
        m.reset();
        assertEquals("routes", m.current().id);
        assertEquals(0, m.selected());
    }

    @Test
    public void keysOfTheWrongLength_fallBackToTheRows() {
        MenuModel.Tab t = new MenuModel.Tab("x", "x", 0, new String[]{"a", "b"}, new String[]{"k"}, null);
        assertEquals("b", t.keys[1]);
        assertEquals("", t.empty);
    }

    @Test
    public void jumpToNavigation_onlyForAMenuJustOpenedOnThePhone() {
        assertTrue(DimTabs.jumpToNavigation(DimTabs.CLOSED, DimTabs.PHONE, 1_000));
        assertTrue(DimTabs.jumpToNavigation(DimTabs.UNKNOWN, DimTabs.PHONE, 0));
        assertTrue(DimTabs.jumpToNavigation(DimTabs.OPEN_OTHER, DimTabs.PHONE, 0));
        assertTrue(DimTabs.jumpToNavigation(DimTabs.PHONE, DimTabs.PHONE, DimTabs.FRESH_MS + 1));
        assertFalse(DimTabs.jumpToNavigation(DimTabs.NAVIGATION, DimTabs.PHONE, 1_000));
        assertFalse(DimTabs.jumpToNavigation(DimTabs.MUSIC, DimTabs.PHONE, 1_000));
        assertFalse(DimTabs.jumpToNavigation(DimTabs.CLOSED, DimTabs.MUSIC, 1_000));
    }
}
