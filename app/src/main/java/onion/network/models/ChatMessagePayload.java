package onion.network.models;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

public class ChatMessagePayload {

    public enum Type {
        TEXT("text"),
        IMAGE("image"),
        VIDEO("video"),
        AUDIO("audio");

        private final String key;

        Type(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Type fromKey(String key) {
            if (key == null) {
                return TEXT;
            }
            for (Type type : values()) {
                if (type.key.equalsIgnoreCase(key)) {
                    return type;
                }
            }
            return TEXT;
        }
    }

    public enum Storage {
        INLINE("inline"),
        FILE("file"),
        REFERENCE("ref");

        private final String key;

        Storage(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Storage fromKey(String key) {
            if (key == null) {
                return INLINE;
            }
            for (Storage storage : values()) {
                if (storage.key.equalsIgnoreCase(key)) {
                    return storage;
                }
            }
            return INLINE;
        }
    }

    private static final int VERSION = 1;

    private final Type type;
    private String text;
    private String mime;
    private String data; // (base64 or reference; populated in later phases)
    private long sizeBytes;
    private long durationMs;
    private String fileName;
    private String preview; // base64 thumbnail or waveform
    private Storage storage = Storage.INLINE;
    private String mediaId;

    private ChatMessagePayload(Type type) {
        this.type = type == null ? Type.TEXT : type;
    }

    public static ChatMessagePayload forText(String text) {
        ChatMessagePayload payload = new ChatMessagePayload(Type.TEXT);
        payload.text = text == null ? "" : text;
        return payload;
    }

    public static ChatMessagePayload forType(Type type) {
        return new ChatMessagePayload(type);
    }

    public Type getType() {
        return type;
    }

    public String getText() {
        return text == null ? "" : text;
    }

    public ChatMessagePayload setText(String value) {
        this.text = value;
        return this;
    }

    public String getMime() {
        return mime;
    }

    public ChatMessagePayload setMime(String value) {
        this.mime = value;
        return this;
    }

    public String getData() {
        return data;
    }

    public ChatMessagePayload setData(String value) {
        this.data = value;
        return this;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public ChatMessagePayload setSizeBytes(long value) {
        this.sizeBytes = value;
        return this;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public ChatMessagePayload setDurationMs(long value) {
        this.durationMs = value;
        return this;
    }

    public String getFileName() {
        return fileName;
    }

    public ChatMessagePayload setFileName(String value) {
        this.fileName = value;
        return this;
    }

    public String getPreview() {
        return preview;
    }

    public ChatMessagePayload setPreview(String value) {
        this.preview = value;
        return this;
    }

    public Storage getStorage() {
        return storage;
    }

    public ChatMessagePayload setStorage(Storage value) {
        if (value != null) {
            this.storage = value;
        }
        return this;
    }

    public boolean isInline() {
        return storage == Storage.INLINE;
    }

    public ChatMessagePayload copy() {
        return fromJson(this.toJson());
    }

    public String toStorageString() {
        return toJson().toString();
    }

    public String toNetworkString() {
        // For now identical to storage representation.
        return toStorageString();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("v", VERSION);
            o.put("type", type.key());
            o.put("storage", storage.key());
            if (!TextUtils.isEmpty(text)) {
                o.put("text", text);
            }
            if (!TextUtils.isEmpty(mime)) {
                o.put("mime", mime);
            }
            if (!TextUtils.isEmpty(data)) {
                o.put("data", data);
            }
            if (sizeBytes > 0) {
                o.put("size", sizeBytes);
            }
            if (durationMs > 0) {
                o.put("duration", durationMs);
            }
            if (!TextUtils.isEmpty(fileName)) {
                o.put("name", fileName);
            }
            if (!TextUtils.isEmpty(preview)) {
                o.put("preview", preview);
            }
            if (!TextUtils.isEmpty(mediaId)) {
                o.put("media_id", mediaId);
            }
        } catch (JSONException ignore) {
        }
        return o;
    }

    public static ChatMessagePayload fromStorageString(String raw) {
        if (raw == null) {
            return forText("");
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            return forText(raw);
        }
        try {
            JSONObject o = new JSONObject(trimmed);
            return fromJson(o);
        } catch (JSONException ex) {
            return forText(raw);
        }
    }

    public static ChatMessagePayload fromJson(JSONObject o) {
        if (o == null) {
            return forText("");
        }
        String typeKey = o.optString("type", Type.TEXT.key());
        ChatMessagePayload payload = new ChatMessagePayload(Type.fromKey(typeKey));
        payload.storage = Storage.fromKey(o.optString("storage", Storage.INLINE.key()));
        payload.text = o.optString("text", null);
        payload.mime = o.optString("mime", null);
        payload.data = o.optString("data", null);
        payload.sizeBytes = o.optLong("size", 0);
        payload.durationMs = o.optLong("duration", 0);
        payload.fileName = o.optString("name", null);
        payload.preview = o.optString("preview", null);
        payload.mediaId = o.optString("media_id", null);
        return payload;
    }

    public String getDisplayText() {
        if (type == Type.TEXT) {
            return getText();
        }
        switch (type) {
            case IMAGE:
                return "[Image]";
            case VIDEO:
                return "[Video]";
            case AUDIO:
                return "[Audio]";
            default:
                return "";
        }
    }

    public boolean isText() {
        return type == Type.TEXT;
    }

    public String getMediaId() {
        return mediaId;
    }

    public ChatMessagePayload setMediaId(String mediaId) {
        this.mediaId = mediaId;
        return this;
    }

    public boolean hasMediaReference() {
        return !TextUtils.isEmpty(mediaId);
    }
}
