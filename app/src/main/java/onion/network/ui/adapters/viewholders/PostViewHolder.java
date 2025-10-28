package onion.network.ui.adapters.viewholders;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;

import onion.network.R;
import onion.network.ui.views.AvatarView;

public final class PostViewHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
    public final FrameLayout link;
    public final View thumblink;
    public final TextView address;
    public final TextView name;
    public final TextView date;
    public final TextView text;
    public final ImageView like;
    public final ImageView comments;
    public final ImageView share;
    public final ImageView delete;
    public final ImageView edit;
    public final AvatarView thumb;
    public final FrameLayout imageContainer;
    public final ImageView image;
    public final ImageView videoOverlay;
    public final MaterialCardView card;
    public final LinearLayout container;
    public final LinearLayout headerRow;
    public final FrameLayout avatarContainer;
    public final MaterialCardView avatarCard;
    public final LinearLayout actionRow;
    public final LinearLayout audioContainer;
    public final ImageButton audioPlay;
    public final TextView audioDuration;

    public PostViewHolder(View root) {
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
        videoOverlay = root.findViewById(R.id.videoOverlay);
        card = root.findViewById(R.id.card);
        container = root.findViewById(R.id.postContent);
        headerRow = root.findViewById(R.id.headerRow);
        avatarContainer = root.findViewById(R.id.avatarContainer);
        avatarCard = root.findViewById(R.id.avatarCard);
        actionRow = root.findViewById(R.id.actionRow);
        audioContainer = root.findViewById(R.id.audioContainer);
        audioPlay = root.findViewById(R.id.audioPlay);
        audioDuration = root.findViewById(R.id.audioDuration);
    }
}
