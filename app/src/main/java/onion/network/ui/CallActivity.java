package onion.network.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.util.EnumSet;

import onion.network.R;
import onion.network.call.CallManager;
import onion.network.call.CallSession;
import onion.network.call.CallState;
import onion.network.call.CallManager.CallListener;
import onion.network.cashes.ItemCache;
import onion.network.helpers.ItemDisplayHelper;
import onion.network.helpers.PermissionHelper;
import onion.network.helpers.ThemeManager;
import onion.network.helpers.UiCustomizationManager;
import onion.network.helpers.VideoCacheManager;
import onion.network.ui.views.AvatarView;

public class CallActivity extends AppCompatActivity implements CallListener {

    private static final String EXTRA_REMOTE = "remote";
    private static final String EXTRA_INCOMING = "incoming";

    private MaterialCardView callCard;
    private MaterialCardView controlsCard;
    private MaterialCardView avatarContainer;
    private AvatarView avatarView;
    private TextView remoteNameView;
    private TextView remoteAddressView;
    private TextView statusView;
    private Chronometer timerView;
    private MaterialButton acceptButton;
    private MaterialButton hangupButton;

    private boolean isIncoming;
    @Nullable
    private String remoteAddress;
    @Nullable
    private String boundRemoteAddress;
    private boolean timerRunning;

    private CallManager callManager;

    public static void startOutgoing(Context context, String remoteAddress) {
        Intent intent = new Intent(context, CallActivity.class);
        intent.putExtra(EXTRA_REMOTE, remoteAddress);
        intent.putExtra(EXTRA_INCOMING, false);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(intent);
    }

