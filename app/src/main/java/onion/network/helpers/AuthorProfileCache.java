package onion.network.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import onion.network.TorManager;
import onion.network.databases.ItemDatabase;
import onion.network.models.AuthorProfile;
import onion.network.models.Item;
import onion.network.models.ItemResult;
import onion.network.settings.Settings;

/**
 * Persists lightweight author profiles so we can avoid bundling the same avatar/name metadata
 * with every single post. Backed by SharedPreferences because the payload is tiny.
 */
public final class AuthorProfileCache {

    private static final String PREF_PREFIX = "author_profile_";

    private AuthorProfileCache() {
    }

    private static SharedPreferences prefs(Context context) {
        return Settings.getPrefs(context);
    }

    public static AuthorProfile get(Context context, String address) {
        if (context == null || TextUtils.isEmpty(address)) return null;
        String raw = prefs(context).getString(PREF_PREFIX + address, null);
        if (TextUtils.isEmpty(raw)) return null;
        try {
            JSONObject obj = new JSONObject(raw);
            return AuthorProfile.fromCache(address, obj);
        } catch (JSONException ex) {
            remove(context, address);
            return null;
        }
    }

    public static boolean store(Context context, AuthorProfile profile) {
        if (context == null || profile == null || TextUtils.isEmpty(profile.getAddress()) || profile.isEmpty()) {
            return false;
        }
        AuthorProfile existing = get(context, profile.getAddress());
        String newRev = profile.ensureRevision();
        if (existing != null && TextUtils.equals(existing.getRevision(), newRev)) {
            // Nothing new.
            return false;
        }
        prefs(context).edit().putString(PREF_PREFIX + profile.getAddress(), profile.toCacheJson().toString()).apply();
        return true;
    }

    public static void remove(Context context, String address) {
        if (context == null || TextUtils.isEmpty(address)) return;
        prefs(context).edit().remove(PREF_PREFIX + address).apply();
    }

    public static AuthorProfile ensureProfile(Context context, String address, AuthorProfile candidate) {
        if (candidate != null && !candidate.isEmpty()) {
            store(context, candidate);
            return candidate;
        }
        return get(context, address);
    }

    public static AuthorProfile loadSelfProfile(Context context) {
        if (context == null) return null;
        String myId = TorManager.getInstance(context).getID();
        ItemDatabase db = ItemDatabase.getInstance(context);

        String name = readFirstField(db.get("name", "", 1), "name");
        String thumb = readFirstField(db.get("thumb", "", 1), "thumb");
        String video = readFirstField(db.get("video", "", 1), "video");
        String videoUri = readFirstField(db.get("video", "", 1), "video_uri");
        String videoThumb = readFirstField(db.get("video_thumb", "", 1), "video_thumb");

        AuthorProfile profile = new AuthorProfile(myId, name, thumb, video, videoUri, videoThumb, null);
        if (!profile.isEmpty()) {
            store(context, profile);
        }
        return profile;
    }

    private static String readFirstField(ItemResult result, String field) {
        if (result == null || result.size() == 0) return null;
        Item item = result.one();
        if (item == null) return null;
        return item.json().optString(field, null);
    }
}
