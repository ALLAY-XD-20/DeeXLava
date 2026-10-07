package dev.pawan.deexlava;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;

/**
 * HTTP stream that decrypts Deezer BF_CBC_STRIPE data on the fly.
 *
 * The file is split in 2048-byte blocks; every 3rd block (index % 3 == 0) is Blowfish-CBC encrypted
 * with a fixed IV, the rest are plain. Decryption keeps the length, so byte positions of the plain
 * stream equal those of the encrypted one and range seeking works (aligned down to a block).
 */
public class DeezerHttpStream extends PersistentHttpStream {

    private static final int BLOCK = 2048;
    private static final IvParameterSpec IV = new IvParameterSpec(new byte[]{0, 1, 2, 3, 4, 5, 6, 7});

    private final SecretKeySpec keySpec;
    private final Cipher cipher;
    private final byte[] block = new byte[BLOCK];
    private int blockLen = 0;
    private int blockOff = 0;

    public DeezerHttpStream(HttpInterface httpInterface, URI uri, Long contentLength, byte[] key) {
        super(httpInterface, uri, contentLength);
        try {
            this.keySpec = new SecretKeySpec(key, "Blowfish");
            this.cipher = Cipher.getInstance("Blowfish/CBC/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Blowfish is not available", e);
        }
    }

    /** Reads the next raw block from the network and decrypts it when needed. */
    private boolean fill() throws IOException {
        long start = super.getPosition();
        int n = 0;
        while (n < BLOCK) {
            int r = super.read(block, n, BLOCK - n);
            if (r < 0) break;
            n += r;
        }
        if (n == 0) {
            blockLen = 0;
            blockOff = 0;
            return false;
        }
        if (n == BLOCK && (start / BLOCK) % 3 == 0) {
            try {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, IV);
                byte[] plain = cipher.doFinal(block, 0, BLOCK);
                System.arraycopy(plain, 0, block, 0, BLOCK);
            } catch (GeneralSecurityException e) {
                throw new IOException("Deezer block decryption failed", e);
            }
        }
        blockLen = n;
        blockOff = 0;
        return true;
    }

    @Override
    public int read() throws IOException {
        if (blockOff >= blockLen && !fill()) return -1;
        return block[blockOff++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (blockOff >= blockLen && !fill()) return -1;
        int n = Math.min(len, blockLen - blockOff);
        System.arraycopy(block, blockOff, b, off, n);
        blockOff += n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long left = n;
        while (left > 0) {
            if (blockOff >= blockLen && !fill()) break;
            int step = (int) Math.min(left, blockLen - blockOff);
            blockOff += step;
            left -= step;
        }
        return n - left;
    }

    @Override
    public int available() {
        return blockLen - blockOff;
    }

    /** Logical (plain) position = raw position minus bytes still buffered. */
    @Override
    public long getPosition() {
        return super.getPosition() - (blockLen - blockOff);
    }

    @Override
    protected void seekHard(long position) throws IOException {
        long aligned = position - (position % BLOCK);
        blockLen = 0;
        blockOff = 0;
        super.seekHard(aligned);
        long skip = position - aligned;
        if (skip > 0 && fill()) {
            blockOff = (int) Math.min(skip, blockLen);
        }
    }
}
