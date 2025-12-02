package onion.network.call;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import onion.network.R;
import onion.network.TorManager;
import onion.network.call.codec.AudioCodec;
import onion.network.call.codec.OpusCodec;
import onion.network.call.codec.PcmCodec;
import onion.network.clients.ChatClient;
import onion.network.databases.ChatDatabase;
import onion.network.models.ChatMessagePayload;
import onion.network.servers.ChatServer;
import onion.network.ui.CallActivity;

public final class CallManager implements ChatServer.OnMessageReceivedListener, ChatClient.OnMessageSentListener {

    private static final String TAG = "CallManager";
    private static CallManager instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<CallListener> listeners = new CopyOnWriteArrayList<>();
    private final Object socketWriteLock = new Object();

    private AudioManager audioManager;
    private DataOutputStream socketOutputStream;

    private static final int SAMPLE_RATE_HZ = 16000;
    private static final int PCM_FRAME_MS = 20;
    private static final int PCM_FRAME_BYTES = SAMPLE_RATE_HZ * PCM_FRAME_MS / 1000 * 2;
    private static final byte FRAME_TYPE_AUDIO = 0x01;
    private static final byte FRAME_TYPE_PING = 0x02;
    private static final byte FRAME_TYPE_PONG = 0x03;
    private static final int JITTER_MIN_FRAMES = 3;
    private static final int JITTER_MAX_FRAMES = 8;
    private static final long HEARTBEAT_PERIOD_MS = 5_000;
    private static final long HEARTBEAT_TIMEOUT_MS = 15_000;
    private static final int SERVER_BIND_MAX_ATTEMPTS = 3;
    private static final long SERVER_BIND_RETRY_DELAY_MS = 250L;

    private CallState state = CallState.IDLE;
    private CallSession session;
    private TcpCallSession tcpSession;
    private Socket transportSocket;
    private final AtomicBoolean suppressTransportCallbacks = new AtomicBoolean(false);
    private final AtomicBoolean gracefulTerminationExpected = new AtomicBoolean(false);
    private String pendingDescriptorJson;
    private volatile long lastPongAt = System.currentTimeMillis();
    private ExecutorService audioExecutor;
    private volatile boolean audioRunning;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private ScheduledExecutorService heartbeatExecutor;
    private volatile boolean heartbeatRunning;
    private volatile long lastPingSent;
    private volatile boolean micMuted;
    private volatile boolean speakerMuted;
    private volatile String lastErrorMessage;
    private AudioCodec audioCodec;

    private CallManager(Context context) {
        this.appContext = context.getApplicationContext();
        initCodec();
    }

    public static synchronized CallManager getInstance(Context context) {
        if (instance == null) {
            instance = new CallManager(context);
        }
        return instance;
    }

    public void initialize() {
        if (audioManager != null) {
            return;
        }

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
        String callId;
        synchronized (this) {
            if (state != CallState.IDLE && state != CallState.ENDED && state != CallState.FAILED) {
                toast("Call already in progress");
                return;
            }
            callId = UUID.randomUUID().toString();
            Log.d(TAG, "startOutgoingCall remote=" + remoteAddress + " callId=" + callId);
            session = new CallSession(remoteAddress, true, callId);
            gracefulTerminationExpected.set(false);
            changeState(CallState.CALLING);
            pendingDescriptorJson = null;
        }
        setupAudioMode(true);
        try {
            startServerTransport();
        } catch (IOException ex) {
            Log.e(TAG, "Unable to start TCP transport: " + ex.getMessage());
            toast("Unable to start call");
            String message = TextUtils.isEmpty(ex.getMessage())
                    ? appContext.getString(R.string.call_error_generic)
                    : ex.getMessage();
            setLastErrorMessage(message);
            tearDown(CallState.FAILED);
        }
    }

