package onion.network;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import net.freehaven.tor.control.ConfigEntry;
import net.freehaven.tor.control.TorControlConnection;

import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import onion.network.helpers.Ed25519Signature;
import onion.network.helpers.TorBridgeParser;
import onion.network.helpers.Utils;
import onion.network.ui.MainActivity;

public class TorManager {

    private static TorManager instance = null;
    private Context context;

    private volatile File hiddenServiceDir;
    private volatile String domain = "";
    private Listener listener = null;
    private LogListener logListener;
    private String status = "";
    private volatile boolean ready = false;

    private TorService torService;
    private TorControlConnection torControlConnection;

    private BroadcastReceiver torStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object value = extras.get(key);
                    if (value instanceof String) {
                        String stringValue = (String) value;
                        log("Tor status: " + stringValue);
                        if (stringValue.toLowerCase().contains("bridge")) {
                            log("Tor is connecting via a bridge: " + stringValue);
                        }
                        if (stringValue.equals("ON")) {
                            MainActivity mainActivity = MainActivity.getInstance();
                            if (mainActivity != null) {
                                mainActivity.startHostService();
                            }
                        }
                        stat(stringValue);
                    }
                }
            }
        }
    };

    public TorManager(Context c) {
        this.context = c;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(torStatusReceiver,
                    new IntentFilter(TorService.ACTION_STATUS), Context.RECEIVER_EXPORTED);
        } else {
            ContextCompat.registerReceiver(context, torStatusReceiver, new IntentFilter(TorService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        try {
            stopTor();
        } catch (IOException e) {
            //ignore
        }
        startTorServer();
    }

    synchronized public static TorManager getInstance(Context context) {
        if (instance == null) {
            instance = new TorManager(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Оновлений startTorServer — мінімальні правки:
     *  - створює каталоги + torrc-defaults тільки якщо його немає (щоб не дублювати записи)
     *  - не блокує UI: все очікування робиться в бекграунд-потоці
     *  - чекає BOOTSTRAP=100 перед setConf / signal / читанням hostname
     */
    public void startTorServer() {
        // Підготуємо каталоги і (опціонально) torrc-defaults до старту Tor, але тільки якщо їх нема.
        File appTorServiceDir = new File(context.getFilesDir().getParentFile(), "app_TorService");
        if (!appTorServiceDir.exists()) appTorServiceDir.mkdirs();

        hiddenServiceDir = new File(appTorServiceDir, "app_TorHiddenService");
        if (!hiddenServiceDir.exists()) hiddenServiceDir.mkdirs();

        // Тепер биндимся до сервісу (це не буде блокувати UI).
        Intent intent = new Intent(context, TorService.class);
        context.bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                TorService.LocalBinder binder = (TorService.LocalBinder) service;
                torService = binder.getService();

                // Все подальше робимо в бекграунд-потоці — щоб НЕ блокувати onServiceConnected/UI thread.
                new Thread(() -> {
                    try {

                        // Збираємо мости (TorBridgeParser може повертати рядки як "obfs4 ...", або вже з "Bridge ...")
                        List<String> bridgeLines = TorBridgeParser.parseBridges();
                        List<String> bridgeConfigs = getBridgeConfigs(bridgeLines);

                        File torcc = new File(appTorServiceDir, "torrc-defaults");
                        try (FileWriter fileWriter = new FileWriter(torcc, false);
                             PrintWriter printWriter = new PrintWriter(fileWriter)) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("Log notice stdout\n");
                            sb.append("HiddenServiceDir ").append(hiddenServiceDir.getAbsolutePath()).append("\n");
                            sb.append("HiddenServicePort 80 127.0.0.1:8080\n");
                            log("Bridge configs to set: " + bridgeConfigs);
                            // Передаємо всі мости одним викликом (якщо список не пустий)
                            if (!bridgeConfigs.isEmpty()) {
                                sb.append("UseBridges 1\n");
                                for (String b : bridgeConfigs) {
                                    sb.append(b + "\n");
                                }
                            }
                            printWriter.print(sb.toString());
                        } catch (IOException e) {
                            log("Failed to write torrc-defaults: " + e.getMessage());
                        }

                        // Чекаємо поки TorService створить control connection (обережно, таймаут).
                        int waitForControl = 0;
                        while (torService.getTorControlConnection() == null && waitForControl < 120) { // max ~60s
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException ie) {
                                log("Interrupted while waiting for TorControlConnection");
                                return;
                            }
                            waitForControl++;
                        }

                        torControlConnection = torService.getTorControlConnection();
                        if (torControlConnection == null) {
                            log("Tor control connection not available after wait");
                            return;
                        }

                        // Аутентифікація
                        torControlConnection.authenticate(new byte[0]);

                        // Чекаємо BOOTSTRAP=100 (Tor повністю ініціалізувався)
                        int bootstrapRetries = 0;
                        boolean bootDone = false;
                        while (bootstrapRetries < 240) {
                            try {
                                String phase = torControlConnection.getInfo("status/bootstrap-phase");
                                log("Bootstrap-phase: " + phase);
                                if (phase != null && phase.contains("PROGRESS=100")) {
                                    bootDone = true;
                                    break;
                                }
                            } catch (IOException ioe) {
                                // іноді getInfo може кидати поки Tor ще в процесі старту
                            }
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ie) {
                                log("Interrupted while waiting for bootstrap");
                                return;
                            }
                            bootstrapRetries++;
                        }
                        if (!bootDone) {
                            log("Warning: BOOTSTRAP=100 not reached within timeout — continuing but operations may fail");
                        }

                        // Запускаємо підключення (NEWNYM) і просимо RELOAD (безпечніше після bootstrap)
                        try {
                            torControlConnection.signal("NEWNYM");
                        } catch (IOException ioe) {
                            log("NEWNYM failed: " + ioe.getMessage());
                        }
                        try {
                            torControlConnection.signal("RELOAD");
                        } catch (IOException ioe) {
                            log("RELOAD failed: " + ioe.getMessage());
                        }

                        if (!bootDone) {
                            log("ERROR: Tor bootstrap not completed, aborting hidden service setup.");
                            return;
                        }

                        // Чекаємо генерацію ключів hidden service (даємо трохи більше часу)
                        File hostnameFile = new File(hiddenServiceDir, "hostname");
                        File secretKeyFile = new File(hiddenServiceDir, "hs_ed25519_secret_key");

                        int retries = 0;
                        while ((!hostnameFile.exists() || !secretKeyFile.exists()) && retries < 120) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ie) {
                                log("Interrupted while waiting for hidden service files");
                                return;
                            }
                            retries++;
                        }

                        List<ConfigEntry> configEntries = torControlConnection.getConf("Bridge");
                        for(ConfigEntry configEntry : configEntries) {
                            log("Bridges in Tor config: " + configEntry.key + " " + configEntry.value);
                        }
                        List<ConfigEntry> useBridges = torControlConnection.getConf("UseBridges");
                        for(ConfigEntry useBridge : useBridges) {
                            log("UseBridges=" + useBridge.key + " " + useBridge.value);
                        }

                        if (hostnameFile.exists()) {
                            domain = Utils.readFileAsString(hostnameFile).trim();
                            log("Onion domain ready: " + domain);
                            ready = true;
                        } else {
                            log("ERROR: hostname file not generated by Tor");
                        }

                        if (!secretKeyFile.exists()) {
                            log("ERROR: hs_ed25519_secret_key not generated by Tor");
                        }

                        try {
                            String torStatus = torControlConnection.getInfo("status/circuit-established");
                            log("Tor Circuit Status: " + torStatus);
                        } catch (IOException ioe) {
                            log("Could not get status/circuit-established: " + ioe.getMessage());
                        }

                    } catch (IOException e) {
                        log("Error in configuring hidden service: " + e.getMessage());
                        e.printStackTrace();
                    }
                }).start();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                torService = null;
                torControlConnection = null;
            }
        }, Context.BIND_AUTO_CREATE);
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
        bridgeConfigs.add("Bridge meek_lite 0.0.2.0:2 url=https://meek.azureedge.net/ front=ajax.aspnetcdn.com");
        bridgeConfigs.add("Bridge snowflake 0.0.3.0:1");
        return bridgeConfigs;
    }

    public void stopTor() throws IOException {
        Intent intent = new Intent(context, TorService.class);
        context.stopService(intent);
        if (torControlConnection != null) {
            torControlConnection.signal("HALT");
        }
    }

    public void stopReceiver() {
        try {
            context.unregisterReceiver(torStatusReceiver);
        } catch (IllegalArgumentException ignore) {}
    }

    void stat(String s) {
        status = s;
        if (listener != null) listener.onChange();
        LogListener l = logListener;
        if (l != null) {
            l.onLog();
        }
        log(s);
    }

    private void log(String s) {
        if (s == null) s = "";
        Log.i("TorManager", s);
    }

    public int getPort() {
        return TorService.httpTunnelPort;
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

    public void setLogListener(LogListener l) {
        logListener = l;
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

    public interface Listener {
        void onChange();
    }

    public interface LogListener {
        void onLog();
    }
}