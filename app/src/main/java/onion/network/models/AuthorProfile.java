package onion.network.models;

import android.graphics.Bitmap;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import onion.network.helpers.Utils;
import onion.network.utils.WallUtils;

/**
 * Lightweight container describing the public profile information that should travel with posts.
 * Stores everything as base64 strings / plain text to keep serialization simple and allows
 * computing a stable revision hash so we can avoid re-sending unchanged blobs.
 */
public class AuthorProfile {

    private final String address;
    private String name;
    private String thumbBase64;
    private String videoBase64;
    private String videoUri;
    private String videoThumbBase64;
    private String revision;

    public AuthorProfile(String address,
                         String name,
                         String thumbBase64,
                         String videoBase64,
                         String videoUri,
                         String videoThumbBase64,
                         String revision) {
        this.address = address;
        this.name = emptyToNull(name);
        this.thumbBase64 = emptyToNull(thumbBase64);
        this.videoBase64 = emptyToNull(videoBase64);
        this.videoUri = emptyToNull(videoUri);
        this.videoThumbBase64 = emptyToNull(videoThumbBase64);
        this.revision = emptyToNull(revision);
    }

    public String getAddress() {
        return address;
    }

    public String getName() {
        return name;
    }

    public String getThumbBase64() {
        return thumbBase64;
    }

    public String getVideoBase64() {
        return videoBase64;
    }

    public String getVideoUri() {
        return videoUri;
    }

    public String getVideoThumbBase64() {
        return videoThumbBase64;
    }

    public String getRevision() {
        return revision;
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(name)
                && TextUtils.isEmpty(thumbBase64)
                && TextUtils.isEmpty(videoBase64)
                && TextUtils.isEmpty(videoUri)
                && TextUtils.isEmpty(videoThumbBase64);
    }

    /**
     * Calculates a stable revision hash and stores it on this instance if absent.
     */
    public String ensureRevision() {
        if (!TextUtils.isEmpty(revision)) {
            return revision;
        }
        revision = computeRevision();
        return revision;
    }

    private String computeRevision() {
        JSONObject obj = new JSONObject();
        try {
            if (!TextUtils.isEmpty(name)) obj.put("name", name);
            if (!TextUtils.isEmpty(thumbBase64)) obj.put("thumb", thumbBase64);
            if (!TextUtils.isEmpty(videoBase64)) obj.put("video", videoBase64);
            if (!TextUtils.isEmpty(videoUri)) obj.put("video_uri", videoUri);
            if (!TextUtils.isEmpty(videoThumbBase64)) obj.put("video_thumb", videoThumbBase64);
        } catch (JSONException ignore) {
        }
        String payload = obj.toString();
        return Utils.sha256Base64(payload.getBytes(Utils.UTF_8));
    }

    public JSONObject toCacheJson() {
        JSONObject obj = new JSONObject();
        try {
            if (!TextUtils.isEmpty(name)) obj.put("name", name);
            if (!TextUtils.isEmpty(thumbBase64)) obj.put("thumb", thumbBase64);
            if (!TextUtils.isEmpty(videoBase64)) obj.put("video", videoBase64);
            if (!TextUtils.isEmpty(videoUri)) obj.put("video_uri", videoUri);
            if (!TextUtils.isEmpty(videoThumbBase64)) obj.put("video_thumb", videoThumbBase64);
            String rev = ensureRevision();
            if (!TextUtils.isEmpty(rev)) obj.put("rev", rev);
        } catch (JSONException ignore) {
        }
        return obj;
    }

    public void applyToJson(JSONObject target) {
        if (target == null) return;
        applyToJsonInternal(target, false);
    }

    public void applyToJsonIfMissing(JSONObject target) {
        if (target == null) return;
        applyToJsonInternal(target, true);
    }

