package onion.network;

import android.app.Application;
import android.content.Context;

import java.util.zip.CheckedOutputStream;

import onion.network.helpers.ThemeManager;

public class App extends Application {

    public static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        ThemeManager.init(this);
        NativeLoader.loadAll();
        onion.network.call.CallManager.getInstance(this).initialize();
    }
}