    public void acceptIncomingCall() {
        String descriptorJson;
        String callId;
        synchronized (this) {
            if (state != CallState.RINGING || session == null) {
                return;
            }
            descriptorJson = pendingDescriptorJson;
            if (TextUtils.isEmpty(descriptorJson)) {
                toast("Call data missing");
                return;
            }
            callId = session.callId;
            pendingDescriptorJson = null;
            changeState(CallState.CONNECTING);
        }
        setupAudioMode(true);
        try {
            TcpCallSession.Descriptor remote = TcpCallSession.decodeDescriptor(descriptorJson);
            startClientTransport(remote);
            sendSignal(CallSignalMessage.answer(callId, "accept"));
        } catch (IOException ex) {
            Log.e(TAG, "Failed to parse descriptor: " + ex.getMessage());
            toast("Unable to connect");
            tearDown(CallState.FAILED);
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
                if (message.getType() != CallSignalMessage.SignalType.CANDIDATE) {
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

    private void startServerTransport() throws IOException {
        closeTransport(true);
        String onion = TorManager.getInstance(appContext).getOnion();
        if (TextUtils.isEmpty(onion)) {
            throw new IOException("Onion domain unavailable");
        }
        IOException lastError = null;
        for (int attempt = 1; attempt <= SERVER_BIND_MAX_ATTEMPTS; attempt++) {
            TcpCallSession transport = new TcpCallSession(TcpCallSession.Role.SERVER, new TransportListener());
            try {
                TcpCallSession.Descriptor descriptor = transport.prepareServerEndpoint(onion, TorManager.CALL_PORT);
                synchronized (this) {
                    tcpSession = transport;
                    if (this.session != null) {
                        this.session.transportToken = descriptor.token;
                    }
                }
                // Відкладена відправка offer поки hidden service повністю доступний.
                if (this.session != null) {
                    String callIdSnapshot = this.session.callId;
                    String payload = TcpCallSession.encodeDescriptor(descriptor);
                    scheduleOfferAfterHsReady(callIdSnapshot, payload);
                }
                return;
            } catch (IOException ex) {
                lastError = ex;
                boolean retriable = isAddressInUse(ex) && attempt < SERVER_BIND_MAX_ATTEMPTS;
                if (!retriable) {
                    break;
                }
                try {
                    Thread.sleep(SERVER_BIND_RETRY_DELAY_MS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw lastError != null ? lastError : new IOException("Unable to start call transport");
    }

    private void scheduleOfferAfterHsReady(String callId, String payload) {
        // запуск у окремому потоці, щоб не блокувати UI
        networkExecutor.execute(() -> {
            final long maxWaitMs = 7_000L; // максимум очікування готовності hidden service
            final long pollIntervalMs = 500L;
            final long publishGraceMs = 1_500L; // додатковий буфер після ready
            TorManager tor = TorManager.getInstance(appContext);
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < maxWaitMs) {
                if (tor.isReady() && !TextUtils.isEmpty(tor.getOnion())) {
                    break;
                }
                try { Thread.sleep(pollIntervalMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            // Невелика додаткова пауза для публікації дескрипторів
            try { Thread.sleep(publishGraceMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            CallSession snapshot;
            synchronized (this) { snapshot = session; }
            if (snapshot == null || !TextUtils.equals(snapshot.callId, callId)) {
                Log.d(TAG, "Skipping offer send: call abandoned");
                return;
            }
            Log.d(TAG, "Sending offer after HS ready (wait=" + (System.currentTimeMillis() - start) + "ms)");
            sendSignal(CallSignalMessage.offer(callId, payload));
        });
    }

    private void startClientTransport(TcpCallSession.Descriptor descriptor) {
        closeTransport(true);
        TcpCallSession transport = new TcpCallSession(TcpCallSession.Role.CLIENT, new TransportListener());
        try {
            int socksPort = TorManager.getInstance(appContext).getPort();
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
            transport.setProxy(proxy);
        } catch (IOException ex) {
            Log.e(TAG, "SOCKS port unavailable: " + ex.getMessage());
            handleTransportClosed(ex);
            return;
        }
        synchronized (this) {
            tcpSession = transport;
            if (this.session != null) {
                this.session.transportToken = descriptor.token;
            }
        }
        transport.connect(descriptor);
    }

    private void onTransportReady(Socket socket) {
        synchronized (this) {
            transportSocket = socket;
        }
        networkExecutor.execute(() -> {
            try {
                performHandshake(socket);
                startAudioStreams(socket);
                changeState(CallState.CONNECTED);
                Log.d(TAG, "TCP transport established");
            } catch (IOException ex) {
                Log.e(TAG, "Handshake failed: " + ex.getMessage());
                handleTransportClosed(ex);
            }
        });
    }

    private void handleTransportClosed(@Nullable Throwable cause) {
        CallState stateSnapshot;
        CallSession sessionSnapshot;
        TcpCallSession transportSnapshot;
        synchronized (this) {
            stateSnapshot = state;
            sessionSnapshot = session;
            transportSnapshot = tcpSession;
        }
        boolean hasActiveTransport = sessionSnapshot != null || transportSnapshot != null;
        if (!hasActiveTransport && (stateSnapshot == CallState.IDLE
                || stateSnapshot == CallState.ENDED
                || stateSnapshot == CallState.FAILED)) {
            Log.d(TAG, "Ignoring transport close callback (state=" + stateSnapshot + ")");
            return;
        }
        if (gracefulTerminationExpected.get()) {
            cause = null;
        }
        if (cause != null) {
            String msg = cause.getMessage();
            if (TextUtils.isEmpty(msg)) {
                msg = appContext.getString(R.string.call_error_generic);
            }
            setLastErrorMessage(msg);
            if (cause instanceof IOException io) {
                // Notify Tor manager on local SOCKS issues to trigger health checks/restart
                TorManager.getInstance(appContext).reportSocksFailure(io);
            }
        } else {
            setLastErrorMessage(null);
        }
        Log.d(TAG, "TCP transport closed: " + (cause == null ? "normal" : cause.getMessage()));
        CallState endState = (cause == null) ? CallState.ENDED : CallState.FAILED;
        tearDown(endState);
    }

    private void closeTransport(boolean suppressCallback) {
        TcpCallSession sessionToClose;
        Socket socketToClose;
        stopAudioStreams();
        stopHeartbeat();
        synchronized (this) {
            sessionToClose = tcpSession;
            tcpSession = null;
            socketToClose = transportSocket;
            transportSocket = null;
        }
        closeQuietly(socketToClose);
        if (sessionToClose != null) {
            if (suppressCallback) {
                suppressTransportCallbacks.set(true);
            }
            sessionToClose.close();
        } else if (suppressCallback) {
            suppressTransportCallbacks.set(false);
        }
    }

    private class TransportListener implements TcpCallSession.Listener {
        @Override
        public void onSocketReady(@NonNull Socket socket) {
            try {
                socket.setTcpNoDelay(true);
            } catch (IOException ignore) {
            }
            mainHandler.post(() -> onTransportReady(socket));
        }

        @Override
        public void onSocketClosed(@Nullable Throwable cause) {
            if (suppressTransportCallbacks.getAndSet(false)) {
                return;
            }
            mainHandler.post(() -> handleTransportClosed(cause));
        }
    }

    private static void closeQuietly(@Nullable Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignore) {
        }
    }

    private void performHandshake(Socket socket) throws IOException {
        CallSession current;
        synchronized (this) {
            current = session;
        }
        if (current == null) {
            throw new IOException("Session ended");
        }
        String token = current.transportToken;
        if (TextUtils.isEmpty(token)) {
            throw new IOException("Transport token missing");
        }
        boolean isServer = current.outgoing;
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(15));
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        if (isServer) {
            String magic = in.readUTF();
            if (!"HELLO".equals(magic)) {
                out.writeUTF("ERR");
                out.flush();
                throw new IOException("Invalid handshake magic");
            }
            String receivedToken = in.readUTF();
            if (!TextUtils.equals(receivedToken, token)) {
                out.writeUTF("ERR");
                out.flush();
                throw new IOException("Invalid handshake token");
            }
            out.writeUTF("OK");
            out.flush();
        } else {
            out.writeUTF("HELLO");
            out.writeUTF(token);
            out.flush();
            String response = in.readUTF();
            if (!"OK".equals(response)) {
                throw new IOException("Handshake rejected");
            }
        }
        socket.setSoTimeout(0);
        synchronized (socketWriteLock) {
            socketOutputStream = out;
        }
        lastPongAt = System.currentTimeMillis();
        lastPingSent = lastPongAt;
        startHeartbeat();
    }

    private void startAudioStreams(Socket socket) {
        stopAudioStreams();
        initCodec();
        audioExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "tcp-audio");
            t.setDaemon(true);
            return t;
        });
        audioRunning = true;
        micMuted = false;
        speakerMuted = false;
        audioExecutor.execute(() -> pumpMicrophone(socket));
        audioExecutor.execute(() -> pumpSpeaker(socket));
    }

    private void stopAudioStreams() {
        audioRunning = false;
        if (audioExecutor != null) {
            audioExecutor.shutdownNow();
            audioExecutor = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignore) {
            }
            try {
                audioRecord.release();
            } catch (Exception ignore) {
            }
            audioRecord = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
            } catch (Exception ignore) {
            }
            try {
                audioTrack.release();
            } catch (Exception ignore) {
            }
            audioTrack = null;
        }
        synchronized (socketWriteLock) {
            socketOutputStream = null;
        }
        releaseCodec();
    }

    private void pumpMicrophone(Socket socket) {
        AudioRecord record = createAudioRecord();
        if (record == null) {
            handleTransportClosed(new IOException("AudioRecord init failed"));
            return;
        }
        audioRecord = record;
        byte[] buffer = new byte[PCM_FRAME_BYTES];
        try {
            record.startRecording();
        } catch (IllegalStateException ex) {
            handleTransportClosed(ex);
            return;
        }
        try {
            while (audioRunning) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                if (micMuted) {
                    continue;
                }
                try {
                    byte[] encoded = audioCodec.encode(buffer, read);
                    if (encoded == null) {
                        continue;
                    }
                    sendFrame(FRAME_TYPE_AUDIO, encoded, encoded.length, false);
                } catch (IOException encodeError) {
                    Log.e(TAG, "Encoding failed: " + encodeError.getMessage());
                }
            }
        } catch (IllegalStateException ex) {
            if (audioRunning) {
                audioRunning = false;
                Log.e(TAG, "Mic pump error: " + ex.getMessage());
                handleTransportClosed(ex);
            }
        } finally {
            try {
                record.stop();
            } catch (Exception ignore) {
            }
        }
    }

    private void pumpSpeaker(Socket socket) {
        AudioTrack track = createAudioTrack();
        if (track == null) {
            handleTransportClosed(new IOException("AudioTrack init failed"));
            return;
        }
        audioTrack = track;
        byte[] buffer = new byte[PCM_FRAME_BYTES * 4];
        ArrayDeque<byte[]> queue = new ArrayDeque<>();
        long frameDurationNs = PCM_FRAME_MS * 1_000_000L;
        long nextPlaybackNs = 0L;
        try {
            track.play();
        } catch (IllegalStateException ex) {
            handleTransportClosed(ex);
            return;
        }
        try {
            InputStream rawIn = socket.getInputStream();
            DataInputStream in = new DataInputStream(rawIn);
            while (audioRunning) {
                byte frameType = in.readByte();
                int length = in.readUnsignedShort();
                byte[] payload = null;
                if (length > 0) {
                    if (buffer.length < length) {
                        buffer = new byte[length];
                    }
                    int offset = 0;
                    while (offset < length) {
                        int read = in.read(buffer, offset, length - offset);
                        if (read == -1) {
                            throw new IOException("Audio stream ended");
                        }
                        offset += read;
                    }
                    payload = new byte[length];
                    System.arraycopy(buffer, 0, payload, 0, length);
                }
                switch (frameType) {
                    case FRAME_TYPE_AUDIO -> {
                        lastPongAt = System.currentTimeMillis();
                        if (payload != null) {
                            try {
                                byte[] decoded = audioCodec.decode(payload, payload.length);
                                if (decoded != null && decoded.length > 0) {
                                    queue.add(decoded);
                                }
                            } catch (IOException decodeError) {
                                Log.e(TAG, "Decoding failed: " + decodeError.getMessage());
                            }
                        }
                        if (nextPlaybackNs == 0L) {
                            nextPlaybackNs = System.nanoTime() + frameDurationNs * JITTER_MIN_FRAMES;
                        }
                        long now = System.nanoTime();
                        while (!queue.isEmpty() && (now >= nextPlaybackNs || queue.size() > JITTER_MAX_FRAMES)) {
                            byte[] frame = queue.poll();
                            if (frame != null) {
                                if (!speakerMuted) {
                                    track.write(frame, 0, frame.length);
                                }
                            }
                            nextPlaybackNs += frameDurationNs;
                            now = System.nanoTime();
                        }
                    }
                    case FRAME_TYPE_PING -> sendFrame(FRAME_TYPE_PONG, null, 0, true);
                    case FRAME_TYPE_PONG -> lastPongAt = System.currentTimeMillis();
                    default -> { /* ignore unknown */ }
                }
            }
        } catch (IOException | IllegalStateException ex) {
            if (audioRunning) {
                audioRunning = false;
                Log.e(TAG, "Speaker pump error: " + ex.getMessage());
                handleTransportClosed(ex);
            }
        } finally {
            try {
                track.pause();
                track.flush();
            } catch (Exception ignore) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Nullable
    private AudioRecord createAudioRecord() {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            minBuffer = PCM_FRAME_BYTES * 4;
        }
        try {
            return new AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer);
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "AudioRecord init failed: " + ex.getMessage());
            return null;
        }
    }

    @Nullable
    private AudioTrack createAudioTrack() {
        int minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            minBuffer = PCM_FRAME_BYTES * 4;
        }
        try {
            return new AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer,
                    AudioTrack.MODE_STREAM);
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "AudioTrack init failed: " + ex.getMessage());
            return null;
        }
    }

    private void sendFrame(byte type, @Nullable byte[] payload, int length, boolean flush) throws IOException {
        DataOutputStream out = socketOutputStream;
        if (out == null) {
            throw new IOException("Output stream unavailable");
        }
        synchronized (socketWriteLock) {
            out.writeByte(type);
            out.writeShort(length);
            if (length > 0 && payload != null) {
                out.write(payload, 0, length);
            }
            if (flush) {
                out.flush();
            }
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatRunning = true;
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tcp-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!heartbeatRunning) {
                return;
            }
            try {
                long now = System.currentTimeMillis();
                if (now - lastPingSent >= HEARTBEAT_PERIOD_MS) {
                    sendFrame(FRAME_TYPE_PING, null, 0, true);
                    lastPingSent = now;
                }
                if (now - lastPongAt > HEARTBEAT_TIMEOUT_MS) {
                    throw new IOException("Heartbeat timeout");
                }
            } catch (IOException ex) {
                Log.e(TAG, "Heartbeat failure: " + ex.getMessage());
                handleTransportClosed(ex);
            }
        }, HEARTBEAT_PERIOD_MS, HEARTBEAT_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        heartbeatRunning = false;
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    private static boolean isAddressInUse(IOException ex) {
        if (ex instanceof BindException) {
            return true;
        }
        String message = ex.getMessage();
        return message != null && message.toLowerCase().contains("address already in use");
    }

    private void initCodec() {
        releaseCodec();
        try {
            audioCodec = new OpusCodec(SAMPLE_RATE_HZ, 1, 32000);
            Log.i(TAG, "Using Opus codec");
        } catch (Exception ex) {
            Log.e(TAG, "Failed to initialize Opus codec, falling back to PCM: " + ex.getMessage());
            audioCodec = new PcmCodec();
        }
    }

    private void releaseCodec() {
        if (audioCodec instanceof OpusCodec opus) {
            opus.release();
        }
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

    public void setMicMuted(boolean muted) {
        micMuted = muted;
    }

    public boolean isMicMuted() {
        return micMuted;
    }

    public void setSpeakerMuted(boolean muted) {
        speakerMuted = muted;
    }

    public boolean isSpeakerMuted() {
        return speakerMuted;
    }

    public @Nullable String consumeLastErrorMessage() {
        String msg = lastErrorMessage;
        lastErrorMessage = null;
        return msg;
    }

    private void setLastErrorMessage(@Nullable String message) {
        lastErrorMessage = message;
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
        gracefulTerminationExpected.set(endState == CallState.ENDED);
        closeTransport(true);
        synchronized (this) {
            pendingDescriptorJson = null;
            session = null;
        }
        setupAudioMode(false);
        changeState(endState);
        Log.d(TAG, "tearDown -> " + endState);
        if (endState != CallState.FAILED) {
            setLastErrorMessage(null);
        }
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
        if (signal.getType() != CallSignalMessage.SignalType.OFFER) {
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
        String descriptor = signal.getSdp();
        if (TextUtils.isEmpty(descriptor)) {
            Log.w(TAG, "Received offer without descriptor");
            sendSignal(CallSignalMessage.error(signal.getCallId(), "descriptor_missing"));
            return;
        }
        synchronized (this) {
            if (session != null && session.callId != null && !session.callId.equals(signal.getCallId())) {
                sendSignal(CallSignalMessage.busy(signal.getCallId()));
                return;
            }
            if (state == CallState.CONNECTED || state == CallState.CONNECTING) {
                sendSignal(CallSignalMessage.busy(signal.getCallId()));
                return;
            }
            session = new CallSession(fromAddress, false, signal.getCallId());
            gracefulTerminationExpected.set(false);
            pendingDescriptorJson = descriptor;
        }
        mainHandler.post(() -> CallActivity.startIncoming(appContext, fromAddress));
        changeState(CallState.RINGING);
    }

    private void handleIncomingAnswer(CallSignalMessage signal) {
        synchronized (this) {
            if (session == null || !session.callId.equals(signal.getCallId())) {
                return;
            }
            if (state == CallState.CALLING) {
                changeState(CallState.CONNECTING);
            }
        }
    }

    private void handleIncomingCandidate(CallSignalMessage signal) {
        Log.d(TAG, "Ignoring legacy candidate signal");
    }

    public interface CallListener {
        void onCallStateChanged(CallState state, @Nullable CallSession session);
    }
}
