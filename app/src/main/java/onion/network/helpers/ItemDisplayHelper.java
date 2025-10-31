package onion.network.helpers;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import onion.network.R;
import onion.network.cashes.ItemCache;

public final class ItemDisplayHelper {

    private ItemDisplayHelper() {
    }

    public static String resolveDisplayName(Context context, String address) {
        if (context == null) return "";
        if (TextUtils.isEmpty(address)) {
            return context.getString(R.string.call_unknown_user);
        }
        try {
            JSONObject obj = ItemCache.getInstance(context)
                    .get(address, "name")
                    .one()
                    .json();
            String name = obj.optString("name", "");
            if (!TextUtils.isEmpty(name)) {
                return name;
            }
        } catch (Exception ignore) {
        }
        return address;
    }
}
