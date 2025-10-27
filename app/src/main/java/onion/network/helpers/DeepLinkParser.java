package onion.network.helpers;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;

public final class DeepLinkParser {
    public static class Result {
        public final String address;
        public final String name;
        public Result(String address, String name) { this.address = address; this.name = name; }
    }

    private DeepLinkParser() {}

    public static Result parse(Intent intent) {
        if (intent == null) return new Result("", "");
        try {
            final Uri uri = intent.getData();
            if (uri == null) return fromExtra(intent);

            final String raw = intent.getDataString();
            if (raw != null) {
                String scheme = raw.substring(0, raw.indexOf(':'));
                String host = raw.substring(raw.indexOf(':') + 1);
                if (host.startsWith("/")) host = host.replaceFirst("^/+", "");
                if (host.length() >= 16) host = host.substring(0, 16);
                if ("onionnet".equals(scheme) || "onionet".equals(scheme) || "onnet".equals(scheme)) {
                    return new Result(host, "");
                }
            }

            if ("network.onion".equals(uri.getHost())) {
                var pp = uri.getPathSegments();
                String address = pp.size() > 0 ? pp.get(0) : "";
                String name = pp.size() > 1 ? pp.get(1) : "";
                return new Result(address, name);
            }

            String host = uri.getHost();
            if (host != null && (host.endsWith(".onion") || host.contains(".onion."))) {
                String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
                if (path.equals("/network.onion") || path.startsWith("/network.onion/")) {
                    for (String s : host.split("\\.")) {
                        if (s.length() == 16) return new Result(s, "");
                    }
                }
            }
        } catch (Throwable t) {
            Log.w("DeepLinkParser", "parse failed", t);
        }
        return fromExtra(intent);
    }

    private static Result fromExtra(Intent i) {
        String address = i.getStringExtra("address");
        return new Result(address == null ? "" : address.trim().toLowerCase(), "");
    }
}