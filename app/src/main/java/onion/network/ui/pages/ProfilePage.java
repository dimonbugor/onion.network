// ===== MODULE: Profile avatar photo/video using registerForActivityResult =====
// Copy each FILE block into the appropriate source file/package.
// Package name kept from your snippet: onion.network.ui.pages

// FILE: ProfilePage.java (refactored)
package onion.network.ui.pages;

import static onion.network.helpers.Const.REQUEST_CHOOSE_MEDIA;
import static onion.network.helpers.Const.REQUEST_TAKE_PHOTO_PROFILE;
import static onion.network.helpers.Const.REQUEST_TAKE_VIDEO;

import android.text.InputType;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;

import onion.network.R;
import onion.network.databases.ItemDatabase;
import onion.network.helpers.DialogHelper;
import onion.network.helpers.Ed25519Signature;
import onion.network.helpers.PermissionHelper;
import onion.network.helpers.ThemeManager;
import onion.network.helpers.Utils;
import onion.network.helpers.VideoCacheManager;
import onion.network.models.FriendTool;
import onion.network.models.Item;
import onion.network.models.ItemResult;
import onion.network.models.ItemTask;
import onion.network.ui.MainActivity;
import onion.network.ui.views.AvatarView;
import onion.network.utils.WallUtils;

public class ProfilePage extends BasePage {

    private static final String TAG = "ProfilePage";
    private static final long MAX_VIDEO_DURATION_MS = 5_500; // 5.5s
    private static final long MAX_VIDEO_FILE_SIZE_BYTES = 2L * 1024 * 1024; // ~2MB

    private AvatarView profileAvatarView;
    private Bitmap profilePhotoBitmap;
    private Bitmap profileVideoThumbBitmap;

    private String videoUriStr = null; // persisted/playable URI string

    private static final EnumSet<PermissionHelper.PermissionRequest> PERMS_MEDIA_ONLY = EnumSet.of(PermissionHelper.PermissionRequest.MEDIA);
    private static final EnumSet<PermissionHelper.PermissionRequest> PERMS_CAMERA_ONLY = EnumSet.of(PermissionHelper.PermissionRequest.CAMERA);
    private static final EnumSet<PermissionHelper.PermissionRequest> PERMS_VIDEO = EnumSet.of(
            PermissionHelper.PermissionRequest.MEDIA,
            PermissionHelper.PermissionRequest.CAMERA,
            PermissionHelper.PermissionRequest.MICROPHONE
    );

    public static final class Row {
        public final String key;
        public final String label;
        public final int type; // InputType для діалога

        public Row(String key, String label) {
            this(key, label, InputType.TYPE_CLASS_TEXT);
        }
        public Row(String key, String label, int type) {
            this.key = key;
            this.label = label;
            this.type = type;
        }
    }

