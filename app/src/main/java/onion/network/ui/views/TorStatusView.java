

package onion.network.ui.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import onion.network.R;
import onion.network.TorManager;

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