// language: Java, file: Bar.java, target: Android 16 (API 36)
// The one-line bar's items. Deliberately a separate model from {@link Metric}:
// the chart plots six series, but the bar shows FIVE items because CPU and
// battery temperature are one field ("TEMP") carrying both readings. The bar
// item's chip slots are the SAME Theme.SERIES indices the chart uses, so a chip
// color in the bar always matches the line of that name in the combined chart.
package com.adit.pulse;

final class Bar {
    static final class Item {
        final String id;
        final String label;
        final int[] slots;   // indices into Theme.SERIES, one chip per reading
        /**
         * Whether the bar draws this item's label.
         *
         * False where the value already carries its own unit, so the label would
         * only repeat it: temperature reads "45°/37°" and power "-3.2W", which
         * say what they are. Frame rate is the opposite - "60" alone says
         * nothing - so FPS keeps its label. The label is always kept for the
         * settings screen, which needs a name for the row.
         */
        final boolean showLabel;
        Item(String id, String label, boolean showLabel, int... slots) {
            this.id = id;
            this.label = label;
            this.showLabel = showLabel;
            this.slots = slots;
        }
    }

    static final Item CPU  = new Item("cpu",  "CPU",  true,  0);
    static final Item MEM  = new Item("mem",  "MEM",  true,  1);
    static final Item TEMP = new Item("temp", "TEMP", false, 2, 3);   // cpu temp, battery temp
    static final Item FPS  = new Item("fps",  "FPS",  true,  4);
    static final Item GPU  = new Item("gpu",  "GPU",  true,  5);      // only when readable
    static final Item W    = new Item("w",    "W",    false, 6);
    static final Item BATT = new Item("batt", "BAT",  true,  7);      // off by default

    static final Item[] ALL = { CPU, MEM, TEMP, FPS, GPU, W, BATT };

    /** The items the bar shows out of the box: the user's stated bar contents. */
    static final String[] DEFAULT_ON = { "cpu", "mem", "temp", "fps", "w" };

    static String[] ids() {
        String[] a = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) a[i] = ALL[i].id;
        return a;
    }

    static Item byId(String id) {
        for (Item it : ALL) if (it.id.equals(id)) return it;
        return null;
    }

    private Bar() { }
}
