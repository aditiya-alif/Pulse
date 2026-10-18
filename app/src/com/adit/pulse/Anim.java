// language: Java, file: Anim.java, target: Android 16 (API 36)
// Spring-ish motion, one place. The brief asks for roundy, bouncy, fluid; that
// is a settle with a slight overshoot and a fast, short fade - not a linear
// tween. Kept as plain interpolators so every view animates the same way.
package com.adit.pulse;

import android.animation.TimeInterpolator;
import android.view.View;
import android.view.animation.OvershootInterpolator;

final class Anim {
    /** Settle with a small overshoot - the "bouncy" of a card arriving. */
    static final TimeInterpolator SETTLE = new OvershootInterpolator(0.9f);

    /** Decelerating ease for movement that should not overshoot. */
    static final TimeInterpolator EASE = t -> 1f - (float) Math.pow(1f - t, 3);

    private Anim() { }

    /** A press response: dip and spring back. */
    static void bounce(View v) {
        v.animate().cancel();
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
                .withEndAction(() -> v.animate()
                        .scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(SETTLE).start())
                .start();
    }
}
