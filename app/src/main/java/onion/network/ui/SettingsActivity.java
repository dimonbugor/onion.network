

package onion.network.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;

import onion.network.R;
import onion.network.WallBot;
import onion.network.cashes.ItemCache;
import onion.network.cashes.SiteCache;
import onion.network.helpers.Utils;
import onion.network.settings.Settings;

public class SettingsActivity extends AppCompatActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Settings.getPrefs(this);

        setContentView(R.layout.prefs);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getFragmentManager().beginTransaction().add(R.id.content, new SettingsFragment()).commit();

    }

    @Override
    protected void onPause() {
        super.onPause();
        WallBot.getInstance(this).init();
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

        Snackbar.make(findViewById(R.id.content), "All settings reset", Snackbar.LENGTH_SHORT).show();

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        int id = item.getItemId();

        if (id == R.id.action_reset) {
            doreset();
        }

        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragment {

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.prefs);

            getPreferenceManager().findPreference("clearcache").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    AlertDialog dialog = new AlertDialog.Builder(getActivity(), R.style.RoundedAlertDialog)
                            .setTitle("Clear Cache")
                            .setMessage("Remove all cache data?")
                            .setNegativeButton("No", null)
                            .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    ItemCache.getInstance(getActivity()).clearCache();
                                    SiteCache.getInstance(getActivity()).clearCache();
                                    Snackbar.make(getView(), "Cache cleared.", Snackbar.LENGTH_SHORT).show();
                                }
                            })
                            .create();
                    dialog.setOnShowListener(d -> {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
                    });
                    dialog.show();
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
            AlertDialog dialog = new AlertDialog.Builder(getActivity(), R.style.RoundedAlertDialog)
                    .setTitle("Third party software used by this app (click to view license)")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            showLicense(items[which]);
                        }
                    })
                    .create();
            dialog.setOnShowListener(d -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
            });
            dialog.show();
        }

        void showLicense(String name) {
            String text;
            try {
                text = Utils.readInputStreamToString(getResources().getAssets().open("licenses/" + name));
            } catch (IOException ex) {
                throw new Error(ex);
            }
            AlertDialog dialog = new AlertDialog.Builder(getActivity(), R.style.RoundedAlertDialog)
                    .setTitle(name)
                    .setMessage(text)
                    .create();
            dialog.setOnShowListener(d -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
            });
            dialog.show();
        }

    }

}
