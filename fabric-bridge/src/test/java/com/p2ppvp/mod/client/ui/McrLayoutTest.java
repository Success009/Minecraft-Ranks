package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.p2ppvp.mod.client.ui.McrLayout.Rect;
import org.junit.jupiter.api.Test;

class McrLayoutTest {
    private static final int[][] SIZES = {{854, 480}, {640, 360}, {480, 270}, {427, 240}, {200, 120}, {150, 90}};

    @Test
    void cardStaysOnScreenAndCenteredAtAllSizes() {
        for (int[] s : SIZES) {
            Rect c = McrLayout.card(s[0], s[1], 320, 220, 8);
            assertTrue(c.w() > 0 && c.h() > 0, "positive size at " + s[0] + "x" + s[1]);
            assertTrue(c.inside(s[0], s[1]), "inside at " + s[0] + "x" + s[1]);
            assertEquals((s[0] - c.w()) / 2, c.x());
            assertEquals((s[1] - c.h()) / 2, c.y());
        }
    }

    @Test
    void cardKeepsPreferredSizeWhenThereIsRoom() {
        Rect c = McrLayout.card(854, 480, 320, 220, 8);
        assertEquals(320, c.w());
        assertEquals(220, c.h());
    }

    @Test
    void cardShrinksToScreenMinusMargin() {
        Rect c = McrLayout.card(300, 200, 320, 220, 10);
        assertEquals(280, c.w());
        assertEquals(180, c.h());
    }

    @Test
    void rowsNeverOverlapOrLeaveTheArea() {
        for (int[] s : SIZES) {
            Rect card = McrLayout.card(s[0], s[1], 250, 220, 6);
            for (int n = 1; n <= 6; n++) {
                Rect[] rows = McrLayout.rows(card, n, 20, 4);
                assertEquals(n, rows.length);
                for (int i = 0; i < n; i++) {
                    assertTrue(rows[i].h() >= 1);
                    assertTrue(rows[i].bottom() <= card.bottom(), "row " + i + " of " + n + " at " + s[0] + "x" + s[1]);
                    if (i > 0) assertTrue(rows[i].y() >= rows[i - 1].bottom(), "no overlap");
                }
            }
        }
    }

    @Test
    void rowHeightIsCappedAndZeroRowsIsEmpty() {
        Rect area = new Rect(0, 0, 100, 400);
        Rect[] rows = McrLayout.rows(area, 3, 20, 4);
        for (Rect r : rows) assertEquals(20, r.h());
        assertEquals(0, McrLayout.rows(area, 0, 20, 4).length);
    }

    @Test
    void rowsInATinyAreaStillHavePositiveHeight() {
        Rect[] rows = McrLayout.rows(new Rect(0, 0, 50, 5), 4, 20, 4);
        assertEquals(4, rows.length);
        for (Rect r : rows) assertTrue(r.h() >= 1);
    }

    @Test
    void splitRowCoversTheWholeWidthWithoutOverlap() {
        Rect row = new Rect(10, 20, 101, 20);
        Rect[] two = McrLayout.splitRow(row, 6);
        assertEquals(row.x(), two[0].x());
        assertEquals(row.right(), two[1].right());
        assertTrue(two[0].right() + 6 <= two[1].x() + 0);
        assertEquals(row.w(), two[0].w() + 6 + two[1].w());
    }

    @Test
    void splitRowWithHugeGapStillValid() {
        Rect[] two = McrLayout.splitRow(new Rect(0, 0, 10, 10), 100);
        assertTrue(two[0].w() >= 1 && two[1].w() >= 1);
        assertTrue(two[1].right() <= 10);
    }

    @Test
    void listAreaKeepsPositiveHeightAndStaysInsideCard() {
        for (int[] s : SIZES) {
            Rect card = McrLayout.card(s[0], s[1], 320, 220, 6);
            Rect list = McrLayout.listArea(card, 30, 28, 6, 20);
            assertTrue(list.h() >= 1 && list.w() >= 1, "positive at " + s[0] + "x" + s[1]);
            assertTrue(list.x() >= card.x() && list.right() <= card.right());
            assertTrue(list.y() >= card.y() && list.bottom() <= card.bottom(), "inside card at " + s[0] + "x" + s[1]);
        }
    }

    @Test
    void listAreaUsesFullHeaderAndFooterWhenRoomy() {
        Rect card = new Rect(0, 0, 300, 220);
        Rect list = McrLayout.listArea(card, 30, 28, 6, 20);
        assertEquals(6 + 30, list.y());
        assertEquals(220 - 12 - 30 - 28, list.h());
    }

    @Test
    void visibleRowsIsAtLeastOneAndFitsTheList() {
        assertEquals(1, McrLayout.visibleRows(5, 20, 2));
        assertEquals(4, McrLayout.visibleRows(90, 20, 2));
        assertEquals(1, McrLayout.visibleRows(0, 20, 2));
        // Degenerate rowH=0 and gap=0: the step is clamped to 1, so 10px holds 10 rows.
        assertEquals(10, McrLayout.visibleRows(10, 0, 0));
    }

    @Test
    void clampScrollStaysInRange() {
        assertEquals(0, McrLayout.clampScroll(-5, 10, 4));
        assertEquals(6, McrLayout.clampScroll(99, 10, 4));
        assertEquals(0, McrLayout.clampScroll(3, 2, 4));
        assertEquals(3, McrLayout.clampScroll(3, 10, 4));
    }

    @Test
    void bottomRightToastIsFullyOnScreenAtAllSizes() {
        for (int[] s : SIZES) {
            Rect t = McrLayout.bottomRight(s[0], s[1], 180, 56, 8);
            assertTrue(t.w() >= 1 && t.h() >= 1);
            assertTrue(t.inside(s[0], s[1]), "toast inside at " + s[0] + "x" + s[1]);
        }
        Rect t = McrLayout.bottomRight(854, 480, 180, 56, 8);
        assertEquals(854 - 180 - 8, t.x());
        assertEquals(480 - 56 - 8, t.y());
    }

    @Test
    void rectContainsIsHalfOpen() {
        Rect r = new Rect(10, 10, 5, 5);
        assertTrue(r.contains(10, 10));
        assertTrue(r.contains(14, 14));
        assertFalse(r.contains(15, 14));
        assertFalse(r.contains(9, 10));
    }

    @Test
    void tinyDetection() {
        assertTrue(McrLayout.isTiny(199, 300));
        assertTrue(McrLayout.isTiny(300, 119));
        assertFalse(McrLayout.isTiny(200, 120));
    }
}
