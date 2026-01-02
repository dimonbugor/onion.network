

package onion.network.models;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import onion.network.clients.HttpClient;
import onion.network.databases.ItemDatabase;
import onion.network.models.ItemResult;
import onion.network.helpers.Utils;
import onion.network.TorManager;
import onion.network.settings.Settings;

public class WallBot {

    private static WallBot instance;
    String TAG = "WallBot";
    Context context;
    Timer timer;
    private static final String[] SYNC_TYPES = new String[]{"post", "name", "thumb", "video", "video_thumb"};
    private static final int SYNC_PAGE_SIZE = 128;
    private static final long ORDER_BASE = 100000000000000L;

    public WallBot(Context context) {
        this.context = context;
        init();
    }

    synchronized public static WallBot getInstance(Context context) {
        if (instance == null)
            instance = new WallBot(context.getApplicationContext());
        return instance;
    }

    void log(String s) {
        Log.i(TAG, s);
    }

    public void init() {

        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }


        if (Settings.getPrefs(context).getBoolean("wallbot", false)) {

            long intervalSeconds = 60;
            try {
                intervalSeconds = Long.parseLong(Settings.getPrefs(context).getString("wallbotinterval", "60"));
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            log("intervalSeconds " + intervalSeconds);

            timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                public void run() {
                    new Thread() {
                        @Override
                        public void run() {
                            try {
                                update();
                            } catch (Exception ex) {
                                log(ex.toString());
                            }
                            try {
                                syncFriends();
                            } catch (Exception ex) {
                                log("syncFriends error " + ex);
                            }
                        }
                    }.start();
                }
            }, 0, 1000 * intervalSeconds);

        }

    }

    private RssEvent[] fetchRss(String addr) {

        try {
            byte[] data;
            Uri a = Uri.parse(addr);
            Uri.Builder builder = a.buildUpon();
            String token = Settings.getPrefs(context).getString("authtoken", "");
            if (token != null && !token.trim().isEmpty() && a.getQueryParameter("auth") == null) {
                builder.appendQueryParameter("auth", token.trim());
                a = builder.build();
            }
            try {
                data = HttpClient.getbin(a);
            } catch (IOException ex) {
                log("feed error");
                log(ex.toString());
                return null;
            }

            DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();

            Document doc = documentBuilder.parse(new ByteArrayInputStream(data));

            ArrayList<RssEvent> ret = new ArrayList<>();

            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Node itemNode = items.item(i);
                if (!(itemNode instanceof Element)) {
                    continue;
                }

                Element item = (Element) itemNode;

                String title = getTag(item, "title");
                String link = getTag(item, "link");
                String guid = getTag(item, "guid");
                String description = getTag(item, "description");

                JSONObject payload;
                try {
                    payload = description == null ? new JSONObject() : new JSONObject(description);
                } catch (Exception ex) {
                    payload = new JSONObject();
                }

                if (title != null && !title.trim().isEmpty() && !payload.has("title")) {
                    payload.put("title", title.trim());
                }
                if (link != null && !link.trim().isEmpty()) {
                    Uri uri = Uri.parse(link);
                    if (uri != null && uri.getQueryParameter("url") != null) {
                        link = uri.getQueryParameter("url");
                    }
                    if (!payload.has("link")) {
                        payload.put("link", link.trim());
                    }
                }
                if (!payload.has("text")) {
                    StringBuilder sb = new StringBuilder();
                    if (title != null) sb.append(title.trim());
                    if (link != null && !link.trim().isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(link.trim());
                    }
                    if (sb.length() > 0) {
                        payload.put("text", sb.toString());
                    }
                }

                long ts = payload.optLong("date", System.currentTimeMillis());
                String eventId = guid;
                if (eventId == null || eventId.trim().isEmpty()) {
                    eventId = payload.optString("event_id", "");
                }
                if (eventId == null || eventId.trim().isEmpty()) {
                    eventId = Utils.sha1Digest((title == null ? "" : title) + "|" + (link == null ? "" : link) + "|" + payload.toString());
                }
                eventId = eventId.trim();
                payload.put("event_id", eventId);
                payload.put("date", ts);

                ret.add(new RssEvent(eventId, payload, ts));
            }

            return ret.toArray(new RssEvent[ret.size()]);

        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return null;
    }

    private void update() throws Exception {
        log("update");

        ItemDatabase db = ItemDatabase.getInstance(context);

        RssEvent[] ss = fetchRss(Settings.getPrefs(context).getString("wallbotfeed", ""));

        if (ss == null) return;

        log(ss.length + " items");

        if (ss.length == 0) return;

        for (int i = 0; i < ss.length; i++) {
            long ts = ss[i].timestamp;
            long idx = ORDER_BASE - ts;
            JSONObject payload = ss[i].payload;
            if (!payload.has("event_id")) {
                try {
                    payload.put("event_id", ss[i].id);
                } catch (Exception ignored) {}
            }
            db.putIgnore(new Item("post", ss[i].id, "" + idx, payload));
        }

        log("ready");
    }

    String getTag(Element tag, String name) {
        NodeList l = tag.getElementsByTagName(name);
        if (l == null || l.getLength() == 0) return null;
        if (!(l.item(0) instanceof Element)) return null;
        return ((Element) l.item(0)).getTextContent();
    }

    private void syncFriends() {
        ArrayList<String> friends = new ArrayList<>();
        try {
            ItemDatabase db = ItemDatabase.getInstance(context);
            ItemResult itemResult = db.get("friend", "", 64);
            for (int i = 0; i < itemResult.size(); i++) {
                String addr = itemResult.at(i).key();
                if (addr != null && !addr.trim().isEmpty()) {
                    friends.add(addr.trim().toLowerCase());
                }
            }
        } catch (Exception ex) {
            log("load friends err " + ex.getMessage());
        }
        if (friends.isEmpty()) {
            return;
        }
        String self = TorManager.getInstance(context).getID();
        for (String addr : friends) {
            if (addr.equals(self)) continue;
            for (String type : SYNC_TYPES) {
                new SyncItemTask(context, addr, type, "", SYNC_PAGE_SIZE).execute2();
            }
        }
    }

    private static class SyncItemTask extends ItemTask {
        private final ItemDatabase db;

        SyncItemTask(Context context, String address, String type, String index, int count) {
            super(context, address, type, index, count);
            this.db = ItemDatabase.getInstance(context);
        }

        @Override
        protected void onPostExecute(ItemResult itemResult) {
            if (itemResult == null || !itemResult.ok()) {
                return;
            }
            try {
                for (int i = 0; i < itemResult.size(); i++) {
                    db.put(itemResult.at(i));
                }
            } catch (Exception ex) {
                Log.w("WallBotSync", "store error " + ex.getMessage());
            }
        }
    }

    private static class RssEvent {
        final String id;
        final JSONObject payload;
        final long timestamp;

        RssEvent(String id, JSONObject payload, long timestamp) {
            this.id = id;
            this.payload = payload;
            this.timestamp = timestamp;
        }
    }

}
