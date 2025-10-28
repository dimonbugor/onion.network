package onion.network.models;

import android.graphics.Bitmap;
import android.text.TextUtils;

import org.json.JSONObject;

import onion.network.helpers.Ed25519Signature;
import onion.network.helpers.Utils;

public final class PostDraft {
    public String text;
    public Bitmap image;
    public byte[] videoData;
    public Bitmap videoThumb;
    public String videoMime;
    public long videoDurationMs;
    public String videoMediaId;
    public byte[] audioData;
    public String audioMime;
    public long audioDurationMs;
    public String audioMediaId;

    public PostDraft copy() {
        PostDraft c = new PostDraft();
        c.text = text;
        c.image = image;
        c.videoData = videoData;
        c.videoThumb = videoThumb;
        c.videoMime = videoMime;
        c.videoDurationMs = videoDurationMs;
        c.videoMediaId = videoMediaId;
        c.audioData = audioData;
        c.audioMime = audioMime;
        c.audioDurationMs = audioDurationMs;
        c.audioMediaId = audioMediaId;
        return c;
    }

    public static PostDraft fromItem(Item item) {
        PostDraft d = new PostDraft();
        if (item == null) return d;
        try {
            JSONObject data = item.json();
            d.text = data.optString("text", "");
            Bitmap bmp = item.bitmap("img");
            if (bmp != null) d.image = bmp;
            d.videoMediaId = data.optString("video_id", null);
            if (!TextUtils.isEmpty(d.videoMediaId)) {
                d.videoMime = data.optString("video_mime", null);
                d.videoDurationMs = data.optLong("video_duration", 0L);
                String t = data.optString("video_thumb", "").trim();
                if (!TextUtils.isEmpty(t)) d.videoThumb = Utils.decodeImage(t);
            } else {
                String vb64 = data.optString("video", "").trim();
                if (!TextUtils.isEmpty(vb64)) {
                    try {
                        d.videoData = Ed25519Signature.base64Decode(vb64);
                    } catch (Exception ignore) {
                        d.videoData = null;
                    }
                    String t = data.optString("video_thumb", "").trim();
                    if (!TextUtils.isEmpty(t)) d.videoThumb = Utils.decodeImage(t);
                    d.videoMime = data.optString("video_mime", null);
                    d.videoDurationMs = data.optLong("video_duration", 0L);
                }
            }
            String audioId = data.optString("audio_id", null);
            if (!TextUtils.isEmpty(audioId)) {
                d.audioMediaId = audioId;
                d.audioMime = data.optString("audio_mime", "audio/mp4");
                d.audioDurationMs = data.optLong("audio_duration", 0L);
            } else {
                String ab64 = data.optString("audio", "").trim();
                if (!TextUtils.isEmpty(ab64)) {
                    try {
                        d.audioData = Ed25519Signature.base64Decode(ab64);
                    } catch (Exception ignore) {
                        d.audioData = null;
                    }
                    d.audioMime = data.optString("audio_mime", "audio/mp4");
                    d.audioDurationMs = data.optLong("audio_duration", 0L);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return d;
    }

    public void clearImage() {
        image = null;
    }

    public void clearVideo() {
        videoData = null;
        videoThumb = null;
        videoMime = null;
        videoDurationMs = 0L;
        videoMediaId = null;
    }

    public void clearAudio() {
        audioData = null;
        audioMime = null;
        audioDurationMs = 0L;
        audioMediaId = null;
    }
}
