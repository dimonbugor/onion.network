

package onion.network;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.Charset;

import onion.network.clients.HttpClient;
import onion.network.databases.ItemDatabase;
import onion.network.databases.RequestDatabase;
import onion.network.helpers.Ed25519Signature;
import onion.network.helpers.OnionUrlBuilder;
import onion.network.helpers.Utils;
import onion.network.models.ItemResult;

public class FriendTool {

    private static FriendTool instance;
    Context context;

    public FriendTool(Context c) {
        context = c;
    }

    synchronized public static FriendTool getInstance(Context context) {
        if (instance == null)
            instance = new FriendTool(context.getApplicationContext());
        return instance;
    }

    static byte[] buildUnfriendMessage(String dest, String addr) {
        return ("unfriend " + dest + " " + addr).getBytes(Charset.forName("UTF-8"));
    }

    private void log(String str) {
        Log.i("FriendTool", str);
    }

    public boolean handleUnfriend(Uri uri) {
        String dest = uri.getQueryParameter("dest");
        String addr = uri.getQueryParameter("addr");
        String sign = uri.getQueryParameter("sign");
        String pkey = uri.getQueryParameter("pkey");

        if (dest == null || addr == null || sign == null || pkey == null) {
            log("Parameter missing");
            return false;
        }

        if (!dest.equals(TorManager.getInstance(context).getID())) {
            log("Wrong destination");
            return false;
        }

        if (!TorManager.getInstance(context).checksig(Ed25519Signature.base64Decode(pkey), Ed25519Signature.base64Decode(sign), buildUnfriendMessage(dest, addr))) {
            log("Invalid signature");
            return false;
        }
        log("Signature OK");

        ItemDatabase.getInstance(context).delete("friend", addr);

        return true;
    }

    public boolean doSendUnfriend(String dest) {
        String addr = TorManager.getInstance(context).getID();
        String sign = Ed25519Signature.base64Encode(TorManager.getInstance(context).sign(buildUnfriendMessage(dest, addr)));
        String pkey = Ed25519Signature.base64Encode(TorManager.getInstance(context).pubkey());

        String uri = "http://" + dest + ".onion/u?";
        uri += "dest=" + Uri.encode(dest) + "&";
        uri += "addr=" + Uri.encode(addr) + "&";
        uri += "sign=" + Uri.encode(sign) + "&";
        uri += "pkey=" + Uri.encode(pkey);

        log(uri);

        try {
            byte[] rs = HttpClient.getbin(Uri.parse(uri));
            log("sendUnfriend OK");
            return true;
        } catch (IOException ex) {
            log("sendUnfriend err");
            return false;
        }
    }

    public void startSendUnfriend(final String dest) {
        new Thread() {
            @Override
            public void run() {
                doSendUnfriend(dest);
            }
        }.start();
    }

    public void unfriend(String address) {
        RequestDatabase.getInstance(context).removeOutgoing(address);
        RequestDatabase.getInstance(context).removeIncoming(address);
        ItemDatabase.getInstance(context).delete("friend", address);
        FriendTool.getInstance(context).startSendUnfriend(address);
    }

    public boolean handleUpdate(Uri uri) {
        String dest = uri.getQueryParameter("dest");
        String addr = uri.getQueryParameter("addr");
        String time = uri.getQueryParameter("time");
        String sign = uri.getQueryParameter("sign");
        String pkey = uri.getQueryParameter("pkey");

        if (dest == null || addr == null || sign == null || pkey == null) {
            log("Parameter missing");
            return false;
        }

        if (!dest.equals(TorManager.getInstance(context).getID())) {
            log("Wrong destination");
            return false;
        }

        if (!TorManager.getInstance(context).checksig(Ed25519Signature.base64Decode(pkey), Ed25519Signature.base64Decode(sign), (dest + " " + addr + " " + time).getBytes(Utils.UTF_8))) {
            log("Invalid signature");
            return false;
        }
        log("Signature OK");

        new ItemTask(context, addr, "name").execute2();
        new ItemTask(context, addr, "thumb").execute2();

        return true;
    }



    volatile long requestGeneration = 1;

    private boolean requestUpdate(String dest) {
        log("requestUpdate " + dest);
        String addr = TorManager.getInstance(context).getID();
        String time = "" + System.currentTimeMillis();
        String pkey = Ed25519Signature.base64Encode(TorManager.getInstance(context).pubkey());
        String sign = Ed25519Signature.base64Encode(TorManager.getInstance(context).sign((dest + " " + addr + " " + time).getBytes(Utils.UTF_8)));
        try {
            return "1".equals(
                    HttpClient.get(Uri.parse(new OnionUrlBuilder(dest, "r")
                                    .arg("dest", dest)
                                    .arg("addr", addr)
                                    .arg("time", time)
                                    .arg("pkey", pkey)
                                    .arg("sign", sign)
                                    .build())));
        } catch (IOException ex) {
            return false;
        }
    }

    private void requestUpdates2() {
        requestGeneration++;
        long currentRequestGeneration = requestGeneration;

        ItemResult itemResult = ItemDatabase.getInstance(context).get("friend", "", 12);
        for(int i = 0; i < itemResult.size(); i++) {
            if(requestGeneration > currentRequestGeneration) {
                log("abort");
            }
            String addr = itemResult.at(i).key();
            boolean ok = requestUpdate(addr);
            log("update " + addr + " " + ok);
        }
    }

    public void requestUpdates() {
        new Thread() {
            @Override
            public void run() {
                requestUpdates2();
            }
        }.start();
    }

}
