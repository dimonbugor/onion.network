package onion.network.call;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import onion.network.BuildConfig;
import onion.network.call.CallSignalMessage.SignalType;
import onion.network.clients.ChatClient;
import onion.network.databases.ChatDatabase;
import onion.network.models.ChatMessagePayload;
import onion.network.servers.ChatServer;
import onion.network.settings.Settings;
import onion.network.TorManager;
import onion.network.ui.CallActivity;

public final class CallManager implements ChatServer.OnMessageReceivedListener, ChatClient.OnMessageSentListener {

    private static final String TAG = "CallManager";
    private static CallManager instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<CallListener> listeners = new CopyOnWriteArrayList<>();

    private PeerConnectionFactory peerFactory;
    private PeerConnection peerConnection;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private JavaAudioDeviceModule audioDeviceModule;
    private AudioManager audioManager;

    private CallState state = CallState.IDLE;
    private CallSession session;
    private boolean pendingAnswer;

    private CallManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static synchronized CallManager getInstance(Context context) {
        if (instance == null) {
            instance = new CallManager(context);
        }
        return instance;
    }

    public void initialize() {
        if (peerFactory != null) {
            return;
        }

        PeerConnectionFactory.InitializationOptions initOptions =
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions();

        PeerConnectionFactory.initialize(initOptions);

        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule();

        peerFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory();

        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);

        ChatServer.getInstance(appContext).addOnMessageReceivedListener(this);
        ChatClient.getInstance(appContext).addOnMessageSentListener(this);
    }

    public void addListener(CallListener listener) {
        if (listener != null) {
            listeners.add(listener);
            deliverState(listener);
        }
    }

    public void removeListener(CallListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public synchronized CallState getState() {
        return state;
    }

    @Nullable
    public synchronized CallSession getSession() {
        return session;
    }

    public void startOutgoingCall(String remoteAddress) {
        initialize();
        if (TextUtils.isEmpty(remoteAddress)) {
            toast("No recipient selected");
            return;
        }
        synchronized (this) {
            if (state != CallState.IDLE && state != CallState.ENDED && state != CallState.FAILED) {
                toast("Call already in progress");
                return;
            }
            String callId = UUID.randomUUID().toString();
            Log.d(TAG, "startOutgoingCall remote=" + remoteAddress + " callId=" + callId);
            session = new CallSession(remoteAddress, true, callId);
            changeState(CallState.CALLING);
        }
        setupAudioMode(true);
        createPeerConnection(true);
        createOffer();
    }

    public void acceptIncomingCall() {
        synchronized (this) {
            if (state != CallState.RINGING || session == null) {
                return;
            }
            changeState(CallState.CONNECTING);
        }
        setupAudioMode(true);
        if (pendingAnswer) {
            createAnswer();
        }
    }

    public void rejectIncomingCall() {
        synchronized (this) {
            if (session == null) return;
            sendSignal(CallSignalMessage.busy(session.callId));
            tearDown(CallState.ENDED);
        }
    }

    public void hangup() {
        synchronized (this) {
            if (session != null && state != CallState.IDLE && state != CallState.ENDED && state != CallState.FAILED) {
                sendSignal(CallSignalMessage.hangup(session.callId));
            }
        }
        tearDown(CallState.ENDED);
    }

    private void createPeerConnection(boolean outgoing) {
        if (peerFactory == null) {
            initialize();
        }
        if (peerFactory == null) {
            toast("WebRTC not initialised");
            return;
        }

        List<PeerConnection.IceServer> iceServers = buildIceServers();
        Log.d(TAG, "createPeerConnection outgoing=" + outgoing + " iceServers=" + iceServers.size());
        PeerConnection.RTCConfiguration rtc = new PeerConnection.RTCConfiguration(iceServers);
        rtc.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtc.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        rtc.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        rtc.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;

        peerConnection = peerFactory.createPeerConnection(rtc, new PeerObserver());
        if (peerConnection == null) {
            toast("Unable to create peer connection");
            return;
        }

        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.optional.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.optional.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioSource = peerFactory.createAudioSource(audioConstraints);
        localAudioTrack = peerFactory.createAudioTrack("AUDIO0", audioSource);
        localAudioTrack.setEnabled(true);
        RtpSender audioSender = peerConnection.addTrack(localAudioTrack, Collections.singletonList("AUDIO_STREAM"));
        if (audioSender != null) {
            RtpParameters params = audioSender.getParameters();
            if (params != null && params.encodings != null) {
                for (RtpParameters.Encoding encoding : params.encodings) {
                    encoding.maxBitrateBps = 32000;
                }
                audioSender.setParameters(params);
            }
        }
    }

    private List<PeerConnection.IceServer> buildIceServers() {
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        String urlsRaw = Settings.getPrefs(appContext).getString("call_turn_urls", BuildConfig.CALL_TURN_URLS);
        String username = Settings.getPrefs(appContext).getString("call_turn_username", BuildConfig.CALL_TURN_USERNAME);
        String password = Settings.getPrefs(appContext).getString("call_turn_password", BuildConfig.CALL_TURN_PASSWORD);
        if (!TextUtils.isEmpty(urlsRaw)) {
            String[] urlParts = urlsRaw.split(",");
            List<String> urlList = new ArrayList<>();
            for (String part : urlParts) {
                String trimmed = part == null ? "" : part.trim();
                if (!trimmed.isEmpty()) {
                    urlList.add(trimmed);
                }
            }
            if (!urlList.isEmpty()) {
                PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(urlList);
                if (!TextUtils.isEmpty(username)) {
                    builder.setUsername(username);
                }
                if (!TextUtils.isEmpty(password)) {
                    builder.setPassword(password);
                }
                servers.add(builder.createIceServer());
            }
        }
        Log.d(TAG, "buildIceServers count=" + servers.size());
        return servers;
    }

    private void createOffer() {
        if (peerConnection == null || session == null) return;
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                if (peerConnection == null) return;
                Log.d(TAG, "createOffer success, setting local description");
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        if (session != null) {
                            Log.d(TAG, "local offer set, sending signal");
                            sendSignal(CallSignalMessage.offer(session.callId, sessionDescription.description));
                        }
                    }
                }, sessionDescription);
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "createOffer failed: " + s);
                toast("Call setup failed: " + s);
                tearDown(CallState.FAILED);
            }
        }, constraints);
    }

    private void createAnswer() {
        if (peerConnection == null || session == null) return;
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                if (peerConnection == null) return;
                Log.d(TAG, "createAnswer success, setting local description");
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        if (session != null) {
                            Log.d(TAG, "local answer set, sending signal");
                            sendSignal(CallSignalMessage.answer(session.callId, sessionDescription.description));
                            pendingAnswer = false;
                        }
                    }
                }, sessionDescription);
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "createAnswer failed: " + s);
                toast("Unable to answer call: " + s);
                tearDown(CallState.FAILED);
            }
        }, constraints);
    }

    private void sendSignal(CallSignalMessage message) {
        if (session == null) return;
        String remote = session.remoteAddress;
        String text = message.toTransportString();
        Log.d(TAG, "sendSignal payloadLen=" + (text == null ? "null" : text.length()));
        networkExecutor.execute(() -> {
            ChatClient client = ChatClient.getInstance(appContext);
            String sender = TorManager.getInstance(appContext).getID();
            long timestamp = System.currentTimeMillis();
            try {
                Log.d(TAG, "sendSignal type=" + message.getType() + " to=" + remote);
                client.sendOne(sender, remote, text, timestamp);
                if (message.getType() != SignalType.CANDIDATE) {
                    ChatMessagePayload payload;
                    CallSignalMessage parsed = CallSignalMessage.fromTransportString(text);
                    if (parsed != null) {
                        payload = ChatMessagePayload.forText(parsed.toDisplayString(true));
                        payload.setMime("application/json");
                        payload.setData(text);
                    } else {
                        payload = ChatMessagePayload.forText(text);
                    }
                    ChatDatabase.getInstance(appContext).addMessage(sender, remote,
                            payload.toStorageString(), timestamp, false, false);
                }
            } catch (IOException ex) {
                Log.e(TAG, "Failed to send call signal: " + ex.getMessage());
                toast("Failed to send call signal");
            }
        });
    }

    private void toast(String message) {
        mainHandler.post(() -> Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show());
    }

    private void changeState(CallState newState) {
        synchronized (this) {
            state = newState;
        }
        Log.d(TAG, "changeState -> " + newState);
        notifyListeners();
    }

    private void notifyListeners() {
        CallState stateSnapshot;
        CallSession sessionSnapshot;
        synchronized (this) {
            stateSnapshot = state;
            sessionSnapshot = session;
        }
        for (CallListener listener : listeners) {
            deliverState(listener, stateSnapshot, sessionSnapshot);
        }
    }

    private void deliverState(CallListener listener) {
        CallState stateSnapshot;
        CallSession sessionSnapshot;
        synchronized (this) {
            stateSnapshot = state;
            sessionSnapshot = session;
        }
        deliverState(listener, stateSnapshot, sessionSnapshot);
    }

    private void deliverState(CallListener listener, CallState stateSnapshot, CallSession sessionSnapshot) {
        if (listener == null) return;
        mainHandler.post(() -> listener.onCallStateChanged(stateSnapshot, sessionSnapshot));
    }

    private void setupAudioMode(boolean start) {
        if (audioManager == null) return;
        mainHandler.post(() -> {
            if (start) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);
                audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            } else {
                audioManager.setSpeakerphoneOn(false);
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.abandonAudioFocus(null);
            }
        });
    }

    private void tearDown(CallState endState) {
        PeerConnection pc;
        AudioSource source;
        AudioTrack track;
        synchronized (this) {
            pc = peerConnection;
            source = audioSource;
            track = localAudioTrack;
            peerConnection = null;
            audioSource = null;
            localAudioTrack = null;
            pendingAnswer = false;
            session = null;
            changeState(endState);
        }
        setupAudioMode(false);
        if (track != null) {
            track.dispose();
        }
        if (source != null) {
            source.dispose();
        }
        if (pc != null) {
            pc.close();
        }
        Log.d(TAG, "tearDown -> " + endState);
    }

    @Override
    public void onMessageReceived() {
        // handled via explicit hook in ChatServer.processMessage
    }

    @Override
    public void onMessageSent() {
        // no-op
    }

    public void onIncomingSignal(String fromAddress, CallSignalMessage signal) {
        if (signal == null || TextUtils.isEmpty(fromAddress)) {
            return;
        }
        Log.d(TAG, "onIncomingSignal type=" + signal.getType() + " from=" + fromAddress);
        if (signal.getType() != SignalType.OFFER) {
            CallSession current;
            synchronized (this) {
                current = session;
            }
            if (current == null || TextUtils.isEmpty(signal.getCallId()) || !TextUtils.equals(current.callId, signal.getCallId())) {
                Log.d(TAG, "Ignoring signal for stale or unknown callId=" + signal.getCallId());
                return;
            }
        }
        switch (signal.getType()) {
            case OFFER -> handleIncomingOffer(fromAddress, signal);
            case ANSWER -> handleIncomingAnswer(signal);
            case CANDIDATE -> handleIncomingCandidate(signal);
            case RINGING -> changeState(CallState.RINGING);
            case BUSY -> {
                toast("User is busy");
                tearDown(CallState.ENDED);
            }
            case HANGUP -> tearDown(CallState.ENDED);
            case ERROR -> {
                toast(signal.getError() == null ? "Call failed" : signal.getError());
                tearDown(CallState.FAILED);
            }
            default -> Log.w(TAG, "Unhandled call signal: " + signal.getType());
        }
    }

    private void handleIncomingOffer(String fromAddress, CallSignalMessage signal) {
        synchronized (this) {
            if (session != null && session.callId != null && !session.callId.equals(signal.getCallId())) {
                // busy with another call
                sendSignal(CallSignalMessage.busy(signal.getCallId()));
                return;
            }
            if (state == CallState.CONNECTED || state == CallState.CONNECTING) {
                sendSignal(CallSignalMessage.busy(signal.getCallId()));
                return;
            }
            session = new CallSession(fromAddress, false, signal.getCallId());
            mainHandler.post(() -> CallActivity.startIncoming(appContext, fromAddress));
            changeState(CallState.RINGING);
        }
        if (peerConnection == null) {
            createPeerConnection(false);
        }
        if (peerConnection == null) {
            tearDown(CallState.FAILED);
            return;
        }
        SessionDescription remoteSdp = new SessionDescription(SessionDescription.Type.OFFER, signal.getSdp());
        Log.d(TAG, "Applying remote offer callId=" + signal.getCallId());
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                pendingAnswer = true;
                if (state == CallState.CONNECTING) {
                    createAnswer();
                }
            }

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "Failed to set remote offer: " + s);
                tearDown(CallState.FAILED);
            }
        }, remoteSdp);
    }

    private void handleIncomingAnswer(CallSignalMessage signal) {
        if (peerConnection == null || session == null) return;
        if (!session.callId.equals(signal.getCallId())) return;
        SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, signal.getSdp());
        Log.d(TAG, "Applying remote answer callId=" + signal.getCallId());
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                changeState(CallState.CONNECTED);
            }

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "Failed to apply answer: " + s);
                tearDown(CallState.FAILED);
            }
        }, answer);
    }

    private void handleIncomingCandidate(CallSignalMessage signal) {
        if (peerConnection == null || session == null) return;
        if (!session.callId.equals(signal.getCallId())) return;
        Log.d(TAG, "Applying remote candidate callId=" + signal.getCallId());
        IceCandidate candidate = new IceCandidate(
                signal.getSdpMid(),
                signal.getSdpMLineIndex() != null ? signal.getSdpMLineIndex() : 0,
                signal.getCandidate());
        peerConnection.addIceCandidate(candidate);
    }

    private class PeerObserver implements PeerConnection.Observer {
        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            if (session != null) {
                Log.d(TAG, "onIceCandidate local -> sending");
                sendSignal(CallSignalMessage.candidate(session.callId, iceCandidate.sdp, iceCandidate.sdpMid, iceCandidate.sdpMLineIndex));
            }
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
            Log.d(TAG, "onIceConnectionChange -> " + newState);
            switch (newState) {
                case CONNECTED, COMPLETED -> changeState(CallState.CONNECTED);
                case FAILED, DISCONNECTED, CLOSED -> tearDown(CallState.ENDED);
            }
        }
        @Override public void onIceConnectionReceivingChange(boolean b) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {}
        @Override public void onAddStream(org.webrtc.MediaStream mediaStream) {}
        @Override public void onRemoveStream(org.webrtc.MediaStream mediaStream) {}
        @Override public void onDataChannel(org.webrtc.DataChannel dataChannel) {}
        @Override public void onRenegotiationNeeded() {}
        @Override public void onAddTrack(org.webrtc.RtpReceiver rtpReceiver, org.webrtc.MediaStream[] mediaStreams) {
            MediaStreamTrack track = rtpReceiver.track();
            if (track instanceof AudioTrack remote) {
                remote.setEnabled(true);
            }
        }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
    }

    private abstract static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) {}
        @Override public void onSetFailure(String s) {}
    }

    public interface CallListener {
        void onCallStateChanged(CallState state, @Nullable CallSession session);
    }
}
