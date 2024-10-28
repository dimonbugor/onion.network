package onion.network.helpers;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.Base64;
import android.util.Log;
import android.view.View;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import onion.network.R;

public class Utils {

    public static final Charset UTF_8 = Charset.forName("UTF-8");

    public static String base64Encode(byte[] data) {
        return data != null ? Base64.encodeToString(data, Base64.NO_WRAP) : "";
    }

    public static byte[] base64Decode(String str) {
        return Base64.decode(str, Base64.NO_WRAP);
    }

    public static byte[] readInputStream(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    public static String readInputStreamToString(InputStream is) throws IOException {
        StringBuilder result = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            result.append(line).append("\n");
        }
        return result.toString().trim(); // видаляємо зайві пробіли
    }

    public static String readFileAsString(File file) {
        return new String(readFileAsBytes(file), UTF_8);
    }

    public static byte[] readFileAsBytes(File file) {
        try {
            return readInputStream(new FileInputStream(file));
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static String formatDate(String timestampStr) {
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            return ""; // або можна повернути якусь стандартну дату
        }

        DateFormat sdf = new SimpleDateFormat("HH:mm yyyy-MM-dd");
        return sdf.format(new Date(timestamp));
    }

    public static String encodeImage(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.WEBP, 50, stream);
        Log.i("PHOTO SIZE", "" + stream.size());
        return base64Encode(stream.toByteArray());
    }

    public static Bitmap decodeImage(String str) {
        if (str != null && !str.trim().isEmpty()) {
            byte[] photodata = base64Decode(str);
            return BitmapFactory.decodeByteArray(photodata, 0, photodata.length);
        }
        return null;
    }

    public static String sha1Digest(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            if (input != null) {
                digest.update(input.getBytes(UTF_8));
            }
            return org.spongycastle.util.encoders.Hex.toHexString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static CharSequence linkify(final Context context, String text) {
        SpannableStringBuilder spannable = new SpannableStringBuilder(text);
        Linkify.addLinks(spannable, Linkify.WEB_URLS);
        for (URLSpan url : spannable.getSpans(0, spannable.length(), URLSpan.class)) {
            int start = spannable.getSpanStart(url);
            int end = spannable.getSpanEnd(url);
            ClickableSpan clickableSpan = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    new AlertDialog.Builder(context)
                            .setTitle(url.getURL())
                            .setMessage("Open link in external app?")
                            .setNegativeButton("No", null)
                            .setPositiveButton("Yes", (dialog, which) ->
                                    context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.getURL()))))
                            .show();
                }
            };
            spannable.setSpan(clickableSpan, start, end, spannable.getSpanFlags(url));
        }
        return spannable;
    }

    public static String getAppName(Context context) {
        return context.getString(R.string.app_name);
    }

    public static boolean isAlphanumeric(String s) {
        return s != null && s.chars().allMatch(Character::isLetterOrDigit);
    }
}