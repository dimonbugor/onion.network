package onion.network.ui.pages;

import static onion.network.helpers.Const.REQUEST_CHOOSE_MEDIA;
import static onion.network.helpers.Const.REQUEST_TAKE_PHOTO_PROFILE;
import static onion.network.helpers.Const.REQUEST_TAKE_VIDEO;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
// media3
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// для thumbnail
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;
import org.json.JSONObject;

import onion.network.helpers.ThemeManager;
import onion.network.helpers.PermissionHelper;
import onion.network.helpers.VideoCacheManager;
import onion.network.helpers.Ed25519Signature;
import onion.network.models.FriendTool;
import onion.network.models.Item;
import onion.network.models.ItemTask;
import onion.network.R;
import onion.network.databases.ItemDatabase;
import onion.network.helpers.Utils;
import onion.network.models.ItemResult;
import onion.network.ui.MainActivity;
import onion.network.ui.views.AvatarView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.EnumSet;
import androidx.core.content.FileProvider;

public class ProfilePage extends BasePage {

    private static final long MAX_VIDEO_DURATION_MS = 5_500; // 5.5 seconds to allow camera rounding
    private static final long MAX_VIDEO_FILE_SIZE_BYTES = 2L * 1024 * 1024; // ~2MB cap for avatar video

    private AvatarView profileAvatarView;
    private Bitmap profilePhotoBitmap;
    private Bitmap profileVideoThumbBitmap;

    // збережені URI/дані
    private String videoUriStr = null;
    private static final EnumSet<PermissionHelper.PermissionRequest> PERMS_MEDIA_ONLY = EnumSet.of(PermissionHelper.PermissionRequest.MEDIA);
    private static final EnumSet<PermissionHelper.PermissionRequest> PERMS_CAMERA_ONLY = EnumSet.of(PermissionHelper.PermissionRequest.CAMERA);
    private static final EnumSet<PermissionHelper.PermissionRequest> PERMS_VIDEO = EnumSet.of(
            PermissionHelper.PermissionRequest.MEDIA,
            PermissionHelper.PermissionRequest.CAMERA,
            PermissionHelper.PermissionRequest.MICROPHONE
    );

    private void updateProfileAvatar() {
        if (profileAvatarView == null) return;

        String videoUri = !TextUtils.isEmpty(videoUriStr) ? videoUriStr : null;
        Bitmap fallback = profileVideoThumbBitmap != null ? profileVideoThumbBitmap : profilePhotoBitmap;

        profileAvatarView.bind(profilePhotoBitmap, fallback, videoUri);

        if (videoUri != null) {
            profileAvatarView.setOnClickListener(v -> activity.lightboxVideo(Uri.parse(videoUri)));
        } else if (profilePhotoBitmap != null) {
            profileAvatarView.setOnClickListener(v -> activity.lightbox(profilePhotoBitmap));
        } else {
            profileAvatarView.setOnClickListener(null);
        }
    }

    private void configureDeleteButtonForPhoto() {
        View deleteBtn = findViewById(R.id.delete_photo);
        if (deleteBtn == null) return;
        deleteBtn.setOnClickListener(v -> {
            AlertDialog dialogDeletePhoto = new AlertDialog.Builder(activity, ThemeManager.getDialogThemeResId(activity))
                    .setTitle("Delete Photo")
                    .setMessage("Do you really want to delete this photo?")
                    .setNegativeButton("No", null)
                    .setPositiveButton("Yes", (dialog, which) -> {
                        ItemDatabase.getInstance(getContext()).put(new Item.Builder("photo", "", "").build());
                        ItemDatabase.getInstance(getContext()).put(new Item.Builder("thumb", "", "").build());
                        activity.load();
                        FriendTool.getInstance(context).requestUpdates();
                    })
                    .create();
            dialogDeletePhoto.setOnShowListener(d -> {
                dialogDeletePhoto.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
                dialogDeletePhoto.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
            });
            dialogDeletePhoto.show();
        });
    }

