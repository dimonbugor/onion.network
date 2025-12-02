package onion.network.call.codec;

import android.util.Log;

import java.io.IOException;

public class OpusCodec implements AudioCodec {

    private static final String TAG = "OpusCodec";
    private static final boolean NATIVE_LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("tor-native");
            loaded = true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load tor-native library", e);
        }
        NATIVE_LOADED = loaded;
    }

    private long encoderPtr;
    private long decoderPtr;
    private boolean released;

    public OpusCodec(int sampleRate, int channelCount, int bitrate) throws IOException {
        if (!NATIVE_LOADED) {
            throw new IOException("Native Opus library not available");
        }
        encoderPtr = nativeCreateEncoder(sampleRate, channelCount, bitrate);
        if (encoderPtr == 0) throw new IOException("Failed to create Opus encoder");
        decoderPtr = nativeCreateDecoder(sampleRate, channelCount);
        if (decoderPtr == 0) {
            nativeDestroyEncoder(encoderPtr);
            throw new IOException("Failed to create Opus decoder");
        }
    }

    @Override
    public byte[] encode(byte[] pcm16le, int length) throws IOException {
        if (pcm16le == null || length <= 0) return new byte[0];
        if (encoderPtr == 0) throw new IOException("Encoder released");
        return nativeEncode(encoderPtr, pcm16le, length);
    }

    @Override
    public byte[] decode(byte[] encoded, int length) throws IOException {
        if (encoded == null || length <= 0) return new byte[0];
        if (decoderPtr == 0) throw new IOException("Decoder released");
        return nativeDecode(decoderPtr, encoded, length);
    }

    @Override
    public String name() {
        return "Opus";
    }

    public synchronized void release() {
        if (released) return;
        if (encoderPtr != 0) {
            nativeDestroyEncoder(encoderPtr);
            encoderPtr = 0;
        }
        if (decoderPtr != 0) {
            nativeDestroyDecoder(decoderPtr);
            decoderPtr = 0;
        }
        released = true;
    }

    private static native long nativeCreateEncoder(int sampleRate, int channels, int bitrate);
    private static native byte[] nativeEncode(long encoderPtr, byte[] pcm, int length) throws IOException;
    private static native void nativeDestroyEncoder(long encoderPtr);

    private static native long nativeCreateDecoder(int sampleRate, int channels);
    private static native byte[] nativeDecode(long decoderPtr, byte[] data, int length) throws IOException;
    private static native void nativeDestroyDecoder(long decoderPtr);

    public static boolean isNativeAvailable() {
        return NATIVE_LOADED;
    }
}
