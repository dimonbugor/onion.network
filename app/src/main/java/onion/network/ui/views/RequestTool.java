

package onion.network.ui.views;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.Charset;

import onion.network.databases.ItemDatabase;
import onion.network.R;
import onion.network.helpers.Ed25519Signature;
import onion.network.settings.Settings;
import onion.network.TorManager;
import onion.network.helpers.Utils;
import onion.network.clients.HttpClient;
import onion.network.databases.RequestDatabase;
import onion.network.ui.MainActivity;

public class RequestTool {

    private static RequestTool instance;
    Context context;

    public RequestTool(Context c) {
        context = c;
    }

    static byte[] msg(String dest, String addr, String name) {
        return ("add " + dest + " " + addr + " " + name).getBytes(Charset.forName("UTF-8"));
    }

    synchronized public static RequestTool getInstance(Context context) {
        if (instance == null)
            instance = new RequestTool(context.getApplicationContext());
        return instance;
    }

    public boolean sendUnsentReq(final String dest) {
        log("sendUnsentReq " + dest);
        if (dest == null || "".equals(dest)) {
            log("no unsent 0");
            return false;
        }
        if (!RequestDatabase.getInstance(context).hasOutgoing(dest)) {
            log("no unsent 1");
            return false;
        }
        log("sending unsent");
        return sendRequest(dest);
    }

    public boolean sendRequest(String dest) {
        String addr = TorManager.getInstance(context).getID();
        String name = ItemDatabase.getInstance(context).get("name", "", 1).one().json().optString("name");

        byte[] sigBytes = TorManager.getInstance(context).sign(msg(dest, addr, name));
        byte[] pubKeyBytes = TorManager.getInstance(context).pubkey();

        // Перевірка на відсутність ключів
        if (sigBytes == null || pubKeyBytes == null) {
            log("❌ Cannot send request — Tor keys not ready yet");
            return false; // або відкласти в чергу
        }

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
            byte[] rs = HttpClient.getbin(uri);
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

    private void log(String str) {
        Log.i("RequestTool", str);
    }

    public boolean handleRequest(Uri uri) {

        final String dest = uri.getQueryParameter("dest");
        final String addr = uri.getQueryParameter("addr");
        final String name = uri.getQueryParameter("name");
        final String sign = uri.getQueryParameter("sign");
        final String pkey = uri.getQueryParameter("pkey");

        if (dest == null || addr == null || name == null || sign == null || pkey == null) {
            log("Parameter missing");
            return false;
        }

        if (!dest.equals(TorManager.getInstance(context).getID())) {
            log("Wrong destination");
            return false;
        }

        if (!TorManager.getInstance(context).checksig(Ed25519Signature.base64Decode(pkey), Ed25519Signature.base64Decode(sign), msg(dest, addr, name))) {
            log("Invalid signature");
            return false;
        }
        log("Signature OK");

        if (ItemDatabase.getInstance(context).hasKey("friend", addr)) {
            log("Already added as friend");
            return false;
        }

        if (!StatusTool.getInstance(context).isOnline(addr)) {
            log("remote hidden service not yet registered");
            throw new RuntimeException("remote hidden service not yet registered");
        }

        if (Settings.getPrefs(context).getBoolean("friendbot", false)) {
            MainActivity.addFriendItem(context, addr, name);
        } else {
            RequestDatabase.getInstance(context).addIncoming(addr, name);
        }

        //new ItemTask(context, addr, "thumb").execute2();
        MainActivity.prefetch(context, addr);
        new Thread() {
            @Override
            public void run() {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ex) {
                }
                //new ItemTask(context, addr, "thumb").execute2();
                MainActivity.prefetch(context, addr);
            }
        }.start();

        MainActivity mainActivity = MainActivity.getInstance();
        if(mainActivity != null) {
            mainActivity.blink(R.drawable.ic_group_add_white_36dp);
            if(mainActivity.requestPage != null) {
                mainActivity.requestPage.load();
            }
        }

        return true;

    }

}
