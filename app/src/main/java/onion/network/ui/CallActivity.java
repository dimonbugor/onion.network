package onion.network.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import onion.network.R;
import java.util.EnumSet;

import onion.network.call.CallManager;
import onion.network.call.CallSession;
import onion.network.call.CallState;
import onion.network.call.CallManager.CallListener;
import onion.network.helpers.ItemDisplayHelper;
import onion.network.helpers.PermissionHelper;

public class CallActivity extends AppCompatActivity implements CallListener {

    private static final String EXTRA_REMOTE = "remote";
    private static final String EXTRA_INCOMING = "incoming";

    private TextView addressView;
    private TextView statusView;
    private Button acceptButton;
    private Button hangupButton;

    private boolean isIncoming;
    private String remoteAddress;

    public static void startOutgoing(Context context, String remoteAddress) {
        Intent intent = new Intent(context, CallActivity.class);
        intent.putExtra(EXTRA_REMOTE, remoteAddress);
        intent.putExtra(EXTRA_INCOMING, false);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void startIncoming(Context context, String remoteAddress) {
        Intent intent = new Intent(context, CallActivity.class);
        intent.putExtra(EXTRA_REMOTE, remoteAddress);
        intent.putExtra(EXTRA_INCOMING, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        addressView = findViewById(R.id.callRemoteAddress);
        statusView = findViewById(R.id.callStatus);
        acceptButton = findViewById(R.id.callAccept);
        hangupButton = findViewById(R.id.callHangup);

        Intent intent = getIntent();
        remoteAddress = intent.getStringExtra(EXTRA_REMOTE);
        isIncoming = intent.getBooleanExtra(EXTRA_INCOMING, false);

        addressView.setText(ItemDisplayHelper.resolveDisplayName(this, remoteAddress));

        CallManager manager = CallManager.getInstance(this);
        manager.addListener(this);

        acceptButton.setVisibility(isIncoming ? View.VISIBLE : View.GONE);
        acceptButton.setOnClickListener(v -> PermissionHelper.runWithPermissions(
                this,
                EnumSet.of(PermissionHelper.PermissionRequest.MICROPHONE),
                () -> {
                    acceptButton.setEnabled(false);
                    manager.acceptIncomingCall();
                },
                () -> manager.rejectIncomingCall()));

        hangupButton.setOnClickListener(v -> manager.hangup());

        if (!isIncoming) {
            manager.startOutgoingCall(remoteAddress);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CallManager.getInstance(this).removeListener(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (!PermissionHelper.handleOnRequestPermissionsResult(this, requestCode, permissions, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onCallStateChanged(CallState state, @Nullable CallSession session) {
        runOnUiThread(() -> {
            switch (state) {
                case CALLING -> {
                    statusView.setText(R.string.call_status_calling);
                    acceptButton.setVisibility(View.GONE);
                }
                case RINGING -> {
                    statusView.setText(R.string.call_status_ringing);
                    acceptButton.setVisibility(View.VISIBLE);
                    acceptButton.setEnabled(true);
                }
                case CONNECTING -> {
                    statusView.setText(R.string.call_status_connecting);
                    acceptButton.setVisibility(View.GONE);
                }
                case CONNECTED -> {
                    statusView.setText(R.string.call_status_connected);
                    acceptButton.setVisibility(View.GONE);
                }
                case ENDED -> {
                    statusView.setText(R.string.call_status_ended);
                    finishWithDelay();
                }
                case FAILED -> {
                    statusView.setText(R.string.call_status_failed);
                    finishWithDelay();
                }
                default -> statusView.setText(R.string.call_status_idle);
            }
        });
    }

    private void finishWithDelay() {
        statusView.postDelayed(this::finish, 1000);
    }
}
