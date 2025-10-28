package onion.network.ui.controllers;

import static onion.network.helpers.Const.REQUEST_PICK_IMAGE_POST;
import static onion.network.helpers.Const.REQUEST_TAKE_PHOTO_POST;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;

import onion.network.R;
import onion.network.helpers.ChatMediaStore;
import onion.network.helpers.Const;
import onion.network.helpers.PermissionHelper;
import onion.network.helpers.Utils;
import onion.network.models.Item;
import onion.network.models.PostDraft;
import onion.network.ui.pages.WallPage;

import java.lang.ref.WeakReference;
import java.util.EnumSet;
import java.util.Locale;

/**
 * Dialog controller for composing/editing a post. Handles:
 *  - choosing/taking image
 *  - picking video
 *  - recording voice note
 *  - preview updates and publish action
 *
 * Works together with WallPage: activity results are processed by WallPage
 * (see handleComposerActivityResult), using the pending draft stored here.
 */
public final class PostComposer {
    final WallPage page;
    public final Item item;
    public final PostDraft draft;
    final Dialog dialog;
    final View root;
    final TextInputEditText textField;

    final FrameLayout mediaContainer;
    final ImageView imageView;
    final ImageView videoBadge;
    final ImageButton removeMediaButton;

    final LinearLayout audioContainer;
    final TextView audioLabel;
    final ImageButton removeAudioButton;

    final ImageButton addImageButton;
    final ImageButton takePhotoButton;
    final ImageButton addVideoButton;
    final ImageButton recordAudioButton;
    final View publishButton;

    MediaRecorder recorder;
    File tempAudioFile;
    boolean recording;
    long recordingStartMs;

    public PostComposer(WallPage page,
                        Item item,
                        PostDraft draft,
                        Dialog dialog,
                        View root,
                        TextInputEditText textField) {
        this.page = page;
        this.item = item;
        this.draft = draft;
        this.dialog = dialog;
        this.root = root;
        this.textField = textField;

        this.mediaContainer = root.findViewById(R.id.mediaPreviewContainer);
        this.imageView = root.findViewById(R.id.image);
        this.videoBadge = root.findViewById(R.id.videoBadge);
        this.removeMediaButton = root.findViewById(R.id.remove_media);

        this.audioContainer = root.findViewById(R.id.audioContainer);
        this.audioLabel = root.findViewById(R.id.audioLabel);
        this.removeAudioButton = root.findViewById(R.id.remove_audio);

        this.addImageButton = root.findViewById(R.id.add_image);
        this.takePhotoButton = root.findViewById(R.id.take_photo);
        this.addVideoButton = root.findViewById(R.id.add_video);
        this.recordAudioButton = root.findViewById(R.id.record_audio);
        this.publishButton = root.findViewById(R.id.publish);
    }

    public void init() {
        updatePreview();

        if (removeMediaButton != null) {
            removeMediaButton.setOnClickListener(v -> {
                draft.clearImage();
                draft.clearVideo();
                updatePreview();
            });
        }
        if (removeAudioButton != null) {
            removeAudioButton.setOnClickListener(v -> {
                draft.clearAudio();
                updatePreview();
            });
        }
        if (addImageButton != null) {
            addImageButton.setOnClickListener(v -> {
                captureText();
                page.pendingDraftAfterActivity = draft.copy();
                page.pendingDraftItem = item;
                cancelAudioRecording();
                page.activity.launchWallImagePicker(page);
            });
        }
        if (takePhotoButton != null) {
            takePhotoButton.setOnClickListener(v -> PermissionHelper.runWithPermissions(
                    page.activity,
                    java.util.EnumSet.of(PermissionHelper.PermissionRequest.CAMERA),
                    () -> {
                        try {
                            page.pendingPhotoUri = page.createPhotoOutputUri();
                        } catch (IOException ex) {
                            page.pendingPhotoUri = null;
                            page.activity.snack("Unable to create photo file");
                            return;
                        }
                        captureText();
                        page.pendingDraftAfterActivity = draft.copy();
                        page.pendingDraftItem = item;
                        cancelAudioRecording();
                        page.activity.launchWallCamera(page, page.pendingPhotoUri);
                    },
                    () -> page.activity.snack("Camera permission required")
            ));
        }
        if (addVideoButton != null) {
            addVideoButton.setOnClickListener(v -> {
                captureText();
                page.pendingDraftAfterActivity = draft.copy();
                page.pendingDraftItem = item;
                cancelAudioRecording();
                page.activity.launchWallVideoPicker(page);
            });
        }
        if (recordAudioButton != null) {
            recordAudioButton.setOnClickListener(v -> {
                if (recording) {
                    stopAudioRecording(true);
                } else {
                    PermissionHelper.runWithPermissions(
                            page.activity,
                            EnumSet.of(PermissionHelper.PermissionRequest.MICROPHONE),
                            this::startAudioRecording,
                            () -> page.activity.snack(page.getContext().getString(R.string.chat_attachment_mic_permission_required))
                    );
                }
            });
        }
        if (publishButton != null) {
            publishButton.setOnClickListener(v -> {
                captureText();
                if (!page.ensureDraftMediaReferences(draft)) return;
                page.doPostPublish(item, draft.copy());
                dialog.dismiss();
            });
        }

        Log.d("PostComposer", "Launch picker: image/video/camera");
    }

