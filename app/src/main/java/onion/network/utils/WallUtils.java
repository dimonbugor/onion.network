package onion.network.utils;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;

import android.util.Base64;

public final class WallUtils {
    private WallUtils() {}

    public static Bitmap scaleBitmap(Bitmap bmp, int maxDim) {
        if (bmp == null) return null;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w <= maxDim && h <= maxDim) return bmp;
        if (w >= h) {
            int nw = maxDim; int nh = (int)((double)h * maxDim / w);
            return Bitmap.createScaledBitmap(bmp, nw, nh, true);
        } else {
            int nh = maxDim; int nw = (int)((double)w * maxDim / h);
            return Bitmap.createScaledBitmap(bmp, nw, nh, true);
        }
    }

    public static long parseDuration(String v) {
        if (TextUtils.isEmpty(v)) return 0L;
        try { return Long.parseLong(v); } catch (NumberFormatException ex) { return 0L; }
    }

    public static Bitmap decodeBitmapBase64(String enc) {
        if (TextUtils.isEmpty(enc)) return null;
        try {
            byte[] b = Base64.decode(enc.trim(), Base64.DEFAULT);
            if (b.length == 0) return null;
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (IllegalArgumentException ex) { return null; }
    }

    public static String firstNonEmpty(String... vals) {
        if (vals == null) return "";
        for (String v : vals) if (!TextUtils.isEmpty(v)) return v;
        return "";
    }

    public static String emptyToNull(String s) { return TextUtils.isEmpty(s) ? null : s; }

    public static String resolveMimeFromUri(Context ctx, Uri uri) {
        String mime = ctx.getContentResolver().getType(uri);
        if (!TextUtils.isEmpty(mime)) return mime;
        String ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
        return TextUtils.isEmpty(ext) ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    }

    public static JSONObject parseJsonSafe(String data) {
        try { return data == null ? new JSONObject() : new JSONObject(data); }
        catch (JSONException ex) { return new JSONObject(); }
    }

    public static Bitmap getActivityResultBitmap(Context ctx, Intent data) throws IOException {
        if (data == null) return null;
        Uri uri = data.getData(); if (uri == null) return null;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(is);
        }
    }

    public static Bitmap fixImageOrientation(Context ctx, Bitmap src, Uri uri) {
        if (src == null || uri == null) return src;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return src;
            ExifInterface exif = new ExifInterface(in);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
            int deg = 0;
            if (o == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            if (deg == 0) return src;
            android.graphics.Matrix m = new android.graphics.Matrix(); m.postRotate(deg);
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Exception ignore) { return src; }
    }
}
