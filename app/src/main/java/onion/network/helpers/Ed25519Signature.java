package onion.network.helpers;

import android.util.Base64;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.security.Security;
import java.util.Arrays;

public class Ed25519Signature {

    static {
        // Додаємо стандартний BouncyCastle як постачальника криптографії
        Security.addProvider(new BouncyCastleProvider());
    }

    public static byte[] getEd25519PublicKey(File hiddenServiceDir) throws IOException {
        // Шлях до файлу публічного ключа Ed25519 у прихованому сервісі
        File publicKeyFile = new File(hiddenServiceDir, "hs_ed25519_public_key");

        if (publicKeyFile.exists()) {
            // Зчитуємо вміст файлу як масив байтів
            byte[] publicKey = Files.readAllBytes(publicKeyFile.toPath());
            // Повертаємо байти публічного ключа (без заголовка)
            return Arrays.copyOfRange(publicKey, 32, publicKey.length); // перші 32 байти — це заголовок
        } else {
            throw new FileNotFoundException("Public key file not found");
        }
    }

    public static byte[] signWithEd25519(File hiddenServiceDir, byte[] msg) throws IOException {
        // Читаємо приватний ключ з файлу hs_ed25519_secret_key
        byte[] privateKeyBytes = Files.readAllBytes(
                new File(hiddenServiceDir, "hs_ed25519_secret_key").toPath());

        // Ініціалізуємо приватний ключ Ed25519
        Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(privateKeyBytes, 0);

        // Створюємо підписувач
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);

        // Оновлюємо підписувач повідомленням
        signer.update(msg, 0, msg.length);

        // Створюємо підпис
        return signer.generateSignature();
    }

    public static boolean checkEd25519Signature(byte[] pubkey, byte[] sig, byte[] msg) {
        // Ініціалізуємо публічний ключ Ed25519
        Ed25519PublicKeyParameters publicKey = new Ed25519PublicKeyParameters(pubkey, 0);

        // Створюємо перевіряючий підписувач
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, publicKey);  // false означає режим перевірки підпису

        // Оновлюємо повідомленням
        verifier.update(msg, 0, msg.length);

        // Перевіряємо підпис
        return verifier.verifySignature(sig);
    }

    public static String base64Encode(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    public static byte[] base64Decode(String data) {
        return Base64.decode(data, Base64.NO_WRAP);
    }
}
