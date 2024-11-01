

package onion.network.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import onion.network.R;

public class Settings {

    public static SharedPreferences getPrefs(Context c) {

        c = c.getApplicationContext();

        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(c);

        PreferenceManager.setDefaultValues(c, R.xml.prefs, false);

        return p;

    }

}
