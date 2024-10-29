package onion.network.clients;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import onion.network.TorManager;

public class TorSocket extends Socket {

    static final int timeout = 30000;

    public TorSocket(Context context, String host) throws IOException {
        this(context, host, 80);
    }

    public TorSocket(Context context, String host, int port) throws IOException {

        try {

            TorManager tor = TorManager.getInstance(context);

            int torport = tor.getPort();
            if (torport > 0) {
                connect(new InetSocketAddress("127.0.0.1", torport), timeout);
            }

            setSoTimeout(timeout);

            InputStream is = getInputStream();
            OutputStream os = getOutputStream();

            // connect to proxy
            {
                os.write(5); // socks 5
                os.write(1); // stream

                os.write((port >> 8) & 0xff);
                os.write((port >> 0) & 0xff);

                os.write(0);
                os.write(0);
                os.write(0);
                os.write(1);

                os.write(0);

                os.write(host.getBytes());
                os.write(0);

                os.flush();
            }

            // get proxy response
            {
                byte[] h = new byte[8];
                is.read(h);
            }

        } catch (IOException ex) {
            try {
                close();
            } catch (IOException ex2) {
            }
            ex.printStackTrace();
            throw ex;
        }

    }

}
