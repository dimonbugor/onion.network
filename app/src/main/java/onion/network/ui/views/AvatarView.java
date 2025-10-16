package onion.network.ui.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.net.Uri;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;

import onion.network.R;

/**
 * AvatarView displays either a static avatar bitmap or plays a looping muted video.
 * It encapsulates all playback lifecycle management so list/grid items can stay simple.
 */
public class AvatarView extends FrameLayout {

    private final ImageView imageView;
    private final PlayerView playerView;

    @Nullable
    private ExoPlayer player;
    @Nullable
    private String currentVideoUri;
    @Nullable
    private Bitmap fallbackBitmap;

    private int placeholderResId = R.drawable.nothumb;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_READY) {
                fadeInVideo();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            showFallback();
        }
    };

    public AvatarView(@NonNull Context context) {
        this(context, null);
    }

    public AvatarView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AvatarView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClipToOutline(true);
        setOutlineProvider(circleOutlineProvider);

        playerView = new PlayerView(context, attrs);
        playerView.setUseController(false);
        playerView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        playerView.setAlpha(0f);
        playerView.setClickable(false);
        playerView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        playerView.setOnTouchListener((v, event) -> {
            if (!hasOnClickListeners()) {
                return false;
            }
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                performClick();
                return true;
            }
            return false;
        });
        addView(playerView);

        imageView = new ImageView(context, attrs);
        imageView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setImageResource(placeholderResId);
        imageView.setClickable(false);
        addView(imageView);
    }

    /**
     * Configure a drawable resource used when no avatar data is present.
     */
    public void setPlaceholderResource(@DrawableRes int resId) {
        placeholderResId = resId;
        if (currentVideoUri == null && imageView.getDrawable() == null) {
            imageView.setImageResource(placeholderResId);
        }
    }

    /**
     * Bind avatar content. Video takes precedence, thumbnail is used as a fallback while buffering.
     */
    public void bind(@Nullable Bitmap photo, @Nullable Bitmap videoThumb, @Nullable String videoUri) {
        fallbackBitmap = null;
        if (!TextUtils.isEmpty(videoUri)) {
            setVideo(videoUri, videoThumb != null ? videoThumb : photo);
        } else {
            setStaticBitmap(photo);
        }
    }

    /**
     * Release media resources; call from adapters when a row is permanently discarded.
     */
    public void release() {
        stopVideo(true);
    }

    /**
     * Pause playback without clearing the view; used when parent screen is backgrounded.
     */
    public void pause() {
        if (player != null) {
            player.setPlayWhenReady(false);
            player.pause();
        }
    }

    /**
     * Resume playback if a video is bound.
     */
    public void resume() {
        if (player != null && !TextUtils.isEmpty(currentVideoUri)) {
            player.setPlayWhenReady(true);
            player.play();
        }
    }

    /**
     * Whether avatar currently represents a video.
     */
    public boolean hasVideo() {
        return !TextUtils.isEmpty(currentVideoUri);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (player != null && !TextUtils.isEmpty(currentVideoUri)) {
            player.setPlayWhenReady(true);
            player.play();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (player != null) {
            player.setPlayWhenReady(false);
            player.pause();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidateOutline();
    }

    private final ViewOutlineProvider circleOutlineProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            int size = Math.min(view.getWidth(), view.getHeight());
            if (size <= 0) {
                outline.setEmpty();
                return;
            }
            outline.setOval(0, 0, size, size);
        }
    };

    private void ensurePlayer() {
        if (player != null) return;
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 250, 500)
                .build();

        player = new ExoPlayer.Builder(getContext())
                .setLoadControl(loadControl)
                .build();
        player.addListener(playerListener);
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setVolume(0f);
        playerView.setPlayer(player);
    }

    private void setStaticBitmap(@Nullable Bitmap bitmap) {
        stopVideo(true);
        imageView.animate().cancel();
        imageView.setAlpha(1f);
        imageView.setVisibility(VISIBLE);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
        } else {
            imageView.setImageResource(placeholderResId);
        }
    }

    private void setVideo(@NonNull String videoUri, @Nullable Bitmap fallback) {
        currentVideoUri = videoUri;
        fallbackBitmap = fallback;

        if (fallback != null) {
            imageView.setImageBitmap(fallback);
        } else {
            imageView.setImageResource(placeholderResId);
        }
        imageView.setAlpha(1f);
        imageView.setVisibility(VISIBLE);

        ensurePlayer();

        try {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)));
            player.prepare();
            if (isAttachedToWindow()) {
                player.setPlayWhenReady(true);
                player.play();
            } else {
                player.setPlayWhenReady(true);
            }
        } catch (Exception ex) {
            showFallback();
            return;
        }

        playerView.setVisibility(VISIBLE);
        playerView.setAlpha(0f);
    }

    private void fadeInVideo() {
        playerView.animate().cancel();
        imageView.animate().cancel();
        playerView.animate().alpha(1f).setDuration(200).start();
        imageView.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction(() -> {
                    if (currentVideoUri != null) {
                        imageView.setVisibility(INVISIBLE);
                    } else {
                        imageView.setAlpha(1f);
                        imageView.setVisibility(VISIBLE);
                    }
                })
                .start();
    }

    private void showFallback() {
        stopVideo(false);
        imageView.animate().cancel();
        imageView.setAlpha(1f);
        imageView.setVisibility(VISIBLE);
        if (fallbackBitmap != null) {
            imageView.setImageBitmap(fallbackBitmap);
        } else {
            imageView.setImageResource(placeholderResId);
        }
    }

    private void stopVideo(boolean releasePlayer) {
        currentVideoUri = null;
        playerView.animate().cancel();
        playerView.setAlpha(0f);
        playerView.setVisibility(GONE);
        if (player != null) {
            player.setPlayWhenReady(false);
            player.stop();
            if (releasePlayer) {
                player.removeListener(playerListener);
                playerView.setPlayer(null);
                player.release();
                player = null;
            }
        }
    }
}
