package onion.network.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.List;

import onion.network.R;
import onion.network.models.FriendPreview;
import onion.network.ui.pages.WallPage;
import onion.network.ui.views.AvatarView;

public final class FriendPreviewAdapter extends RecyclerView.Adapter<FriendPreviewAdapter.FriendPreviewHolder> {
    private final WallPage page;
    private final List<FriendPreview> data;

    public FriendPreviewAdapter(WallPage page, List<FriendPreview> data) {
        this.page = page;
        this.data = data;
    }

    @Override
    public FriendPreviewAdapter.FriendPreviewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.wall_friend_preview_item, parent, false);
        return new FriendPreviewAdapter.FriendPreviewHolder(v);
    }

    @Override
    public void onBindViewHolder(FriendPreviewAdapter.FriendPreviewHolder h, int pos) {
        FriendPreview p = (pos >= 0 && pos < data.size()) ? data.get(pos) : null;
        page.bindFriendPreview(h, p);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    public static final class FriendPreviewHolder extends RecyclerView.ViewHolder {
        public final MaterialCardView card;
        public final MaterialCardView avatarCard;
        public final AvatarView avatar;
        public final android.widget.TextView name;
        public final android.widget.TextView date;
        public final android.widget.TextView postText;
        public final android.widget.ImageView postImage;
        public final View imageContainer;

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
