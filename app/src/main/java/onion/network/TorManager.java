package onion.network;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import onion.network.helpers.Ed25519Signature;
import onion.network.helpers.Utils;
import onion.network.servers.Server;
import onion.network.tor.TorBridgeParser;

public class TorManager {
    private static final String TAG = "TorManager";
    private static TorManager instance = null;
    private Context context;

    private volatile File hiddenServiceDir;
    private volatile String domain = "";
    private String status = "";
    private volatile boolean ready = false;
    private volatile List<String> bridgeOverride = null;
    private final Object restartLock = new Object();
    private volatile boolean restartScheduled = false;
    private static final int MAX_RESTARTS_BEFORE_BRIDGE_REFRESH = 3;
    private static final long RESTART_WINDOW_MS = 5 * 60_000L;
    private volatile int restartAttempts = 0;
    private volatile long lastRestartAttemptMs = 0;

    private native boolean nativeStartTor(String torrcPath, String dataDir);
    private final Object portLock = new Object();
    private volatile int socksPort = -1;

    private native String nativeGetHiddenServiceDomain(String hsDir);

    private native void nativeStopTor();

    synchronized public static TorManager getInstance(Context context) {
        if (instance == null) {
            instance = new TorManager(context.getApplicationContext());
        }
        return instance;
    }

    public TorManager(Context c) {
        this.context = c;

        // Ініціалізація каталогів
        File appTorServiceDir = new File(context.getFilesDir().getParentFile(), "app_TorService");
        if (!appTorServiceDir.exists()) appTorServiceDir.mkdirs();

        hiddenServiceDir = new File(appTorServiceDir, "app_TorHiddenService");
        if (!hiddenServiceDir.exists()) hiddenServiceDir.mkdirs();

        startTorServer();
    }

    public void startTorServer() {
        new Thread(() -> {
            try {
                // Підготовка torrc файлу
                File torrc = prepareTorrc();

                // Запуск Tor
                boolean started = nativeStartTor(torrc.getAbsolutePath(),
                        context.getFilesDir().getAbsolutePath());

                if (!started) {
                    log("Failed to start Tor");
                    return;
                }

                // Чекаємо поки Tor буде готовий
                while (!ready) {
                    Thread.sleep(500);
                }

                // Отримуємо onion адресу
                domain = nativeGetHiddenServiceDomain(hiddenServiceDir.getAbsolutePath());
                if (!domain.isEmpty()) {
                    log("Onion domain ready: " + domain);
                } else {
                    log("Failed to get onion domain");
                }
            } catch (Exception e) {
                log("Error starting Tor: " + e.getMessage());
            }
        }).start();
    }

