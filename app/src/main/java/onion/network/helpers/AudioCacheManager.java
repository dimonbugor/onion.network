package onion.network.helpers;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class AudioCacheManager {

    private static final String CACHE_DIR = "audio_cache";

    private AudioCacheManager() {
    }

    @Nullable
    public static Uri ensureAudioUri(Context context,
                                     @Nullable String ownerKey,
                                     @Nullable String storedUri,
                                     @Nullable String audioDataBase64,
                                     @Nullable String mime) {
        Uri decoded = decodeToCacheIfNeeded(context, ownerKey, audioDataBase64, mime);
        if (decoded != null) {
            return decoded;
        }
        Uri parsed = tryParsePlayableUri(storedUri);
        if (parsed != null && isUriReadable(context, parsed)) {
            return parsed;
        }
        return null;
    }

    @Nullable
    private static Uri tryParsePlayableUri(@Nullable String uriStr) {
        if (TextUtils.isEmpty(uriStr)) return null;
        try {
            Uri uri = Uri.parse(uriStr);
            String scheme = uri.getScheme();
            if ("content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)) {
                return uri;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    @Nullable
    private static Uri decodeToCacheIfNeeded(Context context,
                                             @Nullable String ownerKey,
                                             @Nullable String audioDataBase64,
                                             @Nullable String mime) {
        if (TextUtils.isEmpty(audioDataBase64)) {
            return null;
        }
        try {
            byte[] data = Ed25519Signature.base64Decode(audioDataBase64);
            if (data == null || data.length == 0) {
                return null;
            }
            File file = writeCacheFile(context, ownerKey, data, mime);
            if (file == null) {
                return null;
            }
            return FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    file);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    @Nullable
    private static File writeCacheFile(Context context,
                                       @Nullable String ownerKey,
                                       byte[] data,
                                       @Nullable String mime) throws IOException {
        File dir = new File(context.getCacheDir(), CACHE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String safe = (ownerKey == null || ownerKey.isEmpty())
                ? "self"
                : ownerKey.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        String extension = resolveExtension(mime);
        File file = new File(dir, safe + extension);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(data);
            out.flush();
        }
        return file;
    }

    private static String resolveExtension(@Nullable String mime) {
        if (mime == null) {
            return ".m4a";
        }
        String lower = mime.toLowerCase();
        if (lower.contains("wav")) {
            return ".wav";
        }
        if (lower.contains("ogg")) {
            return ".ogg";
        }
        if (lower.contains("mp3")) {
            return ".mp3";
        }
        return ".m4a";
    }

    private static boolean isUriReadable(Context context, @Nullable Uri uri) {
        if (uri == null) return false;
        try (android.os.ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            return pfd != null;
        } catch (Exception ex) {
            return false;
        }
    }
}
