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

    private static final int TIMEOUT = 30000;

    public TorSocket(Context context, String host, int port) throws IOException {
        TorManager tor = TorManager.getInstance(context);
        int torPort = tor.getPort();

        if (torPort <= 0) throw new IOException("Tor not running");

        connect(new InetSocketAddress("127.0.0.1", torPort), TIMEOUT);
        setSoTimeout(TIMEOUT);

        InputStream is = new BufferedInputStream(getInputStream());
        OutputStream os = getOutputStream();

        // SOCKS5 handshake
        os.write(new byte[]{0x05, 0x01, 0x00}); // version 5, 1 method, no auth
        os.flush();
        if (is.read() != 0x05 || is.read() != 0x00) {
            throw new IOException("SOCKS5 handshake failed");
        }

        // SOCKS5 connect request
        byte[] hostBytes = host.getBytes("UTF-8");
        Log.d("TorSocket", "SOCKS5 request -> host=" + host +
                " (len=" + hostBytes.length + "), port=" + port);
        os.write(0x05); // version
        os.write(0x01); // connect
        os.write(0x00); // reserved
        os.write(0x03); // address type: domain
        os.write(hostBytes.length); // domain length
        os.write(hostBytes); // domain
        os.write((port >> 8) & 0xFF); // port high byte
        os.write(port & 0xFF); // port low byte
        os.flush();

        // ---- читаємо SOCKS5 response ----
        byte[] header = new byte[4];
        readFully(is, header, 0, 4);

        if (header[0] != 0x05) {
            throw new IOException("Invalid SOCKS version: " + header[0]);
        }

        int rep = header[1] & 0xFF;
        if (rep != 0x00) {
            throw new IOException("SOCKS5 connection failed, code: 0x" + Integer.toHexString(rep));
        }

        int atyp = header[3] & 0xFF;
        int addrLen;

        switch (atyp) {
            case 0x01: // IPv4
                addrLen = 4;
                break;
            case 0x03: // domain
                addrLen = is.read(); // ще 1 байт = довжина домену
                break;
            case 0x04: // IPv6
                addrLen = 16;
                break;
            default:
                throw new IOException("Unknown ATYP: " + atyp);
        }

        byte[] addr = new byte[addrLen];
        readFully(is, addr, 0, addrLen);

        byte[] portBuf = new byte[2];
        readFully(is, portBuf, 0, 2);
        int bndPort = ((portBuf[0] & 0xFF) << 8) | (portBuf[1] & 0xFF);

        // Лог для діагностики
        Log.d("TorSocket", "SOCKS5 response OK -> rep=0x" + Integer.toHexString(rep)
                + " atyp=" + atyp + " bndPort=" + bndPort);
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
