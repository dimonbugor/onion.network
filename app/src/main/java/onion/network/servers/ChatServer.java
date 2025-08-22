

package onion.network.servers;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

import onion.network.helpers.Utils;
import onion.network.models.ChatBot;
import onion.network.databases.ChatDatabase;
import onion.network.databases.ItemDatabase;
import onion.network.models.Notifier;
import onion.network.databases.RequestDatabase;
import onion.network.helpers.Ed25519Signature;
import onion.network.settings.Settings;
import onion.network.TorManager;

public class ChatServer {

    private static ChatServer instance;
    String TAG = "chatserver";
    ChatDatabase chatDatabase;
    private Context context;
    private TorManager tor;
    private HashSet<OnMessageReceivedListener> listeners = new HashSet<OnMessageReceivedListener>();

    public ChatServer(Context context) {
        this.context = context;
        this.tor = TorManager.getInstance(context);
        this.chatDatabase = ChatDatabase.getInstance(context);
    }

    synchronized public static ChatServer getInstance(Context context) {
        if (instance == null)
            instance = new ChatServer(context.getApplicationContext());
        return instance;
    }

    void log(String s) {
        Log.i(TAG, s);
    }


    public boolean handle(Uri uri) {
        log("handle " + uri);

        // get & check params

        final String sender = uri.getQueryParameter("a");
        final String receiver = uri.getQueryParameter("b");
        final String time = uri.getQueryParameter("t");
        String m = uri.getQueryParameter("m");
        final String pubkey = uri.getQueryParameter("p");
        final String signature = uri.getQueryParameter("s");
        final String name = uri.getQueryParameter("n") != null ? uri.getQueryParameter("n") : "";

        if (!receiver.equals(tor.getID())) {
            log("message wrong address");
            return false;
        }
        log("message address ok");

        //TODO: torManager.checksig
        if (!tor.checksig(
                Ed25519Signature.base64Decode(pubkey),
                Ed25519Signature.base64Decode(signature),
                (receiver + " " + sender + " " + time + " " + m).getBytes(StandardCharsets.UTF_8))) {
            log("message invalid signature");
            //return false;
        }
        log("message signature ok");

        final String content = new String(Ed25519Signature.base64Decode(m), Charset.forName("UTF-8"));

        final long ltime;
        try {
            ltime = Long.parseLong(time);
        } catch (Exception ex) {
            log("failed to parse time");
            return false;
        }


        // get chat mode

        boolean acceptMessage = true;
        String acceptmessages = Settings.getPrefs(context).getString("acceptmessages", "");
        if ("none".equals(acceptmessages)) {
            log("accept none. message blocked.");
            acceptMessage = false;
        }
        if ("friends".equals(acceptmessages)) {
            log("friends only");
            if (!ItemDatabase.getInstance(context).hasKey("friend", sender)) {
                log("not a friend. message blocked.");
                if (!RequestDatabase.getInstance(context).isDeclined(sender)) {
                    RequestDatabase.getInstance(context).addIncoming(sender, name);
                    log("friend request added.");
                } else {
                    log("friend request already declined. message to auto friend request blocked.");
                }
                acceptMessage = false;
            }
        }


        // handle message

        if (acceptMessage) {
            chatDatabase.addMessage(sender, receiver, content, ltime, true, false);
            callOnMessageReceivedListeners();
            Notifier.getInstance(context).msg(sender);
        }

        ChatBot chatBot = ChatBot.getInstance(context);
        if (chatBot.addr() != null) {
            acceptMessage = chatBot.handle(sender, name, content, ltime, acceptMessage);
        }


        log("message received");

        return acceptMessage;
    }


    synchronized public void addOnMessageReceivedListener(OnMessageReceivedListener l) {
        listeners.add(l);
    }

    synchronized public void removeOnMessageReceivedListener(OnMessageReceivedListener l) {
        listeners.remove(l);
    }

    synchronized private void callOnMessageReceivedListeners() {
        for (OnMessageReceivedListener l : listeners) {
            l.onMessageReceived();
        }
    }

    public interface OnMessageReceivedListener {
        void onMessageReceived();
    }

}
