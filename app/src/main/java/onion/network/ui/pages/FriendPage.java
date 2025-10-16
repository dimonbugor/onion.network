

package onion.network.ui.pages;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import onion.network.helpers.DialogHelper;
import onion.network.helpers.ThemeManager;
import onion.network.helpers.UiCustomizationManager;
import onion.network.helpers.VideoCacheManager;
import onion.network.models.FriendTool;
import onion.network.models.Item;
import onion.network.models.ItemResult;
import onion.network.models.ItemTask;
import onion.network.ui.MainActivity;
import onion.network.R;
import onion.network.ui.views.AvatarView;

public class FriendPage extends BasePage {

    RecyclerView recyclerView;
    FriendAdapter friendAdapter;
    final List<Item> friends = new ArrayList<>();
    int count = 8;

    String smore;
    int imore;

    public FriendPage(MainActivity activity) {
        super(activity);
        activity.getLayoutInflater().inflate(R.layout.friend_page, this, true);
        recyclerView = findViewById(R.id.friendRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        friendAdapter = new FriendAdapter();
        recyclerView.setAdapter(friendAdapter);
    }

    @Override
    public String getTitle() {
        return "Friends";
    }

    @Override
    public int getIcon() {
        return R.drawable.ic_people;
    }

    @Override
    public void load() {
        load(0, "");
    }

    @Override
    public int getFab() {
        return R.id.friendFab;
    }

    @Override
    public void onFab() {
        activity.showAddFriend();
    }

    void load(final int startIndex, String startKey) {
        new ItemTask(getContext(), address, "friend", startKey, count) {

            @Override
            protected void onProgressUpdate(ItemResult... x) {
                renderFriends(startIndex, safeFirst(x), false);
            }

            @Override
            protected void onPostExecute(ItemResult itemResult) {
                renderFriends(startIndex, itemResult, true);
            }

            private ItemResult safeFirst(ItemResult[] results) {
                return results != null && results.length > 0 ? results[0] : null;
            }

        }.execute2();
    }

    public void refreshAppearance() {
        if (friendAdapter != null) {
            friendAdapter.notifyDataSetChanged();
        }
    }

    private void renderFriends(int insertIndex, ItemResult itemResult, boolean finished) {
        if (itemResult == null) {
            return;
        }

        int insertionPoint = Math.min(insertIndex, friends.size());
        if (insertionPoint < friends.size()) {
            friends.subList(insertionPoint, friends.size()).clear();
        }

        for (int idx = 0; idx < itemResult.size(); idx++) {
            Item friend = itemResult.at(idx);
            if (friend == null) continue;
            friends.add(friend);
        }

        findViewById(R.id.loading).setVisibility(itemResult.loading() ? View.VISIBLE : View.GONE);

        boolean showEmpty = insertionPoint == 0 && friends.isEmpty() && !itemResult.loading();
        boolean showLoadMore = updatePaginationControls(insertIndex, itemResult, finished);

        if (friendAdapter != null) {
            friendAdapter.submit(friends, showLoadMore, showEmpty);
        }
    }

    private void bindFriendItem(FriendAdapter.FriendViewHolder holder, Item item) {
        JSONObject data = item.json(context, address);

        final String friendAddress = data.optString("addr");
        String displayName = data.optString("name");

        if (displayName == null || displayName.isEmpty()) {
            displayName = "Anonymous";
        }

        holder.name.setText(displayName);
        holder.address.setText(friendAddress);

        holder.itemRoot.setOnClickListener(v ->
                getContext().startActivity(
                        new Intent(getContext(), MainActivity.class).putExtra("address", friendAddress)
                )
        );

        if (activity.address.isEmpty()) {
            holder.itemRoot.setOnLongClickListener(v -> {
                DialogHelper.showConfirm(
                        context,
                        R.string.dialog_delete_friend_title,
                        R.string.dialog_delete_friend_message,
                        R.string.dialog_button_delete,
                        () -> {
                            FriendTool.getInstance(context).unfriend(item.key());
                            load();
                        },
                        R.string.dialog_button_cancel,
                        null
                );
                return true;
            });
        } else {
            holder.itemRoot.setOnLongClickListener(null);
        }

        Bitmap photoThumb = item.bitmap("thumb");
        Bitmap videoThumb = item.bitmap("video_thumb");
        String storedVideoUri = data.optString("video_uri", "").trim();
        String videoData = data.optString("video", "").trim();
        Uri playableVideo = VideoCacheManager.ensureVideoUri(getContext(), friendAddress, storedVideoUri, videoData);
        holder.thumb.bind(photoThumb, videoThumb, playableVideo != null ? playableVideo.toString() : null);

        applyFriendItemStyle(holder.card);
    }

    private boolean updatePaginationControls(int insertIndex, ItemResult itemResult, boolean finished) {
        smore = finished ? itemResult.more() : null;
        imore = insertIndex + count;
        return smore != null;
    }

    private final class FriendAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_FRIEND = 0;
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
                return TYPE_FRIEND;
            }
            return TYPE_LOAD_MORE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_FRIEND) {
                View view = inflater.inflate(R.layout.friend_item, parent, false);
                return new FriendViewHolder(view);
            } else if (viewType == TYPE_LOAD_MORE) {
                View view = inflater.inflate(R.layout.friend_more, parent, false);
                return new LoadMoreViewHolder(view);
            } else {
                View view = inflater.inflate(R.layout.friend_empty, parent, false);
                return new EmptyViewHolder(view);
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof FriendViewHolder) {
                Item item = adapterItems.get(position);
                bindFriendItem((FriendViewHolder) holder, item);
            } else if (holder instanceof LoadMoreViewHolder) {
                ((LoadMoreViewHolder) holder).bind();
            }
        }

        private class LoadMoreViewHolder extends RecyclerView.ViewHolder {
            private final View button;

            LoadMoreViewHolder(View itemView) {
                super(itemView);
                button = itemView.findViewById(R.id.card);
                View.OnClickListener trigger = v -> loadMore();
                itemView.setOnClickListener(trigger);
                if (button != null) {
                    button.setOnClickListener(trigger);
                }
            }

            void bind() {
                boolean enabled = smore != null;
                itemView.setEnabled(enabled);
                if (button != null) {
                    button.setEnabled(enabled);
                }
            }
        }

        private class EmptyViewHolder extends RecyclerView.ViewHolder {
            EmptyViewHolder(View itemView) {
                super(itemView);
            }
        }

        private class FriendViewHolder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final View itemRoot;
            final TextView name;
            final TextView address;
            final AvatarView thumb;

            FriendViewHolder(View itemView) {
                super(itemView);
                card = (MaterialCardView) itemView;
                itemRoot = itemView.findViewById(R.id.item);
                name = itemView.findViewById(R.id.name);
                address = itemView.findViewById(R.id.address);
                thumb = itemView.findViewById(R.id.thumb);
            }
        }
    }

    private void applyFriendItemStyle(View view) {
        if (!(view instanceof MaterialCardView)) return;
        UiCustomizationManager.FriendCardConfig config = UiCustomizationManager.getFriendCardConfig(getContext());
        UiCustomizationManager.ColorPreset preset = UiCustomizationManager.getColorPreset(getContext());

        MaterialCardView card = (MaterialCardView) view;
        card.setRadius(config.cornerRadiusPx);
        card.setContentPadding(config.horizontalPaddingPx, config.verticalPaddingPx,
                config.horizontalPaddingPx, config.verticalPaddingPx);
        card.setStrokeWidth(UiCustomizationManager.dpToPx(getContext(), 1));
        card.setStrokeColor(preset.getAccentColor(getContext()));

        if (preset == UiCustomizationManager.ColorPreset.SYSTEM) {
            card.setCardBackgroundColor(ThemeManager.getColor(activity, com.google.android.material.R.attr.colorPrimaryContainer));
        } else {
            card.setCardBackgroundColor(preset.getSurfaceColor(getContext()));
        }

        View avatarView = card.findViewById(R.id.thumb);
        if (avatarView != null) {
            View avatarContainer = (View) avatarView.getParent();
            if (avatarContainer != null) {
                ViewGroup.LayoutParams params = avatarContainer.getLayoutParams();
                if (params != null) {
                    params.width = config.avatarSizePx;
                    params.height = config.avatarSizePx;
                    avatarContainer.setLayoutParams(params);
                }
            }
        }

        TextView name = card.findViewById(R.id.name);
        TextView address = card.findViewById(R.id.address);
        if (name != null) {
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.nameTextSizeSp);
        }
        if (address != null) {
            address.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.addressTextSizeSp);
        }

        if (name != null) {
            if (preset == UiCustomizationManager.ColorPreset.SYSTEM) {
                name.setTextColor(ThemeManager.getColor(activity, com.google.android.material.R.attr.colorOnBackground));
            } else {
                name.setTextColor(preset.getOnSurfaceColor(getContext()));
            }
        }
        if (address != null) {
            if (preset == UiCustomizationManager.ColorPreset.SYSTEM) {
                address.setTextColor(ThemeManager.getColor(activity, R.attr.white_80));
            } else {
                int onSurface = preset.getOnSurfaceColor(getContext());
                address.setTextColor(ColorUtils.setAlphaComponent(onSurface, 160));
            }
        }
    }

    void loadMore() {
        if (smore == null) return;
        String smore2 = smore;
        smore = null;
        if (friendAdapter != null) {
            friendAdapter.setLoadMoreVisible(false);
        }
        load(imore, smore2);
    }

}
