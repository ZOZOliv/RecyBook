package org.pibooth.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration de l'application : équivalent du fichier pibooth.cfg,
 * stockée dans les SharedPreferences et modifiable depuis l'écran Réglages.
 * Les clés reprennent celles de pibooth (section_option).
 */
public final class BoothConfig {

    private final SharedPreferences prefs;

    public BoothConfig(Context context) {
        PreferenceManager.setDefaultValues(context, R.xml.preferences, false);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public SharedPreferences prefs() {
        return prefs;
    }

    // ------------------------------------------------------------------ GENERAL

    public String directoryName() {
        String d = getString("general_directory", "pibooth").trim();
        return d.isEmpty() ? "pibooth" : d.replace("/", "_");
    }

    public boolean saveRawCaptures() {
        return prefs.getBoolean("general_save_raw", true);
    }

    public String settingsPin() {
        return getString("general_settings_pin", "").trim();
    }

    // ------------------------------------------------------------------- WINDOW

    public int windowBackground() {
        return parseColor(getString("window_background", "#000000"), Color.BLACK);
    }

    public int windowTextColor() {
        return parseColor(getString("window_text_color", "#FFFFFF"), Color.WHITE);
    }

    public String windowFont() {
        return getString("window_font", "Amatic-Bold");
    }

    public boolean flash() {
        return prefs.getBoolean("window_flash", true);
    }

    public int finishPictureDelay() {
        return getInt("window_finish_picture_delay", 0);
    }

    public int waitPictureDelay() {
        return getInt("window_wait_picture_delay", -1);
    }

    public int chosenDelay() {
        return getInt("window_chosen_delay", 4);
    }

    public int previewDelay() {
        return Math.max(1, getInt("window_preview_delay", 3));
    }

    public boolean previewCountdown() {
        return prefs.getBoolean("window_preview_countdown", true);
    }

    public boolean previewStopOnCapture() {
        return prefs.getBoolean("window_preview_stop_on_capture", false);
    }

    // ------------------------------------------------------------------ PICTURE

    public String orientation() {
        return getString("picture_orientation", "auto");
    }

    /** Choix possibles du nombre de prises (1 ou 2 valeurs entre 1 et 4). */
    public int[] captureChoices() {
        String raw = getString("picture_captures", "4,1");
        List<Integer> values = new ArrayList<>();
        for (String part : raw.split("[,\\s()]+")) {
            if (part.isEmpty()) continue;
            try {
                int v = Integer.parseInt(part);
                if (v >= 1 && v <= 4 && !values.contains(v)) values.add(v);
            } catch (NumberFormatException ignored) {
            }
        }
        if (values.isEmpty()) values.add(1);
        int n = Math.min(2, values.size());
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = values.get(i);
        return out;
    }

    public String captureEffect() {
        return getString("picture_captures_effects", "none");
    }

    public boolean capturesCropping() {
        return prefs.getBoolean("picture_captures_cropping", false);
    }

    public int marginThick() {
        return getInt("picture_margin_thick", 100);
    }

    public String footerText1() {
        return getString("picture_footer_text1", "Footer 1");
    }

    public String footerText2() {
        return getString("picture_footer_text2", "Footer 2");
    }

    public int footerColor() {
        return parseColor(getString("picture_text_color", "#000000"), Color.BLACK);
    }

    public String footerFont1() {
        return getString("picture_text_font1", "Amatic-Bold");
    }

    public String footerFont2() {
        return getString("picture_text_font2", "AmaticSC-Regular");
    }

    public String footerAlignment() {
        return getString("picture_text_alignment", "center");
    }

    public int pictureBackgroundColor() {
        return parseColor(getString("picture_background_color", "#FFFFFF"), Color.WHITE);
    }

    /** URI (SAF) d'une image de fond, ou chaîne vide. */
    public String pictureBackgroundImage() {
        return getString("picture_background_image", "");
    }

    /** URI (SAF) d'un PNG superposé à la photo finale, ou chaîne vide. */
    public String pictureOverlay() {
        return getString("picture_overlay", "");
    }

    // ------------------------------------------------------------------- CAMERA

    /** auto | dslr | hybrid | tablet */
    public String cameraMode() {
        return getString("camera_mode", "auto");
    }

    /** front | back */
    public String tabletLens() {
        return getString("camera_tablet_lens", "front");
    }

    public int rotation() {
        return getInt("camera_rotation", 0);
    }

    public boolean flip() {
        return prefs.getBoolean("camera_flip", false);
    }

    public boolean previewFlip() {
        return prefs.getBoolean("camera_preview_flip", true);
    }

    public boolean autofocus() {
        return prefs.getBoolean("camera_autofocus", true);
    }

    public boolean deleteInternalMemory() {
        return prefs.getBoolean("camera_delete_internal_memory", false);
    }

    public boolean canonCaptureToHost() {
        return prefs.getBoolean("camera_canon_capture_to_ram", false);
    }

    // ------------------------------------------------------------------ PRINTER

    /** none | ipp | android */
    public String printerMode() {
        return getString("printer_mode", "none");
    }

    public String printerUrl() {
        return getString("printer_ipp_url", "").trim();
    }

    public String printerMedia() {
        return getString("printer_ipp_media", "").trim();
    }

    public int printerDelay() {
        return getInt("printer_delay", 10);
    }

    /** Nombre de pages imprimées automatiquement ; -1 pour « max ». */
    public int autoPrint() {
        String v = getString("printer_auto_print", "0");
        if ("max".equalsIgnoreCase(v)) return -1;
        return parseInt(v, 0);
    }

    public int maxPages() {
        return getInt("printer_max_pages", -1);
    }

    public int maxDuplicates() {
        return getInt("printer_max_duplicates", 3);
    }

    public int picturesPerPage() {
        return Math.max(1, Math.min(4, getInt("printer_pictures_per_page", 1)));
    }

    // ---------------------------------------------------------------- helpers

    private String getString(String key, String def) {
        try {
            String v = prefs.getString(key, def);
            return v == null ? def : v;
        } catch (ClassCastException e) {
            return def;
        }
    }

    private int getInt(String key, int def) {
        return parseInt(getString(key, String.valueOf(def)), def);
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** Accepte « #RRGGBB », un nom de couleur ou un tuple pibooth « (r, g, b) ». */
    public static int parseColor(String value, int def) {
        if (TextUtils.isEmpty(value)) return def;
        String v = value.trim();
        try {
            if (v.contains(",")) {
                String[] parts = v.replaceAll("[()\\s]", "").split(",");
                if (parts.length >= 3) {
                    return Color.rgb(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]));
                }
            }
            if (!v.startsWith("#") && v.matches("[0-9a-fA-F]{6}")) v = "#" + v;
            return Color.parseColor(v);
        } catch (Exception e) {
            return def;
        }
    }
}
