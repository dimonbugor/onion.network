/*
 * Network.onion - fully distributed p2p social network using onion routing
 *
 * http://play.google.com/store/apps/details?id=onion.network
 * http://onionapps.github.io/Network.onion/
 * http://github.com/onionApps/Network.onion
 *
 * Author: http://github.com/onionApps - http://jkrnk73uid7p5thz.onion - bitcoin:1kGXfWx8PHZEVriCNkbP5hzD15HS4AyKf
 */

package onion.network;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class OnBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        context.startService(new Intent(context, HostService.class));

    }

}