    private void applyToJsonInternal(JSONObject target, boolean onlyWhenMissing) {
        try {
            if (!TextUtils.isEmpty(name) && (!onlyWhenMissing || isMissing(target, "author_name"))) {
                target.put("author_name", name);
            }
            if (!TextUtils.isEmpty(name) && (!onlyWhenMissing || isMissing(target, "name"))) {
                target.put("name", name);
            }
            if (!TextUtils.isEmpty(thumbBase64) && (!onlyWhenMissing || isMissing(target, "author_thumb"))) {
                target.put("author_thumb", thumbBase64);
            }
            if (!TextUtils.isEmpty(thumbBase64) && (!onlyWhenMissing || isMissing(target, "thumb"))) {
                target.put("thumb", thumbBase64);
            }
            if (!TextUtils.isEmpty(videoThumbBase64) && (!onlyWhenMissing || isMissing(target, "author_video_thumb"))) {
                target.put("author_video_thumb", videoThumbBase64);
            }
            if (!TextUtils.isEmpty(videoBase64) && (!onlyWhenMissing || isMissing(target, "author_video"))) {
                target.put("author_video", videoBase64);
            }
            if (!TextUtils.isEmpty(videoUri) && (!onlyWhenMissing || isMissing(target, "author_video_uri"))) {
                target.put("author_video_uri", videoUri);
            }
            String rev = ensureRevision();
            if (!onlyWhenMissing || isMissing(target, "author_rev")) {
                target.put("author_rev", rev);
            }
        } catch (JSONException ignore) {
        }
    }

    private static boolean isMissing(JSONObject obj, String key) {
        return obj == null || TextUtils.isEmpty(obj.optString(key, "").trim());
    }

    public Bitmap decodeThumbBitmap() {
        return WallUtils.decodeBitmapBase64(thumbBase64);
    }

    public Bitmap decodeVideoThumbBitmap() {
        return WallUtils.decodeBitmapBase64(videoThumbBase64);
    }

    public static AuthorProfile fromPostJson(String address, JSONObject json) {
        if (json == null) {
            return null;
        }
        String name = firstNonEmpty(
                json.optString("author_name", ""),
                json.optString("name", "")
        );
        String thumb = firstNonEmpty(
                json.optString("author_thumb", ""),
                json.optString("thumb", "")
        );
        String videoThumb = firstNonEmpty(
                json.optString("author_video_thumb", ""),
                json.optString("video_thumb", "")
        );
        String video = firstNonEmpty(
                json.optString("author_video", ""),
                json.optString("video", "")
        );
        String videoUri = firstNonEmpty(
                json.optString("author_video_uri", ""),
                json.optString("video_uri", "")
        );
        String rev = json.optString("author_rev", "");

        AuthorProfile profile = new AuthorProfile(address, name, thumb, video, videoUri, videoThumb, rev);
        if (profile.isEmpty()) {
            return null;
        }
        profile.ensureRevision();
        return profile;
    }

    public static AuthorProfile fromCache(String address, JSONObject json) {
        if (json == null) return null;
        return new AuthorProfile(
                address,
                json.optString("name", ""),
                json.optString("thumb", ""),
                json.optString("video", ""),
                json.optString("video_uri", ""),
                json.optString("video_thumb", ""),
                json.optString("rev", "")
        );
    }

    public static AuthorProfile merge(AuthorProfile base, AuthorProfile fallback) {
        if (base == null) {
            return fallback;
        }
        if (fallback == null) {
            return base;
        }
        return new AuthorProfile(
                base.address,
                firstNonEmpty(base.name, fallback.name),
                firstNonEmpty(base.thumbBase64, fallback.thumbBase64),
                firstNonEmpty(base.videoBase64, fallback.videoBase64),
                firstNonEmpty(base.videoUri, fallback.videoUri),
                firstNonEmpty(base.videoThumbBase64, fallback.videoThumbBase64),
                firstNonEmpty(base.revision, fallback.revision)
        );
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (!TextUtils.isEmpty(v)) return v;
        }
        return null;
    }

    private static String emptyToNull(String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }
}
