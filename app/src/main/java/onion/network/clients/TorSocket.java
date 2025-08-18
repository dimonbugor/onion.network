package onion.network.clients;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import onion.network.TorManager;

public class TorSocket extends Socket {

    private static final int TIMEOUT = 15000;

    public TorSocket(Context context, String host, int port) throws IOException {
        TorManager tor = TorManager.getInstance(context);
        int torPort = tor.getPort();

        if (torPort <= 0) throw new IOException("Tor not running");

        connect(new InetSocketAddress("127.0.0.1", torPort), TIMEOUT);
        setSoTimeout(TIMEOUT);

        InputStream is = new BufferedInputStream(getInputStream());
        OutputStream os = getOutputStream();

        // SOCKS5 handshake
        os.write(new byte[]{0x05, 0x01, 0x00});
        os.flush();

        byte[] resp = new byte[2];
        readFully(is, resp, 0, 2);
        if (resp[0] != 0x05 || resp[1] != 0x00) {
            throw new IOException("SOCKS5 handshake failed: " + Arrays.toString(resp));
        }

        // CONNECT request
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        os.write(0x05);
        os.write(0x01);
        os.write(0x00);
        os.write(0x03);
        os.write(hostBytes.length);
        os.write(hostBytes);
        os.write((port >> 8) & 0xFF);
        os.write(port & 0xFF);
        os.flush();

        // Response
        byte[] header = new byte[4];
        readFully(is, header, 0, 4);

        if (header[0] != 0x05) throw new IOException("Invalid SOCKS version: " + header[0]);

        int rep = header[1] & 0xFF;
        if (rep != 0x00) throw new IOException("SOCKS5 connection failed, code=0x" + Integer.toHexString(rep));

        int atyp = header[3] & 0xFF;
        int addrLen;
        switch (atyp) {
            case 0x01: addrLen = 4; break;
            case 0x03: addrLen = is.read(); break;
            case 0x04: addrLen = 16; break;
            default: throw new IOException("Unknown ATYP: " + atyp);
        }

        skipFully(is, addrLen); // не зберігаємо
        skipFully(is, 2);       // пропускаємо порт

        Log.d("TorSocket", "SOCKS5 connected to " + host + ":" + port + " via Tor");
    }

    /**
     * Ефективне читання (альтернатива readNBytes)
     */
    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read;
        while (len > 0) {
            read = in.read(buf, off, len);
            if (read == -1) throw new IOException("Stream ended prematurely");
            off += read;
            len -= read;
        }
    }

    /**
     * Ефективний скіп (альтернатива skipNBytes)
     */
    private static void skipFully(InputStream in, long n) throws IOException {
        long skipped, total = 0;
        byte[] buffer = new byte[512]; // буфер для прискорення
        while (total < n) {
            long toRead = Math.min(n - total, buffer.length);
            skipped = in.read(buffer, 0, (int) toRead);
            if (skipped == -1) throw new IOException("Stream ended prematurely while skipping");
            total += skipped;
        }
    }

    public TorSocket(Context context, String host) throws IOException {
        this(context, host, 80);
    }
}
