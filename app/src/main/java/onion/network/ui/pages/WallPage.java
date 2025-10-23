

package onion.network.ui.pages;

import static onion.network.helpers.Const.REQUEST_PICK_IMAGE_POST;
import static onion.network.helpers.Const.REQUEST_TAKE_PHOTO_POST;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.Locale;

import com.google.android.material.card.MaterialCardView;

import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import onion.network.helpers.DialogHelper;
import onion.network.helpers.Const;
import onion.network.helpers.PermissionHelper;
import onion.network.helpers.UiCustomizationManager;
import onion.network.models.Item;
import onion.network.models.ItemTask;
import onion.network.R;
import onion.network.TorManager;
import onion.network.databases.ItemDatabase;
import onion.network.helpers.ThemeManager;
import onion.network.helpers.Utils;
import onion.network.helpers.VideoCacheManager;
import onion.network.helpers.ChatMediaStore;
import onion.network.helpers.Ed25519Signature;
import onion.network.models.ItemResult;
import onion.network.cashes.ItemCache;
import onion.network.ui.MainActivity;
import onion.network.ui.views.AvatarView;
import com.google.android.material.textfield.TextInputEditText;

public class WallPage extends BasePage {

    private static final int FRIEND_PREVIEW_LIMIT = 12;

    RecyclerView recyclerView;
    WallAdapter wallAdapter;
    RecyclerView friendPreviewRecyclerView;
    FriendPreviewAdapter friendPreviewAdapter;
    final List<Item> posts = new ArrayList<>();
    final List<FriendPreview> friendPreviews = new ArrayList<>();
    final Map<String, FriendPreview> friendPreviewByAddress = new HashMap<>();
    final List<String> friendPreviewOrder = new ArrayList<>();
    final List<WeakReference<AvatarView>> activeAvatars = new ArrayList<>();
    private int friendPreviewGeneration = 0;
    int count = 5;
    String TAG = "WallPage";

    String smore;
    int imore;
    String currentWallOwner = "";
    String currentMyAddress = "";
    private Uri pendingPhotoUri;
    private PostComposer activeComposer;
    private PostDraft pendingDraftAfterActivity;
    private Item pendingDraftItem;


