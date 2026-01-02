

package onion.network.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;

import onion.network.R;
import onion.network.BuildConfig;
import onion.network.helpers.DialogHelper;
import onion.network.helpers.ThemeManager;
import onion.network.models.WallBot;
import onion.network.services.HostService;
import onion.network.cashes.ItemCache;
import onion.network.cashes.SiteCache;
import onion.network.helpers.Utils;
import onion.network.settings.Settings;

public class SettingsActivity extends AppCompatActivity {
    private static final int REQ_MEDIA_PERMS = 702;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        ThemeManager.init(this).applyNoActionBarTheme(this);
        super.onCreate(savedInstanceState);

        Settings.getPrefs(this);

        setContentView(R.layout.prefs);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getFragmentManager().beginTransaction().add(R.id.content, new SettingsFragment()).commit();
        requestHeadlessPermissionsIfNeeded();

    }

    @Override
    protected void onPause() {
        super.onPause();
        WallBot.getInstance(this).init();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MEDIA_PERMS) {
            boolean granted = true;
            for (int g : grantResults) {
                if (g != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                startHeadlessServices();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.prefs, menu);
        return true;
    }

    void doreset() {

        Settings.getPrefs(SettingsActivity.this).edit().clear().commit();
        Settings.getPrefs(SettingsActivity.this);

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

        return super.onOptionsItemSelected(item);
    }

    private void requestHeadlessPermissionsIfNeeded() {
        if (!"headless".equals(BuildConfig.FLAVOR)) {
            return;
        }
        boolean cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!cam || !mic) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    REQ_MEDIA_PERMS);
        } else {
            startHeadlessServices();
        }
    }

    private void startHeadlessServices() {
        ContextCompat.startForegroundService(this, new Intent(this, HostService.class));
    }

    public static class SettingsFragment extends PreferenceFragment {

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.prefs);

            getPreferenceManager().findPreference("clearcache").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    AlertDialog dialog = DialogHelper.styledBuilder(getActivity(), R.style.RoundedAlertDialog)
                            .setTitle(R.string.dialog_clear_cache_title)
                            .setMessage(R.string.dialog_clear_cache_message)
                            .setNegativeButton(R.string.dialog_button_no, null)
                            .setPositiveButton(R.string.dialog_button_yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    ItemCache.getInstance(getActivity()).clearCache();
                                    SiteCache.getInstance(getActivity()).clearCache();
                                    Snackbar.make(getView(), R.string.snackbar_cache_cleared, Snackbar.LENGTH_SHORT).show();
                                }
                            })
                            .create();
                    DialogHelper.show(dialog);
                    return true;
                }
            });

            getPreferenceManager().findPreference("licenses").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    showLibraries();
                    return true;
                }
            });
        }


        void showLibraries() {
            final String[] items;
            try {
                items = getResources().getAssets().list("licenses");
            } catch (IOException ex) {
                throw new Error(ex);
            }
            AlertDialog dialog = DialogHelper.styledBuilder(getActivity(), R.style.RoundedAlertDialog)
                    .setTitle(R.string.dialog_third_party_software_title)
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            showLicense(items[which]);
                        }
                    })
                    .create();
            DialogHelper.show(dialog);
        }

        void showLicense(String name) {
            String text;
            try {
                text = Utils.readInputStreamToString(getResources().getAssets().open("licenses/" + name));
            } catch (IOException ex) {
                throw new Error(ex);
            }
            AlertDialog dialog = DialogHelper.styledBuilder(getActivity(), R.style.RoundedAlertDialog)
                    .setTitle(name)
                    .setMessage(text)
                    .create();
            DialogHelper.show(dialog);
        }

    }

}
