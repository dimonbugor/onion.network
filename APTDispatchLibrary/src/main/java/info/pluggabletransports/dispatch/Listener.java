package info.pluggabletransports.dispatch;

import java.net.InetAddress;

public interface Listener {

    //​ ​ Accept​ ​ waits​ ​ for​ ​ and​ ​ returns​ ​ the​ ​ next​ ​ connection​ ​ to​ ​ the​ ​ listener.
    public void accept​(Connection conn);

    //​ ​ Close​ ​ closes​ ​ the​ ​ listener.
    public void close();

    //​ ​ Addr​ ​ returns​ ​ the​ ​ listener's​ ​ network​ ​ address.
    public InetAddress getAddress();

}
