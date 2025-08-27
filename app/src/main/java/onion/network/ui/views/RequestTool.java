package onion.network.ui.views;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import onion.network.R;
import onion.network.TorManager;
import onion.network.clients.HttpClient;
import onion.network.databases.ItemDatabase;
import onion.network.databases.RequestDatabase;
import onion.network.helpers.Ed25519Signature;
import onion.network.settings.Settings;
import onion.network.ui.MainActivity;

public class RequestTool {

    private static RequestTool instance;
    private final Context context;

    private RequestTool(Context c) {
        context = c.getApplicationContext();
    }

    public static synchronized RequestTool getInstance(Context context) {
        if (instance == null)
            instance = new RequestTool(context);
        return instance;
    }

    private static byte[] msg(String dest, String addr, String name) {
        return ("add " + dest + " " + addr + " " + name).getBytes(StandardCharsets.UTF_8);
    }

    public boolean sendUnsentReq(String dest) {
        log("sendUnsentReq " + dest);
        if (dest == null || dest.isEmpty()) return false;
        if (!RequestDatabase.getInstance(context).hasOutgoing(dest)) return false;
        return sendRequest(dest);
    }

    public boolean sendRequest(String dest) {
        String addr = TorManager.getInstance(context).getID();
        String name = ItemDatabase.getInstance(context).get("name", "", 1).one().json().optString("name");

        byte[] sigBytes = TorManager.getInstance(context).sign(msg(dest, addr, name));
        byte[] pubKeyBytes = TorManager.getInstance(context).pubkey();

        if (sigBytes == null || pubKeyBytes == null) return false;

        String sign = Ed25519Signature.base64Encode(sigBytes);
        String pkey = Ed25519Signature.base64Encode(pubKeyBytes);

        String uri = "http://" + dest + ".onion/f?"
                + "dest=" + Uri.encode(dest) + "&"
                + "addr=" + Uri.encode(addr) + "&"
                + "name=" + Uri.encode(name) + "&"
                + "sign=" + Uri.encode(sign) + "&"
                + "pkey=" + Uri.encode(pkey);

        log(uri);

        try {
            HttpClient.getbin(Uri.parse(uri));
            RequestDatabase.getInstance(context).removeOutgoing(dest);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    public void sendAllRequests() {
        Cursor cursor = RequestDatabase.getInstance(context).getOutgoing();
        while (cursor.moveToNext()) {
            String address = cursor.getString(cursor.getColumnIndex("a"));
            sendRequest(address);
        }
        cursor.close();
    }

    public boolean handleRequest(Uri uri) {
        String dest = uri.getQueryParameter("dest");
        String addr = uri.getQueryParameter("addr");
        String name = uri.getQueryParameter("name");
        String sign = uri.getQueryParameter("sign");
        String pkey = uri.getQueryParameter("pkey");

        if (dest == null || addr == null || name == null || sign == null || pkey == null) return false;
        if (!dest.equals(TorManager.getInstance(context).getID())) return false;

        if (!TorManager.getInstance(context).checksig(
                Ed25519Signature.base64Decode(pkey),
                Ed25519Signature.base64Decode(sign),
                msg(dest, addr, name))) return false;

        if (ItemDatabase.getInstance(context).hasKey("friend", addr)) return false;
        if (!StatusTool.getInstance(context).isOnline(addr)) throw new RuntimeException("remote hidden service not yet registered");

        if (Settings.getPrefs(context).getBoolean("friendbot", false)) {
            MainActivity.addFriendItem(context, addr, name);
        } else {
            RequestDatabase.getInstance(context).addIncoming(addr, name);
        }

        MainActivity.prefetch(context, addr);
        new Thread(() -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ignored) {}
            MainActivity.prefetch(context, addr);
        }).start();

        MainActivity mainActivity = MainActivity.getInstance();
        if (mainActivity != null) {
            mainActivity.blink(R.drawable.ic_group_add);
            if (mainActivity.requestPage != null) {
                mainActivity.requestPage.load();
            }
        }

        return true;
    }

    private void log(String str) {
        Log.i("RequestTool", str);
    }
}
