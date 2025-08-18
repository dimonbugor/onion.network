package onion.network;

import android.util.Log;

public class NativeLoader {
    private static final String TAG = "NativeLoader";

    public static void loadAll() {
        try {
            Log.i(TAG, "Loading tor...");
            System.loadLibrary("tor");

            System.loadLibrary("conjure");
            System.loadLibrary("dnscrypt-proxy");
            System.loadLibrary("i2pd");
            System.loadLibrary("nflog");
            System.loadLibrary("obfs4proxy");
            System.loadLibrary("snowflake");
            System.loadLibrary("webtunnel");
            System.loadLibrary("zmq");

            Log.i(TAG, "Loading tor-native...");
            System.loadLibrary("tor-native");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "❌ Failed to load native libraries", e);
        }
    }
}

