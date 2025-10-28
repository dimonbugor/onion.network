
package onion.network.ui.pages;

import org.json.JSONObject;

import onion.network.helpers.ChatMediaStore;
import onion.network.helpers.Utils;
import onion.network.models.FriendPreview;
import onion.network.models.Item;

import android.graphics.Bitmap;
import android.text.TextUtils;

import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import onion.network.R;
import onion.network.ui.controllers.PostComposer;
import onion.network.utils.WallUtils;
import onion.network.models.PostAssets;
import onion.network.models.PostDraft;
import onion.network.ui.adapters.FriendPreviewAdapter;
import onion.network.ui.adapters.WallAdapter;
import onion.network.ui.adapters.viewholders.PostViewHolder;
import onion.network.ui.views.AvatarView;

import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import static onion.network.helpers.Const.REQUEST_PICK_IMAGE_POST;
import static onion.network.helpers.Const.REQUEST_TAKE_PHOTO_POST;
import static onion.network.ui.MainActivity.extractSingleUri;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.text.method.LinkMovementMethod;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.lang.ref.WeakReference;

import onion.network.TorManager;
import onion.network.cashes.ItemCache;
import onion.network.databases.ItemDatabase;
import onion.network.helpers.AudioCacheManager;
import onion.network.helpers.Const;
import onion.network.helpers.DialogHelper;
import onion.network.helpers.MediaResolver;
import onion.network.helpers.StreamMediaStore;
import onion.network.helpers.ThemeManager;
import onion.network.helpers.UiCustomizationManager;
import onion.network.helpers.VideoCacheManager;
import onion.network.models.ItemResult;
import onion.network.models.ItemTask;
import onion.network.ui.MainActivity;

import android.content.ClipData;
import android.net.Uri;

public class WallPage extends BasePage {

    static final int FRIEND_PREVIEW_LIMIT = 12;
    static final int IMAGE_MAX_DIM_PX = 1080;
    static final String TAG = "WallPage";

    RecyclerView recyclerView;
    WallAdapter wallAdapter;
    RecyclerView friendPreviewRecyclerView;
    FriendPreviewAdapter friendPreviewAdapter;

    final List<Item> posts = new ArrayList<>();
    final List<FriendPreview> friendPreviews = new ArrayList<>();
    final Map<String, FriendPreview> friendPreviewByAddress = new HashMap<>();
    final List<String> friendPreviewOrder = new ArrayList<>();
    final List<WeakReference<AvatarView>> activeAvatars = new ArrayList<>();

    int friendPreviewGeneration = 0;
    int pageSize = 5;

    public String nextMoreKey;
    int nextInsertIndex;

    public String currentWallOwner = "";
    public String currentMyAddress = "";

    public Uri pendingPhotoUri;
    PostComposer activeComposer;
    public PostDraft pendingDraftAfterActivity;
    public Item pendingDraftItem;

    MediaPlayer audioPlayer;
    String playingAudioPostKey;

    public WallPage(MainActivity activity) {
        super(activity);
        activity.getLayoutInflater().inflate(R.layout.wall_page, this, true);
        setupFriendPreviewList();
        setupPostsList();
    }

    private void setupFriendPreviewList() {
        friendPreviewRecyclerView = findViewById(R.id.friendPreviewRecyclerView);
        if (friendPreviewRecyclerView == null) return;
        friendPreviewRecyclerView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        friendPreviewAdapter = new FriendPreviewAdapter(this, friendPreviews);
        friendPreviewRecyclerView.setAdapter(friendPreviewAdapter);
    }

