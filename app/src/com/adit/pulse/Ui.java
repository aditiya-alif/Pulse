// language: Java, file: Ui.java, target: Android 16 (API 36)
// Small view helpers: dp, and a rounded background in one call. Programmatic
// layouts are used for the panel so the whole overlay UI lives in one place and
// there is no layout XML to keep in sync with it.
package com.adit.pulse;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

final class Ui {
    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static GradientDrawable roundStroke(int color, float radiusPx, int strokeColor, float strokePx) {
        GradientDrawable d = round(color, radiusPx);
        d.setStroke(Math.max(1, Math.round(strokePx)), strokeColor);
        return d;
    }

    /** Set a view's padding on all sides in dp. */
    static void pad(View v, Context c, float l, float t, float r, float b) {
        v.setPadding(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    }
}
