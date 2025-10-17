

package onion.network.ui.pages;

import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import onion.network.clients.ChatClient;
import onion.network.databases.ChatDatabase;
import onion.network.helpers.ThemeManager;
import onion.network.helpers.UiCustomizationManager;
import onion.network.models.Item;
import onion.network.R;
import onion.network.TorManager;
import onion.network.helpers.Utils;
import onion.network.servers.ChatServer;
import onion.network.services.UpdateScheduler;
import onion.network.settings.Settings;
import onion.network.ui.MainActivity;

public class ChatPage extends BasePage
        implements ChatClient.OnMessageSentListener, ChatServer.OnMessageReceivedListener {

    String TAG = "chat";
    ChatAdapter adapter;
    ChatDatabase chatDatabase;
    TorManager torManager;
    Cursor cursor;
    RecyclerView recycler;
    ChatServer chatServer;
    ChatClient chatClient;
    Timer timer;
    long idLastLast = -1;
    Item nameItem = new Item();
    private TextInputLayout textInputLayout;
    private TextInputEditText editMessageView;
    private ImageButton microButton;
    private ImageButton sendButton;

    public ChatPage(final MainActivity activity) {
        super(activity);

        chatDatabase = ChatDatabase.getInstance(activity);
        chatServer = ChatServer.getInstance(activity);
        chatClient = ChatClient.getInstance(activity);
        torManager = TorManager.getInstance(activity);

        inflate(activity, R.layout.chat_page, this);

        recycler = (RecyclerView) findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new ChatAdapter();
        recycler.setAdapter(adapter);

        textInputLayout = findViewById(R.id.text_input_layout);
        editMessageView = findViewById(R.id.editmessage);
        microButton = findViewById(R.id.micro);
        sendButton = findViewById(R.id.send);

        if (microButton != null) {
            microButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view){
                activity.snack("Available soon");
            }
        });
        }

        if (sendButton != null) {
            sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String sender = torManager.getID();
                if (sender == null || sender.trim().equals("")) {
                    sendPendingAndUpdate();
                    Log.i(TAG, "no sender id");
                    return;
                }

                String message = editMessageView != null ? editMessageView.getText().toString() : "";
                message = message.trim();
                if (message.equals("")) return;

                Log.i(TAG, "sender " + sender);
                Log.i(TAG, "address " + address);
                Log.i(TAG, "message " + message);
                chatDatabase.addMessage(sender, address, message, System.currentTimeMillis(), false, true);
                Log.i(TAG, "sent");

                if (editMessageView != null) {
                    editMessageView.setText("");
                }

                sendPendingAndUpdate();

                recycler.smoothScrollToPosition(Math.max(0, cursor.getCount() - 1));

                UpdateScheduler.getInstance(context).put(address);
            }
        });
        }

        //load();

        sendPendingAndUpdate();
        applyUiCustomizationFromHost();
    }

    public void applyUiCustomizationFromHost() {
        UiCustomizationManager.ChatComposerConfig config = UiCustomizationManager.getChatComposerConfig(context);
        UiCustomizationManager.ColorPreset preset = UiCustomizationManager.getColorPreset(context);

        if (textInputLayout != null) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) textInputLayout.getLayoutParams();
            if (lp != null) {
                lp.setMargins(config.marginStartPx, lp.topMargin, config.marginEndPx, config.marginBottomPx);
                lp.height = config.heightPx;
                textInputLayout.setLayoutParams(lp);
            }
        }

        if (editMessageView != null) {
            editMessageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.textSizeSp);
            editMessageView.setPadding(config.paddingHorizontalPx, editMessageView.getPaddingTop(),
                    config.paddingHorizontalPx, editMessageView.getPaddingBottom());

            GradientDrawable background = null;
            if (editMessageView.getBackground() instanceof GradientDrawable) {
                background = (GradientDrawable) editMessageView.getBackground().mutate();
            }
            if (background != null) {
                background.setColor(preset.getSurfaceColor(context));
                background.setStroke(UiCustomizationManager.dpToPx(context, 1), preset.getAccentColor(context));
            }
            int onSurface = preset.getOnSurfaceColor(context);
            editMessageView.setTextColor(onSurface);
            editMessageView.setHintTextColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 160)));
        }

        ColorStateList iconTint = ColorStateList.valueOf(preset.getOnSurfaceColor(context));
        if (microButton != null) {
            microButton.setImageTintList(iconTint);
        }
        if (sendButton != null) {
            sendButton.setImageTintList(iconTint);
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    void log(String s) {
        Log.i(TAG, s);
    }

    @Override
    public void onNameItem(Item item) {
        nameItem = item;
        load();

        findViewById(R.id.offline).setVisibility(!activity.nameItemResult.ok() && !activity.nameItemResult.loading() ? View.VISIBLE : View.GONE);
        findViewById(R.id.loading).setVisibility(activity.nameItemResult.loading() ? View.VISIBLE : View.GONE);
    }

    @Override
    public String getTitle() {
        return "Chat";
    }

    @Override
    public int getIcon() {
        return R.drawable.ic_question_answer;
        //return R.drawable.ic_mail_outline_white_36dp;
    }

    void sendPendingAndUpdate() {

        new Thread() {
            @Override
            public void run() {
                sendUnsent();
                post(new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                });
            }
        }.start();

        load();

    }

    synchronized void sendUnsent() {
        try {
            chatClient.sendUnsent(address);
        } catch (IOException e) {
            log(e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        applyUiCustomizationFromHost();
        chatServer.addOnMessageReceivedListener(this);
        chatClient.addOnMessageSentListener(this);
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                log("send unsent");
                sendUnsent();
                //RequestTool.getInstance(context).
            }
        }, 0, 1000 * 60);
    }

    @Override
    public void onPause() {
        timer.cancel();
        timer.purge();
        timer = null;
        chatServer.removeOnMessageReceivedListener(this);
        chatClient.removeOnMessageSentListener(this);
        super.onPause();
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
    public void onTabSelected() {
        log("onTabSelected");
        activity.toggleChatMainMenu();
        if (isPageShown()) {
            load();
        }
    }

    void markReadIfVisible() {
        log("markReadIfVisible");
        if (isPageShown()) {
            log("markRead");
            chatDatabase.markRead(address);
        }
    }

    @Override
    public String getBadge() {
        int n = chatDatabase.getIncomingMessageCount(activity.address);
        if (n <= 0) return "";
        if (n >= 10) return "N";
        return "" + n;
    }

    @Override
    public void load() {

        log("load");

        boolean nochat = nameItem.json().optBoolean("nochat");
        findViewById(R.id.no_msg).setVisibility(nochat ? View.VISIBLE : View.GONE);

        String acceptmessages = Settings.getPrefs(context).getString("acceptmessages", "");
        findViewById(R.id.msg_from_none).setVisibility(!nochat && "none".equals(acceptmessages) ? View.VISIBLE : View.GONE);
        findViewById(R.id.msg_from_friends).setVisibility(!nochat && "friends".equals(acceptmessages) && !itemDatabase.hasKey("friend", address) ? View.VISIBLE : View.GONE);

        Cursor oldCursor = cursor;

        markReadIfVisible();

        cursor = chatDatabase.getMessages(address);
        if (oldCursor != null) {
            oldCursor.close();
        }
        adapter.notifyDataSetChanged();

        cursor.moveToLast();
        long idLast = -1;
        int i = cursor.getColumnIndex("_id");
        if (i >= 0 && cursor.getCount() > 0) {
            idLast = cursor.getLong(i);
        }
        if (idLast != idLastLast) {
            idLastLast = idLast;
            if (oldCursor == null || oldCursor.getCount() == 0)
                recycler.scrollToPosition(Math.max(0, cursor.getCount() - 1));
            else
                recycler.smoothScrollToPosition(Math.max(0, cursor.getCount() - 1));
        }

        activity.initTabs();

    }

    @Override
    public String getPageIDString() {
        return "chat";
    }

    class ChatHolder extends RecyclerView.ViewHolder {
        public TextView message, time, status;
        public View left, right;
        public MaterialCardView card;
        public View abort;
        public LinearLayout cardContent;
        public LinearLayout metaRow;

        public ChatHolder(View v) {
            super(v);
            message = (TextView) v.findViewById(R.id.message);
            time = (TextView) v.findViewById(R.id.time);
            status = (TextView) v.findViewById(R.id.status);
            left = v.findViewById(R.id.left);
            right = v.findViewById(R.id.right);
            card = (MaterialCardView) v.findViewById(R.id.card);
            abort = v.findViewById(R.id.abort);
            cardContent = v.findViewById(R.id.cardContent);
            metaRow = v.findViewById(R.id.metaRow);
        }
    }

    class ChatAdapter extends RecyclerView.Adapter<ChatHolder> {

        @Override
        public ChatHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ChatHolder(activity.getLayoutInflater().inflate(R.layout.chat_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ChatHolder holder, int position) {
            if (cursor == null) return;

            cursor.moveToFirst();
            cursor.moveToPosition(position);

            final long id = cursor.getLong(cursor.getColumnIndex("_id"));
            String content = cursor.getString(cursor.getColumnIndex("content"));
            String sender = cursor.getString(cursor.getColumnIndex("sender"));
            String time = Utils.formatDate(cursor.getString(cursor.getColumnIndex("time")));
            boolean pending = cursor.getInt(cursor.getColumnIndex("outgoing")) > 0;
            boolean tx = sender.equals(torManager.getID());

            if (sender.equals(torManager.getID())) sender = "You";

            if (tx) {
                holder.card.setBackground(
                        holder.card.getContext().getResources().getDrawable(R.drawable.chat_item_background));
                holder.left.setVisibility(View.VISIBLE);
                holder.right.setVisibility(View.GONE);
            } else {
                holder.card.setBackground(
                        holder.card.getContext().getResources().getDrawable(R.drawable.chat_my_item_background));
                holder.left.setVisibility(View.GONE);
                holder.right.setVisibility(View.VISIBLE);
            }

            if (pending)
                holder.card.setCardElevation(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics()));
            else
                holder.card.setCardElevation(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, getResources().getDisplayMetrics()));

            String status = "";
            if (sender.equals(address)) {
                if (activity.name.isEmpty())
                    status = address;
                else
                    status = activity.name;
            } else {
                if (pending) {
                    status = "\u2714";
                } else {
                    status = "\u2714\u2714";
                }
            }


            if (pending) {
                holder.abort.setVisibility(View.VISIBLE);
                holder.abort.setClickable(true);
                holder.abort.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean ok = chatDatabase.abortPendingMessage(id);
                        load();
                        Toast.makeText(activity, ok ? "Pending message aborted." : "Error: Message already sent.", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                holder.abort.setVisibility(View.GONE);
                holder.abort.setClickable(false);
                holder.abort.setOnClickListener(null);
            }

            int color = pending ? ThemeManager.getColor(activity, R.attr.white_50)
                    : ThemeManager.getColor(activity, R.attr.white_80);
            holder.time.setTextColor(color);
            holder.status.setTextColor(color);

            holder.message.setMovementMethod(LinkMovementMethod.getInstance());
            holder.message.setText(Utils.linkify(context, content));

            applyMessageStyle(holder, tx);

            holder.time.setText(time);

            holder.status.setText(status);
        }

        @Override
        public int getItemCount() {
            return cursor != null ? cursor.getCount() : 0;
        }

        private void applyMessageStyle(ChatHolder holder, boolean outgoing) {
            UiCustomizationManager.ChatComposerConfig config = UiCustomizationManager.getChatComposerConfig(context);

            holder.message.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.messageTextSizeSp);
            holder.message.setLineSpacing(0f, config.messageLineSpacingMultiplier);

            holder.status.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.metadataTextSizeSp);
            holder.time.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.metadataTextSizeSp);

            holder.status.setGravity(config.metadataAlignStart ? Gravity.START : Gravity.BOTTOM);
            holder.time.setGravity(config.metadataAlignStart ? Gravity.START : Gravity.END | Gravity.BOTTOM);

            if (holder.cardContent != null) {
                holder.cardContent.setPadding(
                        config.bubblePaddingHorizontalPx,
                        config.bubblePaddingVerticalPx,
                        config.bubblePaddingHorizontalPx,
                        config.bubblePaddingVerticalPx);
            }

            if (holder.metaRow != null) {
                holder.metaRow.setOrientation(config.metadataStacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                holder.metaRow.setGravity(config.metadataAlignStart ? Gravity.START : Gravity.BOTTOM);

                if (config.metadataStacked) {
                    holder.metaRow.setPadding(
                            config.bubblePaddingHorizontalPx,
                            config.bubblePaddingVerticalPx,
                            config.bubblePaddingHorizontalPx,
                            config.bubblePaddingVerticalPx);
                } else {
                    holder.metaRow.setPadding(
                            config.bubblePaddingHorizontalPx,
                            config.bubblePaddingVerticalPx / 2,
                            config.bubblePaddingHorizontalPx,
                            config.bubblePaddingVerticalPx / 2);
                }

                LinearLayout.LayoutParams statusLp;
                LinearLayout.LayoutParams timeLp;

                if (config.metadataStacked) {
                    statusLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    statusLp.gravity = Gravity.START;

                    timeLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    timeLp.gravity = Gravity.START;
                    timeLp.topMargin = config.metadataSpacingVerticalPx;
                } else {
                    statusLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    statusLp.gravity = config.metadataAlignStart ? Gravity.START : Gravity.BOTTOM;

                    timeLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    timeLp.gravity = config.metadataAlignStart ? Gravity.START : Gravity.END | Gravity.BOTTOM;
                }

                holder.status.setLayoutParams(statusLp);
                holder.time.setLayoutParams(timeLp);
            }

            float bubbleRadius = UiCustomizationManager.resolveCornerRadiusPx(context, config.bubbleCornerRadiusPx);
            holder.card.setRadius(bubbleRadius);
            updateBubbleCorners(holder, outgoing, bubbleRadius);
        }

        private void updateBubbleCorners(ChatHolder holder, boolean outgoing,
                                         float resolvedRadius) {
            if (holder.card == null) return;
            if (!(holder.card.getBackground() instanceof GradientDrawable)) return;
            GradientDrawable drawable = (GradientDrawable) holder.card.getBackground().mutate();
            float[] radii;
            if (outgoing) {
                radii = new float[]{resolvedRadius, resolvedRadius, resolvedRadius, resolvedRadius,
                        0f, 0f, resolvedRadius, resolvedRadius};
            } else {
                radii = new float[]{resolvedRadius, resolvedRadius, resolvedRadius, resolvedRadius,
                        resolvedRadius, resolvedRadius, 0f, 0f};
            }
            drawable.setCornerRadii(radii);
        }

    }

}
