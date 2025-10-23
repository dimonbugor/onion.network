

package onion.network.ui.pages;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.MimeTypeMap;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import onion.network.R;
import onion.network.TorManager;
import onion.network.clients.ChatClient;
import onion.network.databases.ChatDatabase;
import onion.network.helpers.ChatMediaStore;
import onion.network.helpers.Const;
import onion.network.helpers.Ed25519Signature;
import onion.network.helpers.PermissionHelper;
import onion.network.helpers.ThemeManager;
import onion.network.helpers.UiCustomizationManager;
import onion.network.helpers.Utils;
import onion.network.models.ChatMessagePayload;
import onion.network.models.Item;
import onion.network.servers.ChatServer;
import onion.network.services.UpdateScheduler;
import onion.network.settings.Settings;
import onion.network.ui.MainActivity;
import onion.network.ui.views.AvatarView;

public class ChatPage extends BasePage
        implements ChatClient.OnMessageSentListener, ChatServer.OnMessageReceivedListener {

    String TAG = "chat";
    ChatAdapter adapter;
    ChatDatabase chatDatabase;
    TorManager torManager;
    Cursor cursor;
    RecyclerView recycler;
    ChatServer chatServer;
    ChatClient chatClient;
    Timer timer;
    long idLastLast = -1;
    Item nameItem = new Item();
    private TextInputLayout textInputLayout;
    private TextInputEditText editMessageView;
    private ImageButton microButton;
    private ImageButton sendButton;
    private ImageButton attachButton;
    private LinearLayout attachmentPreview;
    private ImageView attachmentPreviewIcon;
    private TextView attachmentPreviewText;
    private ImageButton attachmentRemoveButton;

    private AttachmentDraft pendingAttachment;
    private MediaRecorder mediaRecorder;
    private File currentRecordingFile;
    private long recordingStartTimestamp;
    private boolean isRecordingAudio;

    private static final int REQUEST_CHAT_PICK_MEDIA = Const.REQUEST_CHAT_PICK_MEDIA;

    public ChatPage(final MainActivity activity) {
        super(activity);

        chatDatabase = ChatDatabase.getInstance(activity);
        chatServer = ChatServer.getInstance(activity);
        chatClient = ChatClient.getInstance(activity);
        torManager = TorManager.getInstance(activity);

        inflate(activity, R.layout.chat_page, this);

        recycler = (RecyclerView) findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new ChatAdapter();
        recycler.setAdapter(adapter);

        textInputLayout = findViewById(R.id.text_input_layout);
        editMessageView = findViewById(R.id.editmessage);
        microButton = findViewById(R.id.micro);
        sendButton = findViewById(R.id.send);
        attachButton = findViewById(R.id.attach);
        attachmentPreview = findViewById(R.id.attachmentPreview);
        attachmentPreviewIcon = findViewById(R.id.attachmentPreviewIcon);
        attachmentPreviewText = findViewById(R.id.attachmentPreviewText);
        attachmentRemoveButton = findViewById(R.id.attachmentRemove);

        if (attachButton != null) {
            attachButton.setOnClickListener(v -> requestMediaAttachment());
        }

        if (attachmentRemoveButton != null) {
            attachmentRemoveButton.setOnClickListener(v -> clearPendingAttachment(true));
        }

        if (microButton != null) {
            microButton.setOnClickListener(v -> toggleAudioRecording());
        }

        updateAttachmentPreview();

        if (sendButton != null) {
            sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String sender = torManager.getID();
                if (sender == null || sender.trim().equals("")) {
                    sendPendingAndUpdate();
                    Log.i(TAG, "no sender id");
                    return;
                }

                String message = editMessageView != null ? editMessageView.getText().toString() : "";
                message = message.trim();
                boolean hasAttachment = pendingAttachment != null;
                if (!hasAttachment && message.equals("")) return;

                ChatMessagePayload payload;
                if (hasAttachment) {
                    payload = pendingAttachment.payload.copy();
                    if (!TextUtils.isEmpty(message)) {
                        payload.setText(message);
                    }
                } else {
                    payload = ChatMessagePayload.forText(message);
                }

                Log.i(TAG, "sender " + sender);
                Log.i(TAG, "address " + address);
                Log.i(TAG, "message " + message);
                chatDatabase.addMessage(sender, address, payload.toStorageString(), System.currentTimeMillis(), false, true);
                Log.i(TAG, "sent");

                if (editMessageView != null) {
                    editMessageView.setText("");
                }
                clearPendingAttachment(false);

                sendPendingAndUpdate();

                recycler.smoothScrollToPosition(Math.max(0, cursor.getCount() - 1));

                UpdateScheduler.getInstance(context).put(address);
            }
        });
        }

        //load();

        sendPendingAndUpdate();
        applyUiCustomizationFromHost();
    }

    public void applyUiCustomizationFromHost() {
        UiCustomizationManager.ChatComposerConfig config = UiCustomizationManager.getChatComposerConfig(context);
        UiCustomizationManager.ColorPreset preset = UiCustomizationManager.getColorPreset(context);

        if (textInputLayout != null) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) textInputLayout.getLayoutParams();
            if (lp != null) {
                lp.setMargins(config.marginStartPx, lp.topMargin, config.marginEndPx, config.marginBottomPx);
                lp.height = config.heightPx;
                textInputLayout.setLayoutParams(lp);
            }
        }

        if (editMessageView != null) {
            editMessageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.textSizeSp);
            editMessageView.setPadding(config.paddingHorizontalPx, editMessageView.getPaddingTop(),
                    config.paddingHorizontalPx, editMessageView.getPaddingBottom());

            GradientDrawable background = null;
            if (editMessageView.getBackground() instanceof GradientDrawable) {
                background = (GradientDrawable) editMessageView.getBackground().mutate();
            }
            if (background != null) {
                background.setColor(preset.getSurfaceColor(context));
                background.setStroke(UiCustomizationManager.dpToPx(context, 1), preset.getAccentColor(context));
            }
            int onSurface = preset.getOnSurfaceColor(context);
            editMessageView.setTextColor(onSurface);
            editMessageView.setHintTextColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 160)));
        }

        ColorStateList iconTint = ColorStateList.valueOf(preset.getOnSurfaceColor(context));
        if (microButton != null) {
            microButton.setImageTintList(iconTint);
        }
        if (sendButton != null) {
            sendButton.setImageTintList(iconTint);
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    void log(String s) {
        Log.i(TAG, s);
    }

    @Override
    public void onNameItem(Item item) {
        nameItem = item;
        load();

        findViewById(R.id.offline).setVisibility(!activity.nameItemResult.ok() && !activity.nameItemResult.loading() ? View.VISIBLE : View.GONE);
        findViewById(R.id.loading).setVisibility(activity.nameItemResult.loading() ? View.VISIBLE : View.GONE);
    }

    @Override
    public String getTitle() {
        return "Chat";
    }

    @Override
    public int getIcon() {
        return R.drawable.ic_question_answer;
        //return R.drawable.ic_mail_outline_white_36dp;
    }

    void sendPendingAndUpdate() {

        new Thread() {
            @Override
            public void run() {
                sendUnsent();
                post(new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                });
            }
        }.start();

        load();

    }

    synchronized void sendUnsent() {
        try {
            chatClient.sendUnsent(address);
        } catch (IOException e) {
            log(e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        applyUiCustomizationFromHost();
        chatServer.addOnMessageReceivedListener(this);
        chatClient.addOnMessageSentListener(this);
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                log("send unsent");
                sendUnsent();
                //RequestTool.getInstance(context).
            }
        }, 0, 1000 * 60);
    }

    @Override
    public void onPause() {
        stopAudioRecording(false);
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
        chatServer.removeOnMessageReceivedListener(this);
        chatClient.removeOnMessageSentListener(this);
        super.onPause();
    }

    @Override
    public void onMessageSent() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                load();
                activity.initTabs();
            }
        });
    }

    @Override
    public void onMessageReceived() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                load();
                activity.initTabs();
            }
        });
    }

    @Override
    public void onTabSelected() {
        log("onTabSelected");
        activity.toggleChatMainMenu();
        if (isPageShown()) {
            load();
        }
    }

    void markReadIfVisible() {
        log("markReadIfVisible");
        if (isPageShown()) {
            log("markRead");
            chatDatabase.markRead(address);
        }
    }

    @Override
    public String getBadge() {
        int n = chatDatabase.getIncomingMessageCount(activity.address);
        if (n <= 0) return "";
        if (n >= 10) return "N";
        return "" + n;
    }

    @Override
    public void load() {

        log("load");

        boolean nochat = nameItem.json().optBoolean("nochat");
        findViewById(R.id.no_msg).setVisibility(nochat ? View.VISIBLE : View.GONE);

        String acceptmessages = Settings.getPrefs(context).getString("acceptmessages", "");
        findViewById(R.id.msg_from_none).setVisibility(!nochat && "none".equals(acceptmessages) ? View.VISIBLE : View.GONE);
        findViewById(R.id.msg_from_friends).setVisibility(!nochat && "friends".equals(acceptmessages) && !itemDatabase.hasKey("friend", address) ? View.VISIBLE : View.GONE);

        Cursor oldCursor = cursor;

        markReadIfVisible();

        cursor = chatDatabase.getMessages(address);
        if (oldCursor != null) {
            oldCursor.close();
        }
        adapter.notifyDataSetChanged();

        cursor.moveToLast();
        long idLast = -1;
        int i = cursor.getColumnIndex("_id");
        if (i >= 0 && cursor.getCount() > 0) {
            idLast = cursor.getLong(i);
        }
        if (idLast != idLastLast) {
            idLastLast = idLast;
            if (oldCursor == null || oldCursor.getCount() == 0)
                recycler.scrollToPosition(Math.max(0, cursor.getCount() - 1));
            else
                recycler.smoothScrollToPosition(Math.max(0, cursor.getCount() - 1));
        }

        activity.initTabs();

    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CHAT_PICK_MEDIA) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                handlePickedMedia(data.getData());
            }
            return;
        }
    }

    private void requestMediaAttachment() {
        PermissionHelper.runWithPermissions(
                activity,
                EnumSet.of(PermissionHelper.PermissionRequest.MEDIA),
                this::openMediaPicker,
                () -> activity.snack(getString(R.string.snackbar_storage_permission_required))
        );
    }

    private void openMediaPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            activity.startActivityForResult(intent, REQUEST_CHAT_PICK_MEDIA);
        } catch (Exception ex) {
            Log.e(TAG, "Unable to launch media picker", ex);
            activity.snack(getString(R.string.chat_attachment_pick_failed));
        }
    }

    private void handlePickedMedia(Uri uri) {
        if (uri == null) {
            activity.snack(getString(R.string.chat_attachment_pick_failed));
            return;
        }
        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
            if (stream == null) {
                activity.snack(getString(R.string.chat_attachment_pick_failed));
                return;
            }
            byte[] bytes = Utils.readInputStream(stream);
            if (ChatMediaStore.exceedsLimit(bytes.length)) {
                activity.snack(getString(R.string.chat_attachment_file_too_large));
                return;
            }
            String mime = activity.getContentResolver().getType(uri);
            if (TextUtils.isEmpty(mime)) {
                mime = guessMimeFromUri(uri);
            }
            ChatMessagePayload.Type type = inferTypeFromMime(mime);
            if (type == ChatMessagePayload.Type.TEXT) {
                activity.snack(getString(R.string.chat_attachment_unsupported));
                return;
            }
            String path = ChatMediaStore.saveOutgoing(context, type, bytes, mime);
            String name = resolveDisplayName(uri);
            long duration = 0L;
            if (type == ChatMessagePayload.Type.VIDEO) {
                duration = extractDuration(uri);
            }
            ChatMessagePayload payload = ChatMessagePayload.forType(type)
                    .setStorage(ChatMessagePayload.Storage.FILE)
                    .setMime(mime)
                    .setData(path)
                    .setSizeBytes(bytes.length)
                    .setDurationMs(duration);
            AttachmentDraft draft = new AttachmentDraft();
            draft.payload = payload;
            draft.label = buildAttachmentLabel(type, name, bytes.length, duration);
            draft.iconRes = resolveAttachmentIcon(type);
            clearPendingAttachment(true);
            pendingAttachment = draft;
            updateAttachmentPreview();
        } catch (IOException ex) {
            Log.e(TAG, "Failed to import media", ex);
            activity.snack(getString(R.string.chat_attachment_pick_failed));
        }
    }

    private String resolveDisplayName(Uri uri) {
        if (uri == null) return "";
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignore) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return "";
    }

    private long extractDuration(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (!TextUtils.isEmpty(durationStr)) {
                return Long.parseLong(durationStr);
            }
        } catch (Exception ignore) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignore) {
            }
        }
        return 0L;
    }

    private void updateAttachmentPreview() {
        if (attachmentPreview == null) return;
        if (pendingAttachment == null) {
            attachmentPreview.setVisibility(View.GONE);
            return;
        }
        attachmentPreview.setVisibility(View.VISIBLE);
        if (attachmentPreviewIcon != null) {
            attachmentPreviewIcon.setImageResource(pendingAttachment.iconRes);
        }
        if (attachmentPreviewText != null) {
            attachmentPreviewText.setText(pendingAttachment.label);
        }
    }

    private void clearPendingAttachment(boolean deleteFile) {
        if (pendingAttachment != null && deleteFile) {
            deleteDraftFile(pendingAttachment.payload);
        }
        pendingAttachment = null;
        updateAttachmentPreview();
    }

    private void deleteDraftFile(ChatMessagePayload payload) {
        if (payload == null || payload.isInline()) return;
        File file = ChatMediaStore.resolveFile(context, payload.getData());
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void toggleAudioRecording() {
        if (isRecordingAudio) {
            stopAudioRecording(true);
        } else {
            PermissionHelper.runWithPermissions(
                    activity,
                    EnumSet.of(PermissionHelper.PermissionRequest.MICROPHONE),
                    this::startAudioRecordingInternal,
                    () -> activity.snack(getString(R.string.chat_attachment_mic_permission_required))
            );
        }
    }

    private void startAudioRecordingInternal() {
        cancelAudioRecording();
        clearPendingAttachment(true);
        File dir = new File(activity.getCacheDir(), "chat_audio");
        if (!dir.exists() && !dir.mkdirs()) {
            activity.snack(getString(R.string.chat_attachment_recording_failed));
            return;
        }
        currentRecordingFile = new File(dir, "rec_" + System.currentTimeMillis() + ".m4a");
        mediaRecorder = new MediaRecorder();
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioChannels(1);
            mediaRecorder.setAudioEncodingBitRate(96000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setOutputFile(currentRecordingFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecordingAudio = true;
            recordingStartTimestamp = SystemClock.elapsedRealtime();
            if (microButton != null) {
                microButton.setImageResource(R.drawable.ic_close);
            }
            activity.snack(getString(R.string.chat_attachment_recording_started));
        } catch (Exception ex) {
            Log.e(TAG, "Unable to start recording", ex);
            activity.snack(getString(R.string.chat_attachment_recording_failed));
            releaseRecorder();
            if (currentRecordingFile != null) {
                currentRecordingFile.delete();
                currentRecordingFile = null;
            }
        }
    }

    private void stopAudioRecording(boolean keep) {
        if (!isRecordingAudio) {
            return;
        }
        long durationMs = Math.max(0, SystemClock.elapsedRealtime() - recordingStartTimestamp);
        try {
            mediaRecorder.stop();
        } catch (RuntimeException ex) {
            Log.w(TAG, "Recorder failed to stop cleanly", ex);
            keep = false;
        } finally {
            releaseRecorder();
        }
        isRecordingAudio = false;
        recordingStartTimestamp = 0L;
        if (microButton != null) {
            microButton.setImageResource(R.drawable.ic_mic);
        }
        if (!keep) {
            if (currentRecordingFile != null) {
                currentRecordingFile.delete();
                currentRecordingFile = null;
            }
            return;
        }
        if (currentRecordingFile == null || !currentRecordingFile.exists()) {
            activity.snack(getString(R.string.chat_attachment_recording_failed));
            return;
        }
        byte[] bytes = Utils.readFileAsBytes(currentRecordingFile);
        currentRecordingFile.delete();
        currentRecordingFile = null;
        if (bytes.length == 0) {
            activity.snack(getString(R.string.chat_attachment_recording_failed));
            return;
        }
        if (ChatMediaStore.exceedsLimit(bytes.length)) {
            activity.snack(getString(R.string.chat_attachment_file_too_large));
            return;
        }
        try {
            String path = ChatMediaStore.saveOutgoing(context, ChatMessagePayload.Type.AUDIO, bytes, "audio/mp4");
            ChatMessagePayload payload = ChatMessagePayload.forType(ChatMessagePayload.Type.AUDIO)
                    .setStorage(ChatMessagePayload.Storage.FILE)
                    .setMime("audio/mp4")
                    .setData(path)
                    .setSizeBytes(bytes.length)
                    .setDurationMs(durationMs);
            AttachmentDraft draft = new AttachmentDraft();
            draft.payload = payload;
            draft.label = buildAttachmentLabel(ChatMessagePayload.Type.AUDIO, getString(R.string.chat_attachment_voice_message), bytes.length, durationMs);
            draft.iconRes = R.drawable.ic_mic;
            clearPendingAttachment(true);
            pendingAttachment = draft;
            updateAttachmentPreview();
            activity.snack(getString(R.string.chat_attachment_recording_saved));
        } catch (IOException ex) {
            Log.e(TAG, "Failed to persist audio", ex);
            activity.snack(getString(R.string.chat_attachment_recording_failed));
        }
    }

    private void cancelAudioRecording() {
        if (!isRecordingAudio) {
            return;
        }
        try {
            mediaRecorder.stop();
        } catch (Exception ignore) {
        } finally {
            releaseRecorder();
        }
        isRecordingAudio = false;
        if (microButton != null) {
            microButton.setImageResource(R.drawable.ic_mic);
        }
        if (currentRecordingFile != null) {
            currentRecordingFile.delete();
            currentRecordingFile = null;
        }
    }

    private void releaseRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
            } catch (Exception ignore) {
            }
            try {
                mediaRecorder.release();
            } catch (Exception ignore) {
            }
            mediaRecorder = null;
        }
    }

    private String buildAttachmentLabel(ChatMessagePayload.Type type, String name, long sizeBytes, long durationMs) {
        StringBuilder builder = new StringBuilder();
        builder.append(getAttachmentTypeLabel(type));
        if (!TextUtils.isEmpty(name)) {
            builder.append(" • ").append(name);
        }
        if (sizeBytes > 0) {
            builder.append(" • ").append(formatSize(sizeBytes));
        }
        if (durationMs > 0) {
            builder.append(" • ").append(formatDuration(durationMs));
        }
        return builder.toString();
    }

    private String getAttachmentTypeLabel(ChatMessagePayload.Type type) {
        switch (type) {
            case IMAGE:
                return getString(R.string.chat_attachment_image);
            case VIDEO:
                return getString(R.string.chat_attachment_video);
            case AUDIO:
                return getString(R.string.chat_attachment_audio);
            default:
                return getString(R.string.chat_attachment_generic);
        }
    }

    private int resolveAttachmentIcon(ChatMessagePayload.Type type) {
        switch (type) {
            case AUDIO:
                return R.drawable.ic_mic;
            default:
                return R.drawable.ic_insert_photo_white_24dp;
        }
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final long kb = 1024;
        final long mb = kb * 1024;
        if (bytes >= mb) {
            return String.format(Locale.US, "%.1f MB", bytes / (float) mb);
        }
        if (bytes >= kb) {
            return String.format(Locale.US, "%.1f KB", bytes / (float) kb);
        }
        return bytes + " B";
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(1, durationMs / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private String guessMimeFromUri(Uri uri) {
        if (uri == null) return null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
        if (TextUtils.isEmpty(extension)) {
            String path = uri.getPath();
            if (!TextUtils.isEmpty(path)) {
                int idx = path.lastIndexOf('.');
                if (idx >= 0 && idx + 1 < path.length()) {
                    extension = path.substring(idx + 1);
                }
            }
        }
        if (TextUtils.isEmpty(extension)) {
            return null;
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.US));
    }

    private ChatMessagePayload.Type inferTypeFromMime(String mime) {
        if (mime == null) {
            return ChatMessagePayload.Type.TEXT;
        }
        String lower = mime.toLowerCase(Locale.US);
        if (lower.startsWith("image")) {
            return ChatMessagePayload.Type.IMAGE;
        }
        if (lower.startsWith("video")) {
            return ChatMessagePayload.Type.VIDEO;
        }
        if (lower.startsWith("audio")) {
            return ChatMessagePayload.Type.AUDIO;
        }
        return ChatMessagePayload.Type.TEXT;
    }

    @Override
    public String getPageIDString() {
        return "chat";
    }

    class ChatHolder extends RecyclerView.ViewHolder {
        public TextView message, time, status;
        public View left, right;
        public MaterialCardView card;
        public View abort;
        public LinearLayout cardContent;
        public LinearLayout metaRow;
        public FrameLayout attachmentContainer;
        public ImageView attachmentImage;
        public ImageView attachmentVideoBadge;

        public ChatHolder(View v) {
            super(v);
            message = (TextView) v.findViewById(R.id.message);
            time = (TextView) v.findViewById(R.id.time);
            status = (TextView) v.findViewById(R.id.status);
            left = v.findViewById(R.id.left);
            right = v.findViewById(R.id.right);
            card = (MaterialCardView) v.findViewById(R.id.card);
            abort = v.findViewById(R.id.abort);
            cardContent = v.findViewById(R.id.cardContent);
            metaRow = v.findViewById(R.id.metaRow);
            attachmentContainer = v.findViewById(R.id.attachmentContainer);
            attachmentImage = v.findViewById(R.id.attachmentImage);
            attachmentVideoBadge = v.findViewById(R.id.attachmentVideoBadge);
        }
    }

    private static final class AttachmentDraft {
        ChatMessagePayload payload;
        String label;
        int iconRes;
    }

    class ChatAdapter extends RecyclerView.Adapter<ChatHolder> {

        @Override
        public ChatHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ChatHolder(activity.getLayoutInflater().inflate(R.layout.chat_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ChatHolder holder, int position) {
            if (cursor == null) return;

            cursor.moveToFirst();
            cursor.moveToPosition(position);

            final long id = cursor.getLong(cursor.getColumnIndex("_id"));
            String content = cursor.getString(cursor.getColumnIndex("content"));
            ChatMessagePayload payload = ChatMessagePayload.fromStorageString(content);
            String sender = cursor.getString(cursor.getColumnIndex("sender"));
            String time = Utils.formatDate(cursor.getString(cursor.getColumnIndex("time")));
            boolean pending = cursor.getInt(cursor.getColumnIndex("outgoing")) > 0;
            boolean tx = sender.equals(torManager.getID());

            if (sender.equals(torManager.getID())) sender = "You";

            if (tx) {
                holder.card.setBackground(
                        holder.card.getContext().getResources().getDrawable(R.drawable.chat_item_background));
                holder.left.setVisibility(View.VISIBLE);
                holder.right.setVisibility(View.GONE);
            } else {
                holder.card.setBackground(
                        holder.card.getContext().getResources().getDrawable(R.drawable.chat_my_item_background));
                holder.left.setVisibility(View.GONE);
                holder.right.setVisibility(View.VISIBLE);
            }

            if (pending)
                holder.card.setCardElevation(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics()));
            else
                holder.card.setCardElevation(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, getResources().getDisplayMetrics()));

            String status = "";
            if (sender.equals(address)) {
                if (activity.name.isEmpty())
                    status = address;
                else
                    status = activity.name;
            } else {
                if (pending) {
                    status = "\u2714";
                } else {
                    status = "\u2714\u2714";
                }
            }


            if (pending) {
                holder.abort.setVisibility(View.VISIBLE);
                holder.abort.setClickable(true);
                holder.abort.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean ok = chatDatabase.abortPendingMessage(id);
                        load();
                        Toast.makeText(activity, ok ? "Pending message aborted." : "Error: Message already sent.", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                holder.abort.setVisibility(View.GONE);
                holder.abort.setClickable(false);
                holder.abort.setOnClickListener(null);
            }

            int color = pending ? ThemeManager.getColor(activity, R.attr.white_50)
                    : ThemeManager.getColor(activity, R.attr.white_80);
            holder.time.setTextColor(color);
            holder.status.setTextColor(color);

            boolean attachmentVisible = bindAttachment(holder, payload);
            String displayMessage = buildDisplayMessage(payload);
            if (TextUtils.isEmpty(displayMessage)) {
                if (attachmentVisible) {
                    holder.message.setVisibility(View.GONE);
                } else {
                    holder.message.setVisibility(View.VISIBLE);
                    holder.message.setMovementMethod(LinkMovementMethod.getInstance());
                    holder.message.setText(payloadPlaceholder(payload));
                }
            } else {
                holder.message.setVisibility(View.VISIBLE);
                holder.message.setMovementMethod(LinkMovementMethod.getInstance());
                holder.message.setText(Utils.linkify(context, displayMessage));
            }

            applyMessageStyle(holder, tx);

            holder.time.setText(time);

            holder.status.setText(status);
        }

        @Override
        public int getItemCount() {
            return cursor != null ? cursor.getCount() : 0;
        }

        private void applyMessageStyle(ChatHolder holder, boolean outgoing) {
            UiCustomizationManager.ChatComposerConfig config = UiCustomizationManager.getChatComposerConfig(context);

            holder.message.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.messageTextSizeSp);
            holder.message.setLineSpacing(0f, config.messageLineSpacingMultiplier);

            holder.status.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.metadataTextSizeSp);
            holder.time.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.metadataTextSizeSp);

            holder.status.setGravity(config.metadataAlignStart ? Gravity.START : Gravity.BOTTOM);
            holder.time.setGravity(config.metadataAlignStart ? Gravity.START : Gravity.END | Gravity.BOTTOM);

            if (holder.cardContent != null) {
                holder.cardContent.setPadding(
                        config.bubblePaddingHorizontalPx,
                        config.bubblePaddingVerticalPx,
                        config.bubblePaddingHorizontalPx,
                        config.bubblePaddingVerticalPx);
            }

            if (holder.metaRow != null) {
                holder.metaRow.setOrientation(config.metadataStacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                holder.metaRow.setGravity(config.metadataAlignStart ? Gravity.START : Gravity.BOTTOM);

                if (config.metadataStacked) {
                    holder.metaRow.setPadding(
                            config.bubblePaddingHorizontalPx,
                            config.bubblePaddingVerticalPx,
                            config.bubblePaddingHorizontalPx,
                            config.bubblePaddingVerticalPx);
                } else {
                    holder.metaRow.setPadding(
                            config.bubblePaddingHorizontalPx,
                            config.bubblePaddingVerticalPx / 2,
                            config.bubblePaddingHorizontalPx,
                            config.bubblePaddingVerticalPx / 2);
                }

                LinearLayout.LayoutParams statusLp;
                LinearLayout.LayoutParams timeLp;

                if (config.metadataStacked) {
                    statusLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    statusLp.gravity = Gravity.START;

                    timeLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    timeLp.gravity = Gravity.START;
                    timeLp.topMargin = config.metadataSpacingVerticalPx;
                } else {
                    statusLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    statusLp.gravity = config.metadataAlignStart ? Gravity.START : Gravity.BOTTOM;

                    timeLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    timeLp.gravity = config.metadataAlignStart ? Gravity.START : Gravity.END | Gravity.BOTTOM;
                }

                holder.status.setLayoutParams(statusLp);
                holder.time.setLayoutParams(timeLp);
            }

            float bubbleRadius = UiCustomizationManager.resolveCornerRadiusPx(context, config.bubbleCornerRadiusPx);
            holder.card.setRadius(bubbleRadius);
            updateBubbleCorners(holder, outgoing, bubbleRadius);
        }

        private void updateBubbleCorners(ChatHolder holder, boolean outgoing,
                                         float resolvedRadius) {
            if (holder.card == null) return;
            if (!(holder.card.getBackground() instanceof GradientDrawable)) return;
            GradientDrawable drawable = (GradientDrawable) holder.card.getBackground().mutate();
            float[] radii;
            if (outgoing) {
                radii = new float[]{resolvedRadius, resolvedRadius, resolvedRadius, resolvedRadius,
                        0f, 0f, resolvedRadius, resolvedRadius};
            } else {
                radii = new float[]{resolvedRadius, resolvedRadius, resolvedRadius, resolvedRadius,
                        resolvedRadius, resolvedRadius, 0f, 0f};
            }
            drawable.setCornerRadii(radii);
        }

        private String buildDisplayMessage(ChatMessagePayload payload) {
            if (payload == null) {
                return "";
            }
            return payload.getText();
        }

        private String payloadPlaceholder(ChatMessagePayload payload) {
            switch (payload.getType()) {
                case IMAGE:
                    return "[" + activity.getString(R.string.chat_attachment_image) + "]";
                case VIDEO:
                    return "[" + activity.getString(R.string.chat_attachment_video) + "]";
                case AUDIO:
                    return "[" + activity.getString(R.string.chat_attachment_audio) + "]";
                default:
                    return "[" + activity.getString(R.string.chat_attachment_generic) + "]";
            }
        }

        private boolean bindAttachment(ChatHolder holder, ChatMessagePayload payload) {
            if (holder.attachmentContainer == null || holder.attachmentImage == null) {
                return false;
            }
            holder.attachmentContainer.setVisibility(View.GONE);
            holder.attachmentImage.setVisibility(View.GONE);
            holder.attachmentImage.setImageBitmap(null);
            holder.attachmentImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (holder.attachmentVideoBadge != null) {
                holder.attachmentVideoBadge.setVisibility(View.GONE);
            }
            holder.attachmentContainer.setOnClickListener(null);

            if (payload == null) {
                return false;
            }

            switch (payload.getType()) {
                case IMAGE:
                    return bindImageAttachment(holder, payload);
                case VIDEO:
                    return bindVideoAttachment(holder, payload);
                default:
                    return false;
            }
        }

        private boolean bindImageAttachment(ChatHolder holder, ChatMessagePayload payload) {
            Bitmap bitmap = loadImageBitmap(payload);
            if (bitmap == null) {
                return false;
            }
            holder.attachmentContainer.setVisibility(View.VISIBLE);
            holder.attachmentImage.setVisibility(View.VISIBLE);
            holder.attachmentImage.setImageBitmap(bitmap);
            holder.attachmentContainer.setOnClickListener(v ->
                    activity.showLightbox(AvatarView.AvatarContent.photo(bitmap)));
            return true;
        }

        private boolean bindVideoAttachment(ChatHolder holder, ChatMessagePayload payload) {
            File file = resolvePayloadFile(payload);
            if (file == null || !file.exists()) {
                return false;
            }
            Bitmap thumb = loadVideoThumbnail(file);
            Uri contentUri = ChatMediaStore.createContentUri(context, payload.getData());
            if (contentUri == null) {
                return false;
            }
            holder.attachmentContainer.setVisibility(View.VISIBLE);
            holder.attachmentImage.setVisibility(View.VISIBLE);
            if (thumb != null) {
                holder.attachmentImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.attachmentImage.setImageBitmap(thumb);
            } else {
                holder.attachmentImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                holder.attachmentImage.setImageResource(R.drawable.ic_videocam);
            }
            if (holder.attachmentVideoBadge != null) {
                holder.attachmentVideoBadge.setVisibility(View.VISIBLE);
            }
            holder.attachmentContainer.setOnClickListener(v ->
                    activity.showLightbox(AvatarView.AvatarContent.video(contentUri.toString(), thumb)));
            return true;
        }

        private File resolvePayloadFile(ChatMessagePayload payload) {
            if (payload == null || payload.isInline()) {
                return null;
            }
            return ChatMediaStore.resolveFile(context, payload.getData());
        }

        private Bitmap loadImageBitmap(ChatMessagePayload payload) {
            if (payload == null) return null;
            if (!payload.isInline()) {
                File file = resolvePayloadFile(payload);
                if (file != null && file.exists()) {
                    return decodeBitmapFromFile(file, 1024);
                }
            }
            String data = payload.getData();
            if (!TextUtils.isEmpty(data)) {
                try {
                    byte[] bytes = Ed25519Signature.base64Decode(data);
                    return decodeBitmapFromBytes(bytes, 1024);
                } catch (Exception ignore) {
                }
            }
            return null;
        }

        private Bitmap decodeBitmapFromFile(File file, int maxDim) {
            if (file == null) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            options.inSampleSize = calculateSampleSize(options, maxDim);
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        }

        private Bitmap decodeBitmapFromBytes(byte[] data, int maxDim) {
            if (data == null || data.length == 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            options.inSampleSize = calculateSampleSize(options, maxDim);
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        }

        private int calculateSampleSize(BitmapFactory.Options options, int maxDim) {
            int height = options.outHeight;
            int width = options.outWidth;
            int inSampleSize = 1;
            if (height > maxDim || width > maxDim) {
                final int halfHeight = height / 2;
                final int halfWidth = width / 2;
                while ((halfHeight / inSampleSize) >= maxDim && (halfWidth / inSampleSize) >= maxDim) {
                    inSampleSize *= 2;
                }
            }
            return Math.max(1, inSampleSize);
        }

        private Bitmap loadVideoThumbnail(File file) {
            if (file == null || !file.exists()) {
                return null;
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return ThumbnailUtils.createVideoThumbnail(file, new Size(512, 512), null);
                } else {
                    return ThumbnailUtils.createVideoThumbnail(file.getAbsolutePath(), MediaStore.Images.Thumbnails.MINI_KIND);
                }
            } catch (Exception ex) {
                Log.w(TAG, "Unable to create video thumbnail", ex);
                return null;
            }
        }

    }

}
