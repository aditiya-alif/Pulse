// language: Java, file: Legend.java, target: Android 16 (API 36)
// A colour legend: a dot and a name per series.
//
// A gauge shows a value but not what it is, so every ring and bar carries its own
// key. It wraps onto as many rows as it needs, because the number of entries
// changes with what the device exposes.
package com.adit.pulse;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Legend {
    private Legend() { }

    /**
     * A wrapping row of dot+name entries.
     *
     * @param colors one per series
     * @param names  one per series, parallel to {@code colors}
     * @param maxPerRow how many entries before wrapping to a new row
     */
    static LinearLayout build(Context c, int maxPerRow, int[] colors, String[] names) {
        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = newRow(c);
        int inRow = 0;
        for (int i = 0; i < colors.length; i++) {
            if (inRow == maxPerRow) {
                outer.addView(row);
                row = newRow(c);
                inRow = 0;
            }
            row.addView(chip(c, colors[i], i < names.length ? names[i] : null));
            inRow++;
        }
        if (inRow > 0) outer.addView(row);
        return outer;
    }

    private static LinearLayout newRow(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    /** One legend entry. Name may be null for a colour-only key. */
    static View chip(Context c, int color, String name) {
        LinearLayout e = new LinearLayout(c);
        e.setOrientation(LinearLayout.HORIZONTAL);
        e.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.rightMargin = Ui.dp(c, 14);
        e.setLayoutParams(elp);

        View dot = new View(c);
        dot.setBackground(Ui.round(color, Ui.dp(c, 4)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(c, 8), Ui.dp(c, 8));
        dlp.rightMargin = Ui.dp(c, 6);
        e.addView(dot, dlp);

        if (name != null) {
            TextView t = new TextView(c);
            t.setText(name);
            t.setTextSize(11);
            t.setTextColor(Theme.TEXT_DIM);
            t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            e.addView(t);
        }
        return e;
    }
}