    public static final Row[] rows = new Row[] {
            new Row("name",   "Name",    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
            new Row("about",  "About",   InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            new Row("email",  "Email",   InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            new Row("phone",  "Phone",   InputType.TYPE_CLASS_PHONE),
            new Row("website","Website", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI),
    };

    public ProfilePage(final MainActivity activity) {
        super(activity);
        inflate(activity, R.layout.profile_page, this);
        contentView = findViewById(R.id.contentView);

        profileAvatarView = findViewById(R.id.profilephoto);
        if (profileAvatarView != null) profileAvatarView.setPlaceholderResource(R.drawable.nophoto);

        findViewById(R.id.choose_photo).setOnClickListener(v -> showPickMediaDialog());
        findViewById(R.id.choose_photo).setVisibility(address.isEmpty() ? View.VISIBLE : View.GONE);

        configureDeleteButtonForPhoto();
        refreshDeleteButtonVisibility();

        findViewById(R.id.take_photo).setOnClickListener(v -> showCaptureMediaDialog());
        findViewById(R.id.take_photo).setVisibility(address.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override public String getTitle() { return "Profile"; }
    @Override public int getIcon() { return R.drawable.ic_assignment_ind; }

    // ===== Public entrypoint for MainActivity's activity‑result callbacks
    public void handleProfileActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode != MainActivity.RESULT_OK && requestCode != REQUEST_TAKE_PHOTO_PROFILE) return;
        if (requestCode == REQUEST_CHOOSE_MEDIA) {
            Uri uri = MainActivity.extractSingleUri(data);
            if (uri == null) return;
            // Persist long‑term access where supported
            try {
                final int flags = data != null ? data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION) : 0;
                if (flags != 0) getContext().getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Throwable ignore) {}

            String mime = getMimeType(getContext(), uri);
            if (mime != null && mime.startsWith("image/")) {
                try {
                    Bitmap bmp = WallUtils.getActivityResultBitmap(getContext(), new Intent().setData(uri));
                    bmp = WallUtils.fixImageOrientation(getContext(), bmp, uri);
                    if (bmp != null) bmp = ThumbnailUtils.extractThumbnail(bmp, 320, 320);
                    if (bmp != null) showAndSavePhoto(bmp);
                } catch (IOException e) { e.printStackTrace(); }
                return;
            }
            if (mime != null && mime.startsWith("video/")) {
                handlePickedVideo(uri);
                return;
            }
            Snackbar.make(contentView, R.string.chat_attachment_unsupported, Snackbar.LENGTH_SHORT).show();
            return;
        }

        if (requestCode == REQUEST_TAKE_PHOTO_PROFILE) {
            // Camera thumbnail in extras (since we don't provide EXTRA_OUTPUT for photos here)
            if (data != null && data.getExtras() != null) {
                Object extra = data.getExtras().get("data");
                if (extra instanceof Bitmap) {
                    Bitmap bmp = ThumbnailUtils.extractThumbnail((Bitmap) extra, 320, 320);
                    showAndSavePhoto(bmp);
                }
            }
            return;
        }

        if (requestCode == REQUEST_TAKE_VIDEO) {
            Uri uri = pendingVideoUri != null ? pendingVideoUri : MainActivity.extractSingleUri(data);
            if (uri == null) {
                Snackbar.make(contentView, R.string.snackbar_no_video_uri, Snackbar.LENGTH_LONG).show(); return; }
            handlePickedVideo(uri);
            pendingVideoUri = null; // cleanup
        }
    }

    // ===== UI + state helpers (unchanged semantics) =====
    private void updateProfileAvatar() {
        if (profileAvatarView == null) return;
        String videoUri = !TextUtils.isEmpty(videoUriStr) ? videoUriStr : null;
        Bitmap fallback = profileVideoThumbBitmap != null ? profileVideoThumbBitmap : profilePhotoBitmap;
        profileAvatarView.bind(profilePhotoBitmap, fallback, videoUri);
        profileAvatarView.setOnAvatarClickListener(content -> activity.showLightbox(content));
    }

    private void configureDeleteButtonForPhoto() {
        View deleteBtn = findViewById(R.id.delete_photo);
        if (deleteBtn == null) return;
        deleteBtn.setOnClickListener(v -> DialogHelper.showConfirm(
                activity,
                R.string.dialog_delete_photo_title,
                R.string.dialog_delete_photo_message,
                R.string.dialog_button_yes,
                () -> {
                    ItemDatabase.getInstance(getContext()).put(new Item.Builder("photo", "", "").build());
                    ItemDatabase.getInstance(getContext()).put(new Item.Builder("thumb", "", "").build());
                    activity.load();
                    FriendTool.getInstance(context).requestUpdates();
                },
                R.string.dialog_button_no,
                null
        ));
    }

    private void configureDeleteButtonForVideo() {
        View deleteBtn = findViewById(R.id.delete_photo);
        if (deleteBtn == null) return;
        deleteBtn.setOnClickListener(v -> DialogHelper.showConfirm(
                activity,
                R.string.dialog_delete_video_title,
                R.string.dialog_delete_video_message,
                R.string.dialog_button_yes,
                () -> {
                    ItemDatabase.getInstance(getContext()).put(new Item.Builder("video", "", "").build());
                    ItemDatabase.getInstance(getContext()).put(new Item.Builder("video_thumb", "", "").build());
                    videoUriStr = null; profileVideoThumbBitmap = null; updateProfileAvatar(); activity.load();
                    FriendTool.getInstance(context).requestUpdates();
                },
                R.string.dialog_button_no,
                null
        ));
    }

    private void refreshDeleteButtonVisibility() {
        View deleteBtn = findViewById(R.id.delete_photo);
        if (deleteBtn == null) return;
        if (!address.isEmpty()) { deleteBtn.setVisibility(View.GONE); return; }
        boolean hasVideo = !TextUtils.isEmpty(videoUriStr);
        boolean hasPhoto = profilePhotoBitmap != null;
        deleteBtn.setVisibility((hasVideo || hasPhoto) ? View.VISIBLE : View.GONE);
    }

    LinearLayout contentView;

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateProfileAvatar(); if (profileAvatarView != null) profileAvatarView.resume(); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); if (profileAvatarView != null) profileAvatarView.release(); }
    public void onPageResume() { if (profileAvatarView != null) profileAvatarView.resume(); }
    public void onPagePause() { if (profileAvatarView != null) profileAvatarView.pause(); }

    // ===== Data load (kept from your version, trimmed where irrelevant) =====
    public void load() {
        refreshDeleteButtonVisibility();
        new ItemTask(getContext(), address, "photo") {
            @Override protected void onProgressUpdate(ItemResult... values) {
                try {
                    ItemResult itemResult = values[0];
                    String str = itemResult.one().json().optString("photo").trim();
                    profilePhotoBitmap = TextUtils.isEmpty(str) ? null : BitmapFactory.decodeByteArray(Base64.decode(str, Base64.DEFAULT), 0, Base64.decode(str, Base64.DEFAULT).length);
                } catch (Exception ex) { ex.printStackTrace(); profilePhotoBitmap = null; }
                updateProfileAvatar(); refreshDeleteButtonVisibility();
            }
        }.execute2();

        // video uri + thumb
        new ItemTask(getContext(), address, "video") {
            @Override protected void onProgressUpdate(ItemResult... values) {
                try {
                    ItemResult itemResult = values[0];
                    JSONObject json = itemResult.one().json();
                    Uri playableUri = VideoCacheManager.ensureVideoUri(
                            getContext(), address.isEmpty() ? activity.getID() : address,
                            json.optString("video_uri", "").trim(), json.optString("video", "").trim());
                    videoUriStr = playableUri != null ? playableUri.toString() : null;
                } catch (Exception e) { e.printStackTrace(); videoUriStr = null; }
                updateProfileAvatar();
                if (address.isEmpty()) { if (!TextUtils.isEmpty(videoUriStr)) configureDeleteButtonForVideo(); else configureDeleteButtonForPhoto(); }
                refreshDeleteButtonVisibility();
            }
        }.execute2();

        new ItemTask(getContext(), address, "video_thumb") {
            @Override protected void onProgressUpdate(ItemResult... values) {
                try {
                    ItemResult itemResult = values[0];
                    String str = itemResult.one().json().optString("video_thumb", "").trim();
                    profileVideoThumbBitmap = TextUtils.isEmpty(str) ? null : BitmapFactory.decodeByteArray(Base64.decode(str, Base64.DEFAULT), 0, Base64.decode(str, Base64.DEFAULT).length);
                } catch (Exception e) { e.printStackTrace(); profileVideoThumbBitmap = null; }
                updateProfileAvatar();
            }
        }.execute2();

        new ItemTask(getContext(), address, "info") {
            @Override
            protected void onProgressUpdate(ItemResult... values) {
                JSONObject o;
                try {
                    o = values[0].one().json(activity, activity.address);
                } catch (Throwable t) {
                    o = new JSONObject(); // щоб UI не падав, навіть якщо даних нема
                }

                if (contentView == null) contentView = findViewById(R.id.contentView);
                if (contentView == null) return;

                contentView.removeAllViews();

                // 0) ID — показуємо завжди
                {
                    View v = activity.getLayoutInflater().inflate(R.layout.profile_item, contentView, false);
                    v.findViewById(R.id.edit).setVisibility(View.GONE);
                    TextView keyview = v.findViewById(R.id.key);
                    TextView valview = v.findViewById(R.id.val);
                    keyview.setText("ID");
                    valview.setText(activity.getID());
                    valview.setTextColor(getResources().getColor(android.R.color.white));
                    v.setClickable(false);
                    contentView.addView(v);
                }

                boolean isOwn = address.isEmpty();

                // 1) решта полів з ProfilePage.rows
                for (final Row row : rows) {
                    final String key = row.key;                    // ← final
                    final String raw = o.optString(key, "");       // ← final
                    final boolean hasVal = raw != null && raw.trim().length() > 0;

                    // На чужому профілі показуємо тільки name і заповнені поля
                    if (!isOwn && !"name".equals(key) && !hasVal) {
                        continue;
                    }

                    View v = activity.getLayoutInflater().inflate(R.layout.profile_item, contentView, false);
                    v.findViewById(R.id.edit).setVisibility(isOwn ? View.VISIBLE : View.GONE);

                    TextView keyview = v.findViewById(R.id.key);
                    TextView valview = v.findViewById(R.id.val);
                    keyview.setText(row.label);

                    String valShown = hasVal ? raw.trim() : "Unknown";
                    if ("name".equals(key)) {
                        valShown = htmlname(valShown);
                    } else {
                        valShown = htmlbr(valShown);
                    }
                    valview.setText(valShown);

                    // багаторядковість якщо тип містить MULTI_LINE
                    boolean isMultiline = (row.type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
                    valview.setSingleLine(!isMultiline);
                    valview.setTextColor(getResources().getColor(android.R.color.white));

                    if (isOwn) {
                        // зробимо final копію JSON, щоб компілятор не лаявся (або використай новий JSONObject(o.toString()))
                        final JSONObject infoRef = o;

                        v.setOnClickListener(v1 -> {
                            View dialogView = activity.getLayoutInflater().inflate(R.layout.profile_dialog, null, false);
                            final android.widget.EditText textEdit = dialogView.findViewById(R.id.text);
                            textEdit.setText(hasVal ? raw : "");
                            textEdit.setInputType(row.type);

                            AlertDialog dialog = DialogHelper.themedBuilder(activity)
                                    .setView(dialogView)
                                    .setTitle(activity.getString(R.string.dialog_change_field_title, row.label))
                                    .setNegativeButton(R.string.dialog_button_cancel, null)
                                    .setPositiveButton(R.string.dialog_button_publish,
                                            (d, w) -> save(infoRef, key, textEdit.getText().toString()))
                                    .setNeutralButton(R.string.dialog_button_clear,
                                            (d, w) -> { clear(infoRef, key); activity.load(); })
                                    .create();
                            DialogHelper.show(dialog);
                        });
                    } else {
                        v.setClickable(false);
                    }

                    contentView.addView(v);
                }

                View loader = findViewById(R.id.loading);
                if (loader != null) loader.setVisibility(View.GONE);
            }
        }.execute2();
    }

    private String htmlname(String s) {
        return TextUtils.isEmpty(s) ? getContext().getString(R.string.label_anonymous) : s;
    }

    private String htmlbr(String s) {
        return s == null ? "" : s;
    }

    // ===== Picking / Capturing (now routed through MainActivity launchers) =====
    private void showPickMediaDialog() {
        ArrayAdapter<String> adapter = getStringArrayAdapter(getResources().getStringArray(R.array.dialog_choose_media_options));
        AlertDialog dialog = DialogHelper.themedBuilder(activity)
                .setTitle(R.string.dialog_choose_media_title)
                .setAdapter(adapter, (d, which) -> { if (which == 0) pickMedia(true); else pickMedia(false); })
                .create();
        DialogHelper.show(dialog);
    }

    private void showCaptureMediaDialog() {
        ArrayAdapter<String> adapter = getStringArrayAdapter(getResources().getStringArray(R.array.dialog_capture_media_options));
        AlertDialog dialog = DialogHelper.themedBuilder(activity)
                .setTitle(R.string.dialog_capture_media_title)
                .setAdapter(adapter, (d, which) -> { if (which == 0) capturePhoto(); else captureVideo(); })
                .create();
        DialogHelper.show(dialog);
    }

    private ArrayAdapter<String> getStringArrayAdapter(final String[] items) {
        return new ArrayAdapter<String>(getContext(), R.layout.dialog_list_item, items) {
            @NonNull @Override public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextColor(ThemeManager.getColor(getContext(), android.R.attr.textColorPrimary));
                return view;
            }
        };
    }