    public WallPage(MainActivity activity) {
        super(activity);
        activity.getLayoutInflater().inflate(R.layout.wall_page, this, true);
        friendPreviewRecyclerView = findViewById(R.id.friendPreviewRecyclerView);
        if (friendPreviewRecyclerView != null) {
            friendPreviewRecyclerView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
            friendPreviewAdapter = new FriendPreviewAdapter();
            friendPreviewRecyclerView.setAdapter(friendPreviewAdapter);
        }
        recyclerView = findViewById(R.id.wallRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        wallAdapter = new WallAdapter();
        recyclerView.setAdapter(wallAdapter);
    }

    private void openPostComposer(@Nullable Item item, @Nullable PostDraft initialDraft) {
        PostDraft draft = initialDraft != null ? initialDraft.copy() : new PostDraft();
        View dialogView = activity.getLayoutInflater().inflate(R.layout.wall_dialog, null);
        TextInputEditText textEdit = dialogView.findViewById(R.id.text);
        if (!TextUtils.isEmpty(draft.text)) {
            textEdit.setText(draft.text);
            textEdit.setSelection(textEdit.getText().length());
        }
        AlertDialog.Builder builder = DialogHelper.themedBuilder(activity).setView(dialogView);
        builder.setTitle(item != null ? "Edit Post" : "Write Post");
        builder.setCancelable(true);
        Dialog dialog = builder.create();
        PostComposer composer = new PostComposer(item, draft, dialog, dialogView, textEdit);
        dialog.setOnDismissListener(di -> {
            if (activeComposer == composer) {
                activeComposer = null;
            }
            composer.release();
        });
        activeComposer = composer;
        composer.init();
        dialog.show();
    }

    void editPost(final Item item) {
        openPostComposer(item, PostDraft.fromItem(item));
    }

    public void writePost(final String text, final Bitmap bitmap) {
        PostDraft draft = new PostDraft();
        draft.text = text;
        draft.image = bitmap;
        openPostComposer(null, draft);
    }

    private void doPostPublish(@Nullable Item item, PostDraft draft) {
        if (draft == null) return;
        draft.text = draft.text == null ? "" : draft.text;
        try {
            if (item != null) {
                JSONObject o = item.json();
                o.put("text", draft.text);
                applyDraftToJson(o, draft);
                Item updated = new Item(item.type(), item.key(), item.index(), o);
                ItemDatabase.getInstance(getContext()).put(updated);
                activity.load();
            } else {
                JSONObject data = new JSONObject();
                data.put("text", draft.text);
                data.put("date", "" + System.currentTimeMillis());
                applyDraftToJson(data, draft);
                activity.publishPost(data);
                activity.load();
            }
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void applyDraftToJson(JSONObject o, PostDraft draft) throws JSONException {
        if (draft.image != null) {
            o.put("img", Utils.encodeImage(draft.image));
        } else {
            o.remove("img");
        }
        if (draft.videoData != null && draft.videoData.length > 0) {
            o.put("video", Ed25519Signature.base64Encode(draft.videoData));
            if (draft.videoThumb != null) {
                o.put("video_thumb", Utils.encodeImage(draft.videoThumb));
            } else {
                o.remove("video_thumb");
            }
            if (!TextUtils.isEmpty(draft.videoMime)) {
                o.put("video_mime", draft.videoMime);
            } else {
                o.remove("video_mime");
            }
            if (draft.videoDurationMs > 0) {
                o.put("video_duration", draft.videoDurationMs);
            } else {
                o.remove("video_duration");
            }
        } else {
            o.remove("video");
            o.remove("video_thumb");
            o.remove("video_mime");
            o.remove("video_duration");
        }
        if (draft.audioData != null && draft.audioData.length > 0) {
            o.put("audio", Ed25519Signature.base64Encode(draft.audioData));
            if (!TextUtils.isEmpty(draft.audioMime)) {
                o.put("audio_mime", draft.audioMime);
            } else {
                o.remove("audio_mime");
            }
            if (draft.audioDurationMs > 0) {
                o.put("audio_duration", draft.audioDurationMs);
            } else {
                o.remove("audio_duration");
            }
        } else {
            o.remove("audio");
            o.remove("audio_mime");
            o.remove("audio_duration");
        }
    }

    private Bitmap scaleBitmap(Bitmap bitmap, int maxDim) {
        if (bitmap == null) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= maxDim && height <= maxDim) {
            return bitmap;
        }
        if (width >= height) {
            int newWidth = maxDim;
            int newHeight = (int) ((double) height * maxDim / width);
            return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
        } else {
            int newHeight = maxDim;
            int newWidth = (int) ((double) width * maxDim / height);
            return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
        }
    }

    private long parseDuration(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_IMAGE_POST
                || requestCode == REQUEST_TAKE_PHOTO_POST
                || requestCode == Const.REQUEST_PICK_VIDEO_POST) {

            PostDraft draft = pendingDraftAfterActivity;
            Item draftItem = pendingDraftItem;
            pendingDraftAfterActivity = null;
            pendingDraftItem = null;

            if (draft == null) {
                deleteTempPhoto();
                return;
            }

            boolean success = (resultCode == Activity.RESULT_OK);
            if (success) {
                if (requestCode == REQUEST_PICK_IMAGE_POST || requestCode == REQUEST_TAKE_PHOTO_POST) {
                    Bitmap bmp = null;
                    Uri uri = data != null ? data.getData() : null;
                    if (requestCode == REQUEST_TAKE_PHOTO_POST) {
                        uri = pendingPhotoUri;
                        if (uri != null) {
                            try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
                                bmp = BitmapFactory.decodeStream(stream);
                            } catch (IOException ex) {
                                Log.e(TAG, "Failed to decode captured photo", ex);
                            }
                        } else if (data != null && data.getExtras() != null) {
                            Object extra = data.getExtras().get("data");
                            if (extra instanceof Bitmap) {
                                bmp = (Bitmap) extra;
                            }
                        }
                    } else if (data != null) {
                        bmp = getActivityResultBitmap(data);
                    }
                    if (bmp != null && uri != null) {
                        bmp = fixImageOrientation(bmp, uri);
                    }
                    if (bmp != null) {
                        bmp = scaleBitmap(bmp, 1080);
                        draft.image = bmp;
                        draft.clearVideo();
                        draft.clearAudio();
                    } else {
                        activity.snack(activity.getString(R.string.chat_attachment_pick_failed));
                    }
                } else if (requestCode == Const.REQUEST_PICK_VIDEO_POST && data != null) {
                    Uri uri = data.getData();
                    if (uri != null) {
                        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
                            byte[] bytes = Utils.readInputStream(stream);
                            if (ChatMediaStore.exceedsLimit(bytes.length)) {
                                activity.snack(activity.getString(R.string.chat_attachment_file_too_large));
                            } else {
                                String mime = activity.getContentResolver().getType(uri);
                                if (TextUtils.isEmpty(mime)) {
                                    String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
                                    if (!TextUtils.isEmpty(extension)) {
                                        mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                                    }
                                }
                                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                                Bitmap thumb = null;
                                long duration = 0L;
                                try {
                                    retriever.setDataSource(activity, uri);
                                    duration = parseDuration(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                                    thumb = retriever.getFrameAtTime(0);
                                } catch (Exception ex) {
                                    Log.w(TAG, "Unable to extract video metadata", ex);
                                } finally {
                                    try {
                                        retriever.release();
                                    } catch (Exception ignore) {
                                    }
                                }
                                if (thumb != null) {
                                    thumb = scaleBitmap(thumb, 1080);
                                }
                                draft.clearImage();
                                draft.clearAudio();
                                draft.videoData = bytes;
                                draft.videoMime = mime;
                                draft.videoThumb = thumb;
                                draft.videoDurationMs = duration;
                            }
                        } catch (IOException ex) {
                            Log.e(TAG, "Unable to read selected video", ex);
                            activity.snack(activity.getString(R.string.chat_attachment_pick_failed));
                        }
                    }
                }
            } else {
                activity.snack(activity.getString(R.string.chat_attachment_pick_failed));
            }

            deleteTempPhoto();
            openPostComposer(draftItem, draft);
            return;
        }

        if (resultCode != Activity.RESULT_OK) {
            deleteTempPhoto();
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public String getTitle() {
        return "Wall";
    }

    @Override
    public int getIcon() {
        return R.drawable.ic_format_list_bulleted;
    }

    @Override
    public void load() {
        loadFriendPostPreviews();
        load(0, "");
    }

    @Override
    public int getFab() {
        return R.id.wallFab;
    }

    @Override
    public void onFab() {
        openPostComposer(null, new PostDraft());
    }


    @Override
    public void onResume() {
        loadFriendPostPreviews();
    }

    private boolean isViewingOwnWall() {
        MainActivity a = activity;
        return a != null && TextUtils.isEmpty(a.address);
    }

    private void loadFriendPostPreviews() {
        if (friendPreviewRecyclerView == null || friendPreviewAdapter == null) {
            return;
        }

        if (!isViewingOwnWall()) {
            friendPreviewByAddress.clear();
            friendPreviews.clear();
            friendPreviewOrder.clear();
            friendPreviewAdapter.notifyDataSetChanged();
            friendPreviewRecyclerView.setVisibility(View.GONE);
            return;
        }

        friendPreviewGeneration++;
        final int generation = friendPreviewGeneration;

        new ItemTask(getContext(), "", "friend", "", FRIEND_PREVIEW_LIMIT) {

            @Override
            protected void onProgressUpdate(ItemResult... results) {
                ItemResult result = safeFirst(results);
                handleFriendPreviewResult(result, generation);
            }

            @Override
            protected void onPostExecute(ItemResult itemResult) {
                handleFriendPreviewResult(itemResult, generation);
            }

            private ItemResult safeFirst(ItemResult[] results) {
                return results != null && results.length > 0 ? results[0] : null;
            }
        }.execute2();
    }

    private void handleFriendPreviewResult(ItemResult itemResult, int generation) {
        if (generation != friendPreviewGeneration) {
            return;
        }
        if (itemResult == null) {
            updateFriendPreviewVisibility();
            return;
        }

        Map<String, FriendPreview> retained = new HashMap<>(friendPreviewByAddress);
        friendPreviewByAddress.clear();
        friendPreviewOrder.clear();

        for (int idx = 0; idx < itemResult.size(); idx++) {
            Item friendItem = itemResult.at(idx);
            if (friendItem == null) continue;
            JSONObject friendData = friendItem.json(getContext(), activity.address);
            String friendAddress = friendData.optString("addr").trim();
            if (TextUtils.isEmpty(friendAddress)) {
                continue;
            }

            FriendPreview preview = retained.remove(friendAddress);
            if (preview == null) {
                preview = new FriendPreview(friendAddress);
            }

            preview.friendItem = friendItem;
            preview.friendData = friendData;
            preview.displayName = resolveFriendDisplayName(friendData);

            friendPreviewByAddress.put(friendAddress, preview);
            friendPreviewOrder.add(friendAddress);
        }

        refreshDisplayedFriendPreviews();

        for (String friendAddress : friendPreviewOrder) {
            FriendPreview preview = friendPreviewByAddress.get(friendAddress);
            if (preview == null) continue;
            if (preview.lastRequestedGeneration == generation) continue;
            loadLatestPostForFriend(preview, generation);
        }
    }

    private void loadLatestPostForFriend(final FriendPreview preview, final int generation) {
        if (preview == null) return;
        preview.lastRequestedGeneration = generation;

        new ItemTask(getContext(), preview.friendAddress, "post", "", 1) {

            @Override
            protected void onProgressUpdate(ItemResult... results) {
                ItemResult result = safeFirst(results);
                handleFriendPostResult(preview, generation, result);
            }

            @Override
            protected void onPostExecute(ItemResult itemResult) {
                handleFriendPostResult(preview, generation, itemResult);
            }

            private ItemResult safeFirst(ItemResult[] results) {
                return results != null && results.length > 0 ? results[0] : null;
            }
        }.execute2();
    }

    private void handleFriendPostResult(FriendPreview preview, int generation, ItemResult itemResult) {
        if (preview == null) {
            return;
        }
        if (generation != friendPreviewGeneration) {
            return;
        }
        if (!friendPreviewByAddress.containsKey(preview.friendAddress)) {
            return;
        }

        if (itemResult == null) {
            refreshDisplayedFriendPreviews();
            return;
        }

        if (itemResult.size() > 0) {
            Item post = itemResult.at(0);
            preview.latestPost = post;
        } else if (!itemResult.loading() && itemResult.ok()) {
            preview.latestPost = null;
        }

        refreshDisplayedFriendPreviews();
    }

    private void refreshDisplayedFriendPreviews() {
        friendPreviews.clear();
        for (String friendAddress : friendPreviewOrder) {
            FriendPreview preview = friendPreviewByAddress.get(friendAddress);
            if (preview == null) continue;
            if (preview.latestPost != null) {
                friendPreviews.add(preview);
            }
        }
        if (friendPreviewAdapter != null) {
            friendPreviewAdapter.notifyDataSetChanged();
        }
        updateFriendPreviewVisibility();
    }

    private void updateFriendPreviewVisibility() {
        if (friendPreviewRecyclerView == null) return;
        if (!isViewingOwnWall()) {
            friendPreviewRecyclerView.setVisibility(View.GONE);
            return;
        }
        friendPreviewRecyclerView.setVisibility(friendPreviews.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String resolveFriendDisplayName(JSONObject friendData) {
        if (friendData == null) {
            return "Anonymous";
        }
        String displayName = friendData.optString("name", "").trim();
        if (displayName.isEmpty()) {
            displayName = "Anonymous";
        }
        return displayName;
    }




    void load(final int startIndex, String startKey) {

        Log.i(TAG, "load: ");

        new ItemTask(getContext(), address, "post", startKey, count) {

            @Override
            protected void onProgressUpdate(ItemResult... results) {
                renderPosts(startIndex, safeFirst(results), false);
            }

            @Override
            protected void onPostExecute(ItemResult itemResult) {
                renderPosts(startIndex, itemResult, true);
            }

            private ItemResult safeFirst(ItemResult[] results) {
                return results != null && results.length > 0 ? results[0] : null;
            }

        }.execute2();
    }

    void loadMore() {
        if (smore == null) return;
        String smore2 = smore;
        smore = null;
        if (wallAdapter != null) {
            wallAdapter.setLoadMoreVisible(false);
        }
        load(imore, smore2);
    }

    private void renderPosts(int insertIndex, ItemResult itemResult, boolean finished) {
        if (itemResult == null) {
            return;
        }

        currentMyAddress = TorManager.getInstance(context).getID();
        currentWallOwner = TextUtils.isEmpty(address) ? currentMyAddress : address;

        int insertionPoint = Math.min(insertIndex, posts.size());
        if (insertionPoint < posts.size()) {
            posts.subList(insertionPoint, posts.size()).clear();
        }

        for (int pos = 0; pos < itemResult.size(); pos++) {
            Item item = itemResult.at(pos);
            if (item == null) continue;
            posts.add(item);
        }

        findViewById(R.id.loading).setVisibility(itemResult.loading() ? View.VISIBLE : View.GONE);

        boolean showEmpty = insertionPoint == 0 && posts.isEmpty() && !itemResult.loading();
        boolean showLoadMore = updatePaginationControls(insertIndex, itemResult, finished);

        if (wallAdapter != null) {
            wallAdapter.submit(posts, showLoadMore, showEmpty);
        }
    }

    private void bindFriendPreview(FriendPreviewAdapter.FriendPreviewHolder holder, FriendPreview preview) {
        if (holder == null || preview == null) {
            return;
        }

        applyFriendPreviewStyle(holder);
        bindFriendPreviewAvatar(holder, preview);

        if (holder.name != null) {
            holder.name.setText(preview.displayName);
        }

        View.OnClickListener openWallListener = v -> {
            if (TextUtils.isEmpty(preview.friendAddress)) {
                return;
            }
            Context ctx = getContext();
            if (ctx == null) return;
            ctx.startActivity(new Intent(ctx, MainActivity.class).putExtra("address", preview.friendAddress));
        };

        if (holder.card != null) {
            holder.card.setOnClickListener(openWallListener);
        } else {
            holder.itemView.setOnClickListener(openWallListener);
        }

        if (preview.latestPost == null) {
            return;
        }

        holder.itemView.setVisibility(View.VISIBLE);
        JSONObject postData = preview.latestPost.json(getContext(), preview.friendAddress);
        String dateValue = Utils.formatDate(postData.optString("date"));
        if (holder.date != null) {
            if (!TextUtils.isEmpty(dateValue)) {
                holder.date.setText(dateValue);
                holder.date.setVisibility(View.VISIBLE);
            } else {
                holder.date.setVisibility(View.GONE);
            }
        }

        String text = postData.optString("text", "").trim();
        if (holder.postText != null) {
            if (!TextUtils.isEmpty(text)) {
                holder.postText.setText(text);
                holder.postText.setVisibility(View.VISIBLE);
                holder.postText.setAlpha(1f);
            } else {
                holder.postText.setText("");
                holder.postText.setVisibility(View.GONE);
            }
        }

        Bitmap postImage = preview.latestPost.bitmap("img");
        if (holder.postImage != null) {
            if (postImage != null) {
                holder.postImage.setImageBitmap(postImage);
                holder.postImage.setVisibility(View.VISIBLE);
                if (holder.imageContainer != null) {
                    holder.imageContainer.setVisibility(View.VISIBLE);
                }
            } else {
                holder.postImage.setImageDrawable(null);
                holder.postImage.setVisibility(View.GONE);
                if (holder.imageContainer != null) {
                    holder.imageContainer.setVisibility(View.GONE);
                }
            }
        }
    }

    private void applyFriendPreviewStyle(FriendPreviewAdapter.FriendPreviewHolder holder) {
        UiCustomizationManager.FriendCardConfig config = UiCustomizationManager.getFriendCardConfig(getContext());
        UiCustomizationManager.ColorPreset preset = UiCustomizationManager.getColorPreset(getContext());

        if (holder.card != null) {
            float radius = UiCustomizationManager.resolveCornerRadiusPx(getContext(), config.cornerRadiusPx);
            holder.card.setRadius(radius);
            holder.card.setStrokeWidth(UiCustomizationManager.dpToPx(getContext(), 1));
            holder.card.setStrokeColor(preset.getAccentColor(getContext()));
            int background = preset == UiCustomizationManager.ColorPreset.SYSTEM
                    ? ThemeManager.getColor(getContext(), com.google.android.material.R.attr.colorPrimaryContainer)
                    : preset.getSurfaceColor(getContext());
            holder.card.setCardBackgroundColor(background);
            holder.card.setContentPadding(config.horizontalPaddingPx, config.verticalPaddingPx,
                    config.horizontalPaddingPx, config.verticalPaddingPx);
        }

        if (holder.avatarCard != null) {
            holder.avatarCard.setStrokeWidth(UiCustomizationManager.dpToPx(getContext(), 1));
            holder.avatarCard.setStrokeColor(preset.getAccentColor(getContext()));
            ViewGroup.LayoutParams params = holder.avatarCard.getLayoutParams();
            if (params != null) {
                params.width = config.avatarSizePx;
                params.height = config.avatarSizePx;
                holder.avatarCard.setLayoutParams(params);
            }
        }

        if (holder.name != null) {
            holder.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.nameTextSizeSp);
        }

        if (holder.date != null) {
            holder.date.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.addressTextSizeSp);
        }

        if (holder.postText != null) {
            holder.postText.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.addressTextSizeSp);
        }
    }

    private void bindFriendPreviewAvatar(FriendPreviewAdapter.FriendPreviewHolder holder, FriendPreview preview) {
        if (holder.avatar == null) {
            return;
        }
        Item friendItem = preview.friendItem;
        JSONObject friendData = preview.friendData;
        Bitmap photoThumb = friendItem != null ? friendItem.bitmap("thumb") : null;
        Bitmap videoThumb = friendItem != null ? friendItem.bitmap("video_thumb") : null;
        String storedVideoUri = friendData != null ? friendData.optString("video_uri", "").trim() : "";
        String videoData = friendData != null ? friendData.optString("video", "").trim() : "";
        Uri playableVideo = VideoCacheManager.ensureVideoUri(getContext(),
                preview.friendAddress,
                storedVideoUri,
                videoData);
        holder.avatar.bind(photoThumb, videoThumb, playableVideo != null ? playableVideo.toString() : null);
        registerAvatar(holder.avatar);
    }

    private void bindPostView(PostViewHolder holder, Item item, String wallOwner, String myAddress) {
        JSONObject rawData = parseJsonSafe(item.text());
        JSONObject data = item.json(getContext(), address);

        bindInlineImage(holder, data);

        String postAddress = firstNonEmpty(rawData.optString("addr"), data.optString("addr"));
        PostAssets assets = resolvePostAssets(item, rawData, data, wallOwner, myAddress, postAddress);
        String ownerKey = resolveOwnerKey(postAddress, wallOwner, myAddress, item);
        bindAvatar(holder, assets, ownerKey);

        bindPostTexts(holder, data, assets.displayName, postAddress, wallOwner);
        bindPostActions(holder, item, data, wallOwner, myAddress, postAddress);
        applyPostAppearance(holder);
    }

    private void applyPostAppearance(PostViewHolder holder) {
        UiCustomizationManager.PostCardConfig config = UiCustomizationManager.getPostCardConfig(getContext());
        UiCustomizationManager.ColorPreset preset = UiCustomizationManager.getColorPreset(getContext());

        if (holder.card != null) {
            float radius = UiCustomizationManager.resolveCornerRadiusPx(getContext(), config.cardCornerRadiusPx);
            holder.card.setRadius(radius);
            int cardColor = preset == UiCustomizationManager.ColorPreset.SYSTEM
                    ? ThemeManager.getColor(getContext(), com.google.android.material.R.attr.colorSurface)
                    : preset.getSurfaceColor(getContext());
            holder.card.setCardBackgroundColor(cardColor);
            holder.card.setStrokeWidth(UiCustomizationManager.dpToPx(getContext(), 1));
            holder.card.setStrokeColor(preset.getAccentColor(getContext()));
        }

        if (holder.container != null) {
            holder.container.setPadding(
                    0, //config.containerPaddingHorizontalPx,
                    config.containerPaddingVerticalPx,
                    0, //config.containerPaddingHorizontalPx,
                    config.containerPaddingVerticalPx);
        }

        if (holder.link != null) {
            holder.link.setPadding(
                    config.linkPaddingHorizontalPx,
                    holder.link.getPaddingTop(),
                    config.linkPaddingHorizontalPx,
                    holder.link.getPaddingBottom());
        }

        if (holder.text != null) {
            holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.bodyTextSizeSp);
            holder.text.setPadding(0, 0, 0, 0);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.text.getLayoutParams();
            if (lp != null) {
                lp.topMargin = config.textTopMarginPx;
                lp.bottomMargin = config.textBottomMarginPx;
                lp.leftMargin = config.containerPaddingHorizontalPx;
                lp.rightMargin = config.containerPaddingHorizontalPx;
                holder.text.setLayoutParams(lp);
            }
        }

        if (holder.name != null) {
            holder.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.nameTextSizeSp);
        }
        if (holder.address != null) {
            holder.address.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.metadataTextSizeSp);
        }
        if (holder.date != null) {
            holder.date.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.metadataTextSizeSp);
        }

        int onSurface = preset == UiCustomizationManager.ColorPreset.SYSTEM
                ? ThemeManager.getColor(getContext(), com.google.android.material.R.attr.colorOnSurface)
                : preset.getOnSurfaceColor(getContext());
        int accent = preset == UiCustomizationManager.ColorPreset.SYSTEM
                ? ThemeManager.getColor(getContext(), com.google.android.material.R.attr.colorOnPrimary)
                : preset.getAccentColor(getContext());
        int secondary = preset == UiCustomizationManager.ColorPreset.SYSTEM
                ? ThemeManager.getColor(getContext(), R.attr.white_80)
                : ColorUtils.setAlphaComponent(onSurface, 180);

        holder.name.setTextColor(onSurface);
        holder.text.setTextColor(onSurface);
        holder.text.setLinkTextColor(accent);
        holder.address.setTextColor(secondary);
        holder.date.setTextColor(secondary);

        if (holder.imageContainer != null) {
            ViewGroup.MarginLayoutParams imageLp = (ViewGroup.MarginLayoutParams) holder.imageContainer.getLayoutParams();
            if (imageLp != null) {
                imageLp.topMargin = config.imageTopMarginPx;
                holder.imageContainer.setLayoutParams(imageLp);
            }
        }

        if (holder.avatarCard != null) {
            ViewGroup.LayoutParams avatarLp = holder.avatarCard.getLayoutParams();
            if (avatarLp != null) {
                avatarLp.width = config.avatarSizePx;
                avatarLp.height = config.avatarSizePx;
                holder.avatarCard.setLayoutParams(avatarLp);
            }
            holder.avatarCard.setRadius(config.avatarSizePx / 2f);
            holder.avatarCard.setStrokeWidth(UiCustomizationManager.dpToPx(getContext(), 1));
            holder.avatarCard.setStrokeColor(preset.getAccentColor(getContext()));
        }

        if (holder.actionRow != null) {
            holder.actionRow.setPadding(
                    config.containerPaddingHorizontalPx,
                    config.actionRowPaddingVerticalPx,
                    config.containerPaddingHorizontalPx,
                    config.actionRowPaddingVerticalPx);
        }

        ColorStateList iconTint = ColorStateList.valueOf(onSurface);
        styleActionIcon(holder.like, config.actionIconPaddingPx, iconTint);
        styleActionIcon(holder.comments, config.actionIconPaddingPx, iconTint);
        styleActionIcon(holder.share, config.actionIconPaddingPx, iconTint);
        styleActionIcon(holder.edit, config.actionIconPaddingPx, iconTint);
        styleActionIcon(holder.delete, config.actionIconPaddingPx, iconTint);

        if (holder.headerRow != null && holder.avatarContainer != null && holder.link != null) {
            LinearLayout header = holder.headerRow;
            View avatarContainer = holder.avatarContainer;
            FrameLayout linkContainer = holder.link;

            LinearLayout.LayoutParams avatarParams = (LinearLayout.LayoutParams) avatarContainer.getLayoutParams();
            LinearLayout.LayoutParams linkParams = (LinearLayout.LayoutParams) linkContainer.getLayoutParams();

            avatarParams.setMarginStart(0);
            avatarParams.setMarginEnd(config.avatarSpacingHorizontalPx);
            linkParams.setMarginStart(config.avatarSpacingHorizontalPx);
            linkParams.setMarginEnd(0);

//            header.addView(avatarContainer);
//            header.addView(linkContainer);

            avatarContainer.setLayoutParams(avatarParams);
            linkContainer.setLayoutParams(linkParams);
        }
    }

    private void styleActionIcon(ImageView view, int paddingPx, ColorStateList tint) {
        if (view == null) return;
        view.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
        view.setImageTintList(tint);
    }

    private void bindInlineImage(PostViewHolder holder, JSONObject data) {
        if (holder.imageContainer != null) {
            holder.imageContainer.setVisibility(View.GONE);
        }
        ImageView imageView = holder.image;
        if (imageView == null) {
            return;
        }
        imageView.setVisibility(View.GONE);
        try {
            String encoded = data.optString("img", "").trim();
            if (encoded.isEmpty()) {
                return;
            }
            byte[] photoData = Base64.decode(encoded, Base64.DEFAULT);
            if (photoData.length == 0) {
                return;
            }
            final Bitmap bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.length);
            imageView.setImageBitmap(bitmap);
            imageView.setVisibility(View.VISIBLE);
            if (holder.imageContainer != null) {
                holder.imageContainer.setVisibility(View.VISIBLE);
            }
            imageView.setOnClickListener(v -> activity.lightbox(bitmap));
        } catch (Exception ex) {
            ex.printStackTrace();
            imageView.setVisibility(View.GONE);
            if (holder.imageContainer != null) {
                holder.imageContainer.setVisibility(View.GONE);
            }
        }
    }

    private void bindAvatar(PostViewHolder holder, PostAssets assets, String ownerKey) {
        Uri playableVideo = VideoCacheManager.ensureVideoUri(
                getContext(),
                ownerKey,
                assets.storedVideoUri,
                assets.videoData
        );
        holder.thumb.bind(assets.photoThumb, assets.videoThumb, playableVideo != null ? playableVideo.toString() : null);
        registerAvatar(holder.thumb);

        holder.thumblink.setOnClickListener(null);
        holder.thumblink.setOnLongClickListener(null);
        holder.thumblink.setClickable(false);
        holder.thumblink.setLongClickable(false);

        holder.thumb.setOnClickListener(null);
        holder.thumb.setOnLongClickListener(null);

        holder.thumb.setOnAvatarClickListener(content -> {
            if (content.isVideo()) {
                activity.showLightbox(content); // один універсальний метод
            } else {
                activity.showLightbox(content); // той самий
            }
        });
        holder.thumb.setClickable(true);
        holder.thumblink.setClickable(true);
    }

    private void bindPostTexts(PostViewHolder holder,
                               JSONObject data,
                               String displayName,
                               String postAddress,
                               String wallOwner) {
        int primaryTextColor = ThemeManager.getColor(context, com.google.android.material.R.attr.colorOnBackground);
        int secondaryTextColor = ThemeManager.getColor(context, R.attr.white_80);

        holder.name.setTextColor(primaryTextColor);
        holder.text.setTextColor(primaryTextColor);
        holder.text.setLinkTextColor(ThemeManager.getColor(context, com.google.android.material.R.attr.colorOnPrimary));
        holder.address.setTextColor(secondaryTextColor);
        holder.date.setTextColor(secondaryTextColor);

        if (!TextUtils.isEmpty(displayName)) {
            holder.name.setText(displayName);
        }
        holder.address.setText(postAddress);

        holder.text.setMovementMethod(LinkMovementMethod.getInstance());
        String textValue = data.optString("text");
        holder.text.setText(Utils.linkify(context, textValue));
        holder.text.setVisibility(textValue.isEmpty() ? View.GONE : View.VISIBLE);

        holder.date.setText(Utils.formatDate(data.optString("date")));

        boolean isForeignPost = !TextUtils.isEmpty(postAddress) && !postAddress.equals(wallOwner);
        if (isForeignPost) {
            holder.address.setPaintFlags(holder.address.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            holder.name.setPaintFlags(holder.name.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            View.OnClickListener openProfile = v ->
                    getContext().startActivity(new Intent(getContext(), MainActivity.class).putExtra("address", postAddress));
            holder.link.setOnClickListener(openProfile);
            View.OnLongClickListener openProfileLong = v -> {
                openProfile.onClick(v);
                return true;
            };
            holder.thumblink.setOnLongClickListener(openProfileLong);
            holder.thumblink.setLongClickable(true);
            holder.thumb.setOnLongClickListener(openProfileLong);
            holder.thumb.setLongClickable(true);
        } else {
            holder.address.setPaintFlags(holder.address.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
            holder.name.setPaintFlags(holder.name.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
            holder.link.setOnClickListener(null);
            holder.thumblink.setOnLongClickListener(null);
            holder.thumblink.setLongClickable(false);
            holder.thumb.setOnLongClickListener(null);
            holder.thumb.setLongClickable(false);
        }
    }

    private void bindPostActions(PostViewHolder holder,
                                 Item item,
                                 JSONObject data,
                                 String wallOwner,
                                 String myAddress,
                                 String postAddress) {

        holder.like.setOnClickListener(v -> activity.snack("Available soon"));
        holder.comments.setOnClickListener(v -> activity.snack("Available soon"));

        boolean isMyWall = wallOwner.equals(myAddress);
        boolean isMyPost = myAddress.equals(postAddress);

        if (isMyWall) {
            holder.delete.setVisibility(View.VISIBLE);
            holder.delete.setOnClickListener(v -> DialogHelper.showConfirm(
                    context,
                    R.string.dialog_delete_post_title,
                    R.string.dialog_delete_post_message,
                    R.string.dialog_button_delete,
                    () -> {
                        ItemDatabase.getInstance(context).delete(item.type(), item.key());
                        load();
                    },
                    R.string.dialog_button_cancel,
                    null
            ));
        } else {
            holder.delete.setVisibility(View.GONE);
            holder.delete.setOnClickListener(null);
        }

        if (isMyWall && isMyPost) {
            holder.edit.setVisibility(View.VISIBLE);
            holder.edit.setOnClickListener(v -> editPost(item));
        } else {
            holder.edit.setVisibility(View.GONE);
            holder.edit.setOnClickListener(null);
        }

        boolean canShare = !isMyWall
                && !isMyPost
                && TextUtils.isEmpty(data.optString("access"));
        if (canShare) {
            holder.share.setVisibility(View.VISIBLE);
            holder.share.setOnClickListener(v -> activity.sharePost(data));
        } else {
            holder.share.setVisibility(View.GONE);
            holder.share.setOnClickListener(null);
        }
    }

    private PostAssets resolvePostAssets(Item item,
                                         JSONObject rawData,
                                         JSONObject data,
                                         String wallOwner,
                                         String myAddress,
                                         String postAddress) {
        PostAssets assets = new PostAssets();
        assets.photoThumb = decodeBitmapBase64(rawData.optString("thumb", ""));
        if (assets.photoThumb == null) {
            assets.photoThumb = item.bitmap("thumb");
            if (assets.photoThumb == null) {
                assets.photoThumb = decodeBitmapBase64(data.optString("thumb", ""));
            }
        }
        assets.videoThumb = decodeBitmapBase64(rawData.optString("video_thumb", ""));
        if (assets.videoThumb == null) {
            assets.videoThumb = item.bitmap("video_thumb");
            if (assets.videoThumb == null) {
                assets.videoThumb = decodeBitmapBase64(data.optString("video_thumb", ""));
            }
        }
        assets.storedVideoUri = firstNonEmpty(data.optString("video_uri", "").trim(), rawData.optString("video_uri", "").trim());
        assets.videoData = firstNonEmpty(rawData.optString("video", "").trim(), data.optString("video", "").trim());
        assets.displayName = firstNonEmpty(rawData.optString("name", ""), data.optString("name", ""));

        boolean belongsToFriend = !TextUtils.isEmpty(postAddress) && !postAddress.equals(myAddress);
        if (belongsToFriend) {
            ItemCache cache = ItemCache.getInstance(getContext());
            if (assets.photoThumb == null) {
                ItemResult thumbResult = cache.get(postAddress, "thumb");
                if (thumbResult.size() > 0) {
                    assets.photoThumb = thumbResult.one().bitmap("thumb");
                }
            }
            if (assets.videoThumb == null) {
                ItemResult videoThumbResult = cache.get(postAddress, "video_thumb");
                if (videoThumbResult.size() > 0) {
                    assets.videoThumb = videoThumbResult.one().bitmap("video_thumb");
                }
            }
            if (TextUtils.isEmpty(assets.videoData)) {
                ItemResult videoResult = cache.get(postAddress, "video");
                if (videoResult.size() > 0) {
                    assets.videoData = videoResult.one().json().optString("video", "").trim();
                }
            }
            if (TextUtils.isEmpty(assets.displayName)) {
                ItemResult nameResult = cache.get(postAddress, "name");
                if (nameResult.size() > 0) {
                    String cachedName = nameResult.one().json().optString("name", "");
                    if (!TextUtils.isEmpty(cachedName)) {
                        assets.displayName = cachedName;
                    }
                }
            }
            if (assets.photoThumb == null || TextUtils.isEmpty(assets.displayName) || (TextUtils.isEmpty(assets.videoData) && assets.videoThumb == null)) {
                Item friendItem = ItemDatabase.getInstance(getContext()).getByKey("friend", postAddress);
                if (friendItem != null) {
                    if (assets.photoThumb == null) {
                        assets.photoThumb = friendItem.bitmap("thumb");
                    }
                    if (assets.videoThumb == null) {
                        assets.videoThumb = friendItem.bitmap("video_thumb");
                    }
                    if (TextUtils.isEmpty(assets.videoData)) {
                        assets.videoData = friendItem.json().optString("video", "").trim();
                    }
                    if (TextUtils.isEmpty(assets.displayName)) {
                        String friendName = friendItem.json().optString("name", "");
                        if (!TextUtils.isEmpty(friendName)) {
                            assets.displayName = friendName;
                        }
                    }
                }
            }
        }

        boolean isWallOwner = !TextUtils.isEmpty(postAddress)
                && postAddress.equals(wallOwner)
                && !postAddress.equals(myAddress);
        if (isWallOwner) {
            Item friendItem = ItemDatabase.getInstance(getContext()).getByKey("friend", postAddress);
            if (friendItem != null) {
                Bitmap friendThumb = friendItem.bitmap("thumb");
                if (friendThumb != null) {
                    assets.photoThumb = friendThumb;
                }
                Bitmap friendVideoThumb = friendItem.bitmap("video_thumb");
                if (friendVideoThumb != null) {
                    assets.videoThumb = friendVideoThumb;
                }
                String friendVideo = friendItem.json().optString("video", "").trim();
                if (!TextUtils.isEmpty(friendVideo)) {
                    assets.videoData = friendVideo;
                }
                String friendName = friendItem.json().optString("name", "");
                if (!TextUtils.isEmpty(friendName)) {
                    assets.displayName = friendName;
                }
            }
        }
        return assets;
    }

    private boolean updatePaginationControls(int insertIndex, ItemResult itemResult, boolean finished) {
        smore = finished ? itemResult.more() : null;
        imore = insertIndex + count;

        return smore != null;
    }

    private JSONObject parseJsonSafe(String data) {
        try {
            return data == null ? new JSONObject() : new JSONObject(data);
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    private static Bitmap decodeBitmapBase64(String encoded) {
        if (TextUtils.isEmpty(encoded)) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(encoded.trim(), Base64.DEFAULT);
            if (bytes.length == 0) {
                return null;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return "";
    }

    private String resolveOwnerKey(String postAddress, String wallOwner, String myAddress, Item item) {
        if (!TextUtils.isEmpty(postAddress)) {
            return postAddress;
        }
        if (!TextUtils.isEmpty(wallOwner) && !wallOwner.equals(myAddress)) {
            return wallOwner;
        }
        if (!TextUtils.isEmpty(myAddress)) {
            return myAddress;
        }
        return "post_" + item.key();
    }

    private final class FriendPreviewAdapter extends RecyclerView.Adapter<FriendPreviewAdapter.FriendPreviewHolder> {

        @Override
        public FriendPreviewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View view = inflater.inflate(R.layout.wall_friend_preview_item, parent, false);
            return new FriendPreviewHolder(view);
        }

        @Override
        public void onBindViewHolder(FriendPreviewHolder holder, int position) {
            FriendPreview preview = position >= 0 && position < friendPreviews.size()
                    ? friendPreviews.get(position)
                    : null;
            bindFriendPreview(holder, preview);
        }

        @Override
        public int getItemCount() {
            return friendPreviews.size();
        }

        final class FriendPreviewHolder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final MaterialCardView avatarCard;
            final AvatarView avatar;
            final TextView name;
            final TextView date;
            final TextView postText;
            final ImageView postImage;
            final View imageContainer;

            FriendPreviewHolder(View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.previewCard);
                avatarCard = itemView.findViewById(R.id.avatarCard);
                avatar = itemView.findViewById(R.id.avatar);
                name = itemView.findViewById(R.id.friendName);
                date = itemView.findViewById(R.id.postDate);
                postText = itemView.findViewById(R.id.postText);
                postImage = itemView.findViewById(R.id.postImage);
                imageContainer = itemView.findViewById(R.id.postImageContainer);
            }
        }
    }

    private static final class FriendPreview {
        final String friendAddress;
        Item friendItem;
        JSONObject friendData;
        String displayName;
        Item latestPost;
        int lastRequestedGeneration = -1;

        FriendPreview(String friendAddress) {
            this.friendAddress = friendAddress;
        }
    }

    private static final class PostDraft {
        String text;
        Bitmap image;
        byte[] videoData;
        Bitmap videoThumb;
        String videoMime;
        long videoDurationMs;
        byte[] audioData;
        String audioMime;
        long audioDurationMs;

        PostDraft copy() {
            PostDraft copy = new PostDraft();
            copy.text = text;
            copy.image = image;
            copy.videoData = videoData;
            copy.videoThumb = videoThumb;
            copy.videoMime = videoMime;
            copy.videoDurationMs = videoDurationMs;
            copy.audioData = audioData;
            copy.audioMime = audioMime;
            copy.audioDurationMs = audioDurationMs;
            return copy;
        }

        static PostDraft fromItem(Item item) {
            PostDraft draft = new PostDraft();
            if (item == null) {
                return draft;
            }
            try {
                JSONObject data = item.json();
                draft.text = data.optString("text", "");
                Bitmap bmp = item.bitmap("img");
                if (bmp != null) {
                    draft.image = bmp;
                }
                String videoBase64 = data.optString("video", "").trim();
                if (!TextUtils.isEmpty(videoBase64)) {
                    try {
                        draft.videoData = Ed25519Signature.base64Decode(videoBase64);
                    } catch (Exception ignore) {
                        draft.videoData = null;
                    }
                    String thumbBase64 = data.optString("video_thumb", "").trim();
                    if (!TextUtils.isEmpty(thumbBase64)) {
                        draft.videoThumb = Utils.decodeImage(thumbBase64);
                    }
                    draft.videoMime = data.optString("video_mime", null);
                    draft.videoDurationMs = data.optLong("video_duration", 0L);
                }
                String audioBase64 = data.optString("audio", "").trim();
                if (!TextUtils.isEmpty(audioBase64)) {
                    try {
                        draft.audioData = Ed25519Signature.base64Decode(audioBase64);
                    } catch (Exception ignore) {
                        draft.audioData = null;
                    }
                    draft.audioMime = data.optString("audio_mime", "audio/mp4");
                    draft.audioDurationMs = data.optLong("audio_duration", 0L);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return draft;
        }

        void clearImage() {
            image = null;
        }

        void clearVideo() {
            videoData = null;
            videoThumb = null;
            videoMime = null;
            videoDurationMs = 0L;
        }

        void clearAudio() {
            audioData = null;
            audioMime = null;
            audioDurationMs = 0L;
        }
    }

    private final class PostComposer {
        final Item item;
        final PostDraft draft;
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

        PostComposer(Item item, PostDraft draft, Dialog dialog, View root, TextInputEditText textField) {
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

        void init() {
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
                addImageButton.setOnClickListener(v -> PermissionHelper.runWithPermissions(activity,
                        EnumSet.of(PermissionHelper.PermissionRequest.MEDIA),
                        () -> prepareExternalAction(() -> startImageChooser(REQUEST_PICK_IMAGE_POST)),
                        () -> activity.snack(activity.getString(R.string.snackbar_storage_permission_required))));
            }
            if (takePhotoButton != null) {
                takePhotoButton.setOnClickListener(v -> PermissionHelper.runWithPermissions(activity,
                        EnumSet.of(PermissionHelper.PermissionRequest.CAMERA),
                        () -> {
                            try {
                                pendingPhotoUri = createPhotoOutputUri();
                            } catch (IOException ex) {
                                pendingPhotoUri = null;
                                activity.snack("Unable to create photo file");
                                return;
                            }
                            prepareExternalAction(() -> {
                                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                                intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
                                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                try {
                                    activity.startActivityForResult(intent, REQUEST_TAKE_PHOTO_POST);
                                } catch (ActivityNotFoundException ex) {
                                    activity.snack(activity.getString(R.string.chat_attachment_pick_failed));
                                }
                            });
                        },
                        () -> activity.snack("Camera permission required")));
            }
            if (addVideoButton != null) {
                addVideoButton.setOnClickListener(v -> PermissionHelper.runWithPermissions(activity,
                        EnumSet.of(PermissionHelper.PermissionRequest.MEDIA),
                        () -> prepareExternalAction(() -> {
                            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                            intent.setType("video/*");
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            try {
                                activity.startActivityForResult(intent, Const.REQUEST_PICK_VIDEO_POST);
                            } catch (ActivityNotFoundException ex) {
                                activity.snack(activity.getString(R.string.chat_attachment_pick_failed));
                            }
                        }),
                        () -> activity.snack(activity.getString(R.string.snackbar_storage_permission_required))));
            }
            if (recordAudioButton != null) {
                recordAudioButton.setOnClickListener(v -> {
                    if (recording) {
                        stopAudioRecording(true);
                    } else {
                        PermissionHelper.runWithPermissions(activity,
                                EnumSet.of(PermissionHelper.PermissionRequest.MICROPHONE),
                                this::startAudioRecording,
                                () -> activity.snack(activity.getString(R.string.chat_attachment_mic_permission_required)));
                    }
                });
            }
            if (publishButton != null) {
                publishButton.setOnClickListener(v -> {
                    captureText();
                    doPostPublish(item, draft.copy());
                    dialog.dismiss();
                });
            }
        }

        void captureText() {
            draft.text = textField.getText() != null ? textField.getText().toString() : "";
        }

        void prepareExternalAction(Runnable action) {
            captureText();
            pendingDraftAfterActivity = draft.copy();
            pendingDraftItem = item;
            dialog.dismiss();
            if (action != null) {
                action.run();
            }
        }

        void startAudioRecording() {
            cancelAudioRecording();
            File dir = new File(activity.getCacheDir(), "post_audio");
            if (!dir.exists() && !dir.mkdirs()) {
                activity.snack(activity.getString(R.string.chat_attachment_recording_failed));
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
                if (recordAudioButton != null) {
                    recordAudioButton.setImageResource(R.drawable.ic_close);
                }
                activity.snack(activity.getString(R.string.chat_attachment_recording_started));
            } catch (Exception ex) {
                Log.e(TAG, "Unable to start audio recording", ex);
                activity.snack(activity.getString(R.string.chat_attachment_recording_failed));
                cancelAudioRecording();
            }
        }

        void stopAudioRecording(boolean keep) {
            if (!recording) {
                return;
            }
            try {
                recorder.stop();
            } catch (Exception ex) {
                Log.w(TAG, "Recorder stop failed", ex);
                keep = false;
            }
            recording = false;
            if (recordAudioButton != null) {
                recordAudioButton.setImageResource(R.drawable.ic_mic);
            }
            if (recorder != null) {
                try {
                    recorder.reset();
                } catch (Exception ignore) {
                }
                try {
                    recorder.release();
                } catch (Exception ignore) {
                }
                recorder = null;
            }
            if (!keep) {
                if (tempAudioFile != null && tempAudioFile.exists()) {
                    tempAudioFile.delete();
                }
                tempAudioFile = null;
                return;
            }
            if (tempAudioFile == null || !tempAudioFile.exists()) {
                activity.snack(activity.getString(R.string.chat_attachment_recording_failed));
                return;
            }
            byte[] bytes = Utils.readFileAsBytes(tempAudioFile);
            tempAudioFile.delete();
            tempAudioFile = null;
            if (bytes.length == 0) {
                activity.snack(activity.getString(R.string.chat_attachment_recording_failed));
                return;
            }
            if (ChatMediaStore.exceedsLimit(bytes.length)) {
                activity.snack(activity.getString(R.string.chat_attachment_file_too_large));
                return;
            }
            long duration = SystemClock.elapsedRealtime() - recordingStartMs;
            draft.clearImage();
            draft.clearVideo();
            draft.audioData = bytes;
            draft.audioMime = "audio/mp4";
            draft.audioDurationMs = duration;
            updatePreview();
            activity.snack(activity.getString(R.string.chat_attachment_recording_saved));
        }

        void cancelAudioRecording() {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Exception ignore) {
                }
                try {
                    recorder.reset();
                } catch (Exception ignore) {
                }
                try {
                    recorder.release();
                } catch (Exception ignore) {
                }
                recorder = null;
            }
            recording = false;
            if (recordAudioButton != null) {
                recordAudioButton.setImageResource(R.drawable.ic_mic);
            }
            if (tempAudioFile != null && tempAudioFile.exists()) {
                tempAudioFile.delete();
                tempAudioFile = null;
            }
        }

        void updatePreview() {
            boolean hasImage = draft.image != null;
            boolean hasVideo = draft.videoData != null && draft.videoData.length > 0;
            if (mediaContainer != null) {
                if (hasImage || hasVideo) {
                    mediaContainer.setVisibility(View.VISIBLE);
                    if (hasImage) {
                        imageView.setImageBitmap(draft.image);
                    } else if (draft.videoThumb != null) {
                        imageView.setImageBitmap(draft.videoThumb);
                    } else {
                        imageView.setImageBitmap(null);
                    }
                    if (videoBadge != null) {
                        videoBadge.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
                    }
                    if (removeMediaButton != null) {
                        removeMediaButton.setVisibility(View.VISIBLE);
                    }
                } else {
                    mediaContainer.setVisibility(View.GONE);
                    if (removeMediaButton != null) {
                        removeMediaButton.setVisibility(View.GONE);
                    }
                }
            }
            if (audioContainer != null) {
                if (draft.audioData != null && draft.audioData.length > 0) {
                    audioContainer.setVisibility(View.VISIBLE);
                    if (audioLabel != null) {
                        audioLabel.setText(buildAudioLabel(draft.audioDurationMs));
                    }
                } else {
                    audioContainer.setVisibility(View.GONE);
                }
            }
        }

        String buildAudioLabel(long durationMs) {
            long totalSeconds = Math.max(1, durationMs / 1000);
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            return activity.getString(R.string.chat_attachment_voice_message) + " • " + String.format(Locale.US, "%d:%02d", minutes, seconds);
        }

        void release() {
            cancelAudioRecording();
        }
    }

    private final class WallAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_POST = 0;
        private static final int TYPE_LOAD_MORE = 1;
        private static final int TYPE_EMPTY = 2;

        private final List<Item> adapterItems = new ArrayList<>();
        private boolean showLoadMore;
        private boolean showEmpty;
        private boolean videosPaused;

        void submit(List<Item> source, boolean displayLoadMore, boolean displayEmpty) {
            adapterItems.clear();
            adapterItems.addAll(source);
            showEmpty = displayEmpty;
            showLoadMore = showEmpty ? false : displayLoadMore;
            notifyDataSetChanged();
        }

        void setLoadMoreVisible(boolean visible) {
            boolean normalized = showEmpty ? false : visible;
            if (showLoadMore != normalized) {
                showLoadMore = normalized;
                notifyDataSetChanged();
            }
        }

        void setVideosPaused(boolean paused) {
            if (videosPaused != paused) {
                videosPaused = paused;
                notifyDataSetChanged();
            }
        }

        @Override
        public int getItemCount() {
            if (showEmpty) {
                return 1;
            }
            int total = adapterItems.size();
            if (showLoadMore) {
                total += 1;
            }
            return total;
        }

        @Override
        public int getItemViewType(int position) {
            if (showEmpty) {
                return TYPE_EMPTY;
            }
            if (position < adapterItems.size()) {
                return TYPE_POST;
            }
            return TYPE_LOAD_MORE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_POST) {
                View view = inflater.inflate(R.layout.wall_item, parent, false);
                return new PostViewHolder(view);
            } else if (viewType == TYPE_LOAD_MORE) {
                View view = inflater.inflate(R.layout.wall_more, parent, false);
                return new LoadMoreViewHolder(view);
            } else {
                View view = inflater.inflate(R.layout.wall_empty, parent, false);
                return new EmptyViewHolder(view);
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof PostViewHolder) {
                Item item = adapterItems.get(position);
                PostViewHolder postHolder = (PostViewHolder) holder;
                bindPostView(postHolder, item, currentWallOwner, currentMyAddress);
                if (videosPaused) {
                    postHolder.thumb.pause();
                } else {
                    postHolder.thumb.resume();
                }
            } else if (holder instanceof LoadMoreViewHolder) {
                ((LoadMoreViewHolder) holder).bind();
            }
        }

        private class LoadMoreViewHolder extends RecyclerView.ViewHolder {
            private final View button;

            LoadMoreViewHolder(View itemView) {
                super(itemView);
                button = itemView.findViewById(R.id.wallLoadMore);
                button.setOnClickListener(v -> loadMore());
                itemView.setOnClickListener(v -> loadMore());
            }

            void bind() {
                button.setEnabled(smore != null);
                itemView.setEnabled(smore != null);
            }
        }

        private class EmptyViewHolder extends RecyclerView.ViewHolder {
            EmptyViewHolder(View itemView) {
                super(itemView);
            }
        }
    }

    private static final class PostAssets {
        Bitmap photoThumb;
        Bitmap videoThumb;
        String storedVideoUri;
        String videoData;
        String displayName;
    }

    private void registerAvatar(AvatarView avatarView) {
        synchronized (activeAvatars) {
            for (int i = activeAvatars.size() - 1; i >= 0; i--) {
                AvatarView existing = activeAvatars.get(i).get();
                if (existing == null) {
                    activeAvatars.remove(i);
                }
            }
            activeAvatars.add(new WeakReference<>(avatarView));
        }
    }

    private static final class PostViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout link;
        final View thumblink;
        final TextView address;
        final TextView name;
        final TextView date;
        final TextView text;
        final ImageView like;
        final ImageView comments;
        final ImageView share;
        final ImageView delete;
        final ImageView edit;
        final AvatarView thumb;
        final FrameLayout imageContainer;
        final ImageView image;
        final MaterialCardView card;
        final LinearLayout container;
        final LinearLayout headerRow;
        final FrameLayout avatarContainer;
        final MaterialCardView avatarCard;
        final LinearLayout actionRow;

        PostViewHolder(View root) {
            super(root);
            link = root.findViewById(R.id.link);
            thumblink = root.findViewById(R.id.thumblink);
            address = root.findViewById(R.id.address);
            name = root.findViewById(R.id.name);
            date = root.findViewById(R.id.date);
            text = root.findViewById(R.id.text);
            like = root.findViewById(R.id.like);
            comments = root.findViewById(R.id.comments);
            share = root.findViewById(R.id.share);
            delete = root.findViewById(R.id.delete);
            edit = root.findViewById(R.id.edit);
            thumb = root.findViewById(R.id.thumb);
            imageContainer = root.findViewById(R.id.imageContainer);
            image = root.findViewById(R.id.image);
            card = root.findViewById(R.id.card);
            container = root.findViewById(R.id.postContent);
            headerRow = root.findViewById(R.id.headerRow);
            avatarContainer = root.findViewById(R.id.avatarContainer);
            avatarCard = root.findViewById(R.id.avatarCard);
            actionRow = root.findViewById(R.id.actionRow);
        }
    }

    public void refreshAppearance() {
        if (wallAdapter != null) {
            wallAdapter.notifyDataSetChanged();
        }
    }

    public void pauseAvatarVideos() {
        synchronized (activeAvatars) {
            for (int i = activeAvatars.size() - 1; i >= 0; i--) {
                AvatarView avatar = activeAvatars.get(i).get();
                if (avatar == null) {
                    activeAvatars.remove(i);
                } else {
                    avatar.pause();
                }
            }
        }
        if (wallAdapter != null) {
            wallAdapter.setVideosPaused(true);
        }
    }

    public void resumeAvatarVideos() {
        synchronized (activeAvatars) {
            for (int i = activeAvatars.size() - 1; i >= 0; i--) {
                AvatarView avatar = activeAvatars.get(i).get();
                if (avatar == null) {
                    activeAvatars.remove(i);
                } else {
                    avatar.resume();
                }
            }
        }
        if (wallAdapter != null) {
            wallAdapter.setVideosPaused(false);
        }
    }

    private Uri createPhotoOutputUri() throws IOException {
        File dir = new File(getContext().getExternalCacheDir(), "camera");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create directory for photo capture");
        }
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".jpg";
        File file = new File(dir, name);
        if (!file.createNewFile()) {
            throw new IOException("Unable to create photo file");
        }
        return FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
    }

    private void deleteTempPhoto() {
        if (pendingPhotoUri == null) return;
        try {
            getContext().getContentResolver().delete(pendingPhotoUri, null, null);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            pendingPhotoUri = null;
        }
    }

}
