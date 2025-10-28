package onion.network.models;

import org.json.JSONObject;

public final class FriendPreview {
    public final String friendAddress;
    public Item friendItem;
    public JSONObject friendData;
    public String displayName;
    public Item latestPost;
    public int lastRequestedGeneration = -1;

    public FriendPreview(String addr) {
        this.friendAddress = addr;
    }
}
