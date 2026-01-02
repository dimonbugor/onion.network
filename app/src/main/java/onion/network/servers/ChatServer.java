

package onion.network.servers;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

import onion.network.models.ChatBot;
import onion.network.databases.ChatDatabase;
import onion.network.databases.ItemDatabase;
import onion.network.models.Notifier;
import onion.network.databases.RequestDatabase;
import onion.network.helpers.ChatMediaStore;
import onion.network.helpers.Ed25519Signature;
import onion.network.settings.Settings;
import onion.network.TorManager;
import onion.network.call.CallManager;
import onion.network.call.CallSignalMessage;
import onion.network.models.ChatMessagePayload;

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


    public boolean handle(HttpServer.Request request) {
        if (request == null) {
            return false;
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && request.hasBody()) {
            return handlePost(request);
        }
        Uri uri = Uri.parse(request.getPath());
        if (!authOk(uri, request)) {
            return false;
        }
        return handleLegacy(uri);
    }

    private boolean handlePost(HttpServer.Request request) {
        try {
            String body = new String(request.getBody(), StandardCharsets.UTF_8);
            if (TextUtils.isEmpty(body)) {
                log("empty post body");
                return false;
            }
            JSONObject json = new JSONObject(body);
            String sender = json.optString("a", null);
            String receiver = json.optString("b", null);
            String time = json.optString("t", null);
            String message = json.optString("m", null);
            String pubkey = json.optString("p", null);
            String signature = json.optString("s", null);
            String name = json.optString("n", "");
            String auth = json.optString("auth", null);
            if (!authOk(auth)) {
                log("auth fail");
                return false;
            }
            return processMessage(sender, receiver, time, message, pubkey, signature, name);
        } catch (JSONException ex) {
            log("invalid post json");
            return false;
        } catch (Exception ex) {
            log("post handler error: " + ex.getMessage());
            return false;
        }
    }

    private boolean handleLegacy(Uri uri) {
        log("handle " + uri);

        // get & check params

        final String sender = uri.getQueryParameter("a");
        final String receiver = uri.getQueryParameter("b");
        final String time = uri.getQueryParameter("t");
        String m = uri.getQueryParameter("m");
        final String pubkey = uri.getQueryParameter("p");
        final String signature = uri.getQueryParameter("s");
        final String name = uri.getQueryParameter("n") != null ? uri.getQueryParameter("n") : "";
        final String auth = uri.getQueryParameter("auth");
        if (!authOk(auth)) {
            log("auth fail");
            return false;
        }

        return processMessage(sender, receiver, time, m, pubkey, signature, name);
    }

    private boolean authOk(String auth) {
        String token = Settings.getPrefs(context).getString("authtoken", "");
        if (token == null || token.trim().isEmpty()) {
            return true;
        }
        return token.equals(auth);
    }

    private boolean authOk(Uri uri, HttpServer.Request request) {
        String token = Settings.getPrefs(context).getString("authtoken", "");
        if (token == null || token.trim().isEmpty()) {
            return true;
        }
        String q = uri.getQueryParameter("auth");
        if (token.equals(q)) return true;
        String bodyAuth = null;
        try {
            String body = request.hasBody() ? new String(request.getBody(), StandardCharsets.UTF_8) : null;
            if (!TextUtils.isEmpty(body)) {
                JSONObject json = new JSONObject(body);
                bodyAuth = json.optString("auth", null);
            }
        } catch (Exception ignored) {
        }
        return token.equals(bodyAuth);
    }

    private boolean processMessage(String sender,
                                   String receiver,
                                   String time,
                                   String encodedMessage,
                                   String pubkey,
                                   String signature,
                                   String name) {

        if (TextUtils.isEmpty(receiver) || TextUtils.isEmpty(sender) ||
                TextUtils.isEmpty(time) || TextUtils.isEmpty(encodedMessage) ||
                TextUtils.isEmpty(pubkey) || TextUtils.isEmpty(signature)) {
            log("message missing fields");
            return false;
        }

        if (!receiver.equals(tor.getID())) {
            log("message wrong address");
            return false;
        }
        log("message address ok");

        //TODO: torManager.checksig
        if (!tor.checksig(
                Ed25519Signature.base64Decode(pubkey),
                Ed25519Signature.base64Decode(signature),
                (receiver + " " + sender + " " + time + " " + encodedMessage).getBytes(StandardCharsets.UTF_8))) {
            log("message invalid signature");
            //return false;
        }
        log("message signature ok");

        final String decodedPayload;
        try {
            decodedPayload = new String(Ed25519Signature.base64Decode(encodedMessage), Charset.forName("UTF-8"));
        } catch (Exception ex) {
            log("failed to decode content");
            return false;
        }

        CallSignalMessage callSignal = CallSignalMessage.fromTransportString(decodedPayload);
        ChatMessagePayload payload;
        if (callSignal != null) {
            payload = ChatMessagePayload.forText(callSignal.toDisplayString(false))
                    .setMime("application/json")
                    .setData(decodedPayload);
        } else {
            payload = ChatMessagePayload.fromStorageString(decodedPayload);
            if (payload.getType() != ChatMessagePayload.Type.TEXT && payload.isInline()) {
                String dataStr = payload.getData();
                if (!TextUtils.isEmpty(dataStr)) {
                    byte[] rawData = Ed25519Signature.base64Decode(dataStr);
                    if (rawData == null) {
                        log("unable to decode media payload");
                        return false;
                    }
                    if (ChatMediaStore.exceedsLimit(rawData.length)) {
                        log("media payload exceeds allowed size");
                        return false;
                    }
                    try {
                        String path = ChatMediaStore.saveIncoming(context, payload.getType(), rawData, payload.getMime());
                        payload.setStorage(ChatMessagePayload.Storage.FILE).setData(path);
                    } catch (IOException ex) {
                        log("failed to store media: " + ex.getMessage());
                        return false;
                    }
                }
            }
        }
        final String storageContent = payload.toStorageString();

        boolean skipStore = callSignal != null && callSignal.getType() == CallSignalMessage.SignalType.CANDIDATE;
        if (callSignal != null) {
            log("call signal " + callSignal.getType() + " from " + sender);
            CallManager.getInstance(context).onIncomingSignal(sender, callSignal);
        }

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

        if (acceptMessage && !skipStore) {
            chatDatabase.addMessage(sender, receiver, storageContent, ltime, true, false);
            callOnMessageReceivedListeners();
            Notifier.getInstance(context).msg(sender);
        }

        ChatBot chatBot = ChatBot.getInstance(context);
        if (chatBot.addr() != null) {
            acceptMessage = chatBot.handle(sender, name, payload.getText(), ltime, acceptMessage);
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
