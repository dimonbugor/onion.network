package onion.network.tor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import onion.network.helpers.NetworkUtils;
import onion.network.settings.Settings;

public class TorBridgeParser {

    private static final String TAG = "TorBridgeParser";
    private static final String PREF_BRIDGE_CACHE = "tor_bridge_cache_lines";
    private static final BridgeDbClient BRIDGE_DB_CLIENT = new BridgeDbClient();
    private static final ExecutorService CAPTCHA_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicReference<CaptchaChallenge> pendingCaptcha = new AtomicReference<>(null);
    private static final CopyOnWriteArrayList<CaptchaListener> captchaListeners = new CopyOnWriteArrayList<>();

    private static final String[] DEFAULT_BRIDGES = new String[]{
//            "Bridge snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
//            "Bridge snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
            "Bridge conjure 143.110.214.222:80 url=https://registration.refraction.network.global.prod.fastly.net/api front=cdn.sstatic.net"
    };
    private static final int MAX_FETCH_ATTEMPTS = 2;

    public static synchronized List<String> getBridgeConfigs(Context context) {
        if (NetworkUtils.isEmulator()) {
            Log.i(TAG, "Емулятор виявлено — використовуємо пряме підключення без мостів");
            persistBridges(context, Collections.emptyList());
            return Collections.emptyList();
        }
        boolean ipv6Allowed = NetworkUtils.hasGlobalIpv6Connectivity();
        Set<String> bridgeConfigs = new LinkedHashSet<>();
        Map<String, String> fingerprintIndex = new HashMap<>();

        for (String cached : loadCachedBridges(context)) {
            storeBridge(bridgeConfigs, fingerprintIndex, cached);
        }

        collectBridgeLines(context, bridgeConfigs, fingerprintIndex, "obfs4", false, MAX_FETCH_ATTEMPTS, 1);
        if (ipv6Allowed) {
            collectBridgeLines(context, bridgeConfigs, fingerprintIndex, "obfs4", true, MAX_FETCH_ATTEMPTS, 1);
        }

        for (String bridge : DEFAULT_BRIDGES) {
            storeBridge(bridgeConfigs, fingerprintIndex, bridge);
        }

        List<String> result = new ArrayList<>(bridgeConfigs);
        if (!ipv6Allowed) {
            result.removeIf(TorBridgeParser::isIpv6Line);
        }
        persistBridges(context, result);
        return result;
    }

    public static void addCaptchaListener(CaptchaListener listener) {
        if (listener != null) {
            captchaListeners.add(listener);
        }
    }

    public static void removeCaptchaListener(CaptchaListener listener) {
        captchaListeners.remove(listener);
    }

    public static CaptchaChallenge getPendingCaptcha() {
        return pendingCaptcha.get();
    }

    public static void solveCaptcha(Context context,
                                    String response,
                                    CaptchaSolveCallback callback) {
        CaptchaChallenge challenge = pendingCaptcha.get();
        if (challenge == null) {
            if (callback != null) {
                postToMain(() -> callback.onFailure(new IllegalStateException("No captcha pending")));
            }
            return;
        }
        String answer = response == null ? "" : response.trim();
        if (answer.isEmpty()) {
            if (callback != null) {
                postToMain(() -> callback.onFailure(new IllegalArgumentException("Captcha answer is empty")));
            }
            return;
        }

        CAPTCHA_EXECUTOR.execute(() -> {
            try {
                BridgeFetchResult result = BRIDGE_DB_CLIENT.fetch(
                        challenge.transport,
                        challenge.ipv6,
                        answer,
                        challenge.secret
                );
                if (result.getType() == BridgeFetchResult.Type.SUCCESS) {
                    pendingCaptcha.compareAndSet(challenge, null);
                    List<String> merged = mergeAndPersist(context, result.getBridges());
                    if (callback != null) {
                        postToMain(() -> callback.onSuccess(merged));
                    }
                } else if (result.getType() == BridgeFetchResult.Type.CAPTCHA_REQUIRED) {
                    CaptchaChallenge newChallenge = new CaptchaChallenge(
                            result.getCaptchaImage(),
                            result.getCaptchaSecret(),
                            challenge.transport,
                            challenge.ipv6
                    );
                    notifyCaptcha(newChallenge);
                    if (callback != null) {
                        postToMain(() -> callback.onNewChallenge(newChallenge, "Captcha incorrect, try again."));
                    }
                } else {
                    if (callback != null) {
                        postToMain(() -> callback.onFailure(new IOException("BridgeDB returned no data")));
                    }
                }
            } catch (IOException e) {
                if (callback != null) {
                    postToMain(() -> callback.onFailure(e));
                }
            }
        });
    }

    private static void collectBridgeLines(Context context,
                                           Set<String> bridgeConfigs,
                                           Map<String, String> fingerprintIndex,
                                           String transport,
                                           boolean ipv6,
                                           int attempts,
                                           int minTransportCount) {
        for (int i = 0; i < attempts; i++) {
            collectBridgeLinesOnce(context, bridgeConfigs, fingerprintIndex, transport, ipv6);
            if (minTransportCount > 0 && countTransport(bridgeConfigs, transport) >= minTransportCount) {
                break;
            }
        }
    }

    private static void collectBridgeLinesOnce(Context context,
                                               Set<String> bridgeConfigs,
                                               Map<String, String> fingerprintIndex,
                                               String transport,
                                               boolean ipv6) {
        try {
            BridgeFetchResult result = BRIDGE_DB_CLIENT.fetch(transport, ipv6);
            if (result.getType() == BridgeFetchResult.Type.SUCCESS) {
                for (String bridge : result.getBridges()) {
                    storeBridge(bridgeConfigs, fingerprintIndex, bridge);
                }
            } else if (result.getType() == BridgeFetchResult.Type.CAPTCHA_REQUIRED) {
                CaptchaChallenge challenge = new CaptchaChallenge(
                        result.getCaptchaImage(),
                        result.getCaptchaSecret(),
                        transport,
                        ipv6
                );
                notifyCaptcha(challenge);
                Log.w(TAG, "BridgeDB requires captcha for " + transport + (ipv6 ? " (ipv6)" : ""));
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to fetch bridges for " + transport + (ipv6 ? " (ipv6)" : ""), e);
        }
    }

    private static int countTransport(Set<String> bridgeConfigs, String transport) {
        if (bridgeConfigs == null || bridgeConfigs.isEmpty() || transport == null) {
            return 0;
        }
        String needle = ("Bridge " + transport).toLowerCase(Locale.US);
        int count = 0;
        for (String bridge : bridgeConfigs) {
            if (bridge != null && bridge.toLowerCase(Locale.US).startsWith(needle)) {
                count++;
            }
        }
        return count;
    }

    private static void notifyCaptcha(CaptchaChallenge challenge) {
        if (challenge == null || challenge.image == null || challenge.image.length == 0) {
            return;
        }
        pendingCaptcha.set(challenge);
        for (CaptchaListener listener : captchaListeners) {
            try {
                listener.onCaptcha(challenge);
            } catch (Exception ignored) {
            }
        }
    }

    private static List<String> mergeAndPersist(Context context, List<String> additions) {
        Set<String> bridgeConfigs = new LinkedHashSet<>();
        Map<String, String> fingerprintIndex = new HashMap<>();

        for (String cached : loadCachedBridges(context)) {
            storeBridge(bridgeConfigs, fingerprintIndex, cached);
        }
        for (String bridge : DEFAULT_BRIDGES) {
            storeBridge(bridgeConfigs, fingerprintIndex, bridge);
        }
        if (additions != null) {
            for (String newBridge : additions) {
                storeBridge(bridgeConfigs, fingerprintIndex, newBridge);
            }
        }

        List<String> result = new ArrayList<>(bridgeConfigs);
        if (!NetworkUtils.hasGlobalIpv6Connectivity()) {
            result.removeIf(TorBridgeParser::isIpv6Line);
        }
        persistBridges(context, result);
        return result;
    }

    private static List<String> loadCachedBridges(Context context) {
        String raw = Settings.getPrefs(context).getString(PREF_BRIDGE_CACHE, "");
        if (TextUtils.isEmpty(raw)) {
            return Collections.emptyList();
        }
        String[] lines = raw.split("\n");
        List<String> bridges = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                bridges.add(line.trim());
            }
        }
        return bridges;
    }

    private static void persistBridges(Context context, List<String> bridges) {
        String value = "";
        if (bridges != null && !bridges.isEmpty()) {
            value = TextUtils.join("\n", bridges);
        }
        Settings.getPrefs(context).edit().putString(PREF_BRIDGE_CACHE, value).apply();
    }

    private static String normalizeBridgeLine(String bridge) {
        if (bridge == null) {
            return "";
        }
        String trimmed = bridge.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String lower = trimmed.toLowerCase(Locale.US);
        if (!lower.startsWith("bridge ")) {
            trimmed = "Bridge " + trimmed;
        }
        return trimmed;
    }

    private static void storeBridge(Set<String> bridgeConfigs,
                                    Map<String, String> fingerprintIndex,
                                    String bridge) {
        if (bridge == null) {
            return;
        }

        String trimmed = bridge.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        String lower = trimmed.toLowerCase(Locale.US);
        if (lower.contains("bridge webtunnel") || lower.contains(" webtunnel ")) {
            return;
        }

        String normalized = normalizeBridgeLine(trimmed);
        String fingerprint = extractFingerprint(normalized);

        if (fingerprint == null || fingerprint.isEmpty()) {
            bridgeConfigs.add(normalized);
            return;
        }

        String existing = fingerprintIndex.get(fingerprint);
        boolean newIsIpv6 = isIpv6Line(normalized);

        if (existing == null) {
            fingerprintIndex.put(fingerprint, normalized);
            bridgeConfigs.add(normalized);
            return;
        }

        boolean existingIsIpv6 = isIpv6Line(existing);

        if (existingIsIpv6 && !newIsIpv6) {
            bridgeConfigs.remove(existing);
            bridgeConfigs.add(normalized);
            fingerprintIndex.put(fingerprint, normalized);
        } else if (existingIsIpv6 == newIsIpv6) {
            // same preference; keep existing
        } else {
            // existing is preferred (IPv4) -> ignore new IPv6 entry
        }
    }

    private static String extractFingerprint(String bridgeLine) {
        int fingerprintIdx = bridgeLine.toLowerCase(Locale.US).indexOf(" fingerprint=");
        if (fingerprintIdx < 0) {
            String[] parts = bridgeLine.split("\\s+");
            if (parts.length >= 4) {
                String candidate = parts[3];
                if (candidate.matches("[0-9a-fA-F]{40}")) {
                    return candidate.toUpperCase(Locale.US);
                }
            }
            return null;
        }

        String remainder = bridgeLine.substring(fingerprintIdx + 13).trim();
        int spaceIdx = remainder.indexOf(' ');
        String fingerprint = (spaceIdx >= 0 ? remainder.substring(0, spaceIdx) : remainder).trim();
        if (fingerprint.matches("[0-9a-fA-F]{40}")) {
            return fingerprint.toUpperCase(Locale.US);
        }
        return null;
    }

    private static boolean isIpv6Line(String bridgeLine) {
        String[] parts = bridgeLine.split("\\s+");
        if (parts.length < 3) {
            return false;
        }
        String address = parts[2];
        int portSeparator = address.lastIndexOf(':');
        if (portSeparator < 0) {
            return false;
        }
        String host = address.substring(0, portSeparator);
        if (host.startsWith("[")) {
            host = host.substring(1);
        }
        if (host.endsWith("]")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.contains(":");
    }

    private static void postToMain(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    public static final class CaptchaChallenge {
        public final byte[] image;
        public final String secret;
        public final String transport;
        public final boolean ipv6;

        public CaptchaChallenge(byte[] image, String secret, String transport, boolean ipv6) {
            this.image = image;
            this.secret = secret;
            this.transport = transport;
            this.ipv6 = ipv6;
        }
    }

    public interface CaptchaListener {
        void onCaptcha(CaptchaChallenge challenge);
    }

    public interface CaptchaSolveCallback {
        void onSuccess(List<String> bridges);
        void onNewChallenge(CaptchaChallenge challenge, String message);
        void onFailure(Exception exception);
    }

    public static synchronized List<String> refreshBridgeConfigs(Context context) {
        Settings.getPrefs(context).edit().remove(PREF_BRIDGE_CACHE).apply();
        return getBridgeConfigs(context);
    }
}
