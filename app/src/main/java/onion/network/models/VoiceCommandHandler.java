package onion.network.models;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

/**
 * Гачок для локального розпізнавання голосу: передаємо розпізнаний текст,
 * він перетворюється на подію у memory stream (і може бути розширений для STT-двигуна).
 */
public class VoiceCommandHandler {

    private static final String TAG = "VoiceCommandHandler";

    public static void recordRecognizedText(Context context, String text) {
        if (context == null) return;
        if (TextUtils.isEmpty(text)) {
            Log.w(TAG, "empty voice text");
            return;
        }
        VoiceAgent.getInstance(context).handleVoiceInput(text, null);
    }
}
