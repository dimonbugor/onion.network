package onion.network.tor;

import java.util.Collections;
import java.util.List;

/**
 * Wrapper for responses from BridgeDB.
 */
public final class BridgeFetchResult {

    public enum Type {
        SUCCESS,
        CAPTCHA_REQUIRED,
        EMPTY
    }

    private final Type type;
    private final List<String> bridges;
    private final byte[] captchaImage;
    private final String captchaSecret;

    private BridgeFetchResult(Type type, List<String> bridges, byte[] captchaImage, String captchaSecret) {
        this.type = type;
        this.bridges = bridges == null ? Collections.emptyList() : Collections.unmodifiableList(bridges);
        this.captchaImage = captchaImage;
        this.captchaSecret = captchaSecret;
    }

    public static BridgeFetchResult success(List<String> bridges) {
        return new BridgeFetchResult(Type.SUCCESS, bridges, null, null);
    }

    public static BridgeFetchResult captchaRequired(byte[] image, String secret) {
        return new BridgeFetchResult(Type.CAPTCHA_REQUIRED, Collections.emptyList(), image, secret);
    }

    public static BridgeFetchResult empty() {
        return new BridgeFetchResult(Type.EMPTY, Collections.emptyList(), null, null);
    }

    public Type getType() {
        return type;
    }

    public List<String> getBridges() {
        return bridges;
    }

    public byte[] getCaptchaImage() {
        return captchaImage;
    }

    public String getCaptchaSecret() {
        return captchaSecret;
    }
}
