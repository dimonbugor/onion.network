

package onion.network.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import onion.network.R;

public class Settings {

    private static final String FRIENDBOT_PREF_KEY = "friendbot";
    private static final String FRIENDBOT_RESET_FLAG = "friendbot_default_reset_done";

    public static SharedPreferences getPrefs(Context c) {

        c = c.getApplicationContext();

        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(c);

        PreferenceManager.setDefaultValues(c, R.xml.prefs, false);
        PreferenceManager.setDefaultValues(c, R.xml.theme_prefs, false);

        if (!p.getBoolean(FRIENDBOT_RESET_FLAG, false)) {
            p.edit()
                    .putBoolean(FRIENDBOT_PREF_KEY, false)
                    .putBoolean(FRIENDBOT_RESET_FLAG, true)
                    .apply();
        }

        return p;

    }

}
