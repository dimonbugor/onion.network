package onion.network.call;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import onion.network.models.ChatMessagePayload;

public final class CallSignalMessage {

    public enum SignalType {
        OFFER,
        ANSWER,
        CANDIDATE,
        RINGING,
        HANGUP,
        BUSY,
        ERROR
    }

    private static final int VERSION = 1;

    private final SignalType type;
    private final String callId;
    private final String sdp;
    private final String candidate;
    private final String sdpMid;
    private final Integer sdpMLineIndex;
    private final long timestamp;
    private final String error;

    private CallSignalMessage(SignalType type,
                              String callId,
                              String sdp,
                              String candidate,
                              String sdpMid,
                              Integer sdpMLineIndex,
                              long timestamp,
                              String error) {
        this.type = type;
        this.callId = callId;
        this.sdp = sdp;
        this.candidate = candidate;
        this.sdpMid = sdpMid;
        this.sdpMLineIndex = sdpMLineIndex;
        this.timestamp = timestamp;
        this.error = error;
    }

    public SignalType getType() {
        return type;
    }

    public String getCallId() {
        return callId;
    }

    public String getSdp() {
        return sdp;
    }

    public String getCandidate() {
        return candidate;
    }

    public String getSdpMid() {
        return sdpMid;
    }

    public Integer getSdpMLineIndex() {
        return sdpMLineIndex;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getError() {
        return error;
    }

    public JSONObject toJson() {
        JSONObject wrapper = new JSONObject();
        JSONObject obj = new JSONObject();
        try {
            obj.put("v", VERSION);
            obj.put("type", type.name().toLowerCase());
            if (!TextUtils.isEmpty(callId)) obj.put("id", callId);
            if (!TextUtils.isEmpty(sdp)) obj.put("sdp", sdp);
            if (!TextUtils.isEmpty(candidate)) obj.put("candidate", candidate);
            if (!TextUtils.isEmpty(sdpMid)) obj.put("mid", sdpMid);
            if (sdpMLineIndex != null) obj.put("mline", sdpMLineIndex);
            if (timestamp > 0L) obj.put("ts", timestamp);
            if (!TextUtils.isEmpty(error)) obj.put("error", error);
            wrapper.put("call", obj);
        } catch (JSONException ignore) {
        }
        return wrapper;
    }

    public String toTransportString() {
        return toJson().toString();
    }

    public static boolean isCallSignal(String text) {
        if (TextUtils.isEmpty(text)) return false;
        try {
            JSONObject obj = new JSONObject(text.trim());
            return obj.has("call");
        } catch (JSONException ex) {
            return false;
        }
    }

    public static CallSignalMessage fromPayload(ChatMessagePayload payload) {
        if (payload == null) {
            return null;
        }

        String candidateJson = null;
        String data = payload.getData();
        if (!TextUtils.isEmpty(data) && isCallSignal(data)) {
            candidateJson = data;
        } else if (payload.getType() == ChatMessagePayload.Type.TEXT) {
            String text = payload.getText();
            if (!TextUtils.isEmpty(text) && isCallSignal(text)) {
                candidateJson = text;
            }
        }
        if (candidateJson == null && payload.getType() == ChatMessagePayload.Type.TEXT) {
            String storage = payload.toStorageString();
            if (!TextUtils.isEmpty(storage) && isCallSignal(storage)) {
                candidateJson = storage;
            }
        }
        return candidateJson != null ? fromTransportString(candidateJson) : null;
    }

    public static CallSignalMessage fromTransportString(String text) {
        if (TextUtils.isEmpty(text)) return null;
        try {
            JSONObject wrapper = new JSONObject(text.trim());
            if (!wrapper.has("call")) return null;
            JSONObject obj = wrapper.getJSONObject("call");
            int version = obj.optInt("v", 0);
            if (version > VERSION) {
                // future version, but try to parse known fields
            }
            String typeStr = obj.optString("type", "");
            SignalType type;
            try {
                type = SignalType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException ex) {
                return null;
            }
            String id = obj.optString("id", null);
            String sdp = obj.optString("sdp", null);
            String candidate = obj.optString("candidate", null);
            String mid = obj.has("mid") ? obj.optString("mid", null) : null;
            Integer mline = obj.has("mline") ? obj.optInt("mline") : null;
            long ts = obj.optLong("ts", System.currentTimeMillis());
            String error = obj.optString("error", null);
            return new CallSignalMessage(type, id, sdp, candidate, mid,
                    mline != null ? mline : null, ts, error);
        } catch (JSONException ex) {
            return null;
        }
    }

    public static CallSignalMessage offer(String callId, String sdp) {
        android.util.Log.d("CallManager", "CallSignalMessage.offer callId=" + callId);
        return new CallSignalMessage(SignalType.OFFER, callId, sdp, null, null, null, System.currentTimeMillis(), null);
    }

    public static CallSignalMessage answer(String callId, String sdp) {
        android.util.Log.d("CallManager", "CallSignalMessage.answer callId=" + callId);
        return new CallSignalMessage(SignalType.ANSWER, callId, sdp, null, null, null, System.currentTimeMillis(), null);
    }

    public static CallSignalMessage candidate(String callId, String candidate, String sdpMid, Integer sdpMLineIndex) {
        android.util.Log.d("CallManager", "CallSignalMessage.candidate callId=" + callId);
        return new CallSignalMessage(SignalType.CANDIDATE, callId, null, candidate, sdpMid, sdpMLineIndex, System.currentTimeMillis(), null);
    }

    public static CallSignalMessage hangup(String callId) {
        android.util.Log.d("CallManager", "CallSignalMessage.hangup callId=" + callId);
        return new CallSignalMessage(SignalType.HANGUP, callId, null, null, null, null, System.currentTimeMillis(), null);
    }

    public static CallSignalMessage ringing(String callId) {
        android.util.Log.d("CallManager", "CallSignalMessage.ringing callId=" + callId);
        return new CallSignalMessage(SignalType.RINGING, callId, null, null, null, null, System.currentTimeMillis(), null);
    }

    public static CallSignalMessage busy(String callId) {
        android.util.Log.d("CallManager", "CallSignalMessage.busy callId=" + callId);
        return new CallSignalMessage(SignalType.BUSY, callId, null, null, null, null, System.currentTimeMillis(), null);
    }

    public static CallSignalMessage error(String callId, String message) {
        android.util.Log.d("CallManager", "CallSignalMessage.error callId=" + callId);
        return new CallSignalMessage(SignalType.ERROR, callId, null, null, null, null, System.currentTimeMillis(), message);
    }

    public String toDisplayString(boolean outgoing) {
        switch (type) {
            case OFFER:
                return outgoing ? "Calling…" : "Incoming call…";
            case ANSWER:
                return outgoing ? "Call answered" : "Answer sent";
            case CANDIDATE:
                return "Call network update";
            case RINGING:
                return outgoing ? "Ringing…" : "Call ringing";
            case HANGUP:
                return outgoing ? "Call ended" : "Caller hung up";
            case BUSY:
                return outgoing ? "User busy" : "Busy signal";
            case ERROR:
                return "Call error: " + (TextUtils.isEmpty(error) ? "unknown" : error);
            default:
                return "Call signal";
        }
    }
}
