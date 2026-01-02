package onion.network;

import android.app.Application;
import android.content.Context;

import java.util.zip.CheckedOutputStream;

import onion.network.helpers.ThemeManager;

public class App extends Application {

    public static Context context;
    private static final String PREF_AUTHTOKEN = "authtoken";

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        ThemeManager.init(this);
        NativeLoader.loadAll();
        onion.network.call.CallManager.getInstance(this).initialize();
        ensureAuthToken();
    }

    private void ensureAuthToken() {
        String token = onion.network.settings.Settings.getPrefs(this).getString(PREF_AUTHTOKEN, "");
        if (token == null || token.trim().isEmpty()) {
            String gen = java.util.UUID.randomUUID().toString().replace("-", "");
            onion.network.settings.Settings.getPrefs(this).edit().putString(PREF_AUTHTOKEN, gen).apply();
        }
    }
}
