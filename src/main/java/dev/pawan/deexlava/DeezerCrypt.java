package dev.pawan.deexlava;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Per-track key derivation for Deezer's BF_CBC_STRIPE streams. */
final class DeezerCrypt {

    private DeezerCrypt() {}

    /**
     * key[i] = md5hex(songId)[i] ^ md5hex(songId)[i + 16] ^ master[i]   (16 bytes)
     *
     * @param master 16-character master key supplied by the user in config
     */
    static byte[] trackKey(String songId, String master) throws Exception {
        if (master == null || master.length() != 16) {
            throw new IllegalArgumentException("masterDecryptionKey must be exactly 16 characters");
        }
        byte[] md5 = MessageDigest.getInstance("MD5").digest(songId.getBytes(StandardCharsets.US_ASCII));
        StringBuilder hex = new StringBuilder(32);
        for (byte b : md5) hex.append(String.format("%02x", b));
        String h = hex.toString();

        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) (h.charAt(i) ^ h.charAt(i + 16) ^ master.charAt(i));
        }
        return key;
    }
}
