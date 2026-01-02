package onion.network.models;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashSet;

import onion.network.TorManager;
import onion.network.clients.ChatClient;
import onion.network.databases.ItemDatabase;
import onion.network.models.ItemResult;
import onion.network.models.MemoryStream;
import onion.network.settings.Settings;
import onion.network.helpers.Utils;

/**
 * Локальний агент, що читає події (post) і генерує реакції без зовнішніх викликів.
 */
public class AgentLoop implements Runnable {

    private static final String TAG = "AgentLoop";
    private static final long INTERVAL_MS = 30_000L;
    private static final int SCAN_LIMIT = 128;
    private static final long ORDER_BASE = 100000000000000L;

    private static AgentLoop instance;
    private final Context context;
    private final HashSet<String> seen = new HashSet<>();
    private Thread thread;
    private volatile boolean running = false;

    private AgentLoop(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized AgentLoop getInstance(Context context) {
        if (instance == null) {
            instance = new AgentLoop(context);
        }
        return instance;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "AgentLoop");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        while (running) {
            try {
                tick();
            } catch (Exception ex) {
                Log.w(TAG, "tick error: " + ex.getMessage());
            }
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void tick() {
        ItemDatabase db = ItemDatabase.getInstance(context);
        ItemResult r = db.get("post", "", SCAN_LIMIT);
        for (int i = 0; i < r.size(); i++) {
            Item item = r.at(i);
            String eventId = item.json().optString("event_id", item.key());
            if (eventId == null || eventId.trim().isEmpty()) {
                eventId = item.key();
            }
            if (seen.contains(eventId)) {
                continue;
            }
            seen.add(eventId);
            if (seen.size() > 1024) {
                seen.clear();
                seen.add(eventId);
            }
            processEvent(item);
        }
    }

    private void processEvent(Item item) {
        try {
            JSONObject raw = item.json();
            JSONObject payload = raw.has("payload") ? raw.optJSONObject("payload") : raw;
            String sourceEvent = raw.optString("event_id", item.key());
            String text = payload != null ? payload.optString("text", payload.optString("title", "подія")) : "подія";
            long ts = System.currentTimeMillis();

            // Не реагуємо на власні SAY/VOICE_OUT, щоб не зациклити
            if ("VOICE_OUT".equalsIgnoreCase(raw.optString("event"))) {
                return;
            }

            JSONObject replyPayload = new JSONObject();
            replyPayload.put("reply_to", sourceEvent);
            replyPayload.put("text", "Автовідповідь на подію: " + text);
            replyPayload.put("date", ts);
            replyPayload.put("addr", TorManager.getInstance(context).getID());
            replyPayload.put("trace", sourceEvent);

            MemoryStream.appendEvent(context, "SAY", replyPayload);

            // Опціонально надіслати в чат автору події
            if (Settings.getPrefs(context).getBoolean("chatbot", false)) {
                String recipient = payload != null ? payload.optString("addr", "") : "";
                if (recipient != null && !recipient.trim().isEmpty() && !recipient.equals(TorManager.getInstance(context).getID())) {
                    ChatClient.getInstance(context).sendOne(
                            TorManager.getInstance(context).getID(),
                            recipient,
                            replyPayload.optString("text"),
                            ts
                    );
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "processEvent failed: " + ex.getMessage());
        }
    }
}
