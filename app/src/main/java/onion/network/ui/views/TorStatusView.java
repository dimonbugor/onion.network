

package onion.network.ui.views;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import onion.network.R;
import onion.network.TorManager;
import onion.network.tor.TorStatusFormatter;

public class TorStatusView extends LinearLayout implements TorManager.LogListener {

    public TorStatusView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {

        super.onAttachedToWindow();

        if (!isInEditMode()) {
            TorManager torManager = TorManager.getInstance(getContext());
            if(torManager.isReady()) {
                setVisibility(View.GONE);
            } else {
                torManager.addLogListener(this);
            }
        }

    }

    @Override
    protected void onDetachedFromWindow() {
        TorManager torManager = TorManager.getInstance(getContext());
        torManager.removeLogListener(this);
        if (!isInEditMode()) {
            super.onDetachedFromWindow();
        }

    }

    @Override
    public void onTorLog(String line) {
        String status = (line == null) ? "" : line.trim();
        TorStatusFormatter.Status parsed = TorStatusFormatter.parse(status);
        if (!parsed.hasChanged()) {
            return;
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            TextView view = findViewById(R.id.status);

            if (parsed.isReady()) {
                setVisibility(View.GONE);
            } else {
                setVisibility(View.VISIBLE);
                String message = parsed.getMessage();
                if (message != null) {
                    view.setText(message);
                } else if (view.length() == 0) {
                    view.setText(R.string.tor_loading_default);
                }
            }
        });
    }

}