    private void setupPostsList() {
        recyclerView = findViewById(R.id.wallRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        wallAdapter = new WallAdapter(this);
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
        builder.setTitle(item != null ? R.string.dialog_edit_post_title : R.string.dialog_write_post_title);
        Dialog dialog = builder.create();
        PostComposer composer = new PostComposer(this, item, draft, dialog, dialogView, textEdit);
        dialog.setOnDismissListener(di -> {
            if (activeComposer == composer) activeComposer = null;
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
        PostDraft d = new PostDraft();
        d.text = text;
        d.image = bitmap;
        openPostComposer(null, d);
    }

    public void doPostPublish(@Nullable Item item, PostDraft draft) {
        if (draft == null) return;
        draft.text = draft.text == null ? "" : draft.text;
        try {
            if (item != null) {
                JSONObject o = item.json();
                o.put("text", draft.text);
                applyDraftToJson(o, draft);
                Item updated = new Item(item.type(), item.key(), item.index(), o);
                ItemDatabase.getInstance(getContext()).put(updated);
            } else {
                JSONObject data = new JSONObject();
                data.put("text", draft.text);
                data.put("date", String.valueOf(System.currentTimeMillis()));
                applyDraftToJson(data, draft);
                activity.publishPost(data);
            }
            activity.load();
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void applyDraftToJson(JSONObject o, PostDraft draft) throws JSONException {
        if (draft.image != null) o.put("img", onion.network.helpers.Utils.encodeImage(draft.image));
        else o.remove("img");
        applyMediaRef(o, "video", draft.videoMediaId, draft.videoMime, draft.videoDurationMs, draft.videoThumb);
        applyMediaRef(o, "audio", draft.audioMediaId, draft.audioMime, draft.audioDurationMs, null);
    }

    public boolean ensureDraftMediaReferences(PostDraft draft) {
        if (draft == null) return true;
        try {
            if (TextUtils.isEmpty(draft.videoMediaId) && draft.videoData != null && draft.videoData.length > 0) {
                StreamMediaStore.MediaDescriptor d = StreamMediaStore.save(context, draft.videoData, draft.videoMime);
                draft.videoMediaId = d.id;
                draft.videoMime = d.mime;
                draft.videoData = null;
            }
            if (TextUtils.isEmpty(draft.audioMediaId) && draft.audioData != null && draft.audioData.length > 0) {
                StreamMediaStore.MediaDescriptor d = StreamMediaStore.save(context, draft.audioData, draft.audioMime);
                draft.audioMediaId = d.id;
                draft.audioMime = d.mime;
                draft.audioData = null;
            }
            return true;
        } catch (IOException ex) {
            Log.e(TAG, "Unable to persist media", ex);
            activity.snack(activity.getString(R.string.wall_media_store_failed));
            return false;
        }
    }

    private static void applyMediaRef(JSONObject target, String pfx, String mediaId, String mime, long durMs, Bitmap thumb) throws JSONException {
        if (!TextUtils.isEmpty(mediaId)) {
            target.put(pfx + "_id", mediaId);
            if (!TextUtils.isEmpty(mime)) target.put(pfx + "_mime", mime);
            else target.remove(pfx + "_mime");
            if (durMs > 0) target.put(pfx + "_duration", durMs);
            else target.remove(pfx + "_duration");
            if (thumb != null)
                target.put(pfx + "_thumb", onion.network.helpers.Utils.encodeImage(thumb));
            else target.remove(pfx + "_thumb");
            target.put(pfx + "_uri", "/media/" + mediaId);
            target.remove(pfx);
            return;
        }
        target.remove(pfx + "_id");
        target.remove(pfx + "_mime");
        target.remove(pfx + "_duration");
        target.remove(pfx + "_thumb");
        target.remove(pfx + "_uri");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d("WallPage", "onActivityResult rc=" + requestCode + " res=" + resultCode);
        if (requestCode == REQUEST_PICK_IMAGE_POST || requestCode == REQUEST_TAKE_PHOTO_POST || requestCode == Const.REQUEST_PICK_VIDEO_POST) {
            handleComposerActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != Activity.RESULT_OK) {
            deleteTempPhoto();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    public void handleComposerActivityResult(int requestCode, int resultCode, Intent data) {
        PostComposer composer = activeComposer;
        PostDraft draft = composer != null ? composer.draft : pendingDraftAfterActivity;
        Item draftItem = composer != null ? composer.item : pendingDraftItem;
        boolean ok = (resultCode == Activity.RESULT_OK);
        if (!ok || draft == null) {
            activity.snack(activity.getString(R.string.chat_attachment_pick_failed));
            cleanupAfterExternalAction();
            if (composer == null && draft != null) openPostComposer(draftItem, draft);
            return;
        }
        switch (requestCode) {
            case REQUEST_PICK_IMAGE_POST:
            case REQUEST_TAKE_PHOTO_POST:
                handleImagePicked(requestCode, data, draft);
                break;
            case Const.REQUEST_PICK_VIDEO_POST:
                handleVideoPicked(data, draft);
                break;
        }
        cleanupAfterExternalAction();
        if (composer != null) composer.updatePreview();
        else openPostComposer(draftItem, draft);
    }

    private void handleImagePicked(int requestCode, Intent data, PostDraft draft) {
        Log.d("WallPage", "handleImagePicked/handleVideoPicked uri=" + (data != null ? data.getData() : null));
        Bitmap bmp = null;
        Uri uri = data != null ? data.getData() : null;
        try {
            if (requestCode == REQUEST_TAKE_PHOTO_POST) {
                uri = pendingPhotoUri;
                if (uri != null)
                    try (InputStream s = activity.getContentResolver().openInputStream(uri)) {
                        bmp = BitmapFactory.decodeStream(s);
                    }
                else if (data != null && data.getExtras() != null) {
                    Object extra = data.getExtras().get("data");
                    if (extra instanceof Bitmap) bmp = (Bitmap) extra;
                }
            } else if (data != null) {
                bmp = WallUtils.getActivityResultBitmap(activity, data);
            }
        } catch (IOException ex) {
            Log.e(TAG, "Failed to decode captured/picked photo", ex);
        }
        if (bmp != null && uri != null) bmp = WallUtils.fixImageOrientation(activity, bmp, uri);
        if (bmp == null) {
            activity.snack(activity.getString(R.string.chat_attachment_pick_failed));
            return;
        }
        draft.image = WallUtils.scaleBitmap(bmp, IMAGE_MAX_DIM_PX);
        draft.clearVideo();
        draft.clearAudio();
    }

    private void handleVideoPicked(@Nullable Intent data, PostDraft draft) {
        if (data == null) { activity.snack(activity.getString(R.string.chat_attachment_pick_failed)); return; }

        Uri uri = extractSingleUri(data);
        Log.d(TAG, "handleVideoPicked uri=" + uri);

        if (uri == null) { activity.snack(activity.getString(R.string.chat_attachment_pick_failed)); return; }
        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
            byte[] bytes = Utils.readInputStream(stream);
            if (ChatMediaStore.exceedsLimit(bytes.length)) { activity.snack(activity.getString(R.string.chat_attachment_file_too_large)); return; }

            String mime = WallUtils.resolveMimeFromUri(activity, uri);

            MediaMetadataRetriever retr = new MediaMetadataRetriever();
            Bitmap thumb = null; long duration = 0L;
            try {
                retr.setDataSource(activity, uri);
                duration = WallUtils.parseDuration(retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                thumb = retr.getFrameAtTime(0);
            } catch (Exception ex) {
                Log.w(TAG, "Unable to extract video metadata", ex);
            } finally {
                try { retr.release(); } catch (Exception ignore) {}
            }

            if (thumb != null) thumb = WallUtils.scaleBitmap(thumb, IMAGE_MAX_DIM_PX);

            draft.clearImage();
            draft.clearAudio();
            draft.videoData = bytes;
            draft.videoMime = mime;
            draft.videoThumb = thumb;
            draft.videoDurationMs = duration;
            draft.videoMediaId = null;
        } catch (IOException ex) {
            Log.e(TAG, "Unable to read selected video", ex);
            activity.snack(activity.getString(R.string.chat_attachment_pick_failed));
        }
    }

    private void cleanupAfterExternalAction() {
        deleteTempPhoto();
        pendingDraftAfterActivity = null;
        pendingDraftItem = null;
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

    @Override
    public void onPause() {
        stopPostAudioPlayback(false);
    }

    private boolean isViewingOwnWall() {
        MainActivity a = activity;
        return a != null && TextUtils.isEmpty(a.address);
    }

    private void loadFriendPostPreviews() {
        if (friendPreviewRecyclerView == null || friendPreviewAdapter == null) return;
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
                handleFriendPreviewResult(safeFirst(results), generation);
            }

            @Override
            protected void onPostExecute(ItemResult res) {
                handleFriendPreviewResult(res, generation);
            }

            private ItemResult safeFirst(ItemResult[] results) {
                return results != null && results.length > 0 ? results[0] : null;
            }
        }.execute2();
    }

    private void handleFriendPreviewResult(@Nullable ItemResult itemResult, int generation) {
        if (generation != friendPreviewGeneration) return;
        if (itemResult == null) {
            updateFriendPreviewVisibility();
            return;
        }
        Map<String, FriendPreview> retained = new HashMap<>(friendPreviewByAddress);
        friendPreviewByAddress.clear();
        friendPreviewOrder.clear();
        for (int i = 0; i < itemResult.size(); i++) {
            Item friendItem = itemResult.at(i);
            if (friendItem == null) continue;
            JSONObject friendData = friendItem.json(getContext(), activity.address);
            String friendAddress = friendData.optString("addr").trim();
            if (TextUtils.isEmpty(friendAddress)) continue;
            FriendPreview preview = retained.remove(friendAddress);
            if (preview == null) preview = new FriendPreview(friendAddress);
            preview.friendItem = friendItem;
            preview.friendData = friendData;
            preview.displayName = resolveFriendDisplayName(friendData);
            friendPreviewByAddress.put(friendAddress, preview);
            friendPreviewOrder.add(friendAddress);
        }
        refreshDisplayedFriendPreviews();
        for (String addr : friendPreviewOrder) {
            FriendPreview preview = friendPreviewByAddress.get(addr);
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
                handleFriendPostResult(preview, generation, safeFirst(results));
            }

            @Override
            protected void onPostExecute(ItemResult res) {
                handleFriendPostResult(preview, generation, res);
            }

            private ItemResult safeFirst(ItemResult[] results) {
                return results != null && results.length > 0 ? results[0] : null;
            }
        }.execute2();
    }

    private void handleFriendPostResult(FriendPreview preview, int generation, @Nullable ItemResult res) {
        if (preview == null || generation != friendPreviewGeneration || !friendPreviewByAddress.containsKey(preview.friendAddress))
            return;
        if (res == null) {
            refreshDisplayedFriendPreviews();
            return;
        }
        preview.latestPost = res.size() > 0 ? res.at(0) : null;
        refreshDisplayedFriendPreviews();
    }

    private void refreshDisplayedFriendPreviews() {
        friendPreviews.clear();
        for (String addr : friendPreviewOrder) {
            FriendPreview p = friendPreviewByAddress.get(addr);
            if (p != null && p.latestPost != null) friendPreviews.add(p);
        }
        if (friendPreviewAdapter != null) friendPreviewAdapter.notifyDataSetChanged();
        updateFriendPreviewVisibility();
    }

    private void updateFriendPreviewVisibility() {
        if (friendPreviewRecyclerView == null) return;
        friendPreviewRecyclerView.setVisibility(isViewingOwnWall() && !friendPreviews.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static String resolveFriendDisplayName(@Nullable JSONObject friendData) {
        if (friendData == null) return "Anonymous";
        String name = friendData.optString("name", "").trim();
        return name.isEmpty() ? "Anonymous" : name;
    }

    void load(final int startIndex, String startKey) {
        Log.i(TAG, "load()");
        new ItemTask(getContext(), address, "post", startKey, pageSize) {
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

    public void loadMore() {
        if (nextMoreKey == null) return;
        String key = nextMoreKey;
        nextMoreKey = null;
        if (wallAdapter != null) wallAdapter.setLoadMoreVisible(false);
        load(nextInsertIndex, key);
    }

    private void renderPosts(int insertIndex, @Nullable ItemResult res, boolean finished) {
        if (res == null) return;
        currentMyAddress = TorManager.getInstance(context).getID();
        currentWallOwner = TextUtils.isEmpty(address) ? currentMyAddress : address;
        int insertionPoint = Math.min(insertIndex, posts.size());
        if (insertionPoint < posts.size()) posts.subList(insertionPoint, posts.size()).clear();
        for (int i = 0; i < res.size(); i++) {
            Item it = res.at(i);
            if (it != null) posts.add(it);
        }
        findViewById(R.id.loading).setVisibility(res.loading() ? View.VISIBLE : View.GONE);
        boolean showEmpty = insertionPoint == 0 && posts.isEmpty() && !res.loading();
        boolean showLoadMore = updatePaginationControls(insertIndex, res, finished);
        if (wallAdapter != null) wallAdapter.submit(posts, showLoadMore, showEmpty);
    }

    private boolean updatePaginationControls(int insertIndex, ItemResult res, boolean finished) {
        nextMoreKey = finished ? res.more() : null;
        nextInsertIndex = insertIndex + pageSize;
        return nextMoreKey != null;
    }

    // ===== Binding methods used by adapters =====
    public void bindFriendPreview(FriendPreviewAdapter.FriendPreviewHolder holder, FriendPreview preview) {
        if (holder == null || preview == null) return;
        applyFriendPreviewStyle(holder);
        bindFriendPreviewAvatar(holder, preview);
        if (holder.name != null) holder.name.setText(preview.displayName);
        View.OnClickListener openWall = v -> {
            if (TextUtils.isEmpty(preview.friendAddress)) return;
            Context ctx = getContext();
            if (ctx == null) return;
            ctx.startActivity(new Intent(ctx, MainActivity.class).putExtra("address", preview.friendAddress));
        };
        if (holder.card != null) holder.card.setOnClickListener(openWall);
        else holder.itemView.setOnClickListener(openWall);
        if (preview.latestPost == null) return;
        holder.itemView.setVisibility(View.VISIBLE);
        JSONObject postData = preview.latestPost.json(getContext(), preview.friendAddress);
        String dateValue = onion.network.helpers.Utils.formatDate(postData.optString("date"));
        if (holder.date != null) {
            if (!TextUtils.isEmpty(dateValue)) {
                holder.date.setText(dateValue);
                holder.date.setVisibility(View.VISIBLE);
            } else holder.date.setVisibility(View.GONE);
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
                if (holder.imageContainer != null)
                    holder.imageContainer.setVisibility(View.VISIBLE);
            } else {
                holder.postImage.setImageDrawable(null);
                holder.postImage.setVisibility(View.GONE);
                if (holder.imageContainer != null) holder.imageContainer.setVisibility(View.GONE);
            }
        }
    }

    private void applyFriendPreviewStyle(FriendPreviewAdapter.FriendPreviewHolder holder) {
        UiCustomizationManager.FriendCardConfig cfg = UiCustomizationManager.getFriendCardConfig(getContext());
        UiCustomizationManager.ColorPreset preset = UiCustomizationManager.getColorPreset(getContext());
        if (holder.card != null) {
            float r = UiCustomizationManager.resolveCornerRadiusPx(getContext(), cfg.cornerRadiusPx);
            holder.card.setRadius(r);
            holder.card.setStrokeWidth(UiCustomizationManager.dpToPx(getContext(), 1));
            holder.card.setStrokeColor(preset.getAccentColor(getContext()));
            int bg = preset == UiCustomizationManager.ColorPreset.SYSTEM ? ThemeManager.getColor(getContext(), com.google.android.material.R.attr.colorPrimaryContainer) : preset.getSurfaceColor(getContext());
            holder.card.setCardBackgroundColor(bg);
            holder.card.setContentPadding(cfg.horizontalPaddingPx, cfg.verticalPaddingPx, cfg.horizontalPaddingPx, cfg.verticalPaddingPx);
        }
        if (holder.avatarCard != null) {
            holder.avatarCard.setStrokeWidth(UiCustomizationManager.dpToPx(getContext(), 1));
            holder.avatarCard.setStrokeColor(preset.getAccentColor(getContext()));
            ViewGroup.LayoutParams p = holder.avatarCard.getLayoutParams();
            if (p != null) {
                p.width = cfg.avatarSizePx;
                p.height = cfg.avatarSizePx;
                holder.avatarCard.setLayoutParams(p);
            }
        }
        if (holder.name != null)
            holder.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.nameTextSizeSp);
        if (holder.date != null)
            holder.date.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.addressTextSizeSp);
        if (holder.postText != null)
            holder.postText.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.addressTextSizeSp);
    }

    private void bindFriendPreviewAvatar(FriendPreviewAdapter.FriendPreviewHolder holder, FriendPreview preview) {
        if (holder.avatar == null) return;
        Item friendItem = preview.friendItem;
        JSONObject friendData = preview.friendData;
        Bitmap photoThumb = friendItem != null ? friendItem.bitmap("thumb") : null;
        Bitmap videoThumb = friendItem != null ? friendItem.bitmap("video_thumb") : null;
        String storedVideoUri = friendData != null ? friendData.optString("video_uri", "").trim() : "";
        String videoData = friendData != null ? friendData.optString("video", "").trim() : "";
        String videoId = friendData != null ? friendData.optString("video_id", "").trim() : "";
        if (!TextUtils.isEmpty(videoId)) {
            Uri local = StreamMediaStore.createContentUri(context, videoId);
            if (local != null) {
                storedVideoUri = local.toString();
            } else {
                String host = preview.friendAddress.contains(".") ? preview.friendAddress : preview.friendAddress + ".onion";
                storedVideoUri = "http://" + host + "/media/" + videoId;
            }
        }
        Uri playableVideo = VideoCacheManager.ensureVideoUri(getContext(), preview.friendAddress, storedVideoUri, videoData);
        holder.avatar.bind(photoThumb, videoThumb, playableVideo != null ? playableVideo.toString() : null);
        registerAvatar(holder.avatar);
    }

    public void bindPostView(PostViewHolder h, Item item, String wallOwner, String myAddress) {
        JSONObject raw = WallUtils.parseJsonSafe(item.text());
        JSONObject data = item.json(getContext(), address);
        String postAddress = WallUtils.firstNonEmpty(raw.optString("addr"), data.optString("addr"));
        String ownerKey = resolveOwnerKey(postAddress, wallOwner, myAddress, item);
        PostAssets a = resolvePostAssets(item, raw, data, wallOwner, myAddress, postAddress);
        bindPostMedia(h, item, raw, data, a, ownerKey);
        bindPostAudio(h, item, raw, data, a, ownerKey);
        bindAvatar(h, a, ownerKey);
        bindPostTexts(h, data, a.displayName, postAddress, wallOwner);
        bindPostActions(h, item, data, wallOwner, myAddress, postAddress);
        applyPostAppearance(h);
    }

    private void applyPostAppearance(PostViewHolder h) {
        UiCustomizationManager.PostCardConfig cfg = UiCustomizationManager.getPostCardConfig(getContext());
        UiCustomizationManager.ColorPreset preset = UiCustomizationManager.getColorPreset(getContext());
        if (h.card != null) {
            float radius = UiCustomizationManager.resolveCornerRadiusPx(getContext(), cfg.cardCornerRadiusPx);
            h.card.setRadius(radius);
            int cardColor = preset == UiCustomizationManager.ColorPreset.SYSTEM ? ThemeManager.getColor(getContext(), com.google.android.material.R.attr.colorSurface) : preset.getSurfaceColor(getContext());
            h.card.setCardBackgroundColor(cardColor);
            h.card.setStrokeWidth(UiCustomizationManager.dpToPx(getContext(), 1));
            h.card.setStrokeColor(preset.getAccentColor(getContext()));
        }
        if (h.container != null) {
            h.container.setPadding(0, cfg.containerPaddingVerticalPx, 0, cfg.containerPaddingVerticalPx);
        }
        if (h.link != null) {
            h.link.setPadding(cfg.linkPaddingHorizontalPx, h.link.getPaddingTop(), cfg.linkPaddingHorizontalPx, h.link.getPaddingBottom());
        }
        if (h.text != null) {
            h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.bodyTextSizeSp);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) h.text.getLayoutParams();
            if (lp != null) {
                lp.topMargin = cfg.textTopMarginPx;
                lp.bottomMargin = cfg.textBottomMarginPx;
                lp.leftMargin = cfg.containerPaddingHorizontalPx;
                lp.rightMargin = cfg.containerPaddingHorizontalPx;
                h.text.setLayoutParams(lp);
            }
        }
        if (h.name != null) h.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.nameTextSizeSp);
        if (h.address != null)
            h.address.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.metadataTextSizeSp);
        if (h.date != null) h.date.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.metadataTextSizeSp);
        int onSurface = preset == UiCustomizationManager.ColorPreset.SYSTEM ? ThemeManager.getColor(getContext(), com.google.android.material.R.attr.colorOnSurface) : preset.getOnSurfaceColor(getContext());
        int accent = preset == UiCustomizationManager.ColorPreset.SYSTEM ? ThemeManager.getColor(getContext(), com.google.android.material.R.attr.colorOnPrimary) : preset.getAccentColor(getContext());
        int secondary = preset == UiCustomizationManager.ColorPreset.SYSTEM ? ThemeManager.getColor(getContext(), R.attr.white_80) : ColorUtils.setAlphaComponent(onSurface, 180);
        h.name.setTextColor(onSurface);
        h.text.setTextColor(onSurface);
        h.text.setLinkTextColor(accent);
        h.address.setTextColor(secondary);
        h.date.setTextColor(secondary);
        if (h.imageContainer != null) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) h.imageContainer.getLayoutParams();
            if (lp != null) {
                lp.topMargin = cfg.imageTopMarginPx;
                h.imageContainer.setLayoutParams(lp);
            }
        }
        if (h.avatarCard != null) {
            ViewGroup.LayoutParams lp = h.avatarCard.getLayoutParams();
            if (lp != null) {
                lp.width = cfg.avatarSizePx;
                lp.height = cfg.avatarSizePx;
                h.avatarCard.setLayoutParams(lp);
            }
            h.avatarCard.setRadius(cfg.avatarSizePx / 2f);
            h.avatarCard.setStrokeWidth(UiCustomizationManager.dpToPx(getContext(), 1));
            h.avatarCard.setStrokeColor(preset.getAccentColor(getContext()));
        }
        if (h.actionRow != null) {
            h.actionRow.setPadding(cfg.containerPaddingHorizontalPx, cfg.actionRowPaddingVerticalPx, cfg.containerPaddingHorizontalPx, cfg.actionRowPaddingVerticalPx);
        }
        ColorStateList tint = ColorStateList.valueOf(onSurface);
        styleActionIcon(h.like, cfg.actionIconPaddingPx, tint);
        styleActionIcon(h.comments, cfg.actionIconPaddingPx, tint);
        styleActionIcon(h.share, cfg.actionIconPaddingPx, tint);
        styleActionIcon(h.edit, cfg.actionIconPaddingPx, tint);
        styleActionIcon(h.delete, cfg.actionIconPaddingPx, tint);
        if (h.headerRow != null && h.avatarContainer != null && h.link != null) {
            LinearLayout.LayoutParams ap = (LinearLayout.LayoutParams) h.avatarContainer.getLayoutParams();
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.link.getLayoutParams();
            ap.setMarginStart(0);
            ap.setMarginEnd(cfg.avatarSpacingHorizontalPx);
            lp.setMarginStart(cfg.avatarSpacingHorizontalPx);
            lp.setMarginEnd(0);
            h.avatarContainer.setLayoutParams(ap);
            h.link.setLayoutParams(lp);
        }
    }

    private void styleActionIcon(ImageView v, int paddingPx, ColorStateList tint) {
        if (v == null) return;
        v.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
        v.setImageTintList(tint);
    }

    private void bindPostMedia(PostViewHolder h, Item item, JSONObject raw, JSONObject data, PostAssets a, String ownerKey) {
        if (h.imageContainer != null) h.imageContainer.setVisibility(View.GONE);
        ImageView iv = h.image;
        if (iv == null) return;
        iv.setVisibility(View.GONE);
        iv.setOnClickListener(null);
        iv.setImageDrawable(null);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setContentDescription(null);
        if (h.videoOverlay != null) h.videoOverlay.setVisibility(View.GONE);
        try {
            String encoded = raw != null ? raw.optString("img", "").trim() : "";
            if (!encoded.isEmpty()) {
                byte[] photoData = Base64.decode(encoded, Base64.DEFAULT);
                if (photoData.length > 0) {
                    final Bitmap bmp = BitmapFactory.decodeByteArray(photoData, 0, photoData.length);
                    if (bmp != null) {
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        iv.setImageBitmap(bmp);
                        iv.setVisibility(View.VISIBLE);
                        if (h.imageContainer != null) h.imageContainer.setVisibility(View.VISIBLE);
                        iv.setOnClickListener(v -> activity.lightbox(bmp));
                        return;
                    }
                }
            }
        } catch (Exception ignore) {
        }
        if (!hasVideoAttachment(raw)) return;
        String videoId = raw != null ? raw.optString("video_id", "").trim() : "";
        String videoUriStr = raw != null ? raw.optString("video_uri", "").trim() : "";
        String videoData = raw != null ? raw.optString("video", "").trim() : "";
        Bitmap thumb = WallUtils.decodeBitmapBase64(raw != null ? raw.optString("video_thumb", "") : "");
        if (thumb == null && item != null) thumb = item.bitmap("video_thumb");
        if (!TextUtils.isEmpty(videoId) && a != null && TextUtils.equals(videoId, a.videoMediaId)) {
            if (thumb == null) thumb = a.videoThumb;
            if (TextUtils.isEmpty(videoUriStr)) videoUriStr = a.storedVideoUri;
            if (TextUtils.isEmpty(videoData)) videoData = a.videoData;
        }
        Uri playable = VideoCacheManager.ensureVideoUri(getContext(), ownerKey, WallUtils.emptyToNull(videoUriStr), WallUtils.emptyToNull(videoData));
        if (playable == null && !TextUtils.isEmpty(videoId)) {
            Uri local = StreamMediaStore.createContentUri(context, videoId);
            if (local != null) playable = local;
            else if (!TextUtils.isEmpty(ownerKey)) {
                String host = ownerKey.contains(".") ? ownerKey : ownerKey + ".onion";
                try {
                    playable = Uri.parse("http://" + host + "/media/" + videoId);
                } catch (Exception ignore) {
                }
            }
        }
        if (playable == null && !TextUtils.isEmpty(videoUriStr)) {
            try {
                playable = Uri.parse(videoUriStr);
            } catch (Exception ignore) {
            }
        }
        if (playable == null) return;
        if (thumb == null && data != null && a != null && !TextUtils.isEmpty(videoId) && TextUtils.equals(videoId, a.videoMediaId)) {
            thumb = WallUtils.decodeBitmapBase64(data.optString("video_thumb", ""));
        }
        if (thumb != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(thumb);
        } else {
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setImageResource(R.drawable.ic_videocam);
        }
        iv.setVisibility(View.VISIBLE);
        if (h.imageContainer != null) h.imageContainer.setVisibility(View.VISIBLE);
        if (h.videoOverlay != null) h.videoOverlay.setVisibility(View.VISIBLE);
        final Uri finalVideoUri = playable;
        final Bitmap preview = thumb;
        iv.setContentDescription(activity.getString(R.string.wall_post_video_content_description));
        iv.setOnClickListener(v -> activity.lightboxVideo(finalVideoUri, preview));
    }

    private void bindPostAudio(PostViewHolder h, Item item, JSONObject raw, JSONObject data, PostAssets a, String ownerKey) {
        if (h.audioContainer != null) h.audioContainer.setVisibility(View.GONE);
        if (h.audioPlay != null) h.audioPlay.setOnClickListener(null);
        if (h.audioDuration != null) h.audioDuration.setText("");
        if (a == null) return;
        String audioMediaId = a.audioMediaId, audioData = a.audioData, audioUriStr = a.audioUri, audioMime = a.audioMime;
        long audioDuration = a.audioDurationMs;
        if (TextUtils.isEmpty(audioMediaId) && TextUtils.isEmpty(audioData) && TextUtils.isEmpty(audioUriStr))
            return;
        Uri audioUri = resolveAudioUri(ownerKey, audioMediaId, audioUriStr, audioData, audioMime);
        if (audioUri == null) return;
        if (h.audioContainer != null) h.audioContainer.setVisibility(View.VISIBLE);
        if (h.audioDuration != null) h.audioDuration.setText(formatAudioLabel(audioDuration));
        if (h.audioPlay != null) {
            final String postKey = item.key();
            updateAudioPlayButton(h.audioPlay, postKey);
            h.audioPlay.setOnClickListener(v -> togglePostAudio(postKey, audioUri));
        }
    }

    private void bindAvatar(PostViewHolder h, PostAssets a, String ownerKey) {
        Uri playable = VideoCacheManager.ensureVideoUri(getContext(), ownerKey, a.storedVideoUri, a.videoData);
        h.thumb.bind(a.photoThumb, a.videoThumb, playable != null ? playable.toString() : null);
        registerAvatar(h.thumb);
        h.thumblink.setOnClickListener(null);
        h.thumblink.setOnLongClickListener(null);
        h.thumblink.setClickable(false);
        h.thumblink.setLongClickable(false);
        h.thumb.setOnClickListener(null);
        h.thumb.setOnLongClickListener(null);
        h.thumb.setOnAvatarClickListener(content -> activity.showLightbox(content));
        h.thumb.setClickable(true);
        h.thumblink.setClickable(true);
    }

    private void bindPostTexts(PostViewHolder h, JSONObject data, String displayName, String postAddress, String wallOwner) {
        int primary = ThemeManager.getColor(context, com.google.android.material.R.attr.colorOnBackground), secondary = ThemeManager.getColor(context, R.attr.white_80);
        h.name.setTextColor(primary);
        h.text.setTextColor(primary);
        h.text.setLinkTextColor(ThemeManager.getColor(context, com.google.android.material.R.attr.colorOnPrimary));
        h.address.setTextColor(secondary);
        h.date.setTextColor(secondary);
        if (!TextUtils.isEmpty(displayName)) h.name.setText(displayName);
        h.address.setText(postAddress);
        h.text.setMovementMethod(LinkMovementMethod.getInstance());
        String textValue = data.optString("text");
        h.text.setText(onion.network.helpers.Utils.linkify(context, textValue));
        h.text.setVisibility(textValue.isEmpty() ? View.GONE : View.VISIBLE);
        h.date.setText(onion.network.helpers.Utils.formatDate(data.optString("date")));
        boolean isForeign = !TextUtils.isEmpty(postAddress) && !postAddress.equals(wallOwner);
        if (isForeign) {
            underline(h.address, true);
            underline(h.name, true);
            View.OnClickListener open = v -> getContext().startActivity(new Intent(getContext(), MainActivity.class).putExtra("address", postAddress));
            h.link.setOnClickListener(open);
            View.OnLongClickListener openLong = v -> {
                open.onClick(v);
                return true;
            };
            h.thumblink.setOnLongClickListener(openLong);
            h.thumblink.setLongClickable(true);
            h.thumb.setOnLongClickListener(openLong);
            h.thumb.setLongClickable(true);
        } else {
            underline(h.address, false);
            underline(h.name, false);
            h.link.setOnClickListener(null);
            h.thumblink.setOnLongClickListener(null);
            h.thumblink.setLongClickable(false);
            h.thumb.setOnLongClickListener(null);
            h.thumb.setLongClickable(false);
        }
    }

    private void bindPostActions(PostViewHolder h, Item item, JSONObject data, String wallOwner, String myAddress, String postAddress) {
        h.like.setOnClickListener(v -> activity.snack("Available soon"));
        h.comments.setOnClickListener(v -> activity.snack("Available soon"));
        boolean isMyWall = wallOwner.equals(myAddress);
        boolean isMyPost = myAddress.equals(postAddress);
        if (isMyWall) {
            h.delete.setVisibility(View.VISIBLE);
            h.delete.setOnClickListener(v -> DialogHelper.showConfirm(context, R.string.dialog_delete_post_title, R.string.dialog_delete_post_message, R.string.dialog_button_delete, () -> {
                ItemDatabase.getInstance(context).delete(item.type(), item.key());
                load();
            }, R.string.dialog_button_cancel, null));
        } else {
            h.delete.setVisibility(View.GONE);
            h.delete.setOnClickListener(null);
        }
        if (isMyWall && isMyPost) {
            h.edit.setVisibility(View.VISIBLE);
            h.edit.setOnClickListener(v -> editPost(item));
        } else {
            h.edit.setVisibility(View.GONE);
            h.edit.setOnClickListener(null);
        }
        boolean canShare = !isMyWall && !isMyPost && TextUtils.isEmpty(data.optString("access"));
        if (canShare) {
            h.share.setVisibility(View.VISIBLE);
            h.share.setOnClickListener(v -> activity.sharePost(data));
        } else {
            h.share.setVisibility(View.GONE);
            h.share.setOnClickListener(null);
        }
    }

    private void underline(TextView tv, boolean on) {
        tv.setPaintFlags(on ? (tv.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG) : (tv.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG));
    }

    private static boolean hasVideoAttachment(JSONObject raw) {
        return hasNonEmptyField(raw, "video_id", "video", "video_uri");
    }

    private static boolean hasNonEmptyField(JSONObject src, String... fields) {
        if (src == null || fields == null) return false;
        for (String f : fields) if (!TextUtils.isEmpty(src.optString(f, "").trim())) return true;
        return false;
    }

    public void registerAvatar(AvatarView view) {
        synchronized (activeAvatars) {
            for (int i = activeAvatars.size() - 1; i >= 0; i--) {
                AvatarView ex = activeAvatars.get(i).get();
                if (ex == null) activeAvatars.remove(i);
            }
            activeAvatars.add(new WeakReference<>(view));
        }
    }

    private PostAssets resolvePostAssets(Item item, JSONObject raw, JSONObject data, String wallOwner, String myAddress, String postAddress) {
        PostAssets a = new PostAssets();
        a.photoThumb = WallUtils.decodeBitmapBase64(raw.optString("thumb", ""));
        if (a.photoThumb == null) {
            a.photoThumb = item.bitmap("thumb");
            if (a.photoThumb == null)
                a.photoThumb = WallUtils.decodeBitmapBase64(data.optString("thumb", ""));
        }
        a.videoThumb = WallUtils.decodeBitmapBase64(raw.optString("video_thumb", ""));
        if (a.videoThumb == null) {
            a.videoThumb = item.bitmap("video_thumb");
            if (a.videoThumb == null)
                a.videoThumb = WallUtils.decodeBitmapBase64(data.optString("video_thumb", ""));
        }
        a.videoMediaId = WallUtils.firstNonEmpty(data.optString("video_id", "").trim(), raw.optString("video_id", "").trim());
        a.storedVideoUri = WallUtils.firstNonEmpty(data.optString("video_uri", "").trim(), raw.optString("video_uri", "").trim());
        a.videoData = WallUtils.firstNonEmpty(raw.optString("video", "").trim(), data.optString("video", "").trim());
        a.videoMime = WallUtils.firstNonEmpty(data.optString("video_mime", "").trim(), raw.optString("video_mime", "").trim());
        a.videoDurationMs = data.optLong("video_duration", raw.optLong("video_duration", 0L));
        a.audioMediaId = WallUtils.firstNonEmpty(data.optString("audio_id", "").trim(), raw.optString("audio_id", "").trim());
        a.audioUri = WallUtils.firstNonEmpty(data.optString("audio_uri", "").trim(), raw.optString("audio_uri", "").trim());
        a.audioData = WallUtils.firstNonEmpty(raw.optString("audio", "").trim(), data.optString("audio", "").trim());
        a.audioMime = WallUtils.firstNonEmpty(data.optString("audio_mime", "").trim(), raw.optString("audio_mime", "").trim());
        a.audioDurationMs = data.optLong("audio_duration", raw.optLong("audio_duration", 0L));
        a.displayName = WallUtils.firstNonEmpty(raw.optString("name", ""), data.optString("name", ""));
        if (!TextUtils.isEmpty(a.videoMediaId)) {
            Uri localUri = StreamMediaStore.createContentUri(context, a.videoMediaId);
            if (localUri != null) a.storedVideoUri = localUri.toString();
            else if (TextUtils.isEmpty(a.storedVideoUri))
                a.storedVideoUri = "/media/" + a.videoMediaId;
        }
        boolean belongsToFriend = !TextUtils.isEmpty(postAddress) && !postAddress.equals(myAddress);
        if (belongsToFriend) {
            ItemCache cache = ItemCache.getInstance(getContext());
            if (a.photoThumb == null) {
                ItemResult r = cache.get(postAddress, "thumb");
                if (r.size() > 0) a.photoThumb = r.one().bitmap("thumb");
            }
            if (a.videoThumb == null) {
                ItemResult r = cache.get(postAddress, "video_thumb");
                if (r.size() > 0) a.videoThumb = r.one().bitmap("video_thumb");
            }
            if (TextUtils.isEmpty(a.videoData)) {
                ItemResult r = cache.get(postAddress, "video");
                if (r.size() > 0) a.videoData = r.one().json().optString("video", "").trim();
            }
            if (TextUtils.isEmpty(a.audioData)) {
                ItemResult r = cache.get(postAddress, "audio");
                if (r.size() > 0) {
                    JSONObject j = r.one().json();
                    String b64 = j.optString("audio", "").trim();
                    if (!TextUtils.isEmpty(b64)) a.audioData = b64;
                    if (TextUtils.isEmpty(a.audioMime)) {
                        String m = j.optString("audio_mime", "");
                        if (!TextUtils.isEmpty(m)) a.audioMime = m;
                    }
                    if (a.audioDurationMs <= 0)
                        a.audioDurationMs = j.optLong("audio_duration", a.audioDurationMs);
                }
            }
            if (TextUtils.isEmpty(a.displayName)) {
                ItemResult r = cache.get(postAddress, "name");
                if (r.size() > 0) {
                    String n = r.one().json().optString("name", "");
                    if (!TextUtils.isEmpty(n)) a.displayName = n;
                }
            }
            if (a.photoThumb == null || TextUtils.isEmpty(a.displayName) || (TextUtils.isEmpty(a.videoData) && a.videoThumb == null) || TextUtils.isEmpty(a.audioData)) {
                Item friendItem = ItemDatabase.getInstance(getContext()).getByKey("friend", postAddress);
                if (friendItem != null) {
                    if (a.photoThumb == null) a.photoThumb = friendItem.bitmap("thumb");
                    if (a.videoThumb == null) a.videoThumb = friendItem.bitmap("video_thumb");
                    if (TextUtils.isEmpty(a.videoData))
                        a.videoData = friendItem.json().optString("video", "").trim();
                    if (TextUtils.isEmpty(a.audioData)) {
                        String s = friendItem.json().optString("audio", "").trim();
                        if (!TextUtils.isEmpty(s)) a.audioData = s;
                    }
                    if (TextUtils.isEmpty(a.audioMime)) {
                        String s = friendItem.json().optString("audio_mime", "");
                        if (!TextUtils.isEmpty(s)) a.audioMime = s;
                    }
                    if (a.audioDurationMs <= 0)
                        a.audioDurationMs = friendItem.json().optLong("audio_duration", a.audioDurationMs);
                    if (TextUtils.isEmpty(a.audioUri)) {
                        String s = friendItem.json().optString("audio_uri", "");
                        if (!TextUtils.isEmpty(s)) a.audioUri = s;
                    }
                    if (TextUtils.isEmpty(a.displayName)) {
                        String s = friendItem.json().optString("name", "");
                        if (!TextUtils.isEmpty(s)) a.displayName = s;
                    }
                }
            }
        }
        boolean isWallOwner = !TextUtils.isEmpty(postAddress) && postAddress.equals(wallOwner) && !postAddress.equals(myAddress);
        if (isWallOwner) {
            Item friendItem = ItemDatabase.getInstance(getContext()).getByKey("friend", postAddress);
            if (friendItem != null) {
                Bitmap ft = friendItem.bitmap("thumb");
                if (ft != null) a.photoThumb = ft;
                Bitmap vt = friendItem.bitmap("video_thumb");
                if (vt != null) a.videoThumb = vt;
                String fv = friendItem.json().optString("video", "").trim();
                if (!TextUtils.isEmpty(fv)) a.videoData = fv;
                if (TextUtils.isEmpty(a.audioData)) {
                    String fa = friendItem.json().optString("audio", "").trim();
                    if (!TextUtils.isEmpty(fa)) a.audioData = fa;
                }
                if (TextUtils.isEmpty(a.audioMime)) {
                    String m = friendItem.json().optString("audio_mime", "");
                    if (!TextUtils.isEmpty(m)) a.audioMime = m;
                }
                if (a.audioDurationMs <= 0)
                    a.audioDurationMs = friendItem.json().optLong("audio_duration", a.audioDurationMs);
                if (TextUtils.isEmpty(a.audioUri)) {
                    String u = friendItem.json().optString("audio_uri", "");
                    if (!TextUtils.isEmpty(u)) a.audioUri = u;
                }
                String fn = friendItem.json().optString("name", "");
                if (!TextUtils.isEmpty(fn)) a.displayName = fn;
            }
        }
        if (!TextUtils.isEmpty(a.storedVideoUri) && a.storedVideoUri.startsWith("/media/") && !TextUtils.isEmpty(postAddress)) {
            String host = postAddress.contains(".") ? postAddress : postAddress + ".onion";
            a.storedVideoUri = "http://" + host + a.storedVideoUri;
        }
        if (!TextUtils.isEmpty(a.audioUri) && a.audioUri.startsWith("/media/") && !TextUtils.isEmpty(postAddress)) {
            String host = postAddress.contains(".") ? postAddress : postAddress + ".onion";
            a.audioUri = "http://" + host + a.audioUri;
        }
        return a;
    }

    private String resolveOwnerKey(String postAddress, String wallOwner, String myAddress, Item item) {
        if (!TextUtils.isEmpty(postAddress)) return postAddress;
        if (!TextUtils.isEmpty(wallOwner) && !wallOwner.equals(myAddress)) return wallOwner;
        if (!TextUtils.isEmpty(myAddress)) return myAddress;
        return "post_" + item.key();
    }

    // ===== Audio =====
    private Uri resolveAudioUri(String ownerKey, @Nullable String mediaId, @Nullable String storedUri, @Nullable String audioData, @Nullable String audioMime) {
        Context ctx = getContext();
        if (ctx == null) return null;
        Uri local = MediaResolver.resolveMediaUri(ctx, mediaId);
        if (local != null) return local;
        Uri cached = AudioCacheManager.ensureAudioUri(ctx, ownerKey, storedUri, audioData, audioMime);
        if (cached != null) return cached;
        if (!TextUtils.isEmpty(storedUri)) try {
            return Uri.parse(storedUri);
        } catch (Exception ignore) {
        }
        return null;
    }

    private void togglePostAudio(String postKey, Uri audioUri) {
        Context ctx = getContext();
        if (ctx == null || audioUri == null) {
            MainActivity a = activity != null ? activity : MainActivity.getInstance();
            if (a != null) a.snack(a.getString(R.string.chat_attachment_unavailable));
            return;
        }
        try {
            if (audioPlayer != null && TextUtils.equals(playingAudioPostKey, postKey)) {
                if (audioPlayer.isPlaying()) audioPlayer.pause();
                else audioPlayer.start();
            } else {
                stopPostAudioPlayback(false);
                audioPlayer = new MediaPlayer();
                audioPlayer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                audioPlayer.setDataSource(ctx, audioUri);
                audioPlayer.setOnCompletionListener(mp -> stopPostAudioPlayback(true));
                audioPlayer.prepare();
                audioPlayer.start();
                playingAudioPostKey = postKey;
            }
            if (wallAdapter != null) wallAdapter.notifyDataSetChanged();
        } catch (Exception ex) {
            Log.e(TAG, "Unable to play post audio", ex);
            MainActivity a = activity != null ? activity : MainActivity.getInstance();
            if (a != null) a.snack(a.getString(R.string.chat_attachment_unavailable));
            stopPostAudioPlayback(true);
        }
    }

    private void updateAudioPlayButton(ImageButton btn, String postKey) {
        if (btn == null) return;
        boolean isCurrent = audioPlayer != null && TextUtils.equals(playingAudioPostKey, postKey);
        boolean playing = isCurrent && audioPlayer.isPlaying();
        MainActivity a = activity != null ? activity : MainActivity.getInstance();
        btn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (a != null)
            btn.setContentDescription(a.getString(playing ? R.string.chat_audio_pause : R.string.chat_audio_play));
    }

    private void stopPostAudioPlayback(boolean notify) {
        if (audioPlayer != null) {
            try {
                audioPlayer.stop();
            } catch (Exception ignore) {
            }
            try {
                audioPlayer.release();
            } catch (Exception ignore) {
            }
            audioPlayer = null;
        }
        playingAudioPostKey = null;
        if (notify && wallAdapter != null) wallAdapter.notifyDataSetChanged();
    }

    private String formatAudioLabel(long durationMs) {
        long total = durationMs > 0 ? Math.max(1, durationMs / 1000) : 1;
        long m = total / 60, s = total % 60;
        MainActivity a = activity != null ? activity : MainActivity.getInstance();
        String prefix = a != null ? a.getString(R.string.chat_attachment_voice_message) : "Voice message";
        return prefix + " • " + String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    public void refreshAppearance() {
        if (wallAdapter != null) wallAdapter.notifyDataSetChanged();
    }

    public void pauseAvatarVideos() {
        synchronized (activeAvatars) {
            for (int i = activeAvatars.size() - 1; i >= 0; i--) {
                AvatarView av = activeAvatars.get(i).get();
                if (av == null) activeAvatars.remove(i);
                else av.pause();
            }
        }
        stopPostAudioPlayback(false);
        if (wallAdapter != null) wallAdapter.setVideosPaused(true);
    }

    public void resumeAvatarVideos() {
        synchronized (activeAvatars) {
            for (int i = activeAvatars.size() - 1; i >= 0; i--) {
                AvatarView av = activeAvatars.get(i).get();
                if (av == null) activeAvatars.remove(i);
                else av.resume();
            }
        }
        if (wallAdapter != null) wallAdapter.setVideosPaused(false);
    }

    public Uri createPhotoOutputUri() throws IOException {
        File dir = new File(getContext().getExternalCacheDir(), "camera");
        if (!dir.exists() && !dir.mkdirs())
            throw new IOException("Unable to create directory for photo capture");
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".jpg";
        File file = new File(dir, name);
        if (!file.createNewFile()) throw new IOException("Unable to create photo file");
        return FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
    }

    private void deleteTempPhoto() {
        if (pendingPhotoUri == null) return;
        try {
            getContext().getContentResolver().delete(pendingPhotoUri, null, null);
        } catch (Exception ignored) {
        } finally {
            pendingPhotoUri = null;
        }
    }
}
