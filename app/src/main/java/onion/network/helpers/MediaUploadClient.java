package onion.network.helpers;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import onion.network.clients.HttpClient;

import android.net.Uri;

public final class MediaUploadClient {

    private MediaUploadClient() {
    }

    public static final class Result {
        public final String id;
        public final String mime;
        public final long size;

        public Result(String id, String mime, long size) {
            this.id = id;
            this.mime = mime;
            this.size = size;
        }
    }

    public static Result upload(String host, byte[] data, String mime) throws IOException, JSONException {
        if (TextUtils.isEmpty(host)) {
            throw new IOException("Missing upload host");
        }
        if (data == null || data.length == 0) {
            throw new IOException("Empty media payload");
        }
        if (mime == null) {
            mime = "application/octet-stream";
        }
        String normalized = host.startsWith("http") ? host : "http://" + host;
        if (!normalized.endsWith("/upload")) {
            if (!normalized.endsWith("/")) {
                normalized = normalized + "/";
            }
            normalized = normalized + "upload";
        }
        Uri uri = Uri.parse(normalized);
        byte[] responseBytes = HttpClient.postbin(uri, data, mime);
        String responseText = new String(responseBytes, StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(responseText);
        if (!"ok".equalsIgnoreCase(json.optString("status"))) {
            throw new IOException("Upload failed: " + json.optString("message"));
        }
        String id = json.optString("id");
        if (TextUtils.isEmpty(id)) {
            throw new IOException("Upload failed: missing id");
        }
        String returnedMime = json.optString("mime", mime);
        long size = json.optLong("size", data.length);
        return new Result(id, returnedMime, size);
    }
}
