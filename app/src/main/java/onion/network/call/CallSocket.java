package onion.network.call;

import android.net.LocalSocket;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Minimal wrapper to unify java.net.Socket and android.net.LocalSocket.
 */
public final class CallSocket implements Closeable {
    private final Socket netSocket;
    private final LocalSocket localSocket;

    private CallSocket(Socket netSocket, LocalSocket localSocket) {
        this.netSocket = netSocket;
        this.localSocket = localSocket;
    }

    public static CallSocket from(Socket socket) {
        return new CallSocket(socket, null);
    }

    public static CallSocket from(LocalSocket socket) {
        return new CallSocket(null, socket);
    }

    public InputStream getInputStream() throws IOException {
        if (netSocket != null) return netSocket.getInputStream();
        return localSocket.getInputStream();
    }

    public OutputStream getOutputStream() throws IOException {
        if (netSocket != null) return netSocket.getOutputStream();
        return localSocket.getOutputStream();
    }

    public void setSoTimeout(int timeoutMs) throws IOException {
        if (netSocket != null) {
            netSocket.setSoTimeout(timeoutMs);
        } else {
            localSocket.setSoTimeout(timeoutMs);
        }
    }

    @Override
    public void close() throws IOException {
        if (netSocket != null) {
            netSocket.close();
        } else {
            localSocket.close();
        }
    }

    public boolean isClosed() {
        if (netSocket != null) {
            return netSocket.isClosed();
        } else {
            return !localSocket.isConnected();
        }
    }

    public String describe() {
        if (netSocket != null) {
            return String.valueOf(netSocket.getRemoteSocketAddress());
        } else {
            return "local:" + localSocket.getFileDescriptor();
        }
    }
}
