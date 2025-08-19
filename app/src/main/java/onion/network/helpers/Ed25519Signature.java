package onion.network.helpers;

import android.util.Base64;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Arrays;

public class Ed25519Signature {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static byte[] readKey(File file, String header, int keyLength, int skip) throws IOException {
        byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
        int headerIndex = new String(fileBytes, StandardCharsets.US_ASCII).indexOf(header);
        if (headerIndex == -1) {
            throw new IOException("Header not found in key file: " + header);
        }
        int keyStart = headerIndex + header.length() + skip; // пропускаємо службові байти
        if (fileBytes.length < keyStart + keyLength) {
            throw new IOException("Key file too short");
        }
        return Arrays.copyOfRange(fileBytes, keyStart, keyStart + keyLength);
    }

    public static byte[] getEd25519PublicKey(File dir) throws IOException {
        return readKey(new File(dir, "hs_ed25519_public_key"),
                "== ed25519v1-public: type0 ==", 32, 3);
    }

    public static byte[] getEd25519SecretKey(File dir) throws IOException {
        return readKey(new File(dir, "hs_ed25519_secret_key"),
                "== ed25519v1-secret: type0 ==", 64, 3);
    }

    public static byte[] signWithEd25519(File hiddenServiceDir, byte[] msgBytes) throws IOException {
        if (msgBytes == null) {
            throw new IllegalArgumentException("Message to sign is null");
        }

        byte[] privateKeyBytes = getEd25519SecretKey(hiddenServiceDir);

        Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(privateKeyBytes, 0);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(msgBytes, 0, msgBytes.length);
        return signer.generateSignature();
    }

    public static boolean checkEd25519Signature(byte[] pubkey, byte[] sig, byte[] msgBytes) {
        if (pubkey == null || sig == null || msgBytes == null) {
            throw new IllegalArgumentException("Null argument provided to checkEd25519Signature");
        }
        Ed25519PublicKeyParameters publicKey = new Ed25519PublicKeyParameters(pubkey, 0);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, publicKey);
        verifier.update(msgBytes, 0, msgBytes.length);
        return verifier.verifySignature(sig);
    }

    public static String base64Encode(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Data to encode is null");
        }
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    public static byte[] base64Decode(String data) {
        if (data == null) {
            throw new IllegalArgumentException("Data to decode is null");
        }
        return Base64.decode(data, Base64.NO_WRAP);
    }
}