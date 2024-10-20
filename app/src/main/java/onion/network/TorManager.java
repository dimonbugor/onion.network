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
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.digest.DigestUtils;
import org.spongycastle.asn1.ASN1OutputStream;
import org.spongycastle.asn1.x509.RSAPublicKeyStructure;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.torproject.jni.TorService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;

public class TorManager {

    private static String torservdir = "torserv";
    private static TorManager instance = null;
    private Context context;

    private int port = -1;
    private String domain = "";
    private Listener listener = null;
    private LogListener logListener;
    private String status = "";
    private boolean ready = false;

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

        final Server server = Server.getInstance(context);

        domain = Utils.filestr(new File(getServiceDir(), "hostname")).trim();
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

    public void startTor() {
        Intent intent = new Intent(context, TorService.class);
        context.startService(intent);
    }

    public void stopTor() {
        Intent intent = new Intent(context, TorService.class);
        context.stopService(intent);
    }

    public void stopReceiver() {
        context.unregisterReceiver(torStatusReceiver);
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
        if(s == null) {
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
        return new File(context.getFilesDir(), torservdir);
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

    KeyFactory getKeyFactory() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        try {
            return KeyFactory.getInstance("RSA", "BC");
        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    RSAPrivateKey getPrivateKey() {
        String priv = Utils.filestr(new File(getServiceDir(), "private_key"));
        //log(priv);
        priv = priv.replace("-----BEGIN RSA PRIVATE KEY-----\n", "");
        priv = priv.replace("-----END RSA PRIVATE KEY-----", "");
        priv = priv.replaceAll("\\s", "");
        //log(priv);
        byte[] data = Base64.decode(priv, Base64.DEFAULT);
        //log("" + data.length);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(data);
        //log(keySpec.toString());
        try {
            return (RSAPrivateKey) getKeyFactory().generatePrivate(keySpec);
        } catch (InvalidKeySpecException ex) {
            throw new Error(ex);
        }
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