    public static void startIncoming(Context context, String remoteAddress) {
        Intent intent = new Intent(context, CallActivity.class);
        intent.putExtra(EXTRA_REMOTE, remoteAddress);
        intent.putExtra(EXTRA_INCOMING, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.init(this).applyNoActionBarTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        bindViews();

        Intent intent = getIntent();
        remoteAddress = intent.getStringExtra(EXTRA_REMOTE);
        isIncoming = intent.getBooleanExtra(EXTRA_INCOMING, false);

        callManager = CallManager.getInstance(this);
        callManager.addListener(this);

        applyThemeStyling();
        bindRemoteIdentity(remoteAddress);

        setupActions();

        CallState currentState = callManager.getState();
        updateForState(currentState, callManager.getSession());

        if (!isIncoming && shouldStartNewCall(currentState, callManager.getSession())) {
            callManager.startOutgoingCall(remoteAddress);
        }
    }

    private void bindViews() {
        callCard = findViewById(R.id.callCard);
        controlsCard = findViewById(R.id.callControlsCard);
        avatarContainer = findViewById(R.id.callAvatarContainer);
        avatarView = findViewById(R.id.callAvatar);
        remoteNameView = findViewById(R.id.callRemoteName);
        remoteAddressView = findViewById(R.id.callRemoteAddress);
        statusView = findViewById(R.id.callStatus);
        timerView = findViewById(R.id.callTimer);
        acceptButton = findViewById(R.id.callAccept);
        hangupButton = findViewById(R.id.callHangup);
    }

    private void setupActions() {
        if (acceptButton != null) {
            acceptButton.setOnClickListener(v -> PermissionHelper.runWithPermissions(
                    this,
                    EnumSet.of(PermissionHelper.PermissionRequest.MICROPHONE),
                    () -> {
                        acceptButton.setEnabled(false);
                        callManager.acceptIncomingCall();
                    },
                    () -> callManager.rejectIncomingCall()));
        }

        if (hangupButton != null) {
            hangupButton.setOnClickListener(v -> {
                hangupButton.setEnabled(false);
                callManager.hangup();
            });
        }
    }

    private boolean shouldStartNewCall(@NonNull CallState state, @Nullable CallSession session) {
        if (TextUtils.isEmpty(remoteAddress)) {
            return false;
        }
        return session == null || state == CallState.IDLE || state == CallState.ENDED || state == CallState.FAILED;
    }

    private void applyThemeStyling() {
        UiCustomizationManager.FriendCardConfig config = UiCustomizationManager.getFriendCardConfig(this);
        UiCustomizationManager.ColorPreset preset = UiCustomizationManager.getColorPreset(this);

        int accent = preset.getAccentColor(this);
        int onAccent = preset.getOnAccentColor(this);
        int surface = preset == UiCustomizationManager.ColorPreset.SYSTEM
                ? ThemeManager.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
                : preset.getSurfaceColor(this);
        int onSurface = preset.getOnSurfaceColor(this);
        int secondary = preset == UiCustomizationManager.ColorPreset.SYSTEM
                ? ThemeManager.getColor(this, R.attr.white_80)
                : ColorUtils.setAlphaComponent(onSurface, 180);

        if (callCard != null) {
            callCard.setCardBackgroundColor(surface);
            callCard.setStrokeColor(accent);
            callCard.setStrokeWidth(UiCustomizationManager.dpToPx(this, 1));
            callCard.setRadius(UiCustomizationManager.resolveCornerRadiusPx(this, config.cornerRadiusPx));
            callCard.setContentPadding(config.horizontalPaddingPx, config.verticalPaddingPx,
                    config.horizontalPaddingPx, config.verticalPaddingPx);
        }

        if (controlsCard != null) {
            controlsCard.setCardBackgroundColor(surface);
            controlsCard.setStrokeColor(accent);
            controlsCard.setStrokeWidth(UiCustomizationManager.dpToPx(this, 1));
            controlsCard.setRadius(UiCustomizationManager.resolveCornerRadiusPx(this, config.cornerRadiusPx));
            int horizontal = config.horizontalPaddingPx;
            int vertical = config.verticalPaddingPx;
            controlsCard.setContentPadding(horizontal, vertical, horizontal, vertical);
        }

        if (avatarContainer != null) {
            ViewGroup.LayoutParams params = avatarContainer.getLayoutParams();
            int minSize = UiCustomizationManager.dpToPx(this, 120);
            int targetSize = Math.max(minSize, config.avatarSizePx + UiCustomizationManager.dpToPx(this, 32));
            if (params != null) {
                params.width = targetSize;
                params.height = targetSize;
                avatarContainer.setLayoutParams(params);
            }
            float radius = targetSize / 2f;
            avatarContainer.setRadius(radius);
            avatarContainer.setStrokeWidth(UiCustomizationManager.dpToPx(this, 1));
            avatarContainer.setStrokeColor(ColorUtils.setAlphaComponent(accent, 180));
            avatarContainer.setCardBackgroundColor(ColorUtils.setAlphaComponent(accent, 48));
        }

        if (remoteNameView != null) {
            remoteNameView.setTextColor(onSurface);
        }
        if (remoteAddressView != null) {
            remoteAddressView.setTextColor(secondary);
        }
        if (statusView != null) {
            statusView.setTextColor(onSurface);
        }
        if (timerView != null) {
            timerView.setTextColor(accent);
        }

        if (acceptButton != null) {
            acceptButton.setBackgroundTintList(ColorStateList.valueOf(accent));
            acceptButton.setTextColor(onAccent);
            acceptButton.setIconTint(ColorStateList.valueOf(onAccent));
        }

        if (hangupButton != null) {
            int hangupColor = ContextCompat.getColor(this, R.color.call_hangup);
            int white = ContextCompat.getColor(this, android.R.color.white);
            hangupButton.setBackgroundTintList(ColorStateList.valueOf(hangupColor));
            hangupButton.setTextColor(white);
            hangupButton.setIconTint(ColorStateList.valueOf(white));
        }
    }

    private void bindRemoteIdentity(@Nullable String address) {
        if (TextUtils.isEmpty(address)) {
            remoteNameView.setText(ItemDisplayHelper.resolveDisplayName(this, address));
            remoteAddressView.setText(getString(R.string.call_unknown_user));
            avatarView.setPlaceholderResource(R.drawable.nophoto);
            avatarView.bind(null, null, null);
            boundRemoteAddress = null;
            return;
        }

        if (address.equals(boundRemoteAddress)) {
            return;
        }
        boundRemoteAddress = address;

        remoteNameView.setText(ItemDisplayHelper.resolveDisplayName(this, address));
        remoteAddressView.setText(address);

        avatarView.setPlaceholderResource(R.drawable.nophoto);

        Bitmap photoThumb = null;
        Bitmap videoThumb = null;
        Uri playableVideo = null;
        try {
            ItemCache cache = ItemCache.getInstance(this);
            photoThumb = cache.get(address, "thumb").one().bitmap("thumb");
            videoThumb = cache.get(address, "video_thumb").one().bitmap("video_thumb");
            JSONObject videoJson = cache.get(address, "video").one().json();
            playableVideo = VideoCacheManager.ensureVideoUri(
                    this,
                    address,
                    videoJson != null ? videoJson.optString("video_uri", "").trim() : null,
                    videoJson != null ? videoJson.optString("video", "").trim() : null);
        } catch (Exception ignore) {
        }

        String playable = playableVideo != null ? playableVideo.toString() : null;
        avatarView.bind(photoThumb, videoThumb, playable);
    }

    private void updateForState(@NonNull CallState state, @Nullable CallSession session) {
        if (session != null && !TextUtils.isEmpty(session.remoteAddress)) {
            remoteAddress = session.remoteAddress;
            bindRemoteIdentity(remoteAddress);
        }

        updateStatus(state);

        boolean showAccept = isIncoming && state == CallState.RINGING;
        updateAcceptVisibility(showAccept);

        if (state == CallState.CONNECTED) {
            startTimer(session);
        } else if (state == CallState.ENDED || state == CallState.FAILED) {
            stopTimer(false);
            finishWithDelay();
        } else {
            stopTimer(true);
        }

        setKeepScreenOnForState(state);
    }

    private void updateAcceptVisibility(boolean visible) {
        if (acceptButton == null) return;
        acceptButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        acceptButton.setEnabled(visible);
    }

    private void updateStatus(CallState state) {
        int resId;
        switch (state) {
            case CALLING -> resId = R.string.call_status_calling;
            case RINGING -> resId = R.string.call_status_ringing;
            case CONNECTING -> resId = R.string.call_status_connecting;
            case CONNECTED -> resId = R.string.call_status_connected;
            case ENDED -> resId = R.string.call_status_ended;
            case FAILED -> resId = R.string.call_status_failed;
            case IDLE -> resId = R.string.call_status_idle;
            default -> resId = R.string.call_status_idle;
        }
        statusView.setText(resId);
    }

    private void startTimer(@Nullable CallSession session) {
        if (timerView == null || timerRunning) return;
        long base = SystemClock.elapsedRealtime();
        if (session != null) {
            long elapsed = Math.max(0L, System.currentTimeMillis() - session.startedAt);
            base = SystemClock.elapsedRealtime() - elapsed;
        }
        timerView.setBase(base);
        timerView.setVisibility(View.VISIBLE);
        timerView.start();
        timerRunning = true;
    }

    private void stopTimer(boolean hideView) {
        if (timerView == null) return;
        if (timerRunning) {
            timerView.stop();
            timerRunning = false;
        }
        timerView.setVisibility(hideView ? View.GONE : View.VISIBLE);
    }

    private void setKeepScreenOnForState(CallState state) {
        boolean keepOn = state == CallState.CALLING
                || state == CallState.RINGING
                || state == CallState.CONNECTING
                || state == CallState.CONNECTED;
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onCallStateChanged(CallState state, @Nullable CallSession session) {
        runOnUiThread(() -> updateForState(state, session));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String newRemote = intent.getStringExtra(EXTRA_REMOTE);
        boolean incoming = intent.getBooleanExtra(EXTRA_INCOMING, isIncoming);
        if (!TextUtils.equals(remoteAddress, newRemote)) {
            remoteAddress = newRemote;
            bindRemoteIdentity(remoteAddress);
        }
        isIncoming = incoming;
        updateAcceptVisibility(isIncoming && callManager != null && callManager.getState() == CallState.RINGING);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (callManager != null) {
            callManager.removeListener(this);
        }
        stopTimer(true);
        setKeepScreenOnForState(CallState.IDLE);
        if (avatarView != null) {
            avatarView.release();
        }
    }

    @Override
    public void onBackPressed() {
        if (callManager != null) {
            callManager.hangup();
        }
        super.onBackPressed();
    }

    private void finishWithDelay() {
        if (hangupButton != null) {
            hangupButton.setEnabled(false);
        }
        if (acceptButton != null) {
            acceptButton.setEnabled(false);
        }
        statusView.postDelayed(() -> {
            if (!isFinishing()) {
                finish();
            }
        }, 900L);
    }
}
