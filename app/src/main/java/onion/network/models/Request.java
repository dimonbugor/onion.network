package onion.network.models;

import java.util.Map;
import java.util.TreeMap;

public class Request {
    private String method = "";
    private String path = "";
    private Map<String, String> headers = new TreeMap<>();

    public Request() {
    }

    public Request(String method, String path, Map<String, String> headers) {
        this.method = method;
        this.path = path;
        this.headers = headers;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getHeader(String key) {
        return headers.get(key);
    }

    public String getHeader(String key, String defaultValue) {
        String ret = headers.get(key);
        if (ret == null) ret = defaultValue;
        return ret;
    }
}