    private void pickMedia(boolean photo) {
        PermissionHelper.runWithPermissions(activity, PERMS_MEDIA_ONLY,
                () -> activity.launchProfilePickMedia(this, photo),
                () -> Snackbar.make(contentView, R.string.snackbar_storage_permission_required, Snackbar.LENGTH_LONG).show());
    }

    private void capturePhoto() {
        PermissionHelper.runWithPermissions(activity, PERMS_CAMERA_ONLY,
                () -> activity.launchProfileCapturePhoto(this),
                () -> Snackbar.make(contentView, R.string.snackbar_camera_permission_required, Snackbar.LENGTH_LONG).show());
    }

    private Uri pendingVideoUri;

    private Uri createVideoOutputUri() {
        File dir = new File(getContext().getExternalCacheDir(), "camera");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String name = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".mp4";
        File f = new File(dir, name);
        return FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
    }

    private void captureVideo() {
        PermissionHelper.runWithPermissions(activity, PERMS_VIDEO,
                () -> {
                    pendingVideoUri = createVideoOutputUri();
                    activity.launchProfileCaptureVideo(this, pendingVideoUri, (int) Math.max(1, (MAX_VIDEO_DURATION_MS / 1000)), MAX_VIDEO_FILE_SIZE_BYTES);
                },
                () -> Snackbar.make(contentView, R.string.snackbar_video_permissions_required, Snackbar.LENGTH_LONG).show());
    }

