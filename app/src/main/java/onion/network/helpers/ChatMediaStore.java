package onion.network.helpers;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

import onion.network.models.ChatMessagePayload;

public final class ChatMediaStore {

    private static final long MAX_MEDIA_BYTES = 10L * 1024L * 1024L;
    private static final String MEDIA_DIR = "chat_media";

    private ChatMediaStore() {
    }

    public static boolean exceedsLimit(long sizeBytes) {
        return sizeBytes > MAX_MEDIA_BYTES;
    }

    public static String saveIncoming(Context context,
                                      ChatMessagePayload.Type type,
                                      byte[] data,
                                      String mime) throws IOException {
        return saveInternal(context, type, data, mime, "in");
    }

    public static String saveOutgoing(Context context,
                                      ChatMessagePayload.Type type,
                                      byte[] data,
                                      String mime) throws IOException {
        return saveInternal(context, type, data, mime, "out");
    }

    private static String saveInternal(Context context,
                                       ChatMessagePayload.Type type,
                                       byte[] data,
                                       String mime,
                                       String directionTag) throws IOException {
        if (data == null || data.length == 0) {
            return null;
        }
        if (exceedsLimit(data.length)) {
            throw new IOException("Media payload exceeds 10MB limit");
        }
        File root = new File(context.getFilesDir(), MEDIA_DIR);
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Unable to create media directory");
        }
        String extension = resolveExtension(type, mime);
        String baseName = buildFileName(type, directionTag, extension);
        File out = new File(root, baseName);
        try (FileOutputStream fos = new FileOutputStream(out, false)) {
            fos.write(data);
            fos.flush();
        }
        return MEDIA_DIR + "/" + baseName;
    }

    public static File resolveFile(Context context, String relativePath) {
        if (TextUtils.isEmpty(relativePath)) {
            return null;
        }
        return new File(context.getFilesDir(), relativePath);
    }

    public static Uri createContentUri(Context context, String relativePath) {
        File file = resolveFile(context, relativePath);
        if (file == null || !file.exists()) {
            return null;
        }
        return FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                file
        );
    }

    private static String buildFileName(ChatMessagePayload.Type type,
                                        String directionTag,
                                        String extension) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String typeKey = type != null ? type.key() : "media";
        String dir = directionTag == null ? "med" : directionTag;
        return String.format(Locale.US, "%s_%s_%s.%s", dir, typeKey, uuid, extension);
    }

    private static String resolveExtension(ChatMessagePayload.Type type, String mime) {
        if (!TextUtils.isEmpty(mime)) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (!TextUtils.isEmpty(ext)) {
                return sanitizeExtension(ext);
            }
        }
        switch (type != null ? type : ChatMessagePayload.Type.TEXT) {
            case IMAGE:
                return "jpg";
            case VIDEO:
                return "mp4";
            case AUDIO:
                return "ogg";
            default:
                return "bin";
        }
    }

    private static String sanitizeExtension(String ext) {
        return ext.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.US);
    }
}
