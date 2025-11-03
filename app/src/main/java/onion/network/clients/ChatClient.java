

package onion.network.clients;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;

import org.json.JSONException;
import org.json.JSONObject;

import onion.network.call.CallSignalMessage;
import onion.network.databases.ChatDatabase;
import onion.network.databases.ItemDatabase;
import onion.network.TorManager;
import onion.network.helpers.ChatMediaStore;
import onion.network.helpers.MediaUploadClient;
import onion.network.helpers.StreamMediaStore;
import onion.network.helpers.Ed25519Signature;
import onion.network.helpers.Utils;
import onion.network.models.ChatMessagePayload;

public class ChatClient {

    private static ChatClient instance;
    ChatDatabase chatDatabase;
    private String TAG = "chatclient";
    private Context context;
    private TorManager torManager;
    private HashSet<OnMessageSentListener> listeners = new HashSet<OnMessageSentListener>();

    public ChatClient(Context context) {
        this.context = context;
        this.torManager = TorManager.getInstance(context);
        this.chatDatabase = ChatDatabase.getInstance(context);
    }

    synchronized public static ChatClient getInstance(Context context) {
        if (instance == null)
            instance = new ChatClient(context.getApplicationContext());
        return instance;
    }

    private void log(String s) {
        Log.i(TAG, s);
    }

    private boolean sendOne(Cursor cursor) throws IOException {
        String sender = cursor.getString(cursor.getColumnIndexOrThrow("sender"));
        String receiver = cursor.getString(cursor.getColumnIndexOrThrow("receiver"));
        String content = cursor.getString(cursor.getColumnIndexOrThrow("content"));
        long time = cursor.getLong(cursor.getColumnIndexOrThrow("time"));
        return sendOne(sender, receiver, content, time);
    }

    public Uri makeUri(String sender, String receiver, String content, long time) {
        Envelope envelope = buildEnvelope(sender, receiver, prepareContentForSend(content, receiver), time);
        return envelope.toUri();
    }

    public boolean sendOne(String sender, String receiver, String content, long time) throws IOException {

        String networkContent = prepareContentForSend(content, receiver);
        Envelope envelope = buildEnvelope(sender, receiver, networkContent, time);
        boolean sent = sendViaPost(envelope);
        if (!sent) {
            try {
                sent = sendViaGet(envelope);
            } catch (IOException ex) {
                log("exception");
                log("" + ex.getMessage());
                ex.printStackTrace();
                throw ex;
            }
        }
        if (!sent) {
            return false;
        }

        log("message sent");

        chatDatabase.touch(sender, receiver, time);

        callOnMessageSentListeners();

        return true;

    }

    private void sendAll(Cursor cursor) throws IOException {
        while (cursor.moveToNext()) {
            sendOne(cursor);
        }
        cursor.close();
    }

    public boolean hasUnsent(String address) {
        Cursor c = chatDatabase.getUnsent(address);
        boolean ret = c.moveToNext();
        c.close();
        return ret;
    }

    public void sendUnsent() throws IOException {
        sendAll(chatDatabase.getUnsent());
    }

    public void sendUnsent(String address) throws IOException {
        sendAll(chatDatabase.getUnsent(address));
    }

    synchronized public void addOnMessageSentListener(OnMessageSentListener l) {
        listeners.add(l);
    }

    synchronized public void removeOnMessageSentListener(OnMessageSentListener l) {
        listeners.remove(l);
    }

    synchronized private void callOnMessageSentListeners() {
        for (OnMessageSentListener l : listeners) {
            l.onMessageSent();
        }
    }

    public interface OnMessageSentListener {
        void onMessageSent();
    }

    private Envelope buildEnvelope(String sender, String receiver, String rawContent, long time) {
        log("buildEnvelope to=" + receiver + " len=" + (rawContent == null ? "null" : rawContent.length()));
        Envelope envelope = new Envelope();
        envelope.sender = sender;
        envelope.receiver = receiver;
        envelope.time = Long.toString(time);
        envelope.encodedContent = Ed25519Signature.base64Encode(rawContent.getBytes(Utils.UTF_8));
        envelope.publicKey = Ed25519Signature.base64Encode(torManager.pubkey());
        String displayName = ItemDatabase.getInstance(context).getstr("name");
        envelope.name = displayName == null ? "" : displayName;
        envelope.signature = Ed25519Signature.base64Encode(
                torManager.sign((receiver + " " + sender + " " + envelope.time + " " + envelope.encodedContent).getBytes(Utils.UTF_8))
        );
        return envelope;
    }

    private boolean sendViaPost(Envelope envelope) {
        JSONObject body = envelope.toJson();
        try {
            byte[] response = HttpClient.postbin(
                    envelope.toPostUri(),
                    body.toString().getBytes(Utils.UTF_8),
                    "application/json; charset=utf-8"
            );
            String responseText = new String(response, Utils.UTF_8).trim();
            if ("1".equals(responseText)) {
                return true;
            }
            log("POST declined: " + responseText);
            return false;
        } catch (IOException ex) {
            log("POST failed: " + ex.getMessage());
            return false;
        }
    }

    private boolean sendViaGet(Envelope envelope) throws IOException {
        Uri uri = envelope.toUri();
        log("" + uri);
        boolean rs = HttpClient.get(uri).trim().equals("1");
        if (!rs) {
            log("declined");
        }
        return rs;
    }

    private String prepareContentForSend(String rawContent, String receiver) {
        boolean rawLooksJson = rawContent != null && rawContent.trim().startsWith("{");
        try {
            ChatMessagePayload payload = ChatMessagePayload.fromStorageString(rawContent);
            if (payload.getType() == ChatMessagePayload.Type.TEXT) {
                String inlineJson = payload.getData();
                String mime = payload.getMime();
                if (!TextUtils.isEmpty(inlineJson)
                        && !TextUtils.isEmpty(mime)
                        && "application/json".equalsIgnoreCase(mime)
                        && CallSignalMessage.isCallSignal(inlineJson)) {
                    return inlineJson;
                }
                String textValue = payload.getText();
                if (rawLooksJson && TextUtils.isEmpty(textValue)) {
                    return rawContent;
                }
                return rawLooksJson ? textValue : rawContent;
            }
            if (ensureMediaReference(payload, receiver)) {
                return payload.toStorageString();
            }
            return convertToInlinePayload(payload, rawLooksJson ? payload.toStorageString() : rawContent);
        } catch (Exception ex) {
            log("prepareContentForSend error: " + ex.getMessage());
            return rawContent;
        }
    }

    private boolean ensureMediaReference(ChatMessagePayload payload, String receiver) {
        if (payload == null || payload.getType() == ChatMessagePayload.Type.TEXT) {
            return false;
        }
        if (payload.hasMediaReference()) {
            return true;
        }
        byte[] bytes = null;
        String mime = payload.getMime();
        try {
            if (!payload.isInline()) {
                File file = ChatMediaStore.resolveFile(context, payload.getData());
                if (file == null || !file.exists()) {
                    return false;
                }
                bytes = Utils.readFileAsBytes(file);
                if (TextUtils.isEmpty(mime)) {
                    mime = "application/octet-stream";
                }
            } else {
                String base64 = payload.getData();
                if (!TextUtils.isEmpty(base64)) {
                    bytes = Ed25519Signature.base64Decode(base64);
                }
            }
            if (bytes == null || bytes.length == 0) {
                return false;
            }
            if (ChatMediaStore.exceedsLimit(bytes.length)) {
                return false;
            }
            if (TextUtils.isEmpty(receiver)) {
                StreamMediaStore.MediaDescriptor descriptor = StreamMediaStore.save(context, bytes, mime);
                payload.setMediaId(descriptor.id);
                payload.setMime(descriptor.mime);
                payload.setSizeBytes(descriptor.size);
            } else {
                String host = receiver.endsWith(".onion") ? receiver : receiver + ".onion";
                MediaUploadClient.Result result = MediaUploadClient.upload(host, bytes, mime);
                payload.setMediaId(result.id);
                payload.setMime(result.mime);
                payload.setSizeBytes(result.size);
            }
            payload.setStorage(ChatMessagePayload.Storage.REFERENCE);
            payload.setData(null);
            return true;
        } catch (Exception ex) {
            log("ensureMediaReference error: " + ex.getMessage());
            return false;
        }
    }

    private String convertToInlinePayload(ChatMessagePayload payload, String fallback) {
        if (payload == null) {
            return fallback;
        }
        try {
            if (!payload.isInline()) {
                File file = ChatMediaStore.resolveFile(context, payload.getData());
                if (file != null && file.exists()) {
                    byte[] bytes = Utils.readFileAsBytes(file);
                    if (bytes.length > 0 && !ChatMediaStore.exceedsLimit(bytes.length)) {
                        ChatMessagePayload inline = payload.copy();
                        inline.setStorage(ChatMessagePayload.Storage.INLINE);
                        inline.setData(Ed25519Signature.base64Encode(bytes));
                        inline.setSizeBytes(bytes.length);
                        inline.setMediaId(null);
                        return inline.toStorageString();
                    }
                }
            } else if (!TextUtils.isEmpty(payload.getData())) {
                return payload.toStorageString();
            }
        } catch (Exception ex) {
            log("convertToInlinePayload error: " + ex.getMessage());
        }
        return fallback;
    }

    private static final class Envelope {
        String sender;
        String receiver;
        String time;
        String encodedContent;
        String publicKey;
        String signature;
        String name;

        Uri toUri() {
            StringBuilder builder = new StringBuilder("http://")
                    .append(receiver)
                    .append(".onion/m?");
            builder.append("a=").append(Uri.encode(sender)).append("&");
            builder.append("b=").append(Uri.encode(receiver)).append("&");
            builder.append("t=").append(Uri.encode(time)).append("&");
            builder.append("m=").append(Uri.encode(encodedContent)).append("&");
            builder.append("p=").append(Uri.encode(publicKey)).append("&");
            builder.append("s=").append(Uri.encode(signature)).append("&");
            builder.append("n=").append(Uri.encode(name == null ? "" : name));
            return Uri.parse(builder.toString());
        }

        Uri toPostUri() {
            return Uri.parse("http://" + receiver + ".onion/m");
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("a", sender);
                o.put("b", receiver);
                o.put("t", time);
                o.put("m", encodedContent);
                o.put("p", publicKey);
                o.put("s", signature);
                o.put("n", name == null ? "" : name);
                o.put("v", 1);
            } catch (JSONException ignore) {
            }
            return o;
        }
    }

}
