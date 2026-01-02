package onion.network.models;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import onion.network.helpers.Utils;

/**
 * Локальний голосовий агент: приймає розпізнаний текст, формує VOICE_IN/VOICE_OUT події,
 * говорить відповідь через системний TTS. STT підключається окремо та викликає handleVoiceInput().
 */
public class VoiceAgent {

    private static final String TAG = "VoiceAgent";
    private static final String DEFAULT_VOSK_MODEL = "vosk-model-small-en-us-0.15.zip";
    private static VoiceAgent instance;

    private final Context context;
    private TextToSpeech tts;
    private final AtomicBoolean ttsReady = new AtomicBoolean(false);

    private VoiceAgent(Context context) {
        this.context = context.getApplicationContext();
        initTts();
    }

    public static synchronized VoiceAgent getInstance(Context context) {
        if (instance == null) {
            instance = new VoiceAgent(context);
        }
        return instance;
    }

    private void initTts() {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.getDefault());
                ttsReady.set(r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED);
                Log.i(TAG, "TTS ready: " + ttsReady.get());
            } else {
                Log.w(TAG, "TTS init failed: " + status);
            }
        });
    }

    /**
     * Викликається модулем STT після розпізнавання тексту.
     */
    public void handleVoiceInput(String text, String trace) {
        if (text == null) text = "";
        String traceId = trace == null || trace.trim().isEmpty() ? UUID.randomUUID().toString() : trace.trim();

        try {
            JSONObject payloadIn = new JSONObject();
            payloadIn.put("type", "VOICE_IN");
            payloadIn.put("trace", traceId);
            payloadIn.put("text", text);
            payloadIn.put("audio_key", "");
            MemoryStream.appendEvent(context, "VOICE_IN", payloadIn);
        } catch (Exception ex) {
            Log.w(TAG, "VOICE_IN append failed: " + ex.getMessage());
        }

        String reply = simplePolicy(text);
        speakAndRecord(reply, traceId);
    }

    private String simplePolicy(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "Я слухаю.";
        }
        return "Ви сказали: " + text;
    }

    private void speakAndRecord(String reply, String trace) {
        try {
            JSONObject payloadOut = new JSONObject();
            payloadOut.put("type", "VOICE_OUT");
            payloadOut.put("trace", trace);
            payloadOut.put("text", reply);
            payloadOut.put("audio_key", "");
            MemoryStream.appendEvent(context, "VOICE_OUT", payloadOut);
        } catch (Exception ex) {
            Log.w(TAG, "VOICE_OUT append failed: " + ex.getMessage());
        }

        if (ttsReady.get() && reply != null && !reply.trim().isEmpty()) {
            try {
                tts.speak(reply, TextToSpeech.QUEUE_ADD, null, "voice-agent-" + Utils.sha1Digest(reply));
            } catch (Exception ex) {
                Log.w(TAG, "TTS speak failed: " + ex.getMessage());
            }
        }
    }

    /**
     * Запуск прослуховування через Vosk (потребує моделі в assets).
     * @param modelAssetName ім'я каталогу моделі в assets (наприклад, "vosk-model-small-uk-v3")
     */
    public boolean startVoskListening(String modelAssetName, String trace) {
        String name = modelAssetName == null || modelAssetName.trim().isEmpty() ? DEFAULT_VOSK_MODEL : modelAssetName.trim();
        return VoskSttEngine.getInstance(context).startListening(name, trace);
    }

    /**
     * Запуск з дефолтною моделлю (зараз vosk-model-small-en-us-0.15.zip у assets).
     */
    public boolean startVoskListening(String trace) {
        return startVoskListening(DEFAULT_VOSK_MODEL, trace);
    }

    public void stopVoskListening() {
        VoskSttEngine.getInstance(context).stop();
    }
}
