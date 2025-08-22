package onion.network.models;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.TreeMap;

public class Response {
    private static Charset utf8 = Charset.forName("UTF-8");
    private int statusCode = 200;
    private String statusString = "OK";
    private byte[] content = new byte[0];
    private Map<String, String> headers = new TreeMap<>();

    public Response() {
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getStatusString() {
        return statusString;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] data) {
        content = data;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String key) {
        return headers.get(key);
    }

    public void putHeader(String key, String val) {
        headers.put(key, val);
    }

    public void setContentType(String type) {
        putHeader("Content-Type", type);
    }

    public String getContentType() {
        return getHeader("Content-Type");
    }

    public void setStatus(int statusCode, String statusString) {
        this.statusCode = statusCode;
        this.statusString = statusString;
    }

    public void setContent(byte[] data, String contentType) {
        content = data;
        setContentType(contentType);
    }

    public void setContentHtml(String code) {
        setContent(code.getBytes(utf8), "Content-Type: text/html; charset=utf-8");
    }

    public void setContentPlain(String text) {
        setContent(text.getBytes(utf8), "Content-Type: text/plain; charset=utf-8");
    }

    public String getCharset() {
        return "UTF-8";
    }
}
