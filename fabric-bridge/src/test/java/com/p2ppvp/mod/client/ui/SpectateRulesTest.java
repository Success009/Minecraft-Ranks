package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.p2ppvp.mod.client.social.SocialModels.KitRef;
import com.p2ppvp.mod.client.social.SocialModels.LiveMatch;
import org.junit.jupiter.api.Test;

class SpectateRulesTest {
    private static LiveMatch m(int n, boolean full) {
        return new LiveMatch("m1", "Alice", "Bob", new KitRef("official", "Crystal"), n, 5, full);
    }

    @Test
    void noSelectionDisablesButton() {
        assertFalse(SpectateRules.buttonFor(null, "Me", false).enabled());
    }

    @Test
    void openMatchIsJoinable() {
        SpectateRules.ButtonState s = SpectateRules.buttonFor(m(2, false), "Me", false);
        assertTrue(s.enabled());
        assertEquals("Spectate", s.label());
    }

    @Test
    void fullMatchIsDisabledByFlagOrCount() {
        assertEquals("Full", SpectateRules.buttonFor(m(5, true), "Me", false).label());
        assertFalse(SpectateRules.buttonFor(m(5, false), "Me", false).enabled());
    }

    @Test
    void participantsCannotSpectateOwnMatch() {
        assertFalse(SpectateRules.buttonFor(m(0, false), "Alice", false).enabled());
        assertFalse(SpectateRules.buttonFor(m(0, false), "Bob", false).enabled());
    }

    @Test
    void activeSessionDisablesButton() {
        SpectateRules.ButtonState s = SpectateRules.buttonFor(m(0, false), "Me", true);
        assertFalse(s.enabled());
        assertEquals("Joining...", s.label());
    }

    @Test
    void countLabelFormat() {
        assertEquals("3/5", SpectateRules.countLabel(m(3, false)));
    }
}
