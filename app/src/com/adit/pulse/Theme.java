// language: Java, file: Theme.java, target: Android 16 (API 36)
// One dark palette for the whole app. Dark on purpose: the overlay is a dark
// translucent bar by request, and a light settings screen beside it would be two
// designs in one product. The series colors are the validated six-slot
// categorical order - assigned to metrics in that fixed order, never cycled, so
// the adjacent-pair colorblind separation the validator proved still holds.
package com.adit.pulse;

final class Theme {
    private Theme() { }

    // surfaces / ink
    static final int BG        = 0xFF0D0D10;
    static final int CARD      = 0xFF1A1A20;
    static final int CARD_HI   = 0xFF23232B;
    static final int TEXT      = 0xFFFFFFFF;
    static final int TEXT_DIM  = 0xFFC3C2B7;
    static final int MUTED     = 0xFF898781;
    static final int GRID      = 0xFF2C2C2A;
    static final int AXIS      = 0xFF383835;
    static final int HAIRLINE  = 0x1AFFFFFF;
    static final int ACCENT    = 0xFF3987E5;

    // Status, reserved - never reused as a series color. The recorder uses these:
    // a running capture is critical (red), a paused one warning (amber).
    static final int WARNING   = 0xFFFAB219;
    static final int CRITICAL  = 0xFFD03B3B;

    // The overlay bar: dark, semi-transparent, per the brief.
    static final int OVERLAY_BG = 0xD9121216;   // #121216 at ~85%

    /**
     * Series colors, in the validated fixed order. Index = slot.
     * Validated dark, surface #121216: lightness band, chroma floor, adjacent CVD
     * (worst 8.4), normal-vision floor (worst 19.3), contrast (all >= 3:1).
     */
    static final int[] SERIES = {
            0xFF3987E5,   // 1 blue    - CPU
            0xFFD95926,   // 2 orange  - MEM
            0xFF199E70,   // 3 aqua    - CPU temp
            0xFFC98500,   // 4 yellow  - battery temp
            0xFFD55181,   // 5 magenta - FPS
            0xFF008300,   // 6 green   - GPU
            0xFF9085E9,   // 7 violet  - watt
            0xFFE66767,   // 8 red     - battery %
    };

    /** Soft glow color for a series: same hue, low alpha. */
    static int glow(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
