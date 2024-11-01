


package onion.network.helpers;

import android.net.Uri;

public class OnionUrlBuilder {

    String uri;

    public OnionUrlBuilder(String address, String method) {
        uri = "http://" + address + ".onion/" + method;
    }

    public OnionUrlBuilder arg(String key, String val) {
        Uri.Builder uriBuilder = Uri.parse(uri).buildUpon();
        uriBuilder.appendQueryParameter(key, val);
        uri = uriBuilder.toString();
        return this;
    }

    public String build() {
        return uri;
    }

}
