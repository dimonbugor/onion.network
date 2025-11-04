package onion.network.helpers;

import android.util.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Network related helpers shared across the app.
 */
public final class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    private NetworkUtils() {
    }

    /**
     * @return true when the device appears to have routable IPv6 connectivity.
     */
    public static boolean hasGlobalIpv6Connectivity() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet6Address) {
                        if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                            continue;
                        }
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to check IPv6 interfaces", e);
        }
        return false;
    }
}
