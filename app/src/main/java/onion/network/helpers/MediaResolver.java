package onion.network.helpers;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import java.io.File;

public final class MediaResolver {

    private MediaResolver() {
    }

    public static File resolveMediaFile(Context context, String mediaId) {
        if (context == null || TextUtils.isEmpty(mediaId)) {
            return null;
        }
        StreamMediaStore.MediaDescriptor descriptor = StreamMediaStore.get(context, mediaId);
        if (descriptor != null && descriptor.file != null && descriptor.file.exists()) {
            return descriptor.file;
        }
        return null;
    }

    public static Uri resolveMediaUri(Context context, String mediaId) {
        if (context == null || TextUtils.isEmpty(mediaId)) {
            return null;
        }
        StreamMediaStore.MediaDescriptor descriptor = StreamMediaStore.get(context, mediaId);
        if (descriptor == null || descriptor.file == null || !descriptor.file.exists()) {
            return null;
        }
        return StreamMediaStore.createContentUri(context, mediaId);
    }
}
