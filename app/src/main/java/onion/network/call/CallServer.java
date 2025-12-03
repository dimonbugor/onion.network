package onion.network.call;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Singleton UNIX-domain server for call transport (mirrors HTTP Server pattern).
 */
public final class CallServer {
    private static final String TAG = "CallServer";
    private static CallServer instance;

    private final LocalServerSocket serverSocket;
    private final LocalSocket boundSocket;
    private final String socketName; // prefixed with unix:
    private final Object acceptLock = new Object();

    private CallServer(Context ctx) throws IOException {
        File path = new File(ctx.getFilesDir(), "call_socket");
        if (path.exists()) {
            // best effort cleanup old socket
            path.delete();
        }
        LocalSocketAddress address = new LocalSocketAddress(path.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM);
        boundSocket = new LocalSocket();
        boundSocket.bind(address);
        serverSocket = new LocalServerSocket(boundSocket.getFileDescriptor());
        socketName = "unix:" + path.getAbsolutePath();
        Log.i(TAG, "listening on " + socketName);
    }

    public static synchronized CallServer getInstance(Context ctx) throws IOException {
        if (instance == null) {
            instance = new CallServer(ctx.getApplicationContext());
        }
        return instance;
    }

    public String getSocketName() {
        return socketName;
    }

    public LocalSocket accept() throws IOException {
        synchronized (acceptLock) {
            return serverSocket.accept();
        }
    }
}
