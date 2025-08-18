

package onion.network.ui.views;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import onion.network.R;
import onion.network.TorManager;

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

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            TextView view = findViewById(R.id.status);

            if (status.toLowerCase().contains("starting")) {
                setVisibility(VISIBLE);
            }

            if (status.contains("Bootstrapped 100%")) {
                setVisibility(View.GONE);
            } else if (status.contains("Bootstrapped ")) {
                final Pattern pattern = Pattern.compile(".\\d%", Pattern.MULTILINE);
                final Matcher matcher = pattern.matcher(status);
                while (matcher.find()) {
                    view.setText("Loading " + matcher.group(0));
                }
            } else if (view.length() == 0) {
                view.setText("Loading...");
            }
        });
    }

}