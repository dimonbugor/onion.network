

package onion.network.ui;

import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.snackbar.Snackbar;

import onion.network.R;
import onion.network.helpers.ThemeManager;
import onion.network.models.WallBot;
import onion.network.settings.Settings;

public class ThemeSettingsActivity extends AppCompatActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        ThemeManager.init(this).applyNoActionBarTheme(this);
        super.onCreate(savedInstanceState);

        Settings.getPrefs(this);

        setContentView(R.layout.prefs);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }


        getFragmentManager().beginTransaction().add(R.id.content, new SettingsFragment()).commit();

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.prefs, menu);
        return true;
    }

    void doreset() {

        Settings.getPrefs(ThemeSettingsActivity.this).edit().clear().commit();
        Settings.getPrefs(ThemeSettingsActivity.this);

        Intent intent = getIntent();
        finish();
        startActivity(intent);
        overridePendingTransition(0, 0);

    Snackbar.make(findViewById(R.id.content), getString(R.string.all_settings_reset), Snackbar.LENGTH_SHORT).show();

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        int id = item.getItemId();

        if (id == R.id.action_reset) {
            doreset();
        }

        if (id == android.R.id.home) {
            onBackPressed(); // або finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragment {

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.theme_prefs);

            final android.app.Activity activity = getActivity();
            if (activity == null) {
                return;
            }

            ThemeManager themeManager = ThemeManager.init(activity);
            String currentTheme = themeManager.getTheme();

            SwitchPreference themeSwitch = (SwitchPreference) getPreferenceManager().findPreference("theme_dark_mode");
            SwitchPreference monochromeSwitch = (SwitchPreference) getPreferenceManager().findPreference("theme_monochrome_mode");

            if (themeSwitch != null) {
                themeSwitch.setChecked(isDarkTheme(currentTheme));
            }

            if (monochromeSwitch != null) {
                monochromeSwitch.setChecked(isMonochromeTheme(currentTheme));
            }

            if (themeSwitch != null) {
                themeSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                    android.app.Activity host = getActivity();
                    if (host == null) {
                        return false;
                    }
                    boolean enableDark = (Boolean) newValue;
                    boolean enableMonochrome = monochromeSwitch != null && monochromeSwitch.isChecked();
                    return applyThemeSelection(host, enableDark, enableMonochrome);
                });
            }

            if (monochromeSwitch != null) {
                monochromeSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                    android.app.Activity host = getActivity();
                    if (host == null) {
                        return false;
                    }
                    boolean enableMonochrome = (Boolean) newValue;
                    boolean enableDark = themeSwitch != null && themeSwitch.isChecked();
                    return applyThemeSelection(host, enableDark, enableMonochrome);
                });
            }
        }

        private boolean applyThemeSelection(android.app.Activity host, boolean enableDark, boolean enableMonochrome) {
            ThemeManager manager = ThemeManager.init(host);
            String targetThemeKey;
            if (enableMonochrome) {
                targetThemeKey = enableDark ? ThemeManager.themeKeys[2] : ThemeManager.themeKeys[3];
            } else {
                targetThemeKey = enableDark ? ThemeManager.themeKeys[0] : ThemeManager.themeKeys[1];
            }

            if (targetThemeKey.equals(manager.getTheme())) {
                return true;
            }

            manager.setTheme(host, targetThemeKey);
            host.recreate();
            return true;
        }

        private boolean isDarkTheme(String themeKey) {
            if (themeKey == null) {
                return true;
            }
            return ThemeManager.themeKeys[0].equals(themeKey) || ThemeManager.themeKeys[2].equals(themeKey);
        }

        private boolean isMonochromeTheme(String themeKey) {
            if (themeKey == null) {
                return false;
            }
            return ThemeManager.themeKeys[2].equals(themeKey) || ThemeManager.themeKeys[3].equals(themeKey);
        }
    }

}
