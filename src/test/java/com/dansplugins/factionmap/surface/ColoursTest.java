package com.dansplugins.factionmap.surface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ColoursTest {
    @Test
    void hsbMatchesTheAwtFormula() {
        assertEquals("#ff0000", Colours.hsb(0f, 1f, 1f));
        assertEquals("#00ff00", Colours.hsb(1f / 3f, 1f, 1f));
        assertEquals("#808080", Colours.hsb(0.4f, 0f, 0.5f));
        int awt = java.awt.Color.HSBtoRGB(0.73f, 0.35f, 0.65f) & 0xffffff;
        assertEquals(String.format("#%06x", awt), Colours.hsb(0.73f, 0.35f, 0.65f));
    }

    @Test
    void hueShiftKeepsSaturationAndBrightness() {
        String shifted = Colours.hueShift("#ff0000", 120);
        assertEquals("#00ff00", shifted);
        assertNotEquals("#2f6fb0", Colours.hueShift("#2f6fb0", 47));
    }

    @Test
    void distanceIsEuclideanInRgb() {
        assertEquals(0, Colours.distance("#123456", "#123456"));
        assertEquals(255, Colours.distance("#000000", "#ff0000"));
    }
}
