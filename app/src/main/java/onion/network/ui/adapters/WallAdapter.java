package onion.network.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import onion.network.R;
import onion.network.models.Item;
import onion.network.ui.adapters.viewholders.PostViewHolder;
import onion.network.ui.pages.WallPage;

public final class WallAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_POST = 0, TYPE_LOAD_MORE = 1, TYPE_EMPTY = 2;
    private final List<Item> adapterItems = new ArrayList<>();
    private final WallPage page;
    private boolean showLoadMore, showEmpty, videosPaused;

    public WallAdapter(WallPage page) {
        this.page = page;
    }

    public void submit(List<Item> src, boolean displayLoadMore, boolean displayEmpty) {
        adapterItems.clear();
        adapterItems.addAll(src);
        showEmpty = displayEmpty;
        showLoadMore = showEmpty ? false : displayLoadMore;
        notifyDataSetChanged();
    }

    public void setLoadMoreVisible(boolean v) {
        boolean norm = showEmpty ? false : v;
        if (showLoadMore != norm) {
            showLoadMore = norm;
            notifyDataSetChanged();
        }
    }

    public void setVideosPaused(boolean p) {
        if (videosPaused != p) {
            videosPaused = p;
            notifyDataSetChanged();
        }
    }

    @Override
    public int getItemCount() {
        if (showEmpty) return 1;
        int total = adapterItems.size();
        if (showLoadMore) total += 1;
        return total;
    }

    @Override
    public int getItemViewType(int position) {
        if (showEmpty) return TYPE_EMPTY;
        return position < adapterItems.size() ? TYPE_POST : TYPE_LOAD_MORE;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_POST)
            return new PostViewHolder(inf.inflate(R.layout.wall_item, parent, false));
        if (viewType == TYPE_LOAD_MORE)
            return new WallAdapter.LoadMoreViewHolder(inf.inflate(R.layout.wall_more, parent, false));
        return new WallAdapter.EmptyViewHolder(inf.inflate(R.layout.wall_empty, parent, false));
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof PostViewHolder) {
            Item it = adapterItems.get(position);
            PostViewHolder ph = (PostViewHolder) holder;
            page.bindPostView(ph, it, page.currentWallOwner, page.currentMyAddress);
            if (videosPaused) ph.thumb.pause();
            else ph.thumb.resume();
        } else if (holder instanceof WallAdapter.LoadMoreViewHolder) {
            ((WallAdapter.LoadMoreViewHolder) holder).bind();
        }
    }

    private final class LoadMoreViewHolder extends RecyclerView.ViewHolder {
        private final View button;

        LoadMoreViewHolder(View v) {
            super(v);
            button = v.findViewById(R.id.wallLoadMore);
            button.setOnClickListener(v1 -> page.loadMore());
            v.setOnClickListener(v12 -> page.loadMore());
        }

        void bind() {
            boolean enabled = page.nextMoreKey != null;
            button.setEnabled(enabled);
            itemView.setEnabled(enabled);
        }
    }

    private static final class EmptyViewHolder extends RecyclerView.ViewHolder {
        EmptyViewHolder(View v) {
            super(v);
        }
    }
}
