package info.pluggabletransports.dispatch.transports;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;


import com.runjva.sourceforge.jsocks.protocol.Socks4Proxy;
import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import com.runjva.sourceforge.jsocks.protocol.SocksException;
import com.runjva.sourceforge.jsocks.protocol.SocksSocket;
import com.runjva.sourceforge.jsocks.protocol.UserPasswordAuthentication;

import goptbundle.Goptbundle;
import info.pluggabletransports.dispatch.Connection;
import info.pluggabletransports.dispatch.DispatchConstants;
import info.pluggabletransports.dispatch.Dispatcher;
import info.pluggabletransports.dispatch.Listener;
import info.pluggabletransports.dispatch.Transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.Properties;

import static info.pluggabletransports.dispatch.DispatchConstants.PT_TRANSPORTS_MEEK;
import static info.pluggabletransports.dispatch.DispatchConstants.TAG;

public class MeekTransport implements Transport {

    public final static String OPTION_FRONT = "front";
    public final static String OPTION_KEY = "key";
    public final static String OPTION_URL = "url";

    private int mLocalSocksPort = 47352;

    private String mPtStateDir;
    private String mMeekFrontDomain;
    private String mMeekKey;
    private String mMeekUrl;

    private final static String NUL_CHAR = "\u0000";

    @Override
    public void register() {
        Dispatcher.get().register(PT_TRANSPORTS_MEEK, getClass());
    }

    @Override
    public void init(Context context, Properties options) {

        initMeekSharedLibrary(context);

        mPtStateDir = context.getDir("pt-state", Context.MODE_PRIVATE).getAbsolutePath();

        mMeekFrontDomain = options.getProperty(OPTION_FRONT);
        mMeekKey = options.getProperty(OPTION_KEY);
        mMeekUrl = options.getProperty(OPTION_URL);
    }

    @Override
    public Connection connect(String addr) {

        //let's start the transport in it's own thread
        exec(new Runnable() { public void run () { Goptbundle.load(mPtStateDir); } });

        String line = getLogLine("socks5",100);
        //         CMETHOD trebuchet socks5 127.0.0.1:19999

        if (!TextUtils.isEmpty(line))
        {
            String[] parts = line.split(" ");
            for (String part : parts) {
                if (part.contains("127.0.0.1")) {
                    String[] addrParts = part.split(":");
                    mLocalSocksPort = Integer.parseInt(addrParts[1]);
                    break;
                }
            }
        }

        try {

            InetAddress localHost = InetAddress.getByName("127.0.0.1");
            return new MeekConnection(addr, localHost, mLocalSocksPort);

        } catch (IOException e) {
            Log.e(getClass().getName(),"Error making connection",e);
            return null;
        }
    }

    private void exec (Runnable run)
    {
        new Thread (run).start();
    }

    @Override
    public Listener listen(String addr) {
        return null;
    }

    private void initMeekSharedLibrary(Context context) {

        try {
            Goptbundle.setenv(DispatchConstants.TOR_PT_LOG_LEVEL, "DEBUG");
            Goptbundle.setenv(DispatchConstants.TOR_PT_CLIENT_TRANSPORTS, DispatchConstants.PT_TRANSPORTS_MEEK);
            Goptbundle.setenv(DispatchConstants.TOR_PT_MANAGED_TRANSPORT_VER, "1");
            Goptbundle.setenv(DispatchConstants.TOR_PT_EXIT_ON_STDIN_CLOSE, "0");
        } catch (Exception e) {
            Log.e(getClass().getName(), "Error setting env variables", e);
        }

    }

    class MeekConnection implements Connection {

        private InetAddress mLocalAddress;
        private int mLocalPort;
        private String mRemoteAddress;
        private int mRemotePort;

        private InputStream mInputStream;
        private OutputStream mOutputStream;

        public MeekConnection(String remoteAddress, InetAddress localSocks, int port) throws IOException {

            String[] addressparts = remoteAddress.split(":");
            mRemoteAddress = addressparts[0];
            mRemotePort = Integer.parseInt(addressparts[1]);
            mLocalAddress = localSocks;
            mLocalPort = port;

        }

        public Socket getSocket (String address, int port) throws SocksException, UnknownHostException {

            //connect to SOCKS port and pass the values appropriately to configure meek
            //see: https://gitweb.torproject.org/torspec.git/tree/pt-spec.txt#n628

            StringBuffer socksUser = new StringBuffer();

            socksUser.append(OPTION_URL).append("\\=").append(mMeekUrl).append("\\;");
            socksUser.append(OPTION_FRONT).append("\\=").append(mMeekFrontDomain).append("\\;");
            socksUser.append(OPTION_KEY).append("\\=").append(mMeekKey).append("\\;");

            StringBuffer socksPass = new StringBuffer();
            socksPass.append(NUL_CHAR);

            Socks5Proxy proxy = new Socks5Proxy(mLocalAddress,mLocalPort);
            UserPasswordAuthentication auth = new UserPasswordAuthentication(socksUser.toString(),socksPass.toString());
            proxy.setAuthenticationMethod(UserPasswordAuthentication.METHOD_ID, auth);
            SocksSocket s = new SocksSocket(proxy, address, port);
            return s;
        }


        private void initBridgeViaSocks() throws IOException {
            //connect to SOCKS port and pass the values appropriately to configure meek
            //see: https://gitweb.torproject.org/torspec.git/tree/pt-spec.txt#n628

            StringBuffer socksUser = new StringBuffer();
            socksUser.append(OPTION_URL).append("\\=").append(mMeekUrl).append("\\;");
            socksUser.append(OPTION_FRONT).append("\\=").append(mMeekFrontDomain).append("\\;");
            socksUser.append(OPTION_KEY).append("\\=").append(mMeekKey).append("\\;");

            Socks5Proxy proxy = new Socks5Proxy(mLocalAddress,mLocalPort);
            proxy.resolveAddrLocally(false);

            UserPasswordAuthentication auth = new UserPasswordAuthentication(socksUser.toString(),NUL_CHAR);
            proxy.setAuthenticationMethod(UserPasswordAuthentication.METHOD_ID, auth);
            proxy.setAuthenticationMethod(0,null);
            SocksSocket s = new SocksSocket(proxy, mMeekFrontDomain, 443);

            mInputStream = s.getInputStream();
            mOutputStream = s.getOutputStream();

        }



        /**
         * Read from socks socket
         *
         * @param b
         * @param offset
         * @param length
         * @return
         * @throws IOException
         */
        @Override
        public int read(byte[] b, int offset, int length) throws IOException {

            if (mInputStream == null)
                initBridgeViaSocks();

            return mInputStream.read(b,offset,length);
        }

        /**
         * Write to socks socket
         *
         * @param b
         * @throws IOException
         */
        @Override
        public void write(byte[] b) throws IOException {


            if (mOutputStream == null)
                initBridgeViaSocks();

            mOutputStream.write(b);
            mOutputStream.flush();
        }

        /**
         * Close socks socket
         */
        @Override
        public void close() {

            try {
                mOutputStream.close();
                mInputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            Goptbundle.close();
        }

        @Override
        public InetAddress getLocalAddress() {
            return mLocalAddress;
        }

        @Override
        public int getLocalPort() {
            return mLocalPort;
        }

        @Override
        public InetAddress getRemoteAddress() {
            try {
                return InetAddress.getByName(mRemoteAddress);
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        public int getRemotePort() {
            return mRemotePort;
        }

        @Override
        public void setDeadline(Date deadlineTime) {

        }

        @Override
        public void setReadDeadline(Date deadlineTime) {

        }

        @Override
        public void setWriteDeadline(Date deadlineTime) {

        }
    }

    private static String getLogLine(String matchChars, int max){
        try {
            Process process = Runtime.getRuntime().exec("logcat -d");
            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            int i = 0;
            while ((line = bufferedReader.readLine()) != null && i++ < max) {
                if (line.contains(matchChars))
                    return line;
            }
        } catch (IOException e) {
        }
        return null;
    }
}

/**
 * 3.5. Pluggable Transport Client Per-Connection Arguments

 Certain PT transport protocols require that the client provides
 per-connection arguments when making outgoing connections.  On
 the server side, this is handled by the "ARGS" optional argument
 as part of the "SMETHOD" message.

 On the client side, arguments are passed via the authentication
 fields that are part of the SOCKS protocol.

 First the "<Key>=<Value>" formatted arguments MUST be escaped,
 such that all backslash, equal sign, and semicolon characters
 are escaped with a backslash.

 Second, all of the escaped are concatenated together.

 Example:

 shared-secret=rahasia;secrets-file=/tmp/blob

 Lastly the arguments are transmitted when making the outgoing
 connection using the authentication mechanism specific to the
 SOCKS protocol version.

 - In the case of SOCKS 4, the concatenated argument list is
 transmitted in the "USERID" field of the "CONNECT" request.

 - In the case of SOCKS 5, the parent process must negotiate
 "Username/Password" authentication [RFC1929], and transmit
 the arguments encoded in the "UNAME" and "PASSWD" fields.

 If the encoded argument list is less than 255 bytes in
 length, the "PLEN" field must be set to "1" and the "PASSWD"
 field must contain a single NUL character.
 */