    void captureText() {
        draft.text = textField.getText() != null ? textField.getText().toString() : "";
    }

    void prepareExternalAction(Runnable action) {
        captureText();
        page.pendingDraftAfterActivity = draft.copy();
        page.pendingDraftItem = item;
        cancelAudioRecording();
        dialog.dismiss();
        if (action != null) action.run();
    }

    // ===== Audio recording =====
    void startAudioRecording() {
        cancelAudioRecording();
        File dir = new File(page.activity.getCacheDir(), "post_audio");
        if (!dir.exists() && !dir.mkdirs()) {
            page.activity.snack(page.getContext().getString(R.string.chat_attachment_recording_failed));
            return;
        }
        tempAudioFile = new File(dir, "rec_" + System.currentTimeMillis() + ".m4a");
        recorder = new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(tempAudioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recordingStartMs = SystemClock.elapsedRealtime();
            if (recordAudioButton != null) recordAudioButton.setImageResource(R.drawable.ic_close);
            page.activity.snack(page.getContext().getString(R.string.chat_attachment_recording_started));
        } catch (Exception ex) {
            page.activity.snack(page.getContext().getString(R.string.chat_attachment_recording_failed));
            cancelAudioRecording();
        }
    }

    void stopAudioRecording(boolean keep) {
        if (!recording) return;
        try { recorder.stop(); } catch (Exception ex) { keep = false; }
        recording = false;
        if (recordAudioButton != null) recordAudioButton.setImageResource(R.drawable.ic_mic);
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignore) {}
            try { recorder.release(); } catch (Exception ignore) {}
            recorder = null;
        }
        if (!keep) {
            if (tempAudioFile != null && tempAudioFile.exists()) tempAudioFile.delete();
            tempAudioFile = null; return;
        }
        if (tempAudioFile == null || !tempAudioFile.exists()) {
            page.activity.snack(page.getContext().getString(R.string.chat_attachment_recording_failed));
            return;
        }
        byte[] bytes = Utils.readFileAsBytes(tempAudioFile);
        tempAudioFile.delete(); tempAudioFile = null;
        if (bytes.length == 0) {
            page.activity.snack(page.getContext().getString(R.string.chat_attachment_recording_failed));
            return;
        }
        if (ChatMediaStore.exceedsLimit(bytes.length)) {
            page.activity.snack(page.getContext().getString(R.string.chat_attachment_file_too_large));
            return;
        }
        long duration = SystemClock.elapsedRealtime() - recordingStartMs;
        draft.clearImage(); draft.clearVideo();
        draft.audioData = bytes; draft.audioMime = "audio/mp4"; draft.audioDurationMs = duration; draft.audioMediaId = null;
        updatePreview();
        page.activity.snack(page.getContext().getString(R.string.chat_attachment_recording_saved));
    }

    void cancelAudioRecording() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignore) {}
            try { recorder.reset(); } catch (Exception ignore) {}
            try { recorder.release(); } catch (Exception ignore) {}
            recorder = null;
        }
        recording = false;
        if (recordAudioButton != null) recordAudioButton.setImageResource(R.drawable.ic_mic);
        if (tempAudioFile != null && tempAudioFile.exists()) { tempAudioFile.delete(); tempAudioFile = null; }
    }

    public void updatePreview() {
        boolean hasImage = draft.image != null;
        boolean hasVideo = (draft.videoData != null && draft.videoData.length > 0) || !TextUtils.isEmpty(draft.videoMediaId);
        if (mediaContainer != null) {
            if (hasImage || hasVideo) {
                mediaContainer.setVisibility(View.VISIBLE);
                if (hasImage) imageView.setImageBitmap(draft.image);
                else if (draft.videoThumb != null) imageView.setImageBitmap(draft.videoThumb);
                else imageView.setImageBitmap(null);
                if (videoBadge != null) videoBadge.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
                if (removeMediaButton != null) removeMediaButton.setVisibility(View.VISIBLE);
            } else {
                mediaContainer.setVisibility(View.GONE);
                if (removeMediaButton != null) removeMediaButton.setVisibility(View.GONE);
            }
        }
        if (audioContainer != null) {
            if ((draft.audioData != null && draft.audioData.length > 0) || !TextUtils.isEmpty(draft.audioMediaId)) {
                audioContainer.setVisibility(View.VISIBLE);
                if (audioLabel != null) audioLabel.setText(buildAudioLabel(draft.audioDurationMs));
            } else {
                audioContainer.setVisibility(View.GONE);
            }
        }
    }

    String buildAudioLabel(long durationMs) {
        long totalSeconds = Math.max(1, durationMs / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return page.getContext().getString(R.string.chat_attachment_voice_message) +
                " • " + String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    public void release() { cancelAudioRecording(); }
}