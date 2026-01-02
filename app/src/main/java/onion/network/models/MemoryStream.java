package onion.network.models;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import onion.network.TorManager;
import onion.network.databases.ItemDatabase;
import onion.network.helpers.Ed25519Signature;
import onion.network.helpers.Utils;

/**
 * Простий журнал подій ("memory stream") з ланцюжком prev->hash і підписом.
 */
public class MemoryStream {

    private static final String TYPE_EVENT = "post";
    private static final long ORDER_BASE = 100000000000000L;

    public static Item appendEvent(Context context, String event, JSONObject payload) {
        if (context == null) return null;
        try {
            ItemDatabase db = ItemDatabase.getInstance(context);
            String prevHash = latestHash(db);
            long ts = System.currentTimeMillis();

            if (payload == null) {
                payload = new JSONObject();
            }

            JSONObject envelope = new JSONObject();
            envelope.put("kind", "event");
            envelope.put("event", event == null ? "" : event);
            envelope.put("ts", ts);
            envelope.put("node", TorManager.getInstance(context).getID());
            envelope.put("payload", payload);
            envelope.put("prev", prevHash);

            String toSign = envelope.optString("prev", "") + "|" + envelope.optString("event", "") + "|" + ts + "|" + payload.toString();
            byte[] signature = TorManager.getInstance(context).sign(toSign.getBytes(Utils.UTF_8));
            if (signature != null) {
                envelope.put("sig", Ed25519Signature.base64Encode(signature));
            }

            String eventId = Utils.sha256Base64(envelope.toString().getBytes(Utils.UTF_8));
            envelope.put("event_id", eventId);
            String index = "" + (ORDER_BASE - ts);
            Item item = new Item(TYPE_EVENT, eventId, index, envelope);
            db.put(item);
            return item;
        } catch (Exception ex) {
            Log.w("MemoryStream", "appendEvent failed: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Зберегти вхідну подію без модифікації, з базовою перевіркою ланцюжка.
     */
    public static boolean ingest(Context context, Item item) {
        if (context == null || item == null) return false;
        try {
            JSONObject data = item.json();
            if (data == null) return false;
            String eventId = data.optString("event_id", item.key());
            ItemDatabase db = ItemDatabase.getInstance(context);
            String prev = latestHash(db);
            String incomingPrev = data.optString("prev", "");
            if (!prev.isEmpty() && !incomingPrev.isEmpty() && !prev.equals(incomingPrev)) {
                Log.w("MemoryStream", "chain gap detected, still storing");
            }
            db.put(new Item(TYPE_EVENT, eventId, item.index(), data));
            return true;
        } catch (Exception ex) {
            Log.w("MemoryStream", "ingest failed: " + ex.getMessage());
            return false;
        }
    }

    private static String latestHash(ItemDatabase db) {
        try {
            ItemResult r = db.get(TYPE_EVENT, "", 1);
            if (r != null && r.size() > 0) {
                return r.one().json().optString("event_id", "");
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
