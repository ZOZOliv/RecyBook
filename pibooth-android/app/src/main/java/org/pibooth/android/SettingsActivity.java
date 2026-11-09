package org.pibooth.android;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import android.text.InputType;

/**
 * Écran de réglages (équivalent du menu de pibooth et du fichier
 * pibooth.cfg). Ouvert par un appui long sur l'engrenage de l'écran principal.
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.settings);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        private String pendingImageKey;
        private ActivityResultLauncher<String[]> imagePicker;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            imagePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onImagePicked);
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            for (String key : new String[]{"picture_margin_thick", "printer_max_pages", "general_settings_pin"}) {
                EditTextPreference p = findPreference(key);
                if (p != null) {
                    p.setOnBindEditTextListener(e -> e.setInputType(InputType.TYPE_CLASS_NUMBER
                            | InputType.TYPE_NUMBER_FLAG_SIGNED));
                }
            }

            setupImagePreference("picture_background_image");
            setupImagePreference("picture_overlay");
            updateCounters();

            Preference reset = findPreference("counters_reset");
            if (reset != null) {
                reset.setOnPreferenceClickListener(p -> {
                    new AlertDialog.Builder(requireContext())
                            .setMessage(R.string.counters_reset_confirm)
                            .setPositiveButton(android.R.string.ok, (d, w) -> {
                                new Counters(requireContext()).reset();
                                updateCounters();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return true;
                });
            }

            Preference quit = findPreference("general_quit");
            if (quit != null) {
                quit.setOnPreferenceClickListener(p -> {
                    requireActivity().finishAffinity();
                    return true;
                });
            }

            Preference about = findPreference("about");
            if (about != null) {
                about.setSummary(getString(R.string.about_summary, BuildConfigHelper.versionName(requireContext())));
            }
        }

        private void updateCounters() {
            Preference p = findPreference("counters_info");
            if (p != null) p.setSummary(new Counters(requireContext()).toString());
        }

        private void setupImagePreference(String key) {
            Preference p = findPreference(key);
            if (p == null) return;
            refreshImageSummary(p);
            p.setOnPreferenceClickListener(pref -> {
                String current = pref.getSharedPreferences().getString(key, "");
                if (current.isEmpty()) {
                    pickImage(key);
                } else {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(pref.getTitle())
                            .setItems(new CharSequence[]{getString(R.string.image_choose), getString(R.string.image_remove)},
                                    (d, which) -> {
                                        if (which == 0) {
                                            pickImage(key);
                                        } else {
                                            pref.getSharedPreferences().edit().putString(key, "").apply();
                                            refreshImageSummary(pref);
                                        }
                                    })
                            .show();
                }
                return true;
            });
        }

        private void pickImage(String key) {
            pendingImageKey = key;
            imagePicker.launch(new String[]{"image/*"});
        }

        private void onImagePicked(Uri uri) {
            if (uri == null || pendingImageKey == null) return;
            try {
                requireContext().getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
            }
            Preference p = findPreference(pendingImageKey);
            if (p != null) {
                p.getSharedPreferences().edit().putString(pendingImageKey, uri.toString()).apply();
                refreshImageSummary(p);
            }
            pendingImageKey = null;
        }

        private void refreshImageSummary(Preference p) {
            String v = p.getSharedPreferences().getString(p.getKey(), "");
            if (v.isEmpty()) {
                p.setSummary(R.string.image_none);
            } else {
                String name = Uri.parse(v).getLastPathSegment();
                p.setSummary(name != null ? name : v);
            }
        }
    }

    /** Lecture de la version sans dépendre de la classe BuildConfig générée. */
    static final class BuildConfigHelper {
        static String versionName(android.content.Context c) {
            try {
                return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "?";
            }
        }
    }
}