    // ===== Video helpers =====
    private Bitmap extractVideoThumbnail(@NonNull Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(getContext(), uri);
            Bitmap bmp = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST);
            return (bmp != null) ? ThumbnailUtils.extractThumbnail(bmp, 320, 320) : null;
        } catch (Throwable t) { t.printStackTrace(); return null; }
        finally { try { retriever.release(); } catch (Throwable ignore) {} }
    }

    private boolean validateSelectedVideo(@NonNull Uri uri) {
        long durationMs = getVideoDuration(uri);
        if (durationMs > MAX_VIDEO_DURATION_MS) { Snackbar.make(contentView, R.string.snackbar_video_too_long, Snackbar.LENGTH_LONG).show(); return false; }
        long fileSize = getVideoFileSize(uri);
        if (fileSize > MAX_VIDEO_FILE_SIZE_BYTES) { Snackbar.make(contentView, R.string.snackbar_video_too_large, Snackbar.LENGTH_LONG).show(); return false; }
        return true;
    }

    private long getVideoDuration(@NonNull Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try { retriever.setDataSource(getContext(), uri); String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION); return d == null ? 0L : Long.parseLong(d); }
        catch (Exception ex) { ex.printStackTrace(); return 0L; }
        finally { try { retriever.release(); } catch (Exception ignore) {} }
    }

    private long getVideoFileSize(@NonNull Uri uri) {
        try (android.database.Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(android.provider.OpenableColumns.SIZE); if (i >= 0) return c.getLong(i); }
        } catch (Exception ignore) {}
        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            if (in == null) return 0L; long total = 0; byte[] buf = new byte[8192]; int r; while ((r = in.read(buf)) != -1) { total += r; if (total > MAX_VIDEO_FILE_SIZE_BYTES) break; } return total;
        } catch (Exception ex) { ex.printStackTrace(); }
        return 0L;
    }

    private void showAndSavePhoto(final Bitmap bmp320) {
        View view = activity.getLayoutInflater().inflate(R.layout.profile_photo_dialog, null);
        ((ImageView) view.findViewById(R.id.imageView)).setImageBitmap(bmp320);
        AlertDialog dialogSetPhoto = DialogHelper.themedBuilder(activity)
                .setTitle(R.string.dialog_set_photo_title)
                .setView(view)
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .setPositiveButton(R.string.dialog_button_ok, (dialog, which) -> {
                    String v = Utils.encodeImage(bmp320);
                    ItemDatabase.getInstance(getContext()).put(new Item.Builder("photo", "", "").put("photo", v).build());
                    String vthumb = Utils.encodeImage(ThumbnailUtils.extractThumbnail(bmp320, 84, 84));
                    ItemDatabase.getInstance(getContext()).put(new Item.Builder("thumb", "", "").put("thumb", vthumb).build());
                    profilePhotoBitmap = bmp320; updateProfileAvatar(); refreshDeleteButtonVisibility();
                    Snackbar.make(contentView, R.string.snackbar_photo_changed, Snackbar.LENGTH_SHORT).show();
                    activity.load(); FriendTool.getInstance(context).requestUpdates();
                })
                .create();
        DialogHelper.show(dialogSetPhoto);
    }

    private void handlePickedVideo(@NonNull Uri uri) {
        if (!validateSelectedVideo(uri)) { deleteTempVideo(uri); pendingVideoUri = null; return; }
        File persistedFile;
        try { persistedFile = persistVideoToInternal(uri); }
        catch (IOException ex) { ex.printStackTrace(); deleteTempVideo(uri); pendingVideoUri = null; Snackbar.make(contentView, R.string.snackbar_failed_save_video, Snackbar.LENGTH_LONG).show(); return; }
        Uri persistedUri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", persistedFile);
        String videoBase64;
        try { videoBase64 = encodeFileToBase64(persistedFile); }
        catch (IOException ex) { ex.printStackTrace(); deleteTempVideo(uri); pendingVideoUri = null; Snackbar.make(contentView, R.string.snackbar_failed_prepare_video, Snackbar.LENGTH_LONG).show(); return; }
        deleteTempVideo(uri);
        Bitmap thumb = extractVideoThumbnail(persistedUri);
        View view = activity.getLayoutInflater().inflate(R.layout.profile_photo_dialog, null);
        ImageView preview = view.findViewById(R.id.imageView);
        if (thumb != null) preview.setImageBitmap(thumb); else preview.setImageResource(R.drawable.nophoto);
        AlertDialog dialogSetVideo = DialogHelper.themedBuilder(activity)
                .setTitle(R.string.dialog_set_video_title)
                .setView(view)
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .setPositiveButton(R.string.dialog_button_ok, (dialog, which) -> {
                    ItemDatabase.getInstance(getContext())
                            .put(new Item.Builder("video", "", "")
                                    .put("video_uri", persistedUri.toString())
                                    .put("video", videoBase64)
                                    .build());
                    if (thumb != null) {
                        String vthumb = Utils.encodeImage(ThumbnailUtils.extractThumbnail(thumb, 84, 84));
                        ItemDatabase.getInstance(getContext()).put(new Item.Builder("video_thumb", "", "").put("video_thumb", vthumb).build());
                    }
                    videoUriStr = persistedUri.toString(); profileVideoThumbBitmap = thumb; updateProfileAvatar(); refreshDeleteButtonVisibility();
                    if (address.isEmpty()) configureDeleteButtonForVideo();
                    Snackbar.make(contentView, R.string.snackbar_video_changed, Snackbar.LENGTH_SHORT).show();
                    activity.load(); FriendTool.getInstance(context).requestUpdates(); pendingVideoUri = null;
                })
                .create();
        dialogSetVideo.setOnDismissListener(d -> { if (pendingVideoUri != null) { deleteTempVideo(pendingVideoUri); pendingVideoUri = null; } });
        DialogHelper.show(dialogSetVideo);
    }

    private void deleteTempVideo(@Nullable Uri uri) {
        if (uri == null) return; try { getContext().getContentResolver().delete(uri, null, null); } catch (Exception ignore) {}
    }

    private File persistVideoToInternal(@NonNull Uri source) throws IOException {
        File dir = new File(getContext().getFilesDir(), "avatar_video");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Unable to create avatar directory");
        File dest = new File(dir, "avatar.mp4");
        try (InputStream in = getContext().getContentResolver().openInputStream(source); FileOutputStream out = new FileOutputStream(dest, false)) {
            if (in == null) throw new IOException("Empty video data");
            byte[] buffer = new byte[8192]; int read; long total = 0;
            while ((read = in.read(buffer)) != -1) { out.write(buffer, 0, read); total += read; if (total > MAX_VIDEO_FILE_SIZE_BYTES) throw new IOException("Video exceeds size limit"); }
            out.flush();
        }
        return dest;
    }

    private String encodeFileToBase64(@NonNull File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) { byte[] data = Utils.readInputStream(in); return Ed25519Signature.base64Encode(data); }
    }

    private static @Nullable String getMimeType(Context ctx, Uri uri) {
        if (uri == null) return null; String mime = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) mime = ctx.getContentResolver().getType(uri);
        else {
            String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            if (ext != null) mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
        }
        return mime;
    }

    private void save(JSONObject o, String key, @Nullable String val) {
        if (val != null) val = val.trim();
        try { o.put(key, val); } catch (JSONException ex) { throw new RuntimeException(ex); }
        ItemDatabase.getInstance(getContext()).put(new Item("info", "", "", o));
        if ("name".equals(key)) {
            ItemDatabase.getInstance(getContext()).put(new Item.Builder("name", "", "").put("name", val).build());
        }
        activity.load();
        FriendTool.getInstance(context).requestUpdates();
    }

    private void clear(JSONObject o, String key) {
        save(o, key, "name".equals(key) ? "" : null);
    }
}
