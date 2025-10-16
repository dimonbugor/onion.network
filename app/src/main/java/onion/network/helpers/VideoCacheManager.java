package onion.network.helpers;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import android.os.ParcelFileDescriptor;

import onion.network.helpers.Ed25519Signature;

public final class VideoCacheManager {

    private VideoCacheManager() {
    }

    @Nullable
    public static Uri ensureVideoUri(Context context,
                                     @Nullable String ownerKey,
                                     @Nullable String storedUri,
                                     @Nullable String videoDataBase64) {
        Uri uri = tryParsePlayableUri(storedUri);
        if (isUriReadable(context, uri)) {
            return uri;
        }
        if (TextUtils.isEmpty(videoDataBase64)) {
            return null;
        }
        try {
            byte[] data = Ed25519Signature.base64Decode(videoDataBase64);
            File file = writeCacheFile(context, ownerKey, data);
            if (file == null) return null;
            return FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider",
                    file);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
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
    private static File writeCacheFile(Context context, @Nullable String ownerKey, byte[] data) throws IOException {
        File dir = new File(context.getCacheDir(), "avatar_cache");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String safe = (ownerKey == null || ownerKey.isEmpty()) ? "self"
                : ownerKey.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        File file = new File(dir, safe + ".mp4");
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(data);
            out.flush();
        }
        return file;
    }

    private static boolean isUriReadable(Context context, @Nullable Uri uri) {
        if (uri == null) return false;
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            return pfd != null;
        } catch (Exception ex) {
            return false;
        }
    }
}
