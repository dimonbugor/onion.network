

package onion.network.pages;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import onion.network.R;
import onion.network.TorManager;
import onion.network.cashes.ItemCache;
import onion.network.clients.ChatClient;
import onion.network.databases.ChatDatabase;
import onion.network.servers.ChatServer;
import onion.network.ui.MainActivity;
import onion.network.views.AvatarView;

public class ConversationPage extends BasePage implements ChatClient.OnMessageSentListener, ChatServer.OnMessageReceivedListener {

    ChatServer chatServer;
    ChatClient chatClient;
    ConversationAdapter adapter;
    RecyclerView recycler;
    View empty;
    TorManager torManager;
    Cursor cursor;

    public ConversationPage(MainActivity activity) {
        super(activity);

        torManager = TorManager.getInstance(activity);

        chatServer = ChatServer.getInstance(activity);
        chatClient = ChatClient.getInstance(activity);

        inflate(activity, R.layout.conversation_page, this);

        recycler = (RecyclerView) findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new ConversationAdapter();
        recycler.setAdapter(adapter);

        empty = findViewById(R.id.empty);
    }

    @Override
    public String getPageIDString() {
        return "chat";
    }

    @Override
    public void onResume() {
        chatServer.addOnMessageReceivedListener(this);
        chatClient.addOnMessageSentListener(this);
    }

    @Override
    public void onPause() {
        chatServer.removeOnMessageReceivedListener(this);
        chatClient.removeOnMessageSentListener(this);
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
    public String getTitle() {
        return "Conversations";
    }

    @Override
    public int getIcon() {
        return R.drawable.ic_question_answer;
    }

    @Override
    public void load() {
        if (cursor != null) {
            cursor.close();
            cursor = null;
        }

        cursor = ChatDatabase.getInstance(activity).getConversations();

        adapter.notifyDataSetChanged();

        empty.setVisibility(cursor.getCount() <= 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public String getBadge() {
        int n = ChatDatabase.getInstance(context).getIncomingConversationCount();
        if (n <= 0) return "";
        if (n >= 10) return "X";
        return "" + n;
    }

    class ConversationHolder extends RecyclerView.ViewHolder {
        public TextView name, address, message;
        public AvatarView thumb;
        public ImageView direction;
        public View data;

        public ConversationHolder(View v) {
            super(v);
            name = (TextView) v.findViewById(R.id.name);
            address = (TextView) v.findViewById(R.id.address);
            message = (TextView) v.findViewById(R.id.message);
            thumb = (AvatarView) v.findViewById(R.id.thumb);
            direction = (ImageView) v.findViewById(R.id.direction);
            data = v.findViewById(R.id.data);
        }
    }

    class ConversationAdapter extends RecyclerView.Adapter<ConversationHolder> {

        @Override
        public ConversationHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ConversationHolder(activity.getLayoutInflater().inflate(R.layout.conversation_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ConversationHolder holder, int position) {
            if (cursor == null) return;

            cursor.moveToFirst();
            cursor.moveToPosition(position);

            boolean incoming = cursor.getInt(cursor.getColumnIndex("incoming")) != 0;
            String content = cursor.getString(cursor.getColumnIndex("content"));
            String sender = cursor.getString(cursor.getColumnIndex("sender"));
            String receiver = cursor.getString(cursor.getColumnIndex("receiver"));
            String myid = torManager.getID();
            final String remoteAddress = myid.equals(sender) ? receiver : sender;

            holder.message.setText(content);
            holder.address.setText(remoteAddress);

            ItemCache cache = ItemCache.getInstance(activity);
            Bitmap photoThumb = cache.get(remoteAddress, "thumb").one().bitmap("thumb");
            Bitmap videoThumb = cache.get(remoteAddress, "video_thumb").one().bitmap("video_thumb");
            String videoUri = cache.get(remoteAddress, "video").one().json().optString("video", "").trim();
            holder.thumb.bind(photoThumb, videoThumb, videoUri.isEmpty() ? null : videoUri);

            holder.name.setText(ItemCache.getInstance(activity).get(remoteAddress, "name").one().json().optString("name", "Anonymous"));

            holder.itemView.setClickable(true);

            holder.itemView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    activity.startActivity(new Intent(activity, MainActivity.class).putExtra("address", remoteAddress).putExtra("page", "chat"));
                }
            });

            holder.direction.setImageResource(myid.equals(sender) ? R.drawable.ic_call_made : R.drawable.ic_call_received);

            holder.data.setAlpha(incoming ? 1.0f : 0.4f);
        }

        @Override
        public int getItemCount() {
            return cursor != null ? cursor.getCount() : 0;
        }

        @Override
        public void onViewRecycled(ConversationHolder holder) {
            holder.thumb.release();
            super.onViewRecycled(holder);
        }

    }
}
