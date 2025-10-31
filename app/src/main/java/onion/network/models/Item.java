

package onion.network.models;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import onion.network.TorManager;
import onion.network.helpers.AuthorProfileCache;
import onion.network.cashes.ItemCache;
import onion.network.databases.ItemDatabase;
import onion.network.models.AuthorProfile;
import onion.network.helpers.Utils;
import onion.network.settings.Settings;

public class Item {

    private String _type;
    private String _key;
    private String _index;
    private byte[] _data;

    public Item() {
        this("", "", "", "");
    }

    public Item(String type, String key, String index, byte[] data) {
        _type = type;
        _key = key;
        _index = index;
        _data = data;
    }

    private Item(String type, String key, String index, String data) {
        this(type, key, index, data == null ? new byte[0] : data.getBytes(Utils.UTF_8));
    }

    public Item(String type, String key, String index, JSONObject json) {
        this(type, key, index, json.toString());
    }

    public String type() {
        return _type;
    }

    public String key() {
        return _key;
    }

    public String index() {
        return _index;
    }

    public byte[] data() {
        return _data;
    }

    public String text() {
        return new String(_data, Utils.UTF_8);
    }

    public JSONObject json() {
        try {
            return new JSONObject(text());
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    public JSONObject json(Context context, String address) {

        if (address == null) {
            address = "";
        }
        if (address.equals(TorManager.getInstance(context).getID())) {
            address = "";
        }
        address = address.trim();

        JSONObject json = json();

        if ("post".equals(_type)) {

            // ensure the address is filled in for locally authored posts
            if (!json.has("addr") || json.optString("addr").trim().isEmpty()) {
                if ("".equals(address)) {
                    address = TorManager.getInstance(context).getID();
                }
                try {
                    json.put("addr", address);
                } catch (JSONException ex) {
                    throw new RuntimeException(ex);
                }
            }

            String authorAddress = json.optString("addr").trim();
            String myId = TorManager.getInstance(context).getID();

            AuthorProfile profileFromPayload = AuthorProfile.fromPostJson(authorAddress, json);

            if (TextUtils.equals(authorAddress, myId)) {
                AuthorProfile selfProfile = AuthorProfileCache.loadSelfProfile(context);
                if (selfProfile != null && !selfProfile.isEmpty()) {
                    selfProfile.applyToJson(json);
                    AuthorProfileCache.store(context, selfProfile);
                }
            } else {
                AuthorProfile resolved = AuthorProfileCache.ensureProfile(context, authorAddress, profileFromPayload);
                if (resolved != null) {
                    resolved.applyToJsonIfMissing(json);
                }
            }

            AuthorProfile finalProfile = AuthorProfile.fromPostJson(authorAddress, json);
            if (finalProfile != null && !finalProfile.isEmpty()) {
                finalProfile.ensureRevision();
                try {
                    json.put("author_rev", finalProfile.getRevision());
                } catch (JSONException ignore) {
                }
                AuthorProfileCache.store(context, finalProfile);
            } else if (!TextUtils.isEmpty(authorAddress)) {
                AuthorProfile cached = AuthorProfileCache.get(context, authorAddress);
                if (cached != null) {
                    cached.applyToJsonIfMissing(json);
                    cached.ensureRevision();
                    try {
                        json.put("author_rev", cached.getRevision());
                    } catch (JSONException ignore) {
                    }
                }
            }
        }

        // update friend
        if ("friend".equals(_type)) {

            json.remove("video_uri");

            // thumb
            {
                ItemResult rs = ItemCache.getInstance(context).get(json.optString("addr"), "thumb");
                if (rs.size() > 0) {
                    String thumb = rs.one().json().optString("thumb");
                    try {
                        json.put("thumb", thumb);
                    } catch (JSONException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

            // name
            {
                ItemResult rs = ItemCache.getInstance(context).get(json.optString("addr"), "name");
                if (rs.size() > 0) {
                    String name = rs.one().json().optString("name");
                    try {
                        json.put("name", name);
                    } catch (JSONException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

            // video (looping avatar)
            {
                ItemResult rs = ItemCache.getInstance(context).get(json.optString("addr"), "video");
                if (rs.size() > 0) {
                    String video = rs.one().json().optString("video");
                    if (video != null && !video.trim().isEmpty()) {
                        try {
                            json.put("video", video);
                        } catch (JSONException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                }
            }

            // video thumbnail for quick previews
            {
                ItemResult rs = ItemCache.getInstance(context).get(json.optString("addr"), "video_thumb");
                if (rs.size() > 0) {
                    String videoThumb = rs.one().json().optString("video_thumb");
                    if (videoThumb != null && !videoThumb.trim().isEmpty()) {
                        try {
                            json.put("video_thumb", videoThumb);
                        } catch (JSONException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                }
            }

        }

        // set flags if it's my meta
        if ("".equals(address)) {
            if ("name".equals(_type) || "info".equals(_type)) {
                json.remove("nochat");
                json.remove("friendbot");

                String acceptmessages = Settings.getPrefs(context).getString("acceptmessages", "");
                boolean chatbotmode = Settings.getPrefs(context).getBoolean("chatbot", false);
                if ("none".equals(acceptmessages) && chatbotmode == false) {
                    try {
                        json.put("nochat", true);
                    } catch (JSONException ex) {
                        ex.printStackTrace();
                    }
                }

                if (Settings.getPrefs(context).getBoolean("friendbot", false)) {
                    try {
                        json.put("friendbot", true);
                    } catch (JSONException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

        _data = json.toString().getBytes(Utils.UTF_8);

        return json;

    }


    public Bitmap bitmap(String key) {
        try {
            String str = json().optString(key);
            str = str.trim();
            if (str.isEmpty()) return null;
            byte[] photodata = Base64.decode(str, Base64.DEFAULT);
            if (photodata.length == 0) return null;
            return BitmapFactory.decodeByteArray(photodata, 0, photodata.length);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }


    public static class Builder {
        private JSONObject o = new JSONObject();
        private String type, key, index;

        public Builder(String type, String key, String index) {
            this.type = type;
            this.key = key;
            this.index = index;
        }

        public Builder put(String key, String val) {
            try {
                o.put(key, val);
            } catch (JSONException ex) {
                throw new RuntimeException(ex);
            }
            return this;
        }

        public Item build() {
            return new Item(type, key, index, o);
        }
    }


}








