package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.p2ppvp.mod.client.ui.LeaderboardRules.Row;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeaderboardRulesTest {
    private static final List<Row> ROWS = List.of(
        new Row("Alpha", 300, 10, 2),
        new Row("Bravo", 200, 5, 5),
        new Row("Charlie", 150, 3, 7),
        new Row("alphonse", 120, 1, 1),
        new Row("Delta", 100, 0, 4)
    );

    @Test
    void filterMatchesCaseInsensitiveSubstring() {
        List<Row> out = LeaderboardRules.filter(ROWS, "ALPH");
        assertEquals(2, out.size());
        assertEquals("Alpha", out.get(0).name());
        assertEquals("alphonse", out.get(1).name());
    }

    @Test
    void blankOrNullQueryReturnsAll() {
        assertEquals(5, LeaderboardRules.filter(ROWS, "").size());
        assertEquals(5, LeaderboardRules.filter(ROWS, "   ").size());
        assertEquals(5, LeaderboardRules.filter(ROWS, null).size());
    }

    @Test
    void nullRowsReturnsEmpty() {
        assertTrue(LeaderboardRules.filter(null, "a").isEmpty());
    }

    @Test
    void pageCountIsAtLeastOne() {
        assertEquals(1, LeaderboardRules.pageCount(0, 10));
        assertEquals(1, LeaderboardRules.pageCount(10, 10));
        assertEquals(2, LeaderboardRules.pageCount(11, 10));
        assertEquals(1, LeaderboardRules.pageCount(5, 0));
    }

    @Test
    void clampPageStaysInRange() {
        assertEquals(0, LeaderboardRules.clampPage(-3, 25, 10));
        assertEquals(2, LeaderboardRules.clampPage(9, 25, 10));
        assertEquals(1, LeaderboardRules.clampPage(1, 25, 10));
    }

    @Test
    void pageRangeSlicesCorrectly() {
        assertArrayEquals(new int[] {0, 10}, LeaderboardRules.pageRange(0, 25, 10));
        assertArrayEquals(new int[] {20, 25}, LeaderboardRules.pageRange(2, 25, 10));
        assertArrayEquals(new int[] {20, 25}, LeaderboardRules.pageRange(99, 25, 10));
        assertArrayEquals(new int[] {0, 0}, LeaderboardRules.pageRange(0, 0, 10));
    }

    @Test
    void rankLabelIsPlainAscii() {
        assertEquals("#1", LeaderboardRules.rankLabel(0));
        assertEquals("#12", LeaderboardRules.rankLabel(11));
    }

    @Test
    void rankColorsForTopThree() {
        assertEquals(0xFFFFD700, LeaderboardRules.rankColor(0));
        assertEquals(0xFFC0C0C0, LeaderboardRules.rankColor(1));
        assertEquals(0xFFCD7F32, LeaderboardRules.rankColor(2));
        assertEquals(0xFF888888, LeaderboardRules.rankColor(3));
    }

    @Test
    void indexOfPlayerIgnoresCase() {
        assertEquals(1, LeaderboardRules.indexOfPlayer(ROWS, "bravo"));
        assertEquals(-1, LeaderboardRules.indexOfPlayer(ROWS, "nobody"));
        assertEquals(-1, LeaderboardRules.indexOfPlayer(ROWS, ""));
        assertEquals(-1, LeaderboardRules.indexOfPlayer(ROWS, null));
        assertEquals(-1, LeaderboardRules.indexOfPlayer(null, "Alpha"));
    }

    @Test
    void pinSelfOnlyWhenOffPage() {
        assertFalse(LeaderboardRules.shouldPinSelf(-1, 0, 25, 10));
        assertFalse(LeaderboardRules.shouldPinSelf(3, 0, 25, 10));
        assertTrue(LeaderboardRules.shouldPinSelf(15, 0, 25, 10));
        assertFalse(LeaderboardRules.shouldPinSelf(15, 1, 25, 10));
    }

    @Test
    void shortNameTruncatesWithEllipsis() {
        assertEquals("Alpha", LeaderboardRules.shortName("Alpha", 10));
        assertEquals("Abcdefg...", LeaderboardRules.shortName("Abcdefghijklmnop", 10));
        assertEquals("", LeaderboardRules.shortName(null, 10));
    }

    @Test
    void matchesIsWinsPlusLosses() {
        assertEquals(12, ROWS.get(0).matches());
    }
}
