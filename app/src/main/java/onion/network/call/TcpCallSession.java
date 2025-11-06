package onion.network.call;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * WIP: TCP-транспорт для голосових викликів поверх Tor hidden service.
 *
 * На першому кроці даємо каркас, який вміє підняти локальний {@link ServerSocket} та прийняти
 * вхідне з'єднання, а також асинхронно встановити вихідне з'єднання. Подальші етапи (аудіо,
 * узгодження через Tor, шифрування тощо) будуть додані окремо.
 */
public final class TcpCallSession implements Closeable {

    private static final String TAG = "TcpCallSession";
    private static final int ACCEPT_BACKLOG = 1;
    private static final int CONNECT_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(20);
    private static final SecureRandom RNG = new SecureRandom();

    public interface Listener {
        /**
         * Викликається, коли TCP-сокет готовий до обміну даними.
         */
        void onSocketReady(@NonNull Socket socket);

        /**
         * Повідомляємо про завершення або помилку. {@code cause == null} означає штатне завершення.
         */
        void onSocketClosed(@Nullable Throwable cause);
    }

    public enum Role {
        SERVER,
        CLIENT
    }

    /**
     * Дані про підняту end-point адресу. У наступних кроках сюди додамо onion-host / auth.
     */
    public static final class Descriptor {
        public final String host;
        public final int port;
        public final String token;

        public Descriptor(@NonNull String host, int port, @NonNull String token) {
            this.host = host;
            this.port = port;
            this.token = token;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s:%d?token=%s", host, port, token);
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tcp-call");
        t.setDaemon(true);
        return t;
    });

    private final Listener listener;
    private final Role role;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Socket activeSocket;
    private Descriptor localDescriptor;
    private Proxy proxy;

    public TcpCallSession(@NonNull Role role, @NonNull Listener listener) {
        this.role = role;
        this.listener = listener;
    }

    /**
     * Готуємо endpoint для вхідних підключень. {@code advertisedHost} — адреса, яку передамо партнеру
     * (наприклад, onion-домен), {@code port} — локальний порт для прив'язки (0 для довільного).
     */
    @NonNull
    public synchronized Descriptor prepareServerEndpoint(@NonNull String advertisedHost, int port) throws IOException {
        ensureRole(Role.SERVER);
        if (serverSocket != null) {
            return Objects.requireNonNull(localDescriptor, "descriptor");
        }
        InetAddress loopback = InetAddress.getByName(null); // 127.0.0.1
        int bindPort = port <= 0 ? 0 : port;
        serverSocket = new ServerSocket(bindPort, ACCEPT_BACKLOG, loopback);
        serverSocket.setReuseAddress(true);
        String token = generateToken();
        localDescriptor = new Descriptor(advertisedHost, serverSocket.getLocalPort(), token);
        Log.d(TAG, "Server prepared on " + localDescriptor);
        executor.execute(this::acceptLoop);
        return localDescriptor;
    }

    @NonNull
    public Descriptor prepareServerEndpoint() throws IOException {
        return prepareServerEndpoint("127.0.0.1", 0);
    }

    public synchronized void setProxy(@Nullable Proxy proxy) {
        this.proxy = proxy;
    }

    /**
     * Підключаємось до віддаленого endpoint'а. Поки що працює напряму по TCP;
     * інтеграцію з Tor додамо пізніше.
     */
    public void connect(@NonNull Descriptor remote) {
        ensureRole(Role.CLIENT);
        if (running.get()) {
            Log.w(TAG, "connect() called while already running");
            return;
        }
        running.set(true);
        executor.execute(() -> {
            try {
                Log.d(TAG, "Connecting to " + remote);
                Socket socket = (proxy != null) ? new Socket(proxy) : new Socket();
                socket.connect(new InetSocketAddress(remote.host, remote.port), CONNECT_TIMEOUT_MS);
                synchronized (this) {
                    activeSocket = socket;
                }
                listener.onSocketReady(socket);
            } catch (IOException ex) {
                Log.e(TAG, "connect failed: " + ex.getMessage());
                running.set(false);
                listener.onSocketClosed(ex);
            }
        });
    }

    private void acceptLoop() {
        running.set(true);
        try {
            Socket socket = serverSocket.accept();
            synchronized (this) {
                activeSocket = socket;
            }
            listener.onSocketReady(socket);
        } catch (IOException ex) {
            if (running.get()) {
                Log.e(TAG, "accept failed: " + ex.getMessage());
                listener.onSocketClosed(ex);
            }
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        running.set(false);
        executor.execute(() -> {
            try {
                closeQuietly(activeSocket);
                closeQuietly(serverSocket);
            } finally {
                listener.onSocketClosed(null);
            }
        });
        executor.shutdown();
    }

    private void ensureRole(Role expected) {
        if (role != expected) {
            throw new IllegalStateException("Session role is " + role + " but required " + expected);
        }
    }

    private static void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignore) {
        }
    }

    private static void closeQuietly(@Nullable ServerSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignore) {
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);
        return UUID.nameUUIDFromBytes(bytes).toString();
    }

    public static String encodeDescriptor(@NonNull Descriptor descriptor) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("host", descriptor.host);
            obj.put("port", descriptor.port);
            obj.put("token", descriptor.token);
            return obj.toString();
        } catch (JSONException ex) {
            throw new IllegalStateException("Failed to encode descriptor", ex);
        }
    }

    public static Descriptor decodeDescriptor(@Nullable String json) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("Descriptor empty");
        }
        try {
            JSONObject obj = new JSONObject(json);
            String host = obj.optString("host", "");
            int port = obj.optInt("port", -1);
            String token = obj.optString("token", "");
            if (host.isEmpty() || port <= 0 || token.isEmpty()) {
                throw new IOException("Descriptor malformed");
            }
            return new Descriptor(host, port, token);
        } catch (JSONException ex) {
            throw new IOException("Descriptor parse error", ex);
        }
    }
}