    private void configureDeleteButtonForVideo() {
        View deleteBtn = findViewById(R.id.delete_photo);
        if (deleteBtn == null) return;
        deleteBtn.setOnClickListener(vbtn -> {
            AlertDialog dlg = new AlertDialog.Builder(activity, ThemeManager.getDialogThemeResId(activity))
                    .setTitle("Delete Video")
                    .setMessage("Do you really want to delete this video?")
                    .setNegativeButton("No", null)
                    .setPositiveButton("Yes", (d, which) -> {
                        ItemDatabase.getInstance(getContext()).put(new Item.Builder("video", "", "").build());
                        ItemDatabase.getInstance(getContext()).put(new Item.Builder("video_thumb", "", "").build());
                        videoUriStr = null;
                        profileVideoThumbBitmap = null;
                        updateProfileAvatar();
                        activity.load();
                        FriendTool.getInstance(context).requestUpdates();
                    })
                    .create();
            dlg.setOnShowListener(d2 -> {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
            });
            dlg.show();
        });
    }

    private void refreshDeleteButtonVisibility() {
        View deleteBtn = findViewById(R.id.delete_photo);
        if (deleteBtn == null) return;
        if (!address.isEmpty()) {
            deleteBtn.setVisibility(View.GONE);
            return;
        }
        boolean hasVideo = !TextUtils.isEmpty(videoUriStr);
        boolean hasPhoto = profilePhotoBitmap != null;
        deleteBtn.setVisibility((hasVideo || hasPhoto) ? View.VISIBLE : View.GONE);
    }
    public static Row[] rows = {
            new Row("name", "Name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
            //new Row("location", "Location", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            new Row("lang", "Languages", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            //new Row("occupation", "Occupation", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            new Row("interests", "Interests", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            new Row("hobbies", "Hobbies", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            new Row("website", "Website", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            new Row("about", "About me", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            new Row("bio", "Bio", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE),
    };
    LinearLayout contentView;

    public ProfilePage(final MainActivity activity) {
        super(activity);
        inflate(activity, R.layout.profile_page, this);
        contentView = (LinearLayout) findViewById(R.id.contentView);

        profileAvatarView = (AvatarView) findViewById(R.id.profilephoto);
        if (profileAvatarView != null) {
            profileAvatarView.setPlaceholderResource(R.drawable.nophoto);
        }

        findViewById(R.id.choose_photo).setOnClickListener(v -> showPickMediaDialog());
        findViewById(R.id.choose_photo).setVisibility(address.isEmpty() ? View.VISIBLE : View.GONE);

        configureDeleteButtonForPhoto();
        refreshDeleteButtonVisibility();

        findViewById(R.id.take_photo).setOnClickListener(v -> showCaptureMediaDialog());
        findViewById(R.id.take_photo).setVisibility(address.isEmpty() ? View.VISIBLE : View.GONE);

        //load();
    }

    @Override
    public String getTitle() {
        return "Profile";
    }

    @Override
    public int getIcon() {
        //return R.drawable.ic_account_circle_white_36dp;
        return R.drawable.ic_assignment_ind;
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) return;
        if (data == null && requestCode != REQUEST_TAKE_PHOTO_PROFILE) return;

        // ======= 1) PICK MEDIA (галерея) =======
        if (requestCode == REQUEST_CHOOSE_MEDIA) {
            Uri uri = data.getData();
            if (uri == null) return;

            // persist (щоб мати довгостроковий доступ)
            final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);
            } catch (Exception ignore) {
            }

            String mime = getMimeType(getContext(), uri);
            if (mime != null && mime.startsWith("image/")) {
                // === Фото з галереї
                Bitmap bmp = getActivityResultBitmap(data);
                bmp = fixImageOrientation(bmp, uri);
                if (bmp == null) return;
                bmp = ThumbnailUtils.extractThumbnail(bmp, 320, 320);
                showAndSavePhoto(bmp);
                return;
            } else if (mime != null && mime.startsWith("video/")) {
                // === Відео з галереї (URI зберігаємо як String, плюс thumbnail)
                handlePickedVideo(uri);
                return;
            } else {
                Snackbar.make(contentView, "Unsupported media type", Snackbar.LENGTH_SHORT).show();
                return;
            }
        }

        // ======= 2) TAKE PHOTO (камера фото) =======
        if (requestCode == REQUEST_TAKE_PHOTO_PROFILE) {
            Bitmap bmp = (Bitmap) data.getExtras().get("data"); // мініатюра
            if (bmp == null) return;
            bmp = ThumbnailUtils.extractThumbnail(bmp, 320, 320);
            showAndSavePhoto(bmp);
            return;
        }

        // ======= 3) TAKE VIDEO (камера відео) =======
        if (requestCode == REQUEST_TAKE_VIDEO) {
            Uri uri = (data != null && data.getData() != null) ? data.getData() : pendingVideoUri;
            if (uri == null) {
                Snackbar.make(contentView, "No video URI", Snackbar.LENGTH_LONG).show();
                return;
            }
            handlePickedVideo(uri);
            pendingVideoUri = null;
            return;
        }

    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateProfileAvatar();
        if (profileAvatarView != null) {
            profileAvatarView.resume();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (profileAvatarView != null) {
            profileAvatarView.release();
        }
    }

    public void onPageResume() {
        if (profileAvatarView != null) profileAvatarView.resume();
    }
    public void onPagePause() {
        if (profileAvatarView != null) profileAvatarView.pause();
    }

    void save(JSONObject o, String key, String val) {
        if (val != null) {
            val = val.trim();
        }
        try {
            o.put(key, val);
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
        ItemDatabase.getInstance(getContext()).put(new Item("info", "", "", o));

        if (key.equals("name")) {
            ItemDatabase.getInstance(getContext()).put(new Item.Builder("name", "", "").put("name", val).build());
        }

        activity.load();

        FriendTool.getInstance(context).requestUpdates();
    }

    void clear(JSONObject o, String key) {
        save(o, key, "name".equals(key) ? "" : null);
    }

    public void load() {

        refreshDeleteButtonVisibility();
        new ItemTask(getContext(), address, "photo") {
            @Override
            protected void onProgressUpdate(ItemResult... values) {
                try {
                    ItemResult itemResult = values[0];
                    String str = itemResult.one().json().optString("photo");
                    str = str.trim();
                    if (!str.isEmpty()) {
                        byte[] photodata = Base64.decode(str, Base64.DEFAULT);
                        if (photodata.length == 0) throw new Exception();
                        profilePhotoBitmap = BitmapFactory.decodeByteArray(photodata, 0, photodata.length);
                    } else {
                        profilePhotoBitmap = null;
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    profilePhotoBitmap = null;
                }

                updateProfileAvatar();
                refreshDeleteButtonVisibility();
            }
        }.execute2();

        new ItemTask(getContext(), address, "info") {

            @Override
            protected void onProgressUpdate(ItemResult... values) {


                ItemResult itemResult = values[0];


                final JSONObject o = itemResult.one().json(activity, activity.address);


                Log.i(TAG, "onProgressUpdate: " + o);


                findViewById(R.id.is_friend_bot).setVisibility(o.optBoolean("friendbot", false) ? View.VISIBLE : View.GONE);


                contentView.removeAllViews();


//                findViewById(R.id.offline).setVisibility(!itemResult.ok() && !itemResult.loading() ? View.VISIBLE : View.GONE);
                findViewById(R.id.loading).setVisibility(itemResult.loading() ? View.VISIBLE : View.GONE);


                if (address.isEmpty()) {

                    View v = activity.getLayoutInflater().inflate(R.layout.profile_item, contentView, false);
                    //v.findViewById(R.id.edit).setVisibility(View.GONE);
                    ((ImageView) v.findViewById(R.id.edit)).setImageResource(R.drawable.ic_link_black);
                    TextView keyview = ((TextView) v.findViewById(R.id.key));
                    TextView valview = ((TextView) v.findViewById(R.id.val));
                    keyview.setText("ID");
                    valview.setText(activity.getID());
                    v.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            activity.showId();
                        }
                    });
                    contentView.addView(v);

                } else {

                    View v = activity.getLayoutInflater().inflate(R.layout.profile_item, contentView, false);
                    v.findViewById(R.id.edit).setVisibility(View.GONE);
                    TextView keyview = ((TextView) v.findViewById(R.id.key));
                    TextView valview = ((TextView) v.findViewById(R.id.val));
                    keyview.setText("ID");
                    valview.setText(activity.getID());
                    v.setClickable(false);
                    contentView.addView(v);

                }


                for (final Row row : rows) {

                    View v = activity.getLayoutInflater().inflate(R.layout.profile_item, contentView, false);

                    v.findViewById(R.id.edit).setVisibility(address.isEmpty() ? View.VISIBLE : View.GONE);

                    TextView keyview = ((TextView) v.findViewById(R.id.key));
                    TextView valview = ((TextView) v.findViewById(R.id.val));

                    final String label = row.label;
                    keyview.setText(label);

                    final String key = row.key;

                    final String val = o.optString(key);
                    if (val == null || val.isEmpty()) {

                        if (!address.isEmpty() && !"name".equals(key)) {
                            continue;
                        }

                        valview.setText("Unknown");
                        //valview.setTextColor(0xffbbbbbb);
                        valview.setTextColor(ThemeManager.getColor(activity, R.attr.white_50));
                    } else {
                        valview.setText(val.trim());
                        //valview.setTextColor(0xff000000);
                        valview.setTextColor(getResources().getColor(android.R.color.white));
                    }

                    if ((row.type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
                        valview.setSingleLine(false);
                    }

                    if (address.isEmpty()) {

                        v.setClickable(true);

                        v.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {

                                if (key.equals("birth")) {

                                    int y = 0, m = 0, d = 0;
                                    try {
                                        String[] ss = val.split("-");
                                        y = Integer.parseInt(ss[0]);
                                        m = Integer.parseInt(ss[1]);
                                        d = Integer.parseInt(ss[2]);
                                    } catch (Exception ex) {
                                    }

                                    Dialog dialog = new DatePickerDialog(getContext(), new DatePickerDialog.OnDateSetListener() {
                                        @Override
                                        public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {
                                            save(o, key, year + "-" + monthOfYear + "-" + dayOfMonth);
                                        }
                                    }, y, m, d);

                                    dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                                        @Override
                                        public void onCancel(DialogInterface dialog) {
                                            clear(o, key);
                                        }
                                    });

                                    dialog.show();

                                    return;

                                }

                                View dialogView = activity.getLayoutInflater().inflate(R.layout.profile_dialog, null, false);
                                final EditText textEdit = (EditText) dialogView.findViewById(R.id.text);
                                textEdit.setText(val);
                                textEdit.setInputType(row.type);
                                AlertDialog dialog = new AlertDialog.Builder(activity, ThemeManager.getDialogThemeResId(activity))
                                        .setView(dialogView)
                                        .setTitle("Change " + label)
                                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                            }
                                        })
                                        .setPositiveButton("Publish", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                save(o, key, textEdit.getText().toString());
                                            }
                                        })
                                        .setNeutralButton("Clear", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                clear(o, key);
                                                /*try {
                                                    o.put(key, null);
                                                } catch (JSONException ex) {
                                                    throw new RuntimeException(ex);
                                                }
                                                ItemDatabase.getInstance(getContext()).put(new Item("info", "", "", o));*/
                                                activity.load();
                                            }
                                        })
                                        .create();
                                dialog.setOnShowListener(d -> {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
                                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
                                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
                                });
                                dialog.show();

                            }
                        });

                    }


                    contentView.addView(v);

                }

            }

        }.execute2();

        // 1) Підтягнути відео
        new ItemTask(getContext(), address, "video") {
            @Override
            protected void onProgressUpdate(ItemResult... values) {
                try {
                    ItemResult itemResult = values[0];
                    JSONObject json = itemResult.one().json();
                    Uri playableUri = VideoCacheManager.ensureVideoUri(
                            getContext(),
                            address.isEmpty() ? activity.getID() : address,
                            json.optString("video_uri", "").trim(),
                            json.optString("video", "").trim());
                    videoUriStr = playableUri != null ? playableUri.toString() : null;
                } catch (Exception e) {
                    e.printStackTrace();
                    videoUriStr = null;
                }

                updateProfileAvatar();

                if (address.isEmpty()) {
                    if (!TextUtils.isEmpty(videoUriStr)) {
                        configureDeleteButtonForVideo();
                    } else {
                        configureDeleteButtonForPhoto();
                    }
                }

                refreshDeleteButtonVisibility();
            }
        }.execute2();

        new ItemTask(getContext(), address, "video_thumb") {
            @Override
            protected void onProgressUpdate(ItemResult... values) {
                try {
                    ItemResult itemResult = values[0];
                    String str = itemResult.one().json().optString("video_thumb", "").trim();
                    Bitmap thumb = null;
                    if (!str.isEmpty()) {
                        byte[] data = Base64.decode(str, Base64.DEFAULT);
                        if (data.length > 0) {
                            thumb = BitmapFactory.decodeByteArray(data, 0, data.length);
                        }
                    }
                    profileVideoThumbBitmap = thumb;
                } catch (Exception e) {
                    e.printStackTrace();
                    profileVideoThumbBitmap = null;
                }

                updateProfileAvatar();
            }
        }.execute2();
    }

    public static class Row {
        public String key;
        public String label;
        int type;

        public Row(String key, String label, int type) {
            this.key = key;
            this.label = label;
            this.type = type;
        }
    }
    // --- thumbnail з відео (перша секунда, або 0)
    private Bitmap extractVideoThumbnail(@NonNull Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(getContext(), uri);
            // frame at 1s (1000000 мкс) — інколи 0 дає чорний кадр
            Bitmap bmp = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST);
            return (bmp != null) ? ThumbnailUtils.extractThumbnail(bmp, 320, 320) : null;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignore) {
            }
        }
    }

    private boolean validateSelectedVideo(@NonNull Uri uri) {
        long durationMs = getVideoDuration(uri);
        if (durationMs > MAX_VIDEO_DURATION_MS) {
            Snackbar.make(contentView, "Video must be 5 seconds or shorter", Snackbar.LENGTH_LONG).show();
            return false;
        }

        long fileSize = getVideoFileSize(uri);
        if (fileSize > MAX_VIDEO_FILE_SIZE_BYTES) {
            Snackbar.make(contentView, "Video file is too large for an avatar", Snackbar.LENGTH_LONG).show();
            return false;
        }

        return true;
    }

    private long getVideoDuration(@NonNull Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(getContext(), uri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr == null) return 0L;
            return Long.parseLong(durationStr);
        } catch (Exception ex) {
            ex.printStackTrace();
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignore) {
            }
        }
    }

    private long getVideoFileSize(@NonNull Uri uri) {
        try (android.database.Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (sizeIndex >= 0) {
                    return cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        try (java.io.InputStream inputStream = getContext().getContentResolver().openInputStream(uri)) {
            if (inputStream == null) return 0L;
            long total = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_VIDEO_FILE_SIZE_BYTES) {
                    break;
                }
            }
            return total;
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return 0L;
    }

    private void showPickMediaDialog() {
        ArrayAdapter<String> adapter = getStringArrayAdapter(new String[]{"Photo from gallery", "Video from gallery"});
        AlertDialog dialog = new AlertDialog.Builder(activity, ThemeManager.getDialogThemeResId(activity))
                .setTitle("Choose media")
                .setAdapter(adapter, (dialog1, which) -> {
                    if (which == 0) pickMedia(true);
                    else pickMedia(false);
                })
                .create();
        dialog.show();
    }

    private void showCaptureMediaDialog() {
        ArrayAdapter<String> adapter = getStringArrayAdapter(new String[]{"Take Photo", "Record Video (5s)"});
        AlertDialog dialog = new AlertDialog.Builder(activity, ThemeManager.getDialogThemeResId(activity))
                .setTitle("Capture")
                .setAdapter(adapter, (dialog1, which) -> {
                    if (which == 0) capturePhoto();
                    else captureVideo();
                })
                .create();
        dialog.show();
    }

    private ArrayAdapter<String> getStringArrayAdapter(final String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), R.layout.dialog_list_item, items) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                textView.setTextColor(ThemeManager.getColor(getContext(), android.R.attr.textColorPrimary));
                return view;
            }
        };
        return adapter;
    }

    private void pickMedia(boolean photo) {
        PermissionHelper.runWithPermissions(activity,
                PERMS_MEDIA_ONLY,
                () -> pickMediaInternal(photo),
                () -> Snackbar.make(contentView, "Storage permission required", Snackbar.LENGTH_LONG).show());
    }

    private void pickMediaInternal(boolean photo) {
        prepareForCapture();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(photo ? "image/*" : "video/*");
        activity.startActivityForResult(intent, REQUEST_CHOOSE_MEDIA);
    }

    private void capturePhoto() {
        PermissionHelper.runWithPermissions(activity,
                PERMS_CAMERA_ONLY,
                this::launchPhotoCapture,
                () -> Snackbar.make(contentView, "Camera permission required", Snackbar.LENGTH_LONG).show());
    }

    private void launchPhotoCapture() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        activity.startActivityForResult(takePictureIntent, REQUEST_TAKE_PHOTO_PROFILE);
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

    private void prepareForCapture() {
        if (profileAvatarView != null) {
            profileAvatarView.pause();
        }
    }

    private void captureVideo() {
        PermissionHelper.runWithPermissions(activity,
                PERMS_VIDEO,
                this::launchVideoCapture,
                () -> Snackbar.make(contentView, "Camera & Mic permissions are required for video", Snackbar.LENGTH_LONG).show());
    }

    private void launchVideoCapture() {
        prepareForCapture();

        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        int maxSeconds = (int) Math.max(1, (MAX_VIDEO_DURATION_MS / 1000));
        intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, maxSeconds);
        intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0);
        intent.putExtra(MediaStore.EXTRA_FINISH_ON_COMPLETION, true);
        intent.putExtra(MediaStore.EXTRA_SIZE_LIMIT, MAX_VIDEO_FILE_SIZE_BYTES);

        pendingVideoUri = createVideoOutputUri();
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingVideoUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        activity.startActivityForResult(intent, REQUEST_TAKE_VIDEO);
    }

    private void showAndSavePhoto(final Bitmap bmp320) {
        View view = activity.getLayoutInflater().inflate(R.layout.profile_photo_dialog, null);
        ((ImageView) view.findViewById(R.id.imageView)).setImageBitmap(bmp320);

        AlertDialog dialogSetPhoto = new AlertDialog.Builder(activity, ThemeManager.getDialogThemeResId(activity))
                .setTitle("Set Photo")
                .setView(view)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (dialog, which) -> {
                    String v = Utils.encodeImage(bmp320);
                    ItemDatabase.getInstance(getContext()).put(new Item.Builder("photo", "", "").put("photo", v).build());

                    String vthumb = Utils.encodeImage(ThumbnailUtils.extractThumbnail(bmp320, 84, 84));
                    ItemDatabase.getInstance(getContext()).put(new Item.Builder("thumb", "", "").put("thumb", vthumb).build());

                    profilePhotoBitmap = bmp320;
                    updateProfileAvatar();
                    refreshDeleteButtonVisibility();

                    Snackbar.make(contentView, "Photo changed", Snackbar.LENGTH_SHORT).show();
                    activity.load();
                    FriendTool.getInstance(context).requestUpdates();
                })
                .create();
        dialogSetPhoto.setOnShowListener(d ->
        {
            dialogSetPhoto.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
            dialogSetPhoto.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
        });
        dialogSetPhoto.show();
    }

    private void handlePickedVideo(final Uri uri) {
        if (!validateSelectedVideo(uri)) {
            deleteTempVideo(uri);
            pendingVideoUri = null;
            return;
        }

        File persistedFile;
        try {
            persistedFile = persistVideoToInternal(uri);
        } catch (IOException ex) {
            ex.printStackTrace();
            deleteTempVideo(uri);
            pendingVideoUri = null;
            Snackbar.make(contentView, "Failed to save video", Snackbar.LENGTH_LONG).show();
            return;
        }

        Uri persistedUri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", persistedFile);

        String videoBase64;
        try {
            videoBase64 = encodeFileToBase64(persistedFile);
        } catch (IOException ex) {
            ex.printStackTrace();
            deleteTempVideo(uri);
            pendingVideoUri = null;
            Snackbar.make(contentView, "Failed to prepare video", Snackbar.LENGTH_LONG).show();
            return;
        }

        deleteTempVideo(uri);

        // thumbnail (320x320)
        Bitmap thumb = extractVideoThumbnail(persistedUri);
        View view = activity.getLayoutInflater().inflate(R.layout.profile_photo_dialog, null);
        ImageView preview = view.findViewById(R.id.imageView);
        if (thumb != null) preview.setImageBitmap(thumb);
        else preview.setImageResource(R.drawable.nophoto);

        AlertDialog dialogSetVideo = new AlertDialog.Builder(activity, ThemeManager.getDialogThemeResId(activity))
                .setTitle("Set Video Avatar")
                .setView(view)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (dialog, which) -> {
                    // записуємо лише URI (string) + маленький thumb
                    ItemDatabase.getInstance(getContext())
                            .put(new Item.Builder("video", "", "")
                                    .put("video_uri", persistedUri.toString())
                                    .put("video", videoBase64)
                                    .build());

                    if (thumb != null) {
                        String vthumb = Utils.encodeImage(ThumbnailUtils.extractThumbnail(thumb, 84, 84));
                        ItemDatabase.getInstance(getContext())
                                .put(new Item.Builder("video_thumb", "", "").put("video_thumb", vthumb).build());
                    }

                    videoUriStr = persistedUri.toString();
                    profileVideoThumbBitmap = thumb;
                    updateProfileAvatar();
                    refreshDeleteButtonVisibility();
                    if (address.isEmpty()) {
                        configureDeleteButtonForVideo();
                    }

                    Snackbar.make(contentView, "Video avatar set", Snackbar.LENGTH_SHORT).show();
                    activity.load();
                    FriendTool.getInstance(context).requestUpdates();
                    pendingVideoUri = null;
                })
                .create();
        dialogSetVideo.setOnShowListener(d ->
        {
            dialogSetVideo.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
            dialogSetVideo.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ThemeManager.getColor(activity, android.R.attr.actionMenuTextColor));
        });
        dialogSetVideo.setOnDismissListener(d -> {
            if (pendingVideoUri != null) {
                deleteTempVideo(pendingVideoUri);
                pendingVideoUri = null;
            }
        });
        dialogSetVideo.show();
    }

    private void deleteTempVideo(@Nullable Uri uri) {
        if (uri == null) return;
        try {
            getContext().getContentResolver().delete(uri, null, null);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private File persistVideoToInternal(@NonNull Uri source) throws IOException {
        File dir = new File(getContext().getFilesDir(), "avatar_video");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create avatar directory");
        }

        File dest = new File(dir, "avatar.mp4");
        try (InputStream in = getContext().getContentResolver().openInputStream(source);
             FileOutputStream out = new FileOutputStream(dest, false)) {
            if (in == null) throw new IOException("Empty video data");
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
                if (total > MAX_VIDEO_FILE_SIZE_BYTES) {
                    throw new IOException("Video exceeds size limit");
                }
            }
            out.flush();
        }
        return dest;
    }

    private String encodeFileToBase64(@NonNull File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = Utils.readInputStream(in);
            return Ed25519Signature.base64Encode(data);
        }
    }

    private static @Nullable String getMimeType(Context ctx, Uri uri) {
        if (uri == null) return null;
        String mime = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            mime = ctx.getContentResolver().getType(uri);
        } else {
            String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            if (ext != null)
                mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
        }
        return mime;
    }

}
