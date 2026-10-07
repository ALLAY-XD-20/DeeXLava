package dev.pawan.deexlava;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * DeeXLava — Deezer source for Lavalink v4.
 * Author: Pawan
 *
 * Enable with:  plugins.deexlava.engine: enable
 */
@Service
public class DeeXLavaPlugin implements AudioPlayerManagerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DeeXLavaPlugin.class);

    private final DeeXLavaConfig config;

    public DeeXLavaPlugin(DeeXLavaConfig config) {
        this.config = config;
    }

    @Override
    public AudioPlayerManager configure(AudioPlayerManager manager) {
        if (!config.isEngineEnabled()) {
            log.info("DeeXLava engine: disable — Deezer source not registered");
            return manager;
        }
        DeezerSourceManager source = new DeezerSourceManager(config);
        source.setPlayerManager(manager);
        manager.registerSourceManager(source);
        if (config.canPlayDirect()) {
            log.info("DeeXLava engine: enable — Deezer source '{}' registered (direct playback + mirror fallback)",
                    config.getSourceName());
        } else {
            log.info("DeeXLava engine: enable — Deezer source '{}' registered (metadata only, playback via mirror; "
                    + "set arl + masterDecryptionKey for direct playback)", config.getSourceName());
        }
        return manager;
    }
}
