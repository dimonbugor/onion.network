package onion.network.ui.menu;

public class MenuState {
    public boolean photo;
    public boolean camera;
    public boolean blogTitle;
    public boolean share;
    public boolean home;
    public boolean addPost;
    public boolean style;

    public boolean call;
    public boolean refreshQr;
    public boolean scanQr;
    public boolean showMyQr;
    public boolean enterId;
    public boolean showMyId;
    public boolean showUri;
    public boolean inviteFriends;

    public static MenuState blog() {
        MenuState s = new MenuState();
        s.photo = s.camera = s.blogTitle = s.share = s.home = s.addPost = s.style = true;
        return s;
    }
    public static MenuState normal() {
        MenuState s = new MenuState();
        s.refreshQr = s.scanQr = s.showMyQr = s.enterId = s.showMyId = s.showUri = s.inviteFriends = true;
        return s;
    }
}