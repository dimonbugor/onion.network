package onion.network.clients;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import onion.network.TorManager;

public class TorSocket extends Socket {

    private static final int TIMEOUT = 30000;

    public TorSocket(Context context, String host, int port) throws IOException {
        TorManager tor = TorManager.getInstance(context);
        int torPort = tor.getPort();

        if (torPort <= 0) throw new IOException("Tor not running");

        connect(new InetSocketAddress("127.0.0.1", torPort), TIMEOUT);
        setSoTimeout(TIMEOUT);

        InputStream is = getInputStream();
        OutputStream os = getOutputStream();

        // SOCKS5 handshake
        os.write(new byte[] { 0x05, 0x01, 0x00 }); // version 5, 1 method, no auth
        os.flush();
        if (is.read() != 0x05 || is.read() != 0x00) {
            throw new IOException("SOCKS5 handshake failed");
        }

        // SOCKS5 connect request
        byte[] hostBytes = host.getBytes("UTF-8");
        os.write(0x05); // version
        os.write(0x01); // connect
        os.write(0x00); // reserved
        os.write(0x03); // address type: domain
        os.write(hostBytes.length); // domain length
        os.write(hostBytes); // domain
        os.write((port >> 8) & 0xFF); // port high byte
        os.write(port & 0xFF); // port low byte
        os.flush();

        // SOCKS5 response
        byte[] response = new byte[4];
        is.read(response);
        if (response[1] != 0x00) {
            throw new IOException("SOCKS5 connection failed, code: " + response[1]);
        }

        // skip remaining address info
        int addrType = is.read();
        int skip = switch (addrType) {
            case 0x01 -> 4; // IPv4
            case 0x03 -> is.read(); // domain
            case 0x04 -> 16; // IPv6
            default -> throw new IOException("Unknown address type: " + addrType);
        };
        is.skip(skip + 2); // skip address + port
    }

    public TorSocket(Context context, String host) throws IOException {
        this(context, host, 80);
    }
}