    private File prepareTorrc() throws IOException {
        File appTorServiceDir = new File(context.getFilesDir().getParentFile(), "app_TorService");
        File torrc = new File(appTorServiceDir, "torrc-defaults");

        List<String> bridgeConfigs;
        List<String> overrideSnapshot = null;
        synchronized (this) {
            if (bridgeOverride != null && !bridgeOverride.isEmpty()) {
                overrideSnapshot = new ArrayList<>(bridgeOverride);
                bridgeOverride = null;
            }
        }
        if (overrideSnapshot != null) {
            bridgeConfigs = overrideSnapshot;
        } else {
            bridgeConfigs = TorBridgeParser.getBridgeConfigs(context);
        }
        try (PrintWriter writer = new PrintWriter(new FileWriter(torrc))) {
            writer.println("Log notice stdout");
            writer.println("DataDirectory " + appTorServiceDir.getAbsolutePath() + "/data");
            writer.println("SocksPort auto");
            writer.println("ExitPolicy accept *:*");
            writer.println("NumCPUs 2");
            writer.println("ClientPreferIPv6ORPort 0");
            writer.println("ClientPreferIPv6DirPort 0");

            // Hidden service config
            writer.println("HiddenServiceDir " + hiddenServiceDir.getAbsolutePath());
            Server server = Server.getInstance(context);
            writer.println("HiddenServicePort 80 " + server.getSocketName());
            writer.println();

            if (!bridgeConfigs.isEmpty()) {
                String libObfs4Path = context.getApplicationInfo().nativeLibraryDir + "/libobfs4proxy.so";
                new File(libObfs4Path).setExecutable(true);
                String libWebtunnelPath = context.getApplicationInfo().nativeLibraryDir + "/libwebtunnel.so";
                File webtunnelFile = new File(libWebtunnelPath);
                if (webtunnelFile.exists()) webtunnelFile.setExecutable(true);
                String libSnowflakePath = context.getApplicationInfo().nativeLibraryDir + "/libsnowflake.so";
                File snowflakeFile = new File(libSnowflakePath);
                if (snowflakeFile.exists()) snowflakeFile.setExecutable(true);
                String libConjurePath = context.getApplicationInfo().nativeLibraryDir + "/libconjure.so";
                File conjureFile = new File(libConjurePath);
                if (conjureFile.exists()) conjureFile.setExecutable(true);

                List<String> sanitizedBridges = new ArrayList<>();
                for (String bridge : bridgeConfigs) {
                    if (bridge == null) {
                        continue;
                    }
                    String trimmed = bridge.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    String normalized = trimmed.toLowerCase(Locale.US);
                    if (normalized.contains("bridge conjure") || normalized.contains(" conjure ")) {
                        Log.i("TorManager", "Skipping conjure bridge: " + bridge);
                        continue;
                    }
                    if (!normalized.startsWith("bridge ")) {
                        trimmed = "Bridge " + trimmed;
                    }
                    sanitizedBridges.add(trimmed);
                }

                if (!sanitizedBridges.isEmpty()) {
                    writer.println("UseBridges 1");
                    Set<String> clients = new HashSet<>();
                    for (String bridge : sanitizedBridges) {
                        String normalized = bridge.toLowerCase(Locale.US);
                        writer.println(bridge);

                        if (normalized.contains("bridge obfs4") || normalized.contains(" obfs4 ")) {
                            clients.add("obfs4");
                        }
                        if (normalized.contains("bridge webtunnel") || normalized.contains(" webtunnel ")) {
                            clients.add("webtunnel");
                        }
                        if (normalized.contains("bridge snowflake") || normalized.contains(" snowflake ")) {
                            clients.add("snowflake");
                        }
                        if (normalized.contains("bridge meek") || normalized.contains(" meek_lite ") || normalized.contains(" meek ")) {
                            clients.add("meek");
                        }
                    }
                    writer.println();

                    if (clients.contains("obfs4")) {
                        writer.println("ClientTransportPlugin obfs4 exec " + libObfs4Path);
                    }
                    if (clients.contains("webtunnel")) {
                        writer.println("ClientTransportPlugin webtunnel exec " + libWebtunnelPath);
                    }
                    if (clients.contains("snowflake")) {
                        writer.println("ClientTransportPlugin snowflake exec " + libSnowflakePath);
                    }
                    writer.println();
                }
            }
        }

        return torrc;
    }

    public void stopTor() {
        new Thread(() -> {
            nativeStopTor();
        }).start();
    }

    public void applyBridgeOverrideAndRestart(List<String> bridges) {
        if (bridges == null || bridges.isEmpty()) {
            return;
        }
        synchronized (this) {
            bridgeOverride = new ArrayList<>(bridges);
        }
        new Thread(() -> {
            try {
                nativeStopTor();
            } catch (Exception e) {
                log("Error stopping Tor: " + e.getMessage());
            }
            ready = false;
            socksPort = -1;

            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            startTorServer();
        }).start();
    }

    // викликається з native через log_message()
    @SuppressWarnings("unused") // викликається з JNI
    private void onLog(String line) {
        if (line == null || line.isEmpty()) return;
        log(line);
        onPortInLogListener(line);
        if(line.contains("Bootstrapped 100%")) {
            ready = true;
            synchronized (restartLock) {
                restartAttempts = 0;
            }
        }
        synchronized (logListeners) {
            for (LogListener l : logListeners) {
                l.onTorLog(line); // розсилка всім слухачам
            }
        }
    }

    private void log(String s) {
        if (s == null) s = "";
        Log.i("TorManager", s);
    }

    private void onPortInLogListener(String line) {
        int i = line.indexOf("Socks listener listening on port ");
        if (i >= 0 && i < line.length()) {
            try {
                String num = line.substring(i).replaceAll("\\D+", "");
                int p = Integer.parseInt(num);
                socksPort = p;
                synchronized (portLock) { portLock.notifyAll(); }
            } catch (Exception ignored) {}
        }

        if (line.contains("We need more descriptors")) {
            Log.w(TAG, "Tor still missing directory info; keep waiting or retry");
        }
    }

