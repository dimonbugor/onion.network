package onion.network;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import onion.network.helpers.Ed25519Signature;
import onion.network.helpers.TorBridgeParser;
import onion.network.helpers.Utils;
import onion.network.servers.Server;

public class TorManager {
    private static TorManager instance = null;
    private Context context;

    private volatile File hiddenServiceDir;
    private volatile String domain = "";
    private String status = "";
    private volatile boolean ready = false;

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

        List<String> bridgeConfigs = TorBridgeParser.getBridgeConfigs();

        try (PrintWriter writer = new PrintWriter(new FileWriter(torrc))) {
            writer.println("Log notice stdout");
//            writer.println("Log debug stdout");
            writer.println("DataDirectory " + appTorServiceDir.getAbsolutePath() + "/data");
            writer.println("SocksPort auto");
//            writer.println("SocksPort 9050");
            writer.println("ExitPolicy accept *:*");
            writer.println("NumCPUs 2");

            // Hidden service config
            writer.println("HiddenServiceDir " + hiddenServiceDir.getAbsolutePath());
//            writer.println("HiddenServiceVersion 3");
//            writer.println("HiddenServicePort 80 127.0.0.1:8080");
            Server server = Server.getInstance(context);
            writer.println("HiddenServicePort 80 " + server.getSocketName());
            writer.println();

            if (!bridgeConfigs.isEmpty()) {
                String libObfs4Path = context.getApplicationInfo().nativeLibraryDir + "/libobfs4proxy.so";
                new File(libObfs4Path).setExecutable(true);
                String libWebtunnelPath = context.getApplicationInfo().nativeLibraryDir + "/libwebtunnel.so";
                new File(libWebtunnelPath).setExecutable(true);
                String libSnowflakePath = context.getApplicationInfo().nativeLibraryDir + "/libsnowflake.so";
                new File(libSnowflakePath).setExecutable(true);
                String libMeekPath = Utils.copyAssetFile(context, "meek-client");

                writer.println("UseBridges 1");
                writer.println();

                Set<String> clients = new HashSet<>();
                for (String bridge : bridgeConfigs) {
                    if (bridge.contains("obfs4")) {
                        writer.println(bridge);
                        clients.add("obfs4");
                    }
                    if (bridge.contains("webtunnel")) {
                        writer.println(bridge);
                        clients.add("webtunnel");
                    }
                    if (bridge.contains("snowflake")) {
                        writer.println(bridge);
                        clients.add("snowflake");
                    }
                    if (bridge.contains("meek")) {
                        writer.println(bridge);
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
                if (clients.contains("meek")) {
                    writer.println("ClientTransportPlugin meek exec " + libMeekPath);
                }
                writer.println();

//                writer.println("ClientUseIPv4 1");
//                writer.println("ClientUseIPv6 1");
//                writer.println("ClientPreferIPv6ORPort 1");
            }
        }

        return torrc;
    }

    public void stopTor() {
        new Thread(() -> {
            nativeStopTor();
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

    private static String computeOnionV3(byte[] ed25519PubKey32) {
        if (ed25519PubKey32 == null || ed25519PubKey32.length != 32) {
            throw new IllegalArgumentException("ed25519 pubkey must be 32 bytes");
        }

        try {
            // version byte
            byte version = 0x03;

            // checksum input = ".onion checksum" || pubkey || version
            byte[] prefix = ".onion checksum".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] checksumInput = new byte[prefix.length + 32 + 1];
            System.arraycopy(prefix, 0, checksumInput, 0, prefix.length);
            System.arraycopy(ed25519PubKey32, 0, checksumInput, prefix.length, 32);
            checksumInput[checksumInput.length - 1] = version;

            // SHA3-256 (є в Java 9+, на Android – через BouncyCastle "BC")
            java.security.MessageDigest md;
            try {
                md = java.security.MessageDigest.getInstance("SHA3-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                md = java.security.MessageDigest.getInstance("SHA3-256", "BC");
            }
            byte[] hash = md.digest(checksumInput);

            // беремо перші 2 байти як checksum
            byte[] checksum2 = new byte[] { hash[0], hash[1] };

            // addr_bytes = pubkey || checksum || version
            byte[] addr = new byte[32 + 2 + 1];
            System.arraycopy(ed25519PubKey32, 0, addr, 0, 32);
            addr[32] = checksum2[0];
            addr[33] = checksum2[1];
            addr[34] = version;

            // Base32 без паддінгу, нижній регістр (RFC4648)
            org.apache.commons.codec.binary.Base32 b32 = new org.apache.commons.codec.binary.Base32(false); // no padding
            String onion = b32.encodeAsString(addr).toLowerCase(java.util.Locale.US);

            // має бути 56 символів
            if (onion.endsWith("=")) onion = onion.replace("=", "");
            return onion;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to compute onion v3", ex);
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
}