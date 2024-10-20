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

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public class TorStatusView extends LinearLayout {

    public TorStatusView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void update() {
        TorManager torManager = TorManager.getInstance(getContext());

        setVisibility(!torManager.isReady() ? View.VISIBLE : View.GONE);

        String status = torManager.getStatus();
        if(status == null) {
            status = "";
        }
        status = status.trim();
        TextView view = (TextView) findViewById(R.id.status);
        if (status.contains("ON")) {
            this.setVisibility(View.GONE);
        } else if (status.startsWith("Loading ")) {
            view.setText(status);
        } else if (view.length() == 0) {
            view.setText("Loading...");
        }
    }

    @Override
    protected void onAttachedToWindow() {

        super.onAttachedToWindow();

        if (!isInEditMode()) {
            TorManager torManager = TorManager.getInstance(getContext());
            torManager.setLogListener(new TorManager.LogListener() {
                @Override
                public void onLog() {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            update();
                        }
                    });
                }
            });
            update();
        }

    }

    @Override
    protected void onDetachedFromWindow() {

        TorManager torManager = TorManager.getInstance(getContext());
        torManager.setLogListener(null);

        if (!isInEditMode()) {
            super.onDetachedFromWindow();
        }

    }

}