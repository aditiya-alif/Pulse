// language: Java, file: RecordingsActivity.java, target: Android 16 (API 36)
// The saved captures.
//
// Lifted out of the home screen: the brief is that home is a welcome, and a list
// that grows without limit is not part of a welcome. Here it can be the whole
// screen and the empty state can explain itself.
package com.aditiyaalif.pulse;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class RecordingsActivity extends Screen {

    private LinearLayout host;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = scaffold();
        addBackBar("Recordings");
        addHeading("Recordings", "Each capture of one app, saved when you stopped it.");

        host = column();
        col.addView(host);
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    /** Loading runs off the main thread: each capture is a JSON file to parse. */
    private void load() {
        if (firstLoad) showLoading(host, 3);
        new Thread(() -> {
            List<Recording> recs = new Store(this).list();
            main.post(() -> render(recs));
        }, "pulse-history").start();
    }

    private void render(List<Recording> recs) {
        host.removeAllViews();
        hideLoading(host);
        if (recs.isEmpty()) {
            LinearLayout empty = card("Nothing yet");
            empty.addView(text("No recordings yet. Start the overlay, open an app, "
                    + "then tap the bar to begin recording.", 12.5f, Theme.MUTED,
                    Typeface.NORMAL));
            reveal(empty, 0);
            host.addView(empty);
            endFirstLoad();
            return;
        }
        for (int i = 0; i < recs.size(); i++) {
            View row = recRow(recs.get(i));
            reveal(row, i);
            host.addView(row);
        }
        endFirstLoad();
    }

    private View recRow(Recording r) {
        LinearLayout card = card(null);
        LinearLayout row = row();
        row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));

        AppIcon.Info info = AppIcon.get(this, r.pkg);
        ImageView icon = new ImageView(this);
        if (info.icon != null) {
            icon.setImageDrawable(info.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            icon.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 8)));
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(this, 40), Ui.dp(this, 40));
        ilp.rightMargin = Ui.dp(this, 12);
        row.addView(icon, ilp);

        LinearLayout mid = column();
        mid.addView(text(info.label.isEmpty() ? r.pkg : info.label, 15, Theme.TEXT, Typeface.BOLD));
        SimpleDateFormat f = new SimpleDateFormat("d MMM yyyy · HH:mm", Locale.US);
        String meta = f.format(new java.util.Date(r.startedAt)) + " · " + fmtDur(r.durationMs());
        if (!Float.isNaN(r.fpsAvg)) meta += " · " + Math.round(r.fpsAvg) + " fps";
        mid.addView(text(meta, 11.5f, Theme.MUTED, Typeface.NORMAL));
        row.addView(mid, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView del = text("✕", 14, Theme.MUTED, Typeface.NORMAL);
        del.setGravity(Gravity.CENTER);
        del.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 14)));
        del.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        del.setOnClickListener(v -> {
            v.setClickable(false);
            confirmDelete(r, () -> v.setClickable(true));
        });
        row.addView(del);

        row.setOnClickListener(v -> {
            Anim.bounce(v);
            android.content.Intent i = new android.content.Intent(this, DetailActivity.class);
            i.putExtra(DetailActivity.EXTRA_ID, r.id);
            startActivity(i);
        });
        card.addView(row);
        return card;
    }

    private void confirmDelete(Recording r, Runnable onCancel) {
        AppIcon.Info info = AppIcon.get(this, r.pkg);
        String name = info.label.isEmpty() ? r.pkg : info.label;
        SimpleDateFormat f = new SimpleDateFormat("d MMM yyyy · HH:mm", Locale.US);
        String when = f.format(new java.util.Date(r.startedAt));
        confirm("Delete recording?",
                "The recording of " + name + " on " + when
                        + " will be deleted permanently. This cannot be undone.",
                "Delete", Theme.CRITICAL, () -> {
                    new Store(this).delete(r.id);
                    load();
                    toast("Recording deleted");
                });
    }
}
