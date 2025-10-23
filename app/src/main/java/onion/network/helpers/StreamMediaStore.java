package onion.network.helpers;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class StreamMediaStore {

    private StreamMediaStore() {
    }

    public static final long MAX_MEDIA_BYTES = 10L * 1024L * 1024L;
    private static final String DIRECTORY = "media_store";
    private static final String META_SUFFIX = ".json";
    private static final String DATA_SUFFIX = ".bin";

    public static final class MediaDescriptor {
        public final String id;
        public final String mime;
        public final File file;
        public final long size;

        MediaDescriptor(String id, String mime, File file, long size) {
            this.id = id;
            this.mime = mime;
            this.file = file;
            this.size = size;
        }
    }

    public static MediaDescriptor save(Context context, byte[] data, String mime) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("Empty media payload");
        }
        if (data.length > MAX_MEDIA_BYTES) {
            throw new IOException("Media payload exceeds limit");
        }
        return save(context, new ByteArrayInputStream(data), data.length, mime);
    }

    public static MediaDescriptor save(Context context, InputStream inputStream, long lengthHint, String mime) throws IOException {
        if (inputStream == null) {
            throw new IOException("Input stream null");
        }
        File dir = new File(context.getFilesDir(), DIRECTORY);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create media directory");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        File dataFile = new File(dir, id + DATA_SUFFIX);
        long written = copyToFile(inputStream, dataFile);
        if (written > MAX_MEDIA_BYTES) {
            dataFile.delete();
            throw new IOException("Media payload exceeds limit");
        }
        File metaFile = new File(dir, id + META_SUFFIX);
        JSONObject meta = new JSONObject();
        try {
            meta.put("mime", sanitizeMime(mime));
            meta.put("size", written);
        } catch (Exception ignore) {
        }
        try (FileWriter writer = new FileWriter(metaFile, false)) {
            writer.write(meta.toString());
        }
        return new MediaDescriptor(id, sanitizeMime(mime), dataFile, written);
    }

    public static MediaDescriptor get(Context context, String id) {
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        File dir = new File(context.getFilesDir(), DIRECTORY);
        File dataFile = new File(dir, id + DATA_SUFFIX);
        if (!dataFile.exists()) {
            return null;
        }
        String mime = "application/octet-stream";
        long size = dataFile.length();
        File metaFile = new File(dir, id + META_SUFFIX);
        if (metaFile.exists()) {
            try {
                String metaContent = new String(Utils.readFileAsBytes(metaFile), StandardCharsets.UTF_8);
                JSONObject meta = new JSONObject(metaContent);
                mime = meta.optString("mime", mime);
                size = meta.optLong("size", size);
            } catch (Exception ignore) {
            }
        }
        return new MediaDescriptor(id, mime, dataFile, size);
    }

    public static boolean delete(Context context, String id) {
        if (TextUtils.isEmpty(id)) {
            return false;
        }
        File dir = new File(context.getFilesDir(), DIRECTORY);
        File dataFile = new File(dir, id + DATA_SUFFIX);
        File metaFile = new File(dir, id + META_SUFFIX);
        boolean removed = false;
        if (dataFile.exists()) {
            removed = dataFile.delete();
        }
        if (metaFile.exists()) {
            metaFile.delete();
        }
        return removed;
    }

    public static Uri createContentUri(Context context, String mediaId) {
        MediaDescriptor descriptor = get(context, mediaId);
        if (descriptor == null || descriptor.file == null || !descriptor.file.exists()) {
            return null;
        }
        return FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                descriptor.file
        );
    }

    private static String sanitizeMime(String mime) {
        if (TextUtils.isEmpty(mime)) {
            return "application/octet-stream";
        }
        return mime;
    }

    private static long copyToFile(InputStream in, File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
                if (total > MAX_MEDIA_BYTES) {
                    break;
                }
            }
            out.flush();
            return total;
        }
    }
}
