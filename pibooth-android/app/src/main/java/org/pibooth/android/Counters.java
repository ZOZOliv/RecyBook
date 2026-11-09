package org.pibooth.android;

import android.content.Context;
import android.content.SharedPreferences;

/** Compteurs persistants (équivalent de pibooth/counters.py). */
public final class Counters {

    private static final String FILE = "counters";
    private final SharedPreferences prefs;

    public Counters(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int taken() {
        return prefs.getInt("taken", 0);
    }

    public int printed() {
        return prefs.getInt("printed", 0);
    }

    public int forgotten() {
        return prefs.getInt("forgotten", 0);
    }

    public int remainingDuplicates() {
        return prefs.getInt("remaining_duplicates", 0);
    }

    public void incTaken() {
        prefs.edit().putInt("taken", taken() + 1).apply();
    }

    public void addPrinted(int pages) {
        prefs.edit().putInt("printed", printed() + pages).apply();
    }

    public void incForgotten() {
        prefs.edit().putInt("forgotten", forgotten() + 1).apply();
    }

    public void setRemainingDuplicates(int value) {
        prefs.edit().putInt("remaining_duplicates", Math.max(0, value)).apply();
    }

    public void reset() {
        prefs.edit().clear().apply();
    }

    @Override
    public String toString() {
        return "prises : " + taken() + " · imprimées : " + printed() + " · oubliées : " + forgotten();
    }
}
