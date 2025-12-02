package onion.network.call.codec;

import java.io.IOException;

/**
 * Абстракція аудіокодека. Дозволяє легко підмінити PCM на Opus чи інший формат.
 */
public interface AudioCodec {
    /**
     * @param pcm16le PCM data in little-endian 16-bit format.
     * @param length number of valid bytes in {@code pcm16le}
     * @return encoded frame (may be a copy of the input)
     */
    byte[] encode(byte[] pcm16le, int length) throws IOException;

    /**
     * Decode an encoded frame into raw PCM (16-bit LE).
     *
     * @param encoded encoded frame bytes
     * @param length number of bytes to read from {@code encoded}
     * @return decoded PCM frame
     */
    byte[] decode(byte[] encoded, int length) throws IOException;

    /**
     * @return human-readable codec name, e.g. "PCM", "Opus".
     */
    String name();
}
