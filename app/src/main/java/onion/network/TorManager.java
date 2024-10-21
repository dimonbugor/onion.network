/*
 * Network.onion - fully distributed p2p social network using onion routing
 *
 * http://play.google.com/store/apps/details?id=onion.network
 * http://onionapps.github.io/Network.onion/
 * http://github.com/onionApps/Network.onion
 *
 * Author: http://github.com/onionApps - http://jkrnk73uid7p5thz.onion - bitcoin:1kGXfWx8PHZEVriCNkbP5hzD15HS4AyKf
 */

package onion.network;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import net.freehaven.tor.control.ConfigEntry;
import net.freehaven.tor.control.TorControlConnection;

import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.digest.DigestUtils;
import org.spongycastle.asn1.ASN1OutputStream;
import org.spongycastle.asn1.x509.RSAPublicKeyStructure;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.torproject.jni.TorService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import info.guardianproject.netcipher.proxy.OrbotHelper;

public class TorManager {

    private static TorManager instance = null;
    private Context context;

    private File hiddenServiceDir;
    private String domain = "";
    private Listener listener = null;
    private LogListener logListener;
    private String status = "";
    private boolean ready = false;

    private Server server;
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
                        // Використовуйте stringValue, як вам потрібно
                        log("Tor status: " + stringValue);
                        if(stringValue.equals("ON")) {
                            try {
                                torControlConnection.signal("DEBUG");
                                torControlConnection.launchThread(true);
                                torControlConnection.authenticate(new byte[0]); // Аутентифікація

                                // Створіть директорію, якщо не існує
                                if (!hiddenServiceDir.exists()) {
                                    hiddenServiceDir.mkdirs();
                                }

                                // Налаштування HiddenServiceDir
                                //torControlConnection.setConf("HiddenServiceDir", hiddenServiceDir.getAbsolutePath());
                                torControlConnection.setConf("HiddenServiceDir",
                                        Uri.fromFile(hiddenServiceDir).getEncodedPath());

                                // Налаштування HiddenServicePort
                                torControlConnection.setConf("HiddenServicePort", "80 " + getSocketName());

                                // Налаштування логів
                                torControlConnection.setConf("Log", "debug file " +
                                        new File(hiddenServiceDir, "LOG").getAbsolutePath());

                                // Виконання команди перезавантаження
                                torControlConnection.signal("RELOAD");
                                torControlConnection.getInfo("md/id/OR");
                            } catch (IOException e) {
                                Log.e("Tor", "Error during Tor configuration: " + e.getMessage(), e);
                            } finally {
                                domain = Utils.filestr(new File(hiddenServiceDir, "hostname")).trim();
                                log(domain);
                                if (listener != null) listener.onChange();
                            }
                        }
                        stat(stringValue);
                    }
                }
            }
        }
    };

    private String getSocketName() {
        //return "127.0.0.1:8080";
        return "127.0.0.1:" + getHiddenServicePort();
    }

    public TorManager(Context c) {

        this.context = c;
        server = Server.getInstance(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(torStatusReceiver,
                    new IntentFilter(TorService.ACTION_STATUS), Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(torStatusReceiver,
                    new IntentFilter(TorService.ACTION_STATUS));
        }

        hiddenServiceDir = new File(getServiceDir(), "hidden_service");
        if (!hiddenServiceDir.exists()) {
            hiddenServiceDir.mkdirs();
        }

        if (!hiddenServiceDir.canWrite()) {
            // Лог або обробка, якщо директорія не має дозволів на запис
            Log.e("Tor", "Directory is not writable: " + hiddenServiceDir.getAbsolutePath());
        }

        //domain = Utils.filestr(new File(getServiceDir(), "hostname")).trim();
        domain = Utils.filestr(new File(getServiceDir(), "hidden_service")).trim();
        log(domain);

        stopTor();
        startTor();
    }

    synchronized public static TorManager getInstance(Context context) {
        if (instance == null) {
            instance = new TorManager(context.getApplicationContext());
        }
        return instance;
    }

    static String computeID(RSAPublicKeySpec pubkey) {
        RSAPublicKeyStructure myKey = new RSAPublicKeyStructure(pubkey.getModulus(), pubkey.getPublicExponent());
        ByteArrayOutputStream bs = new ByteArrayOutputStream();
        ASN1OutputStream as = new ASN1OutputStream(bs);
        try {
            as.writeObject(myKey.toASN1Object());
        } catch (IOException ex) {
            throw new Error(ex);
        }
        byte[] b = bs.toByteArray();
        b = DigestUtils.getSha1Digest().digest(b);
        return new Base32().encodeAsString(b).toLowerCase().substring(0, 16);
    }

    public void startTor() {
        Intent intent = new Intent(context, TorService.class);
        context.bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                TorService.LocalBinder binder = (TorService.LocalBinder) service;
                torService = binder.getService();
                torControlConnection = torService.getTorControlConnection();
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
        context.unregisterReceiver(torStatusReceiver);
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

    public static int getHiddenServicePort() {
        //return 9051;
        return 31512;
    }

    private void log(String s) {
        if (s == null) {
            s = "";
        }
        Log.i("TorManager", s);
    }

    public int getPort() {
        return TorService.socksPort;
    }

    public String getOnion() {
        return domain.trim();
    }

    public String getID() {
        return domain.replace(".onion", "").trim();
    }

    File getServiceDir() {
        //return new File(context.getExternalFilesDir(null), torservdir);
        return TorService.getDefaultsTorrc(context).getParentFile();
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

    public KeyFactory getKeyFactory() {
        try {
            // Використовуйте стандартну реалізацію Android для RSA
            return KeyFactory.getInstance("RSA");
        } catch (NoSuchAlgorithmException ex) {
            throw new Error("RSA algorithm not available", ex);
        }
    }

    // Метод для відновлення публічного ключа з закодованої строки
    public PublicKey getPublicKey(String key) throws Exception {
        byte[] keyBytes = Base64.decode(key, Base64.DEFAULT);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        return getKeyFactory().generatePublic(spec);
    }

    PrivateKey getPrivateKey() throws InvalidKeySpecException {
        String priv = Utils.filestr(new File(getServiceDir(), "private_key"));
        //log(priv);
        priv = priv.replace("-----BEGIN RSA PRIVATE KEY-----\n", "");
        priv = priv.replace("-----END RSA PRIVATE KEY-----", "");
        priv = priv.replaceAll("\\s", "");
        byte[] keyBytes = Base64.decode(priv, Base64.DEFAULT);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return getKeyFactory().generatePrivate(spec);
    }

    RSAPrivateKeySpec getPrivateKeySpec() {
        try {
            return getKeyFactory().getKeySpec(getPrivateKey(), RSAPrivateKeySpec.class);
        } catch (InvalidKeySpecException ex) {
            throw new Error(ex);
        }
    }

    byte[] pubkey() {
        return getPrivateKeySpec().getModulus().toByteArray();
    }

    byte[] sign(byte[] msg) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(getPrivateKey());
            signature.update(msg);
            return signature.sign();
        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    boolean checksig(String id, byte[] pubkey, byte[] sig, byte[] msg) {
        RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(new BigInteger(pubkey), BigInteger.valueOf(65537));

        if (!id.equals(computeID(publicKeySpec))) {
            log("invalid id");
            return false;
        }

        PublicKey publicKey;
        try {
            publicKey = getKeyFactory().generatePublic(publicKeySpec);
        } catch (InvalidKeySpecException ex) {
            ex.printStackTrace();
            return false;
        }

        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initVerify(publicKey);
            signature.update(msg);
            return signature.verify(sig);
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    public interface Listener {
        void onChange();
    }

    public interface LogListener {
        void onLog();
    }


}
