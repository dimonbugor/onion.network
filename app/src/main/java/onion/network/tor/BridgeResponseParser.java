package onion.network.tor;

import android.util.Base64;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses BridgeDB HTML responses to extract bridge lines or captcha challenges.
 */
class BridgeResponseParser {

    private static final List<String> SUPPORTED_TRANSPORT_PREFIXES = Arrays.asList(
            "obfs4",
            "snowflake",
            "webtunnel", "conjure", "vanilla"
    );

    private static final Pattern CAPTCHA_PATTERN = Pattern.compile(
            "data:image/(?:gif|png|jpeg|bmp|webp)(?:;charset=utf-8)?;base64,([A-Za-z0-9+/]+?={0,2})\""
    );
    private static final Pattern CAPTCHA_SECRET_PATTERN = Pattern.compile(
            "input.+?type=\"hidden\".+?name=\"\\w+?\".+?value=\"([A-Za-z0-9-_]+?)\""
    );

    BridgeFetchResult parse(InputStream stream) throws IOException {
        if (stream == null) {
            return BridgeFetchResult.empty();
        }

        String html = readAll(stream);
        if (html.isEmpty()) {
            return BridgeFetchResult.empty();
        }

        List<String> bridges = extractBridgeLines(html);
        if (!bridges.isEmpty()) {
            return BridgeFetchResult.success(bridges);
        }

        byte[] captcha = extractCaptchaImage(html);
        String secret = extractCaptchaSecret(html);
        if (captcha != null && secret != null && !secret.isEmpty()) {
            return BridgeFetchResult.captchaRequired(captcha, secret);
        }

        return BridgeFetchResult.empty();
    }

    private List<String> extractBridgeLines(String html) {
        List<String> bridges = new ArrayList<>();

        Document document = Jsoup.parse(html);
        Element linesElement = document.getElementById("bridgelines");
        if (linesElement == null) {
            return bridges;
        }

        String innerHtml = Parser.unescapeEntities(linesElement.html(), false);
        String[] rawLines = innerHtml.split("(?i)<br\\s*/?>");
        for (String rawLine : rawLines) {
            String cleaned = sanitizeBridgeLine(rawLine);
            if (cleaned.isEmpty()) {
                continue;
            }

            String lower = cleaned.toLowerCase(Locale.US);
            String prefix = cleaned.split("\\s+", 2)[0].toLowerCase(Locale.US);
            if (!SUPPORTED_TRANSPORT_PREFIXES.contains(prefix)) {
                if (lower.startsWith("bridge ")) {
                    String candidatePrefix = cleaned.substring(6).trim();
                    if (!candidatePrefix.isEmpty()) {
                        prefix = candidatePrefix.split("\\s+", 2)[0].toLowerCase(Locale.US);
                    }
                }
            }

            if (SUPPORTED_TRANSPORT_PREFIXES.contains(prefix)) {
                bridges.add(cleaned);
            }
        }
        return bridges;
    }

    private String sanitizeBridgeLine(String rawLine) {
        if (rawLine == null) {
            return "";
        }
        String cleaned = rawLine
                .replace("&nbsp;", " ")
                .replace("\u00A0", " ")
                .trim();
        cleaned = cleaned.replaceAll("\\s+", " ");
        cleaned = cleaned.replaceAll("(?i)</?span[^>]*>", "");
        cleaned = cleaned.replaceAll("(?i)</?strong[^>]*>", "");
        cleaned = cleaned.replaceAll("(?i)</?em[^>]*>", "");
        cleaned = cleaned.replaceAll("(?i)</?div[^>]*>", "");
        return cleaned.trim();
    }

    private byte[] extractCaptchaImage(String html) {
        Matcher matcher = CAPTCHA_PATTERN.matcher(html);
        if (matcher.find()) {
            String data = matcher.group(1);
            try {
                return Base64.decode(data, Base64.DEFAULT);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private String extractCaptchaSecret(String html) {
        Matcher matcher = CAPTCHA_SECRET_PATTERN.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String readAll(InputStream inputStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
