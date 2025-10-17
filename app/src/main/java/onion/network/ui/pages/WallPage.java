

package onion.network.ui.pages;

import static onion.network.helpers.Const.REQUEST_PICK_IMAGE_POST;
import static onion.network.helpers.Const.REQUEST_TAKE_PHOTO_POST;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;

import onion.network.helpers.DialogHelper;
import onion.network.helpers.PermissionHelper;
import onion.network.models.Item;
import onion.network.models.ItemTask;
import onion.network.R;
import onion.network.TorManager;
import onion.network.databases.ItemDatabase;
import onion.network.helpers.ThemeManager;
import onion.network.helpers.Utils;
import onion.network.helpers.VideoCacheManager;
import onion.network.models.ItemResult;
import onion.network.cashes.ItemCache;
import onion.network.ui.MainActivity;
import onion.network.ui.views.AvatarView;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class WallPage extends BasePage {

    RecyclerView recyclerView;
    WallAdapter wallAdapter;
    final List<Item> posts = new ArrayList<>();
    int count = 5;
    String TAG = "WallPage";
    String postEditText = null;

    String smore;
    int imore;
    String currentWallOwner = "";
    String currentMyAddress = "";
    private Uri pendingPhotoUri;


    public WallPage(MainActivity activity) {
        super(activity);
        activity.getLayoutInflater().inflate(R.layout.wall_page, this, true);
        recyclerView = findViewById(R.id.wallRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        wallAdapter = new WallAdapter();
        recyclerView.setAdapter(wallAdapter);
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
        load(0, "");
    }

    @Override
    public int getFab() {
        return R.id.wallFab;
    }

    @Override
    public void onFab() {
        writePost(null, null);
    }

    void getData(JSONObject o, View dlg) {
        try {
            o.put("text", ((EditText) dlg.findViewById(R.id.text)).getText().toString());
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
    }

    void doPostPublish(Item item, String text, Bitmap bitmap, View dialogView) {
        if (item != null) {
            JSONObject o = item.json();
            getData(o, dialogView);
            Item item2 = new Item(item.type(), item.key(), item.index(), o);
            ItemDatabase.getInstance(getContext()).put(item2);
            activity.load();
        } else {
            JSONObject data = new JSONObject();
            getData(data, dialogView);
            try {
                data.put("date", "" + System.currentTimeMillis());
                if (bitmap != null) {
                    data.put("img", Utils.encodeImage(bitmap));
                }
            } catch (JSONException ex) {
                throw new RuntimeException(ex);
            }
            activity.publishPost(data);
            activity.load();
        }
    }

    @Override
    public void onResume() {
        postEditText = null;
    }

    void doPost(final Item item, final String text, Bitmap bitmap) {

        final View dialogView = activity.getLayoutInflater().inflate(R.layout.wall_dialog, null);
        final EditText textEdit = (EditText) dialogView.findViewById(R.id.text);

        if (item != null) {
            textEdit.setText(item.json().optString("text"));
            bitmap = item.bitmap("img");
        }

        if (text != null) {
            textEdit.setText(text);
        }

        if (bitmap != null) {
            ((ImageView) dialogView.findViewById(R.id.image)).setImageBitmap(bitmap);
        } else {
            dialogView.findViewById(R.id.image).setVisibility(View.GONE);
        }

        AlertDialog.Builder b = DialogHelper.themedBuilder(activity).setView(dialogView);

        final Bitmap bmp = bitmap;

        if (item != null) {
            b.setTitle("Edit Post");
        } else {
            b.setTitle("Write Post");
        }

        b.setCancelable(true);

        final Dialog d = b.create();

        dialogView.findViewById(R.id.publish).setOnClickListener(v -> {
            doPostPublish(item, text, bmp, dialogView);
            d.cancel();
        });

        if (item == null) {
            dialogView.findViewById(R.id.take_photo).setOnClickListener(v -> {
                postEditText = textEdit.getText().toString();
                PermissionHelper.runWithPermissions(activity,
                        EnumSet.of(PermissionHelper.PermissionRequest.CAMERA),
                        () -> {
                            try {
                                pendingPhotoUri = createPhotoOutputUri();
                            } catch (IOException ex) {
                                pendingPhotoUri = null;
                                activity.snack("Unable to create photo file");
                                return;
                            }

                            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
                            takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            d.cancel();
                            activity.startActivityForResult(takePictureIntent, REQUEST_TAKE_PHOTO_POST);
                        },
                        () -> activity.snack("Camera permission required"));
            });
            dialogView.findViewById(R.id.add_image).setOnClickListener(v -> {
                postEditText = textEdit.getText().toString();
                PermissionHelper.runWithPermissions(activity,
                        EnumSet.of(PermissionHelper.PermissionRequest.MEDIA),
                        () -> {
                            d.cancel();
                            startImageChooser(REQUEST_PICK_IMAGE_POST);
                        },
                        () -> activity.snack("Storage permission required"));
            });
        } else {
            dialogView.findViewById(R.id.take_photo).setVisibility(View.GONE);
            dialogView.findViewById(R.id.add_image).setVisibility(View.GONE);
        }

        d.show();

    }

    void editPost(final Item item) {

        doPost(item, null, null);

    }

    public void writePost(final String text, final Bitmap bitmap) {

        doPost(null, text, bitmap);

    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            deleteTempPhoto();
            return;
        }

        Bitmap bmp = null;

        if (requestCode == REQUEST_PICK_IMAGE_POST) {
            Uri uri = data.getData();
            bmp = getActivityResultBitmap(data);
            bmp = fixImageOrientation(bmp, uri);
        }

        if (requestCode == REQUEST_TAKE_PHOTO_POST) {
            Uri uri = pendingPhotoUri;
            if (uri != null) {
//                bmp = decodeCapturedPhoto(uri);
                bmp = fixImageOrientation(bmp, uri);
            } else if (data != null && data.getExtras() != null) {
                bmp = (Bitmap) data.getExtras().get("data");
            }
        }

        if (bmp == null) {
            deleteTempPhoto();
            return;
        }

        int maxdim = 1080; // 🔼 Підвищено до 1080 для кращої якості

        if (bmp.getWidth() >= bmp.getHeight() && bmp.getWidth() > maxdim) {
            bmp = Bitmap.createScaledBitmap(
                    bmp,
                    maxdim,
                    (int) ((double) bmp.getHeight() * maxdim / bmp.getWidth()),
                    true
            );
        } else if (bmp.getHeight() > maxdim) {
            bmp = Bitmap.createScaledBitmap(
                    bmp,
                    (int) ((double) bmp.getWidth() * maxdim / bmp.getHeight()),
                    maxdim,
                    true
            );
        }

        writePost(postEditText, bmp);
        postEditText = null;
        deleteTempPhoto();
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

    private void bindPostView(PostViewHolder holder, Item item, String wallOwner, String myAddress) {
        JSONObject rawData = parseJsonSafe(item.text());
        JSONObject data = item.json(getContext(), address);

        bindInlineImage(holder.image, data);

        String postAddress = firstNonEmpty(rawData.optString("addr"), data.optString("addr"));
        PostAssets assets = resolvePostAssets(item, rawData, data, wallOwner, myAddress, postAddress);
        String ownerKey = resolveOwnerKey(postAddress, wallOwner, myAddress, item);
        bindAvatar(holder.thumb, assets, ownerKey);

        bindPostTexts(holder, data, assets.displayName, postAddress, wallOwner);
        bindPostActions(holder, item, data, wallOwner, myAddress, postAddress);
    }

    private void bindInlineImage(ImageView imageView, JSONObject data) {
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
            imageView.setOnClickListener(v -> activity.lightbox(bitmap));
        } catch (Exception ex) {
            ex.printStackTrace();
            imageView.setVisibility(View.GONE);
        }
    }

    private void bindAvatar(AvatarView avatarView, PostAssets assets, String ownerKey) {
        Uri playableVideo = VideoCacheManager.ensureVideoUri(
                getContext(),
                ownerKey,
                assets.storedVideoUri,
                assets.videoData
        );
        avatarView.bind(assets.photoThumb, assets.videoThumb, playableVideo != null ? playableVideo.toString() : null);
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
            holder.thumblink.setOnClickListener(openProfile);
        } else {
            holder.address.setPaintFlags(holder.address.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
            holder.name.setPaintFlags(holder.name.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
            holder.link.setOnClickListener(null);
            holder.thumblink.setOnClickListener(null);
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

    private final class WallAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_POST = 0;
        private static final int TYPE_LOAD_MORE = 1;
        private static final int TYPE_EMPTY = 2;

        private final List<Item> adapterItems = new ArrayList<>();
        private boolean showLoadMore;
        private boolean showEmpty;

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
                bindPostView((PostViewHolder) holder, item, currentWallOwner, currentMyAddress);
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

    private static final class PostViewHolder extends RecyclerView.ViewHolder {
        final View link;
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
        final ImageView image;

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
            image = root.findViewById(R.id.image);
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
