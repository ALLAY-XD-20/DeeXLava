package dev.pawan.deexlava;

import com.sedmelluq.discord.lavaplayer.container.flac.FlacAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Deezer track. Plays the decrypted Deezer stream when arl + key are configured,
 * otherwise (or if that fails) plays an equivalent track from another source.
 */
public class DeezerTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(DeezerTrack.class);

    private final DeezerSourceManager mgr;

    public DeezerTrack(AudioTrackInfo info, DeezerSourceManager mgr) {
        super(info);
        this.mgr = mgr;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        if (mgr.getConfig().canPlayDirect()) {
            try {
                DeezerApi.StreamInfo s = mgr.getApi().resolveStream(trackInfo.identifier);
                byte[] key = DeezerCrypt.trackKey(s.songId(), mgr.getConfig().getMasterDecryptionKey());
                try (HttpInterface http = mgr.getHttpInterface();
                     DeezerHttpStream stream = new DeezerHttpStream(http, new URI(s.url()), null, key)) {
                    InternalAudioTrack delegate = s.flac()
                            ? new FlacAudioTrack(trackInfo, stream)
                            : new Mp3AudioTrack(trackInfo, stream);
                    processDelegate(delegate, executor);
                    return;
                }
            } catch (InterruptedException e) {
                throw e; // track was stopped
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) throw e;
                log.warn("Deezer direct playback failed for {} ({}): {}",
                        trackInfo.identifier, trackInfo.title, e.getMessage());
            }
        }

        if (mgr.getConfig().isMirrorEnabled()) {
            InternalAudioTrack mirror = mgr.findMirror(trackInfo);
            if (mirror != null) {
                log.info("Deezer track {} playing via mirror", trackInfo.identifier);
                processDelegate(mirror, executor);
                return;
            }
        }

        throw new FriendlyException("Deezer track unavailable: " + trackInfo.title,
                FriendlyException.Severity.COMMON, null);
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new DeezerTrack(trackInfo, mgr);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return mgr;
    }
}
