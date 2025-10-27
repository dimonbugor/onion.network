package onion.network.ui.controllers;

import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import onion.network.R;

public class LightboxController {
    public interface Host {
        void pauseAvatarVideos();
        void resumeAvatarVideos();
        void onLightboxHidden();
    }

    private final View imageView;   // ImageView
    private final PlayerView videoView;
    private ExoPlayer player;
    private final Host host;
    private final View rootForSnack; // будь-який view для Snackbar (якщо треба)

    private final Player.Listener listener = new Player.Listener() {
        @Override public void onRenderedFirstFrame() { crossfadePreviewToVideo(); }
        @Override public void onPlayerError(@NonNull PlaybackException error) {
            if (imageView != null) imageView.setVisibility(View.VISIBLE);
            if (videoView != null) { videoView.setAlpha(0f); videoView.setVisibility(View.INVISIBLE); }
        }
    };

    public LightboxController(View imageView, PlayerView videoView, Host host, View rootForSnack) {
        this.imageView = imageView;
        this.videoView = videoView;
        this.host = host;
        this.rootForSnack = rootForSnack;
        if (this.videoView != null) {
            this.videoView.setVisibility(View.INVISIBLE);
            this.videoView.setOnClickListener(null);
            this.videoView.setClickable(false);
        }
        if (this.imageView != null) {
            this.imageView.setVisibility(View.GONE);
            this.imageView.setOnClickListener(null);
            this.imageView.setClickable(false);
        }
    }

    private void ensurePlayer(View contextView) {
        if (player != null) return;
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 250, 500)
                .build();
        DefaultRenderersFactory rf = new DefaultRenderersFactory(contextView.getContext())
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        player = new ExoPlayer.Builder(contextView.getContext())
                .setRenderersFactory(rf)
                .setLoadControl(load)
                .build();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setVolume(1f);
        videoView.setPlayer(player);
        player.addListener(listener);
    }

    public void showImage(Bitmap bmp) {
        host.pauseAvatarVideos();
        hideVideoImmediate(true);
        if (imageView == null) return;
        imageView.clearAnimation();
        imageView.setAlpha(0f);
        imageView.setVisibility(View.VISIBLE);
        imageView.bringToFront();
        imageView.setClickable(true);
        imageView.setOnClickListener(v -> hideAll());
        // set bitmap
        // ((ImageView)imageView).setImageBitmap(bmp);
        // :- якщо imageView саме ImageView
        android.widget.ImageView iv = (android.widget.ImageView) imageView;
        iv.setImageBitmap(bmp);
        imageView.animate().alpha(1f).setDuration(180).start();
    }

    public void showVideo(Uri videoUri, Bitmap preview) {
        host.pauseAvatarVideos();
        hideImageImmediate();
        if (imageView instanceof android.widget.ImageView && preview != null) {
            ((android.widget.ImageView) imageView).setImageBitmap(preview);
            imageView.setVisibility(View.VISIBLE);
            imageView.bringToFront();
            imageView.setOnClickListener(v -> hideAll());
        }
        ensurePlayer(videoView);
        player.stop();
        player.setMediaItem(MediaItem.fromUri(videoUri));
        player.prepare();
        player.play();

        videoView.setShutterBackgroundColor(0x00000000);
        videoView.setKeepContentOnPlayerReset(true);
        videoView.setAlpha(0f);
        videoView.setVisibility(View.VISIBLE);
        videoView.bringToFront();
        videoView.startAnimation(AnimationUtils.loadAnimation(videoView.getContext(), R.anim.lightbox_show));
        videoView.setOnClickListener(v -> hideAll());
    }

    public void hideAll() {
        hideVideo(true, false);
        hideImage();
        videoView.postDelayed(this::notifyResume, 350);
    }

    private void notifyResume() {
        host.onLightboxHidden();
        // якщо обидва сховані — резюмимо
        if ((imageView == null || imageView.getVisibility() != View.VISIBLE)
                && (videoView == null || videoView.getVisibility() != View.VISIBLE)) {
            host.resumeAvatarVideos();
        }
    }

    public void release() {
        if (player != null) {
            player.removeListener(listener);
            player.release();
            player = null;
        }
        if (videoView != null) {
            videoView.setPlayer(null);
            videoView.setOnClickListener(null);
            videoView.setClickable(false);
            videoView.setVisibility(View.INVISIBLE);
        }
    }

    private void hideImage() {
        if (imageView == null || imageView.getVisibility() != View.VISIBLE) return;
        imageView.clearAnimation();
        imageView.setAlpha(0f);
        imageView.setVisibility(View.GONE);
        imageView.setOnClickListener(null);
        imageView.setClickable(false);
    }

    private void hideImageImmediate() {
        if (imageView == null) return;
        imageView.clearAnimation();
        imageView.setAlpha(0f);
        imageView.setVisibility(View.GONE);
        imageView.setOnClickListener(null);
        imageView.setClickable(false);
    }

    private void hideVideo(boolean immediate, boolean suppressResume) {
        if (videoView == null || videoView.getVisibility() != View.VISIBLE) {
            if (!suppressResume) notifyResume();
            return;
        }
        if (player != null) {
            player.setPlayWhenReady(false);
            player.pause();
            player.clearMediaItems();
        }
        videoView.setOnClickListener(null);
        videoView.setClickable(false);
        Runnable end = () -> {
            videoView.clearAnimation();
            videoView.setAlpha(0f);
            videoView.setVisibility(View.GONE);
            if (!suppressResume) notifyResume();
        };
        if (immediate) end.run();
        else {
            videoView.startAnimation(AnimationUtils.loadAnimation(videoView.getContext(), R.anim.lightbox_hide));
            videoView.postDelayed(end, 250);
        }
    }

    private void hideVideoImmediate(boolean suppressResume) {
        hideVideo(true, suppressResume);
    }

    private void crossfadePreviewToVideo() {
        if (videoView == null) return;
        videoView.animate().cancel();
        videoView.animate().alpha(1f).setDuration(180).start();
        if (imageView != null && imageView.getVisibility() == View.VISIBLE) {
            imageView.animate().cancel();
            imageView.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                imageView.setVisibility(View.GONE);
                imageView.setOnClickListener(null);
                imageView.setClickable(false);
            }).start();
        }
    }
}