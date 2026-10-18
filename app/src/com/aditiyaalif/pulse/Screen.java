// language: Java, file: Screen.java, target: Android 16 (API 36)
// The shared shell every screen is built from.
//
// Main and Detail had grown their own copies of card(), divider(), text() and the
// confirm dialog - the same shapes written twice, which is how two screens start
// to drift apart. They live here once. A screen subclasses this, calls scaffold()
// and adds its content; the helpers below are the whole visual vocabulary.
//
// Nothing here is a framework widget. Cards, buttons, switches and dialogs are
// plain views with drawn backgrounds, so the app has one design language rather
// than the platform's mixed in.
package com.aditiyaalif.pulse;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public abstract class Screen extends Activity {
    protected final Handler main = new Handler(Looper.getMainLooper());
    /** Root frame; dialogs and scrims attach here so they belong to the screen. */
    protected FrameLayout screen;
    /** The scrolling content column. */
    protected LinearLayout content;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Diag.init(this);
        goEdgeToEdge();
    }

    /**
     * Span the whole display, notch and gesture bar included.
     *
     * Without this the window stops at the bars, which on HyperOS leaves a coloured
     * strip top and bottom; the insets are then consumed as padding by scaffold(),
     * so nothing is drawn under the clock or the gesture pill.
     */
    protected void goEdgeToEdge() {
        android.view.Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams a = w.getAttributes();
            a.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? android.view.WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : android.view.WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(a);
        }
        w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
    }

    /**
     * Build the standard frame: dark background, a scrolling column, insets as
     * padding. Sets {@link #screen} and {@link #content} and returns the column so
     * the subclass can add to it.
     */
    protected LinearLayout scaffold() {
        screen = new FrameLayout(this);
        screen.setBackgroundColor(Theme.BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        // Scrolling stays; the bar is hidden. A visible scrollbar is a piece of the
        // platform's chrome, and this app draws its own - the content itself is the
        // only affordance it wants.
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        screen.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final float topBase = topPadding();
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            content.setPadding(Ui.dp(this, 16) + left, Ui.dp(this, topBase) + top,
                    Ui.dp(this, 16) + right, Ui.dp(this, 28) + bottom);
            return insets;
        });
        setContentView(screen);
        return content;
    }

    /** Home wants more headroom than a pushed screen under a back arrow. */
    protected float topPadding() { return 8f; }

    @Override protected void onResume() {
        super.onResume();
        // Screens that show live readings refresh on a timer; a screen that shows
        // nothing to refresh leaves this false and pays nothing.
        //
        // The first tick is DELAYED, not immediate. Each subclass already does its
        // own read in its onResume, so firing onTick now as well ran the whole
        // batched round trip twice, back to back, on every resume.
        if (autoRefreshMs > 0) {
            main.removeCallbacks(tick);
            main.postDelayed(tick, autoRefreshMs);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
        // Belt and braces: a placeholder left attached would keep animating its
        // sweep in the background for as long as the view tree lived. Removing it
        // on the way out means no screen can leak a spinner, whatever path it took.
        stopAllLoading();
    }

    /** Every host that has been given a placeholder, so onPause can clear them. */
    private final java.util.List<LinearLayout> loadingHosts = new java.util.ArrayList<>();

    private void stopAllLoading() {
        for (LinearLayout h : loadingHosts) hideLoading(h);
        loadingHosts.clear();
    }

    private long autoRefreshMs;

    /**
     * Refresh on a timer while this screen is in front.
     *
     * A module screen reads the device once when it opens; if that first read
     * landed before the link was up, the screen would sit on "--" until the user
     * left and came back. Re-reading on an interval means it fills itself in as
     * soon as the device answers, and keeps the numbers current while it is open.
     */
    protected void autoRefresh(long ms) {
        autoRefreshMs = ms;
        main.removeCallbacks(tick);
        if (ms > 0) main.postDelayed(tick, ms);
    }

    /** Subclasses override to do the actual re-read. */
    protected void onTick() { }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (autoRefreshMs <= 0) return;
            onTick();
            main.postDelayed(this, autoRefreshMs);
        }
    };

    // --------------------------------------------------------------- heading

    /** A back arrow in a rounded square, then the screen title. */
    protected void addBackBar(String title) {
        LinearLayout row = row();
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(this, 14);
        row.setLayoutParams(rlp);

        TextView back = text("‹", 24, Theme.TEXT, Typeface.NORMAL);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 20)));
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        back.setOnClickListener(v -> { Anim.bounce(v); finish(); });
        row.addView(back);

        if (title != null && !title.isEmpty()) {
            TextView t = text(title, 20, Theme.TEXT, Typeface.BOLD);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = Ui.dp(this, 12);
            row.addView(t, tlp);
        }
        content.addView(row);
    }

    /** A screen title and one line under it. */
    protected void addHeading(String title, String subtitle) {
        content.addView(text(title, 26, Theme.TEXT, Typeface.BOLD));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 13, Theme.MUTED, Typeface.NORMAL);
            LinearLayout.LayoutParams slp = wrap();
            slp.topMargin = Ui.dp(this, 2);
            slp.bottomMargin = Ui.dp(this, 20);
            content.addView(sub, slp);
        } else {
            View gap = new View(this);
            content.addView(gap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 16)));
        }
    }

    // ------------------------------------------------------------------ cards

    protected LinearLayout card(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.roundStroke(Theme.CARD, Ui.dp(this, 20),
                Theme.HAIRLINE, Ui.dp(this, 1)));
        Ui.pad(card, this, 16, 15, 16, 15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 14);
        card.setLayoutParams(lp);
        if (title != null) {
            TextView t = text(title, 13, Theme.MUTED, Typeface.BOLD);
            t.setLetterSpacing(0.08f);
            LinearLayout.LayoutParams tlp = wrap();
            tlp.bottomMargin = Ui.dp(this, 10);
            card.addView(t, tlp);
        }
        return card;
    }

    protected View divider() {
        View v = new View(this);
        v.setBackgroundColor(Theme.HAIRLINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
        lp.topMargin = Ui.dp(this, 13);
        lp.bottomMargin = Ui.dp(this, 13);
        v.setLayoutParams(lp);
        return v;
    }

    /** A small caps label that groups rows inside a card. */
    protected View groupLabel(String s) {
        TextView t = text(s, 11, Theme.MUTED, Typeface.BOLD);
        t.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = Ui.dp(this, 4);
        lp.bottomMargin = Ui.dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }

    // ------------------------------------------------------------------ text

    protected TextView text(String s, float size, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (style == Typeface.BOLD) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        }
        return t;
    }

    /**
     * A value over its label, sized to share a row with siblings.
     *
     * The label gets a top margin but the value gets NONE: a bottom margin on the
     * value pushes it up off whatever sits under it, which is what made a stat
     * value overlap a bar drawn below it.
     */
    protected LinearLayout stat(String label, String value) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(text(value, 17, Theme.TEXT, Typeface.BOLD));
        TextView l = text(label, 10, Theme.MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = Ui.dp(this, 3);
        c.addView(l, lp);
        return c;
    }

    /** A row of stats: pass label/value pairs. */
    protected LinearLayout statRow(String... pairs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            r.addView(stat(pairs[i], pairs[i + 1]));
        }
        return r;
    }

    // --------------------------------------------------------------- controls

    /**
     * Pin a button's width to its WIDEST possible label.
     *
     * A button whose text changes - "Pair" then "Pairing…" then back - resizes as
     * it goes, and in a weighted row the row then re-lays out and the whole screen
     * twitches. Worse, a weighted row gives every button the same width whatever it
     * says, so a longer label is simply cut off.
     *
     * Passing every label the button can ever show sets a minimum width that fits
     * the longest of them, so the button is never clipped and never resizes as its
     * text changes. Measure with the same typeface and padding the button uses, or
     * the numbers are meaningless.
     */
    protected void lockWidthToLongest(TextView button, String... labels) {
        if (button == null || labels == null || labels.length == 0) return;
        android.text.TextPaint p = button.getPaint();
        float widest = 0;
        for (String s : labels) {
            if (s == null) continue;
            widest = Math.max(widest, p.measureText(s));
        }
        int padding = button.getPaddingLeft() + button.getPaddingRight();
        button.setMinWidth((int) Math.ceil(widest) + padding);
        button.setMinimumWidth((int) Math.ceil(widest) + padding);
    }

    protected TextView button(String label, View.OnClickListener click) {
        TextView t = text(label, 13.5f, Theme.TEXT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 14)));
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        t.setOnClickListener(click);
        return t;
    }

    /** A filled pill button - the primary action on a screen. */
    protected TextView pill(String label, int bg, int fg, View.OnClickListener click) {
        TextView t = text(label, 13, fg, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        // A button label is one line: wrapping it would make the row taller than
        // its neighbours and break the row of pills into a ragged stack.
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setBackground(Ui.round(bg, Ui.dp(this, 16)));
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        t.setOnClickListener(click);
        return t;
    }

    /** An icon in a circle - the minimal navigation button. */
    protected View iconButton(String icon, View.OnClickListener click) {
        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 20)));
        box.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        Icon ic = new Icon(this, icon, Theme.TEXT_DIM);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                Ui.dp(this, 21), Ui.dp(this, 21));
        ilp.gravity = Gravity.CENTER;
        box.addView(ic, ilp);
        box.setOnClickListener(v -> { Anim.bounce(v); click.onClick(v); });
        box.setTag(ic);   // so a caller can tint the glyph (see MainActivity's power button)
        return box;
    }

    /** A horizontal, vertically centred row. */
    protected LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    protected LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    protected static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ------------------------------------------------------------------ modal

    /**
     * Ask before something irreversible.
     *
     * Built from this app's own views, not an AlertDialog: the platform dialog
     * wears the system theme - light, square, Material - a different language from
     * everything else here. The window is translucent with a dark scrim, so the
     * dialog is a card of the same shape and colour as every other card.
     */
    protected void confirm(String title, String body, String okLabel, int okColor,
                           Runnable onOk) {
        if (screen == null) return;

        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setOnClickListener(v -> { });   // swallow taps outside the card

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.roundStroke(Theme.CARD, Ui.dp(this, 24),
                Theme.HAIRLINE, Ui.dp(this, 1)));
        Ui.pad(card, this, 20, 20, 20, 18);

        card.addView(text(title, 17, Theme.TEXT, Typeface.BOLD));

        TextView b = text(body, 13, Theme.TEXT_DIM, Typeface.NORMAL);
        b.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        card.addView(b, blp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(this, 20);
        card.addView(actions, alp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        clp.leftMargin = Ui.dp(this, 28);
        clp.rightMargin = Ui.dp(this, 28);
        scrim.addView(card, clp);

        final boolean[] closed = {false};
        Runnable close = () -> {
            if (closed[0]) return;
            closed[0] = true;
            scrim.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                try { screen.removeView(scrim); } catch (Throwable ignored) { }
            }).start();
        };

        // Both buttons take their natural width rather than a forced half. A fixed
        // half cut off the longer label ("Uninstall", "Clear data") and made the two
        // buttons resize as the dialog text changed. A small minimum keeps a short
        // label like "Cancel" from looking cramped.
        TextView cancel = text("Cancel", 13.5f, Theme.TEXT, Typeface.BOLD);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 14)));
        cancel.setPadding(Ui.dp(this, 16), Ui.dp(this, 12),
                Ui.dp(this, 16), Ui.dp(this, 12));
        cancel.setMinWidth(Ui.dp(this, 92));
        cancel.setOnClickListener(v -> { Anim.bounce(v); close.run(); });
        actions.addView(cancel);

        TextView ok = text(okLabel, 13.5f, 0xFFFFFFFF, Typeface.BOLD);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(Ui.round(okColor, Ui.dp(this, 14)));
        ok.setPadding(Ui.dp(this, 16), Ui.dp(this, 12),
                Ui.dp(this, 16), Ui.dp(this, 12));
        ok.setMinWidth(Ui.dp(this, 92));
        ok.setOnClickListener(v -> {
            Anim.bounce(v);
            close.run();
            if (onOk != null) onOk.run();
        });
        LinearLayout.LayoutParams olp = wrap();
        olp.leftMargin = Ui.dp(this, 10);
        actions.addView(ok, olp);

        scrim.setAlpha(0f);
        screen.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.animate().alpha(1f).setDuration(160).start();
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate().scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(Anim.SETTLE).start();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Show a loading placeholder in a host until real content arrives.
     *
     * Call {@code showLoading(host)} before starting a read and
     * {@code hideLoading(host)} once it lands. The placeholder is removed by tag,
     * so it never disturbs the real rows added around it.
     */
    private static final String LOADING_TAG = "pulse-loading";

    protected void showLoading(LinearLayout host, Shimmer s) {
        if (host == null) return;
        hideLoading(host);
        s.setTag(LOADING_TAG);
        s.setAlpha(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 4);
        host.addView(s, lp);
        // Remember the host so onPause can clear it even if the load never lands.
        if (!loadingHosts.contains(host)) loadingHosts.add(host);
        s.animate().alpha(1f).setDuration(160).start();
    }

    /** A text-line placeholder - the common case, for lists. */
    protected void showLoading(LinearLayout host, int rows) {
        showLoading(host, Shimmer.lines(this, rows));
    }

    protected void hideLoading(LinearLayout host) {
        if (host == null) return;
        // Removed at once, not faded: the real content is added in the same layout
        // pass, so a fade here would leave the placeholder sitting above the new
        // rows and the list would jump up when it finally went. The incoming
        // content's own reveal is the visible transition.
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            View c = host.getChildAt(i);
            if (LOADING_TAG.equals(c.getTag())) host.removeViewAt(i);
        }
    }

    /**
     * Reveal a block of content that replaced a placeholder.
     *
     * A short fade plus a small rise, staggered by index, so a list arrives as a
     * sequence rather than a single flash. Pass the index to stagger; the effect is
     * capped so a long list does not take a second to settle.
     *
     * ONLY while {@link #firstLoad} is true. A refresh rebuilds the list with fresh
     * view objects, so a per-view guard would not survive - and without a per-screen
     * flag the whole list re-animated on every refresh tick, which is the flicker
     * that was reported. The transition is meant to mark the data ARRIVING, once.
     */
    protected boolean firstLoad = true;

    protected void reveal(View v, int index) {
        if (v == null || !firstLoad) return;
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(this, 10));
        long delay = Math.min(index, 6) * 30L;
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay)
                .setDuration(240)
                .setInterpolator(Anim.EASE)
                .start();
    }

    /** Call once the first real content has been shown; later refreshes do not animate. */
    protected void endFirstLoad() { firstLoad = false; }

    protected void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * A sheet of actions, rising from the bottom.
     *
     * Used wherever a tap has to offer several things to do with one item - an
     * app, a process, a command. A bottom sheet keeps the choice attached to the
     * thing tapped and leaves the list behind it visible, which a full screen
     * would not. Drawn from this app's own views, like the confirm dialog.
     */
    protected Sheet sheet(String title) {
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB3000000);
        final Sheet[] holder = new Sheet[1];
        scrim.setOnClickListener(v -> { if (holder[0] != null) holder[0].close(); });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.roundStroke(Theme.CARD, Ui.dp(this, 24),
                Theme.HAIRLINE, Ui.dp(this, 1)));
        Ui.pad(card, this, 18, 16, 18, 18);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 17, Theme.TEXT, Typeface.BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = text("✕", 15, Theme.MUTED, Typeface.NORMAL);
        x.setGravity(Gravity.CENTER);
        x.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 14)));
        x.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        head.addView(x);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.dp(this, 14);
        card.addView(head, hlp);

        // THE BODY SCROLLS, AND THE CARD HAS A CEILING.
        //
        // A sheet can carry a long list - every sensor on the device - and without a
        // cap the card would grow past the top of the screen and simply clip. The
        // ceiling is expressed on the card's own layout params, which is the one
        // place a fixed maximum survives measurement.
        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(body);
        card.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int screenH = getResources().getDisplayMetrics().heightPixels;
        int maxCard = (int) (screenH * 0.72f);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        clp.height = maxCard;   // a fixed ceiling; the body scrolls inside it
        scrim.addView(card, clp);

        Sheet s = new Sheet(scrim, body, x);
        holder[0] = s;

        scrim.setAlpha(0f);
        screen.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.animate().alpha(1f).setDuration(150).start();
        card.setTranslationY(Ui.dp(this, 40));
        card.animate().translationY(0).setDuration(260)
                .setInterpolator(Anim.EASE).start();
        return s;
    }

    /** The body of a {@link #sheet}: add actions, then it closes itself. */
    protected final class Sheet {
        private final FrameLayout scrim;
        private final LinearLayout body;
        private boolean closed;

        Sheet(FrameLayout scrim, LinearLayout body, View closeBtn) {
            this.scrim = scrim;
            this.body = body;
            closeBtn.setOnClickListener(v -> { Anim.bounce(v); close(); });
        }

        /**
         * Add any view to the sheet's body.
         *
         * For content that is not an action - a list of names, say. The body
         * scrolls, so a long list is fine.
         */
        public Sheet addView(View v) {
            body.addView(v);
            return this;
        }

        /** Add one action row. Runs after the sheet closes, so the UI settles first. */
        public Sheet action(String label, int color, Runnable run) {            TextView row = text(label, 14.5f, color, Typeface.NORMAL);
            row.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(Screen.this, 14)));
            Ui.pad(row, Screen.this, 14, 13, 14, 13);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(Screen.this, 8);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> {
                Anim.bounce(v);
                close();
                if (run != null) main.postDelayed(run, 120);
            });
            body.addView(row);
            return this;
        }

        /** A one-line explanation under the actions. */
        public Sheet note(String s) {
            TextView t = text(s, 11.5f, Theme.MUTED, Typeface.NORMAL);
            t.setLineSpacing(Ui.dp(Screen.this, 2), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(Screen.this, 4);
            t.setLayoutParams(lp);
            body.addView(t);
            return this;
        }

        public void close() {
            if (closed) return;
            closed = true;
            scrim.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                try { screen.removeView(scrim); } catch (Throwable ignored) { }
            }).start();
        }
    }

    protected void open(Class<?> target) {
        startActivity(new Intent(this, target));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /**
     * Open a device Settings screen by intent, over the shell.
     *
     * ON A WORKER THREAD: Shell.run opens a socket, and a socket on the main thread
     * throws NetworkOnMainThreadException. Every caller of this is a click handler,
     * so the work is moved off the UI thread here rather than at each call site -
     * which is how the same crash was written three times.
     */
    protected void openDeviceSettings(String action) {
        new Thread(() -> Shell.run("am start -a " + action), "pulse-open-settings").start();
    }

    protected static String fmtDur(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + " s";
        return (sec / 60) + " m " + (sec % 60) + " s";
    }
}
