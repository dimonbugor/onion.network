

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

        Snackbar.make(findViewById(R.id.content), "All settings reset", Snackbar.LENGTH_SHORT).show();

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

            SwitchPreference themeSwitch = (SwitchPreference) getPreferenceManager().findPreference("theme_dark_mode");
            if (themeSwitch != null) {
                final android.app.Activity activity = getActivity();
                if (activity == null) {
                    return;
                }
                boolean isDark = ThemeManager.init(activity).getTheme().equals(ThemeManager.themeKeys[0]);
                themeSwitch.setChecked(isDark);
                themeSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enableDark = (Boolean) newValue;
                    android.app.Activity host = getActivity();
                    if (host == null) {
                        return false;
                    }
                    ThemeManager.init(host).setTheme(host,
                            enableDark ? ThemeManager.themeKeys[0] : ThemeManager.themeKeys[1]);
                    host.recreate();
                    return true;
                });
            }
        }
    }

}
