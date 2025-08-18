package onion.network;

import android.app.Application;
import android.content.Context;

import java.util.zip.CheckedOutputStream;

public class App extends Application {

    public static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        NativeLoader.loadAll();
    }
}