    public int waitSocksPort(long timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (portLock) {
            while (socksPort <= 0) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new IOException("SOCKS port not ready");
                try { portLock.wait(left); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            return socksPort;
        }
    }

    public int getPort() throws IOException {
        return waitSocksPort(30_000);
    }

    public String getOnion() {
        return domain.trim();
    }

    public String getID() {
        return domain.replace(".onion", "").trim();
    }

    public File getHiddenServiceDir() {
        return hiddenServiceDir;
    }

    public String getStatus() {
        return status;
    }

    public boolean isReady() {
        return ready;
    }

    public byte[] pubkey() {
        try {
            File secret = new File(getHiddenServiceDir(), "hs_ed25519_public_key");
            if (!secret.exists()) {
                log("Public key file missing!");
                return null;
            }
            return Ed25519Signature.getEd25519PublicKey(getHiddenServiceDir());
        } catch (IOException e) {
            log("pubkey error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public byte[] sign(byte[] msg) {
        try {
            File secret = new File(getHiddenServiceDir(), "hs_ed25519_secret_key");
            if (!secret.exists()) {
                log("Private key file missing!");
                return null;
            }
            return Ed25519Signature.signWithEd25519(getHiddenServiceDir(), msg);
        } catch (IOException e) {
            log("Sign error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public boolean checksig(byte[] ed25519PubKey32, byte[] sig, byte[] msg) {
        try {
            return Ed25519Signature.checkEd25519Signature(ed25519PubKey32, sig, msg);
        } catch (Exception e) {
            log("checksig exception: " + e.getMessage());
            return false;
        }
    }

    public interface LogListener {
        void onTorLog(String line);
    }

    private final List<LogListener> logListeners = new ArrayList<>();

    public void addLogListener(LogListener listener) {
        synchronized (logListeners) {
            logListeners.add(listener);
        }
    }

    public void removeLogListener(LogListener listener) {
        synchronized (logListeners) {
            logListeners.remove(listener);
        }
    }

    public void reportSocksFailure(IOException exception) {
        if (exception == null) return;
        String message = exception.getMessage();
        if (message == null) {
            return;
        }
        boolean relevant = message.contains("127.0.0.1") &&
                (message.contains("ECONNREFUSED") || message.contains("ENETUNREACH") || message.contains("EPIPE"));
        if (!relevant) {
            return;
        }
        ensureTorAlive("SOCKS failure: " + message);
    }

    private void ensureTorAlive(String reason) {
        int currentPort = socksPort;
        if (!ready || currentPort <= 0) {
            return;
        }
        boolean reachable = false;
        try (Socket test = new Socket()) {
            test.connect(new InetSocketAddress("127.0.0.1", currentPort), 3_000);
            reachable = true;
        } catch (IOException ignored) {
        }
        if (reachable) {
            return;
        }
        boolean refreshBridges = false;
        synchronized (restartLock) {
            if (restartScheduled) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastRestartAttemptMs > RESTART_WINDOW_MS) {
                restartAttempts = 0;
            }
            restartAttempts++;
            lastRestartAttemptMs = now;
            if (restartAttempts >= MAX_RESTARTS_BEFORE_BRIDGE_REFRESH) {
                refreshBridges = true;
                restartAttempts = 0;
            }
            restartScheduled = true;
        }
        final boolean refreshBridgesFinal = refreshBridges;
        log("Restarting Tor (" + reason + ")");
        new Thread(() -> {
            try {
                nativeStopTor();
            } catch (Exception e) {
                log("nativeStopTor during restart failed: " + e.getMessage());
            }
            if (refreshBridgesFinal) {
                try {
                    List<String> refreshed = TorBridgeParser.refreshBridgeConfigs(context);
                    synchronized (TorManager.this) {
                        bridgeOverride = new ArrayList<>(refreshed);
                    }
                    log("Bridge cache refreshed after repeated failures");
                } catch (Exception e) {
                    log("Failed to refresh bridges: " + e.getMessage());
                }
            }
            ready = false;
            socksPort = -1;
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException ignored) {
            }
            startTorServer();
            synchronized (restartLock) {
                restartScheduled = false;
            }
        }).start();
    }
}
