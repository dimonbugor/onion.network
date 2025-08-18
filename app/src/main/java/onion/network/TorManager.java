package onion.network;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import onion.network.helpers.Ed25519Signature;
import onion.network.helpers.TorBridgeParser;

public class TorManager {
    private static TorManager instance = null;
    private Context context;

    private volatile File hiddenServiceDir;
    private volatile String domain = "";
    private String status = "";
    private volatile boolean ready = false;

    private native boolean nativeStartTor(String torrcPath, String dataDir);

    private native boolean nativeIsBootstrapped();

    private native String nativeGetHiddenServiceDomain(String hsDir);

    private native void nativeStopTor();

    private native int nativeGetSocksPort();

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
                while (!nativeIsBootstrapped()) {
                    Thread.sleep(1000);
                }

                // Отримуємо onion адресу
                domain = nativeGetHiddenServiceDomain(hiddenServiceDir.getAbsolutePath());
                if (!domain.isEmpty()) {
                    ready = true;
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

        List<String> bridgeConfigs = getBridgeConfigs(TorBridgeParser.parseBridges());

        try (PrintWriter writer = new PrintWriter(new FileWriter(torrc))) {
            writer.println("Log notice stdout");
            writer.println("DataDirectory " + appTorServiceDir.getAbsolutePath() + "/data");
            writer.println("SocksPort auto");
            writer.println("ExitPolicy accept *:*");
            writer.println("NumCPUs 2");

            // Hidden service config
            writer.println("HiddenServiceDir " + hiddenServiceDir.getAbsolutePath());
            writer.println("HiddenServiceVersion 3");
            writer.println("HiddenServicePort 80 127.0.0.1:8080");

//            if (!bridgeConfigs.isEmpty()) {
//                writer.println("UseBridges 1");
//                for (String bridge : bridgeConfigs) {
//                    writer.println(bridge);
//                }
//                writer.println("ClientUseIPv4 1");
//                writer.println("ClientUseIPv6 1");
//                writer.println("ClientPreferIPv6ORPort 1");
//            }
        }

        return torrc;
    }

    private static List<String> getBridgeConfigs(List<String> bridgeLines) {
        List<String> bridgeConfigs = new ArrayList<>();

        if (bridgeLines != null && !bridgeLines.isEmpty()) {
            for (String bridge : bridgeLines) {
                if (bridge == null) continue;
                String val = bridge.trim();
                if (val.length() == 0) continue;
                // Якщо парсер вже повернув 'Bridge ...' — прибираємо префікс
                if (val.toLowerCase().startsWith("bridge ")) {
                    val = val.substring(6).trim();
                }
                bridgeConfigs.add("Bridge " + val);
            }
        }

        // Додаємо meek_lite і snowflake
        bridgeConfigs.add("Bridge meek 0.0.2.0:3 url=https://meek.azureedge.net/ front=ajax.aspnetcdn.com");
        bridgeConfigs.add("Bridge meek_lite 192.0.2.20:80 url=https://1314488750.rsc.cdn77.org front=www.phpmyadmin.net utls=HelloRandomizedALPN");
        bridgeConfigs.add("Bridge snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn");
        bridgeConfigs.add("Bridge snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn");
        return bridgeConfigs;
    }

    public void stopTor() {
        nativeStopTor();
    }

    // викликається з native через log_message()
    @SuppressWarnings("unused") // викликається з JNI
    private void onLog(String line) {
        if (line == null) return;
        log(line);
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

    public int getPort() {
        return nativeGetSocksPort();
    }

    public String getOnion() {
        return domain.trim();
    }

    public String getID() {
        return domain.replace(".onion", "").trim();
    }

    File getHiddenServiceDir() {
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

    public boolean checksig(byte[] pubkey, byte[] sig, byte[] msg) {
        return Ed25519Signature.checkEd25519Signature(pubkey, sig, msg);
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