package com.zwiftboard.android;

import android.content.Context;
import android.content.SharedPreferences;

/** Réglages persistants : positions de touche par bouton (par orientation) et options. */
public final class Prefs {
    private Prefs() {}

    public static final String REPEAT = "opt_repeat";          // répéter la touche tant que le bouton est maintenu
    public static final String BUBBLE = "opt_bubble";          // bulle flottante
    public static final String IDLE_RESET = "opt_idle_reset";  // anti-veille des pods
    public static final String ONLY_WHOOSH = "opt_only_whoosh"; // n'agir que si MyWhoosh est au premier plan
    public static final String VERBOSE = "opt_verbose";        // journal détaillé (trames brutes)

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("zwiftboard", Context.MODE_PRIVATE);
    }

    public static boolean get(Context c, String key) {
        boolean def = BUBBLE.equals(key) || IDLE_RESET.equals(key);
        return sp(c).getBoolean(key, def);
    }

    public static void set(Context c, String key, boolean v) {
        sp(c).edit().putBoolean(key, v).apply();
    }

    private static String posKey(String button, boolean landscape) {
        return "pos_" + (landscape ? "land_" : "port_") + button;
    }

    /** Position en fraction de l'écran {x, y}, ou null si non définie. */
    public static float[] getPos(Context c, String button, boolean landscape) {
        String s = sp(c).getString(posKey(button, landscape), null);
        if (s == null) return null;
        String[] parts = s.split(";");
        if (parts.length != 2) return null;
        try {
            return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void setPos(Context c, String button, boolean landscape, float fx, float fy) {
        sp(c).edit().putString(posKey(button, landscape), fx + ";" + fy).apply();
    }

    public static void clearPos(Context c, String button, boolean landscape) {
        sp(c).edit().remove(posKey(button, landscape)).apply();
    }

    public static void clearAllPos(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String b : Protocol.BUTTONS.values()) {
            e.remove(posKey(b, true));
            e.remove(posKey(b, false));
        }
        e.apply();
    }

    public static int getInt(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    public static void setInt(Context c, String key, int v) {
        sp(c).edit().putInt(key, v).apply();
    }
}
