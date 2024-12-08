package onion.network;

import static info.pluggabletransports.dispatch.transports.Obfs4Transport.setPropertiesFromBridgeString;

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

import net.freehaven.tor.control.TorControlConnection;

import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Properties;

import info.pluggabletransports.dispatch.Connection;
import info.pluggabletransports.dispatch.Dispatcher;
import info.pluggabletransports.dispatch.Transport;
import info.pluggabletransports.dispatch.transports.MeekTransport;
import info.pluggabletransports.dispatch.transports.Obfs4Transport;
import onion.network.helpers.Ed25519Signature;

import onion.network.helpers.TorBridgeParser;
import onion.network.helpers.Utils;
import onion.network.ui.MainActivity;

public class TorManager {

    private static TorManager instance = null;
    private Context context;

    private File hiddenServiceDir;
    private String domain = "";
    private Listener listener = null;
    private LogListener logListener;
    private String status = "";
    private boolean ready = false;

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
                        if(stringValue.equals("ON")) {
                            MainActivity mainActivity = MainActivity.getInstance();
                            if(mainActivity != null) {
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
            context.registerReceiver(torStatusReceiver,
                    new IntentFilter(TorService.ACTION_STATUS));
        }

        domain = Utils.readFileAsString(new File(getHiddenServiceDir(), "hostname")).trim();
        log(domain);

        stopTor();
        startTorServer();
    }

    synchronized public static TorManager getInstance(Context context) {
        if (instance == null) {
            instance = new TorManager(context.getApplicationContext());
        }
        return instance;
    }

    public void startTorServer() {
        Intent intent = new Intent(context, TorService.class);
        context.bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                TorService.LocalBinder binder = (TorService.LocalBinder) service;
                torService = binder.getService();
                while (torService.getTorControlConnection() == null) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                torControlConnection = torService.getTorControlConnection();
                if (torControlConnection != null) {
                    new Thread(() -> {
                        try {
                            // Аутентифікація Tor
                            torControlConnection.authenticate(new byte[0]);

                            // Створення і налаштування прихованого сервісу
                            File appTorServiceDir = new File(context.getFilesDir().getParentFile(), "app_TorService");
                            if (!appTorServiceDir.exists()) {
                                appTorServiceDir.mkdirs();
                            }

                            hiddenServiceDir = new File(appTorServiceDir, "app_TorHiddenService");
                            if (!hiddenServiceDir.exists()) {
                                hiddenServiceDir.mkdirs();
                            }

                            // Налаштування Tor як прихований сервіс
                            File torcc = new File(appTorServiceDir, "torrc-defaults");
                            StringBuilder sb = new StringBuilder();
                            sb.append("Log notice stdout\n");
                            sb.append("HiddenServiceDir ").append(hiddenServiceDir.getAbsolutePath()).append("\n");
                            sb.append("HiddenServicePort 80 127.0.0.1:8080\n");

                            // Запис конфігурації для Tor
                            FileWriter fileWriter = new FileWriter(torcc, true);
                            PrintWriter printWriter = new PrintWriter(fileWriter);
                            printWriter.print(sb);
                            printWriter.close();

                            // Оновлення конфігурації Tor
                            torControlConnection.signal("RELOAD");

                            // Перевірка статусу
                            String torStatus = torControlConnection.getInfo("status/circuit-established");
                            log("Tor Circuit Status: " + torStatus);

                            // Отримуємо адресу .onion
                            domain = Utils.readFileAsString(new File(getHiddenServiceDir(), "hostname")).trim();

                        } catch (IOException e) {
                            log("Error in configuring hidden service: " + e.getMessage());
                        }
                    }).start();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                torService = null;
                torControlConnection = null;
            }
        }, Context.BIND_AUTO_CREATE);
    }

    public void stopTor() {
        Intent intent = new Intent(context, TorService.class);
        context.stopService(intent);
    }

    public void stopReceiver() {
        try {
            context.unregisterReceiver(torStatusReceiver);
        } catch (IllegalArgumentException ignore){}
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
        if (s == null) {
            s = "";
        }
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
            return Ed25519Signature.getEd25519PublicKey(getHiddenServiceDir());
        } catch (IOException e) {
            log("pubkey error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public byte[] sign(byte[] msg) {
        try {
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