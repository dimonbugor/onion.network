package onion.network.call.codec;

import java.io.IOException;
import java.util.Arrays;

/**
 * Тривіальний кодек, який просто копіює PCM 16-bit little-endian дані.
 * Використовується як заглушка до впровадження Opus.
 */
public final class PcmCodec implements AudioCodec {

    @Override
    public byte[] encode(byte[] pcm16le, int length) {
        if (pcm16le == null || length <= 0) {
            return new byte[0];
        }
        return Arrays.copyOf(pcm16le, length);
    }

    @Override
    public byte[] decode(byte[] encoded, int length) throws IOException {
        if (encoded == null || length <= 0) {
            return new byte[0];
        }
        return Arrays.copyOf(encoded, length);
    }

    @Override
    public String name() {
        return "PCM";
    }
}
