package onion.network.models;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Обгортка над Vosk для локального STT. Потребує моделі в assets (наприклад, vosk-model-small-uk-v3).
 */
public class VoskSttEngine implements RecognitionListener {

    private static final String TAG = "VoskSttEngine";
    private static VoskSttEngine instance;

    private final Context context;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private Model model;
    private SpeechService speechService;
    private String currentTrace = "";

    private VoskSttEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized VoskSttEngine getInstance(Context context) {
        if (instance == null) {
            instance = new VoskSttEngine(context);
        }
        return instance;
    }

    public void ensureModel(String assetModelName) {
        if (ready.get()) return;
        StorageService.unpack(context, assetModelName, "vosk-model", (m) -> {
            model = m;
            ready.set(true);
            Log.i(TAG, "Vosk model ready: " + assetModelName);
        }, (e) -> {
            ready.set(false);
            Log.e(TAG, "Failed to unpack Vosk model: " + e.getMessage());
        });
    }

    public boolean startListening(String assetModelName, String trace) {
        ensureModel(assetModelName);
        if (!ready.get() || model == null) {
            Log.w(TAG, "Vosk model not ready");
            return false;
        }
        stop();
        try {
            Recognizer rec = new Recognizer(model, 16000.f);
            speechService = new SpeechService(rec, 16000.f);
            currentTrace = trace == null ? "" : trace;
            speechService.startListening(this);
            Log.i(TAG, "Vosk listening started");
            return true;
        } catch (Exception ex) {
            Log.e(TAG, "startListening failed: " + ex.getMessage());
            return false;
        }
    }

    public void stop() {
        try {
            if (speechService != null) {
                speechService.stop();
                speechService = null;
                Log.i(TAG, "Vosk listening stopped");
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        // no-op
    }

    @Override
    public void onResult(String hypothesis) {
        handleHypothesis(hypothesis, false);
    }

    @Override
    public void onFinalResult(String hypothesis) {
        handleHypothesis(hypothesis, true);
    }

    @Override
    public void onError(Exception e) {
        Log.e(TAG, "Vosk error: " + e.getMessage());
        stop();
    }

    @Override
    public void onTimeout() {
        stop();
    }

    private void handleHypothesis(String hypothesis, boolean isFinal) {
        if (hypothesis == null) return;
        try {
            JSONObject o = new JSONObject(hypothesis);
            String text = o.optString("text", "").trim();
            if (!text.isEmpty() && isFinal) {
                VoiceAgent.getInstance(context).handleVoiceInput(text, currentTrace);
            }
        } catch (Exception ex) {
            Log.w(TAG, "Failed to parse Vosk hypothesis: " + ex.getMessage());
        }
    }
}
