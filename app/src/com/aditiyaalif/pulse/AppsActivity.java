// language: Java, file: AppsActivity.java, target: Android 16 (API 36)
// App control: freeze, suspend, disable, stop, clean, uninstall.
//
// The list is third-party apps only. System packages are not just risky to touch,
// they are mostly meaningless to a user here - what someone wants to shut up is
// the app they installed. Tapping an app opens a sheet of actions for it.
//
// Every destructive action asks first, and the two PERSISTENT modes (suspend,
// disable) say plainly that they outlive Pulse, because those are the ones a user
// can forget about.
package com.aditiyaalif.pulse;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AppsActivity extends Screen {
    private Prefs prefs;
    private LinearLayout listHost;
    private TextView heldBanner;
    private LinearLayout autoReleaseRow;
    private TextView setBanner, freezeSetBtn;
    private String query = "";

    /**
     * Selection mode: one button turns the list into a picker.
     *
     * The alternative - an "Add to set" entry in each app's sheet - means opening a
     * sheet, choosing, and closing it once per app, which is tedious for the common
     * case of picking several at once. In select mode a tap toggles the app instead
     * of opening its sheet, exactly like a multi-select in a file manager, and one
     * button adds everything chosen.
     */
    private boolean selecting;
    private final java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();
    /** The list's canonical order (by label), so reordering is always stable. */
    private final List<String> order = new ArrayList<>();
    private LinearLayout selectCard;
    private TextView selectCount;
    private TextView selectBtn;
    /** Shown only while the selection is non-empty. */
    private TextView clearAllBtn;

    /** The auto-release windows offered, in hours. 0 means never. */
    private static final int[] RELEASE_HOURS = { 0, 2, 8, 24 };
    private static final String[] RELEASE_LABELS = { "Never", "2h", "8h", "24h" };

    /**
     * Marks the trailing control of a row, and carries its package.
     *
     * A dedicated holder rather than a plain string tag: a string tag would be
     * indistinguishable from any other string a view might carry, and the row's
     * package has to travel with the control so it can be rebuilt in place.
     */
    private static final class Trailing {
        final String pkg;
        Trailing(String pkg) { this.pkg = pkg; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        LinearLayout col = scaffold();
        addBackBar("Apps");
        addHeading("App control", "Hold apps back, or clear them out.");

        // ---- ONE freeze card. There is no separate per-app freeze: the set IS the
        // list, one button starts it, one button stops it. Two boxes that both
        // looked like "the frozen apps" - each with its own release - was the
        // confusion this replaces.
        LinearLayout setCard = card("Freeze");
        setCard.addView(text("Pick the apps to keep stopped, then press Freeze. They "
                + "stay stopped - killed again every few seconds - until you release.",
                11.5f, Theme.MUTED, Typeface.NORMAL));

        setBanner = text("", 12.5f, Theme.TEXT_DIM, Typeface.NORMAL);
        LinearLayout.LayoutParams sblp = wrap();
        sblp.topMargin = Ui.dp(this, 12);
        setCard.addView(setBanner, sblp);

        // A single line naming anything held by the persistent modes, so those are
        // never invisible - but with no second release button competing.
        heldBanner = text("", 11.5f, Theme.MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams hblp = wrap();
        hblp.topMargin = Ui.dp(this, 6);
        setCard.addView(heldBanner, hblp);

        LinearLayout setActions = row();
        LinearLayout.LayoutParams salp = wrap();
        salp.topMargin = Ui.dp(this, 12);
        setActions.setLayoutParams(salp);
        freezeSetBtn = pill("Freeze", Theme.CRITICAL, 0xFFFFFFFF, v -> {
            Anim.bounce(v);
            toggleFreezeSet();
        });
        // "Freeze" flips to "Unfreeze", which is wider - lock to the wider one so
        // the button does not resize (and the row does not shift) on every toggle.
        lockWidthToLongest(freezeSetBtn, "Freeze", "Unfreeze");
        setActions.addView(freezeSetBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams selp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        selp2.leftMargin = Ui.dp(this, 10);
        // A pill like Freeze, not a plain button: the two sit in one row, and a
        // pill's shorter vertical padding made them different heights and radii.
        selectBtn = pill("Select apps", Theme.CARD_HI, Theme.TEXT, v -> {
            Anim.bounce(v);
            enterSelect();
        });
        setActions.addView(selectBtn, selp2);
        setCard.addView(setActions);

        // Auto-release: the backstop that keeps a forgotten freeze from lasting
        // forever. Chips rather than a picker, since the useful windows are few.
        setCard.addView(divider());
        setCard.addView(groupLabel("Auto-release"));
        autoReleaseRow = row();
        setCard.addView(autoReleaseRow);
        rebuildAutoRelease();

        // One extra action, not a second release: the Freeze button above already
        // flips to Unfreeze while the set is held, so a separate Release button was
        // the same control twice. This is the one-off background sweep, which is a
        // different thing entirely.
        setCard.addView(divider());
        setCard.addView(button("Kill background", v -> {
            Anim.bounce(v);
            new Thread(() -> {
                Shell.run("am kill-all");
                main.post(() -> toast("Background processes cleared"));
            }, "pulse-killall").start();
        }));
        col.addView(setCard);

        // ---- The selection bar. Hidden until select mode is on; while it is, it
        // replaces the set card's buttons with "Add N" / "Cancel".
        selectCard = card(null);
        selectCard.setVisibility(View.GONE);
        selectCount = text("", 13, Theme.TEXT, Typeface.BOLD);
        selectCard.addView(selectCount);
        LinearLayout selActions = row();
        LinearLayout.LayoutParams selalp = wrap();
        selalp.topMargin = Ui.dp(this, 12);
        selActions.setLayoutParams(selalp);
        TextView addSel = pill("Save set", Theme.ACCENT, 0xFFFFFFFF, v -> {
            Anim.bounce(v);
            addSelectedToSet();
        });
        selActions.addView(addSel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams caplp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        caplp.leftMargin = Ui.dp(this, 10);
        selActions.addView(button("Cancel", v -> {
            Anim.bounce(v);
            exitSelect();
        }), caplp);
        selectCard.addView(selActions);

        // Clear all: empties the selection in one tap, so a fresh set can be built
        // without un-ticking app by app.
        clearAllBtn = button("Clear all", v -> {
            Anim.bounce(v);
            selected.clear();
            refreshSelectBar();
            refreshRowTrailing();
            reorderRows();
        });
        LinearLayout.LayoutParams clrlp = wrap();
        clrlp.topMargin = Ui.dp(this, 8);
        selectCard.addView(clearAllBtn, clrlp);
        col.addView(selectCard);


        // Search.
        LinearLayout searchCard = card(null);
        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setHintTextColor(Theme.MUTED);
        search.setTextColor(Theme.TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 14)));
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.US);
                render();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        searchCard.addView(search);
        col.addView(searchCard);

        listHost = column();
        col.addView(listHost);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        updateHeldBanner();
        refreshSet();
    }

    /**
     * Refresh the freeze-set card: how many apps are in the set, and whether they
     * are frozen right now. The button flips its label with the state, so one
     * control both starts and stops the batch.
     */
    private void refreshSet() {
        if (setBanner == null) return;
        int n = Freeze.batch(this).size();
        boolean active = Freeze.batchActive(this);

        if (n == 0) {
            setBanner.setText("No apps in the set yet. Press Select apps to choose some.");
            setBanner.setTextColor(Theme.MUTED);
        } else if (active) {
            setBanner.setText(n + (n == 1 ? " app is" : " apps are")
                    + " being kept stopped. Press Unfreeze to let them run again.");
            setBanner.setTextColor(Theme.CRITICAL);
        } else {
            setBanner.setText(n + (n == 1 ? " app in the set." : " apps in the set.")
                    + " Press Freeze to keep them stopped.");
            setBanner.setTextColor(Theme.TEXT_DIM);
        }

        freezeSetBtn.setText(active ? "Unfreeze" : "Freeze");
        freezeSetBtn.setBackground(Ui.round(
                active ? Theme.CARD_HI : Theme.CRITICAL, Ui.dp(this, 16)));
        freezeSetBtn.setTextColor(active ? Theme.TEXT : 0xFFFFFFFF);
    }

    /** Start or stop the whole batch with the one button. */
    private void toggleFreezeSet() {
        final boolean active = Freeze.batchActive(this);
        if (active) {
            new Thread(() -> {
                Freeze.releaseBatch(this);
                main.post(() -> { toast("Released"); refreshSet(); render(); });
            }, "pulse-relbatch").start();
            return;
        }
        if (Freeze.batch(this).isEmpty()) {
            toast("Add apps to the set first");
            return;
        }
        // START THE REAPER ON THE MAIN THREAD, FIRST.
        //
        // startForegroundService is a main-thread call - running it from the worker
        // thread below silently failed, so the service never came up and nothing
        // ever reaped: the apps stayed running and could still post notifications.
        // It also has to happen BEFORE the freeze, so the first force-stop and the
        // reaper are not racing.
        FreezeService.ensure(this);
        new Thread(() -> {
            int n = Freeze.freezeBatch(this);
            // A second ensure from the worker, so a race with a dying service still
            // leaves it up. Safe to call repeatedly.
            main.post(() -> {
                FreezeService.ensure(this);
                toast(n + (n == 1 ? " app frozen" : " apps frozen"));
                refreshSet();
                render();
            });
        }, "pulse-freezebatch").start();
    }

    // ---------------------------------------------------------- select mode

    /**
     * Turn the list into a picker: taps toggle apps instead of opening sheets.
     *
     * The rows are NOT rebuilt. Each row keeps its trailing slot, and entering the
     * mode swaps that slot's contents in place - rebuilding the list (which reads
     * the package list on a background thread) made the list blink out and threw
     * the scroll back to the top, which is a bad thing to do to someone who is
     * halfway down a list looking for an app.
     */
    private void enterSelect() {
        selecting = true;
        // Seed with what is already in the set, so the mode shows the current
        // membership rather than an empty slate - and adding is incremental.
        selected.clear();
        selected.addAll(Freeze.batch(this));
        selectCard.setVisibility(View.VISIBLE);
        selectCard.setAlpha(0f);
        selectCard.animate().alpha(1f).setDuration(200).start();
        selectBtn.setText("Selecting…");
        selectBtn.setEnabled(false);
        refreshSelectBar();
        refreshRowTrailing();
        // Apps already in the set rise to the top straight away, so the mode opens
        // showing the current membership as one group.
        reorderRows();
    }

    private void exitSelect() {
        selecting = false;
        selected.clear();
        selectCard.setVisibility(View.GONE);
        selectBtn.setText("Select apps");
        selectBtn.setEnabled(true);
        refreshRowTrailing();
    }

    /**
     * Swap every row's trailing control between the checkbox and the dots.
     *
     * The rows keep their trailing slot tagged, and this replaces just that slot's
     * contents. The list is not rebuilt, so it stays exactly where it is - at the
     * same scroll position - which is the whole point.
     */
    private void refreshRowTrailing() {
        if (listHost == null) return;
        for (int i = 0; i < listHost.getChildCount(); i++) {
            View rowCard = listHost.getChildAt(i);
            if (!(rowCard instanceof LinearLayout)) continue;
            LinearLayout card = (LinearLayout) rowCard;
            if (card.getChildCount() == 0) continue;
            View inner = card.getChildAt(0);
            if (!(inner instanceof LinearLayout)) continue;
            LinearLayout r = (LinearLayout) inner;

            // The trailing control is the LAST child and carries a Trailing tag.
            if (r.getChildCount() == 0) continue;
            View trailing = r.getChildAt(r.getChildCount() - 1);
            Object tag = trailing.getTag();
            if (!(tag instanceof Trailing)) continue;
            String pkg = ((Trailing) tag).pkg;
            r.removeViewAt(r.getChildCount() - 1);
            r.addView(buildTrailing(pkg));
        }
    }

    /** Build the right-hand control for a row: a checkbox in select mode, dots otherwise. */
    private View buildTrailing(String pkg) {
        if (selecting) {
            TextView check = text("", 14, Theme.MUTED, Typeface.BOLD);
            check.setGravity(Gravity.CENTER);
            check.setLayoutParams(new LinearLayout.LayoutParams(
                    Ui.dp(this, 24), Ui.dp(this, 24)));
            applyCheck(check, selected.contains(pkg));
            check.setTag(new Trailing(pkg));
            return check;
        }
        TextView more = text("⋯", 18, Theme.MUTED, Typeface.NORMAL);
        more.setGravity(Gravity.CENTER);
        more.setTag(new Trailing(pkg));
        return more;
    }

    /** Update the "N selected" line, and the Clear all button's visibility. */
    private void refreshSelectBar() {
        if (selectCount == null) return;
        int n = selected.size();
        selectCount.setText(n == 0 ? "Tap apps to select them"
                : n + (n == 1 ? " app selected" : " apps selected"));
        selectCount.setTextColor(n == 0 ? Theme.MUTED : Theme.TEXT);
        // Clear all only means something when something is selected.
        if (clearAllBtn != null) clearAllBtn.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
    }

    /**
     * Make the freeze set match the selection exactly.
     *
     * REPLACE, not add: the mode was seeded with the current set, so a user who
     * unticked an app means "remove it", and one who ticked a new app means "add
     * it". Writing the selection wholesale is the only reading that honours both.
     */
    private void addSelectedToSet() {
        int n = 0;
        java.util.LinkedHashSet<String> keep = new java.util.LinkedHashSet<>();
        for (String pkg : selected) {
            if (Freeze.isProtected(pkg)) continue;
            keep.add(pkg);
            n++;
        }
        prefs.setFreezeSet(keep);
        selected.clear();
        refreshSet();
        exitSelect();
        toast(n == 0 ? "Freeze set cleared"
                : n + (n == 1 ? " app in the set" : " apps in the set"));
    }

    private void updateHeldBanner() {
        if (heldBanner == null) return;
        int f = Freeze.frozen(this).size();
        int s = Freeze.suspended(this).size();
        int d = Freeze.disabled(this).size();
        if (f + s + d == 0) {
            heldBanner.setText("Nothing is being held. Apps run normally.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (f > 0) sb.append(f).append(" frozen");
        if (s > 0) { if (sb.length() > 0) sb.append(" · "); sb.append(s).append(" suspended"); }
        if (d > 0) { if (sb.length() > 0) sb.append(" · "); sb.append(d).append(" disabled"); }
        heldBanner.setText(sb.toString());
    }

    /** The auto-release window chips, rebuilt so the selection moves. */
    private void rebuildAutoRelease() {
        autoReleaseRow.removeAllViews();
        int current = prefs.freezeAutoReleaseHours();
        for (int i = 0; i < RELEASE_HOURS.length; i++) {
            final int hours = RELEASE_HOURS[i];
            boolean on = hours == current;
            TextView t = pill(RELEASE_LABELS[i], on ? Theme.ACCENT : Theme.CARD_HI,
                    on ? 0xFFFFFFFF : Theme.TEXT_DIM, v -> {
                        Anim.bounce(v);
                        if (hours == current) return;
                        prefs.setFreezeAutoReleaseHours(hours);
                        // Re-arm from now with the new window.
                        prefs.setFreezeReleaseAt(0L);
                        if (hours > 0 && Freeze.anythingHeld(this)) {
                            prefs.setFreezeReleaseAt(System.currentTimeMillis()
                                    + hours * 3600_000L);
                        }
                        rebuildAutoRelease();
                        toast(hours == 0 ? "No auto-release" : "Auto-release in " + hours + "h");
                    });
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            autoReleaseRow.addView(t);
            if (i < RELEASE_HOURS.length - 1) {
                View gap = new View(this);
                gap.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
                autoReleaseRow.addView(gap);
            }
        }
    }

    // ------------------------------------------------------------------ list

    private void render() {
        if (listHost == null) return;
        listHost.removeAllViews();
        if (firstLoad) showLoading(listHost, 5);
        new Thread(() -> {
            Shell.ensure(this);
            // LAUNCHER apps, not just third-party: Chrome, the Play Store, Maps and
            // Gmail ship as system packages but are ordinary apps a user opens.
            Set<String> pkgs = Freeze.launchable(this);
            List<String> sorted = new ArrayList<>(pkgs);
            // Sort by the label the user sees, not the package name.
            final java.util.Map<String, String> labels = new java.util.HashMap<>();
            for (String p : sorted) labels.put(p, AppIcon.get(this, p).label);
            Collections.sort(sorted, (a, b2) -> {
                String la = labels.get(a), lb = labels.get(b2);
                return (la == null ? a : la).compareToIgnoreCase(lb == null ? b2 : lb);
            });
            main.post(() -> buildList(sorted));
        }, "pulse-apps").start();
    }

    private void buildList(List<String> pkgs) {
        listHost.removeAllViews();
        Set<String> frozen = Freeze.frozen(this);        Set<String> suspended = Freeze.suspended(this);
        Set<String> disabled = Freeze.disabled(this);

        // Remember the canonical order of the rows actually shown, so reordering
        // during selection can always lay them back out correctly.
        order.clear();
        int shown = 0;
        for (String pkg : pkgs) {
            AppIcon.Info info = AppIcon.get(this, pkg);
            String label = info.label.isEmpty() ? pkg : info.label;
            if (!query.isEmpty()
                    && !label.toLowerCase(Locale.US).contains(query)
                    && !pkg.toLowerCase(Locale.US).contains(query)) continue;
            order.add(pkg);
            View row = appRow(pkg, info, frozen, suspended, disabled);
            reveal(row, shown);
            listHost.addView(row);
            shown++;
        }
        if (shown == 0) {
            LinearLayout empty = card(null);
            empty.addView(text("No apps match.", 12.5f, Theme.MUTED, Typeface.NORMAL));
            listHost.addView(empty);
        }
        endFirstLoad();
    }

    private View appRow(String pkg, AppIcon.Info info, Set<String> frozen,
                        Set<String> suspended, Set<String> disabled) {
        LinearLayout card = card(null);
        LinearLayout r = row();
        r.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));

        // FIT_CENTER inside a fixed box: a plain ImageView lets a small adaptive
        // icon render at its own size, so some icons came out tiny and others
        // filled the row.
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
        r.addView(icon, ilp);

        LinearLayout mid = column();
        mid.addView(text(info.label.isEmpty() ? pkg : info.label, 15, Theme.TEXT, Typeface.BOLD));

        String state = null;
        int stateColor = Theme.MUTED;
        if (disabled.contains(pkg)) { state = "Disabled"; stateColor = Theme.CRITICAL; }
        else if (suspended.contains(pkg)) { state = "Suspended"; stateColor = Theme.WARNING; }
        else if (frozen.contains(pkg)) { state = "Frozen"; stateColor = Theme.ACCENT; }

        if (state != null) {
            TextView badge = text(state, 10.5f, stateColor, Typeface.BOLD);
            badge.setLetterSpacing(0.06f);
            LinearLayout.LayoutParams blp = wrap();
            blp.topMargin = Ui.dp(this, 3);
            mid.addView(badge, blp);
        } else {
            mid.addView(text(pkg, 11, Theme.MUTED, Typeface.NORMAL));
        }
        r.addView(mid, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // In select mode the trailing control is a checkbox that reflects the
        // choice; otherwise it is the "more actions" dots. Either way the control
        // carries a Trailing tag, so entering or leaving the mode can swap it in
        // place without rebuilding the row.
        r.addView(buildTrailing(pkg));

        r.setOnClickListener(v -> {
            Anim.bounce(v);
            if (selecting) {
                boolean on;
                if (selected.contains(pkg)) { selected.remove(pkg); on = false; }
                else { selected.add(pkg); on = true; }
                // Find the control NOW, not the one captured when the row was
                // built: entering select mode swaps each row's trailing control for
                // a checkbox, so a reference held from build time points at the
                // discarded view and the visible checkbox never changed.
                View t = currentTrailing(r);
                if (t instanceof TextView) applyCheck((TextView) t, on);
                refreshSelectBar();
                // Selected apps rise to the top, so the set being built is visible
                // at a glance instead of scattered down the list.
                reorderRows();
            } else {
                openSheet(pkg, info, suspended.contains(pkg), disabled.contains(pkg));
            }
        });
        card.addView(r);
        // Tag the row card with its package too, so reorderRows can tell which rows
        // are selected without walking into each row's trailing control.
        card.setTag(new Trailing(pkg));
        return card;
    }

    /** The row's current trailing control, or null. */
    private View currentTrailing(LinearLayout r) {
        if (r.getChildCount() == 0) return null;
        View last = r.getChildAt(r.getChildCount() - 1);
        return last.getTag() instanceof Trailing ? last : null;
    }

    /**
     * Float the selected rows to the top, keeping the rest in their real order.
     *
     * Done by REORDERING the existing row views, never by rebuilding them - the
     * list stays put, the scroll does not jump, and the checkbox that was just
     * ticked stays where it is. Both groups keep the list's own order (by name), so
     * the selection does not shuffle as it grows and a deselected row drops back to
     * its proper place rather than to the end.
     */
    private void reorderRows() {
        if (listHost == null || !selecting || order.isEmpty()) return;

        // Index every row view by its package, then lay them out in the canonical
        // order with the selected group first.
        java.util.Map<String, View> byPkg = new java.util.HashMap<>();
        for (int i = 0; i < listHost.getChildCount(); i++) {
            View card = listHost.getChildAt(i);
            Object tag = card.getTag();
            if (tag instanceof Trailing) byPkg.put(((Trailing) tag).pkg, card);
        }
        if (byPkg.isEmpty()) return;

        List<View> out = new ArrayList<>();
        for (String pkg : order) if (selected.contains(pkg)) {
            View v = byPkg.remove(pkg);
            if (v != null) out.add(v);
        }
        for (String pkg : order) {
            View v = byPkg.remove(pkg);
            if (v != null) out.add(v);
        }
        // Anything not in the canonical order (should not happen) keeps its place.
        out.addAll(byPkg.values());

        // If the order is already right, leave the view tree untouched - a no-op
        // rebuild is what would make the scroll twitch on every tap.
        boolean same = out.size() == listHost.getChildCount();
        if (same) {
            for (int i = 0; i < out.size(); i++) {
                if (listHost.getChildAt(i) != out.get(i)) { same = false; break; }
            }
        }
        if (same) return;

        listHost.removeAllViews();
        for (View v : out) listHost.addView(v);
    }

    /** Paint a selection checkbox for its state. */
    private void applyCheck(TextView check, boolean on) {
        if (check == null) return;
        check.setText(on ? "✓" : "");
        check.setTextColor(on ? 0xFFFFFFFF : Theme.MUTED);
        check.setBackground(Ui.round(on ? Theme.ACCENT : Theme.CARD_HI, Ui.dp(this, 8)));
    }

    // ----------------------------------------------------------------- sheet

    private void openSheet(String pkg, AppIcon.Info info, boolean isSuspended,
                           boolean isDisabled) {
        String name = info.label.isEmpty() ? pkg : info.label;
        Sheet s = sheet(name);

        // The freeze set: the batch button's membership list. There is no per-app
        // freeze - the set is the only freeze, so this is how one app joins it.
        boolean inSet = Freeze.inBatch(this, pkg);
        if (inSet) {
            s.action("Remove from freeze set", Theme.TEXT, () -> {
                Freeze.removeFromBatch(this, pkg);
                refreshSet();
                render();
                toast("Removed from the set");
            });
        } else {
            s.action("Add to freeze set", Theme.ACCENT, () -> {
                if (Freeze.isProtected(pkg)) { toast("This app is protected"); return; }
                Freeze.addToBatch(this, pkg);
                refreshSet();
                render();
                toast("Added to the set");
            });
        }

        // Suspend / unsuspend - persistent.
        if (isSuspended) {
            s.action("Unsuspend", Theme.WARNING, () -> runAndRefresh(
                    () -> Freeze.unsuspend(this, pkg), "Unsuspended"));
        } else {
            s.action("Suspend", Theme.TEXT, () -> confirm("Suspend " + name + "?",
                    "A suspended app is frozen at the system level and will not run "
                            + "at all. This is not undone when Pulse is closed - use "
                            + "Release all, or reopen this sheet to unsuspend.",
                    "Suspend", Theme.WARNING, () -> runAndRefresh(
                            () -> Freeze.suspend(this, pkg), "Suspended")));
        }

        // Disable / enable - persistent, and hides the app.
        if (isDisabled) {
            s.action("Enable", Theme.WARNING, () -> runAndRefresh(
                    () -> Freeze.enable(this, pkg), "Enabled"));
        } else {
            s.action("Disable", Theme.TEXT, () -> confirm("Disable " + name + "?",
                    "A disabled app is turned off completely and disappears from the "
                            + "launcher. This is not undone when Pulse is closed - use "
                            + "Release all, or reopen this sheet to enable it again.",
                    "Disable", Theme.CRITICAL, () -> runAndRefresh(
                            () -> Freeze.disable(this, pkg), "Disabled")));
        }

        s.action("Force stop", Theme.TEXT, () -> runAndRefresh(
                () -> Shell.run("am force-stop " + pkg), "Stopped"));

        s.action("Restrict background", Theme.TEXT, () -> runAndRefresh(
                () -> Shell.run("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND ignore"),
                "Background restricted"));

        // Background data (netpolicy) - needs the uid, so look it up first.
        s.action("Permissions & limits", Theme.TEXT, () -> openPerms(pkg, name));

        s.action("Clear cache", Theme.TEXT, () -> runAndRefresh(
                () -> Shell.run("pm clear --cache-only " + pkg), "Cache cleared"));

        s.action("Clear data", Theme.CRITICAL, () -> confirm("Clear data for " + name + "?",
                "All of this app's data - logins, settings, saved files - will be "
                        + "deleted. This cannot be undone.",
                "Clear data", Theme.CRITICAL, () -> runAndRefresh(
                        () -> Shell.run("pm clear " + pkg), "Data cleared")));

        s.action("Uninstall", Theme.CRITICAL, () -> confirm("Uninstall " + name + "?",
                "The app will be removed for this user. It can be restored by "
                        + "reinstalling it, but its data will be gone.",
                "Uninstall", Theme.CRITICAL, () -> runAndRefresh(
                        () -> Shell.run("pm uninstall --user 0 " + pkg), "Uninstalled")));

        s.action("App info", Theme.TEXT, () -> openDeviceSettings(
                "android.settings.APPLICATION_DETAILS_SETTINGS -d package:" + pkg));

        s.note("Freeze stops the app running in the background and lasts only while "
                + "Pulse is running. Suspend and disable are held by the system and "
                + "stay until released.");
    }

    /** Run a shell action off the main thread, then refresh the list. */
    private void runAndRefresh(Runnable work, String done) {
        new Thread(() -> {
            work.run();
            main.post(() -> { render(); updateHeldBanner(); toast(done); });
        }, "pulse-app-action").start();
    }

    /**
     * Permissions and background limits for one app.
     *
     * Two parts. The appops list is the per-permission switchboard - camera,
     * location, microphone, background - which is what actually governs the app at
     * runtime. Below it are the two coarser levers: the standby bucket (how freely
     * the system lets it wake) and the background-network block.
     *
     * A separate sheet rather than rows in the main one: ten permission rows plus
     * the actions would be a scroll nobody reads, and this way the app's actions
     * stay one tap away.
     */
    private void openPerms(String pkg, String name) {
        new Thread(() -> {
            List<String[]> ops = AppOps.read(pkg);
            int uid = AppOps.uid(pkg);
            boolean netRestricted = AppOps.networkRestricted(uid);
            int bucket = AppOps.standbyBucket(pkg);
            main.post(() -> buildPermsSheet(pkg, name, ops, netRestricted, bucket));
        }, "pulse-appops").start();
    }

    private void buildPermsSheet(String pkg, String name, List<String[]> ops,
                                 boolean netRestricted, int bucket) {
        Sheet s = sheet(name + " · limits");

        // Standby bucket.
        s.action("Standby: " + AppOps.bucketName(bucket), Theme.TEXT, () ->
                confirm("Set standby bucket?",
                        "Choose how freely the system lets this app wake. \"Restricted\" "
                                + "is the strictest, \"Active\" the most permissive.",
                        "Restricted", Theme.WARNING, () -> {
                            new Thread(() -> {
                                AppOps.setStandbyBucket(pkg, "restricted");
                                main.post(() -> toast("Bucket: restricted"));
                            }, "pulse-bucket").start();
                        }));

        // Background network.
        s.action(netRestricted ? "Allow background data" : "Restrict background data",
                netRestricted ? Theme.ACCENT : Theme.TEXT, () -> {
                    new Thread(() -> {
                        int uid = AppOps.uid(pkg);
                        AppOps.setNetworkRestricted(uid, !netRestricted);
                        main.post(() -> {
                            toast(netRestricted ? "Background data allowed"
                                    : "Background data restricted");
                            openPerms(pkg, name);
                        });
                    }, "pulse-netpolicy").start();
                });

        // The permission switches.
        for (String[] row : ops) {
            String id = row[0], label = row[1], mode = row[2];
            boolean denied = AppOps.denied(mode);
            s.action((denied ? "Allow " : "Deny ") + label,
                    denied ? Theme.ACCENT : Theme.TEXT, () -> {
                        new Thread(() -> {
                            AppOps.set(pkg, id, denied ? "allow" : "ignore");
                            main.post(() -> {
                                toast(label + (denied ? " allowed" : " denied"));
                                openPerms(pkg, name);
                            });
                        }, "pulse-appop").start();
                    });
        }
        s.note("These are the system's own per-app switches. Denying one takes effect "
                + "at once and is remembered by the device, not by Pulse.");
    }
}
