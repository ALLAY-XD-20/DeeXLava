package dev.pawan.deexlava;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bound from application.yml under plugins.deexlava
 * Minimal setup:  plugins.deexlava.engine: enable
 */
@Component
@ConfigurationProperties(prefix = "plugins.deexlava")
public class DeeXLavaConfig {

    private String engine = "enable";
    private String sourceName = "deexlava";
    private String arl = "";
    private String masterDecryptionKey = "";
    private List<String> formats = new ArrayList<>(List.of("FLAC", "MP3_320", "MP3_256", "MP3_128"));
    private int searchLimit = 10;
    private int playlistLimit = 100;
    private int artistTopLimit = 25;
    private boolean mirrorEnabled = true;
    private List<String> providers = new ArrayList<>(List.of(
            "ytmsearch:%ISRC%", "ytsearch:%ISRC%", "ytmsearch:%QUERY%", "ytsearch:%QUERY%"));

    /** "enable" (default) turns the Deezer source on; "disable" / "off" / "false" turns it off. */
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public boolean isEngineEnabled() {
        String e = engine == null ? "enable" : engine.trim().toLowerCase(Locale.ROOT);
        return !(e.equals("disable") || e.equals("disabled") || e.equals("false")
                || e.equals("off") || e.equals("no") || e.equals("0"));
    }

    /** Source name reported to clients. Keep it different from LavaSrc's "deezer" unless LavaSrc Deezer is disabled. */
    public String getSourceName() { return sourceName == null || sourceName.isBlank() ? "deexlava" : sourceName.trim(); }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    /** Deezer "arl" login cookie. Needed for direct (non-mirror) playback. */
    public String getArl() { return arl == null ? "" : arl.trim(); }
    public void setArl(String arl) { this.arl = arl; }

    /** 16-character Deezer track-decryption key (you supply it; it is not bundled). */
    public String getMasterDecryptionKey() { return masterDecryptionKey == null ? "" : masterDecryptionKey.trim(); }
    public void setMasterDecryptionKey(String k) { this.masterDecryptionKey = k; }

    public List<String> getFormats() { return formats; }
    public void setFormats(List<String> v) { this.formats = v == null || v.isEmpty() ? new ArrayList<>(List.of("MP3_128")) : v; }

    public int getSearchLimit() { return searchLimit; }
    public void setSearchLimit(int v) { this.searchLimit = Math.max(1, Math.min(v, 50)); }

    public int getPlaylistLimit() { return playlistLimit; }
    public void setPlaylistLimit(int v) { this.playlistLimit = Math.max(1, Math.min(v, 1000)); }

    public int getArtistTopLimit() { return artistTopLimit; }
    public void setArtistTopLimit(int v) { this.artistTopLimit = Math.max(1, Math.min(v, 100)); }

    public boolean isMirrorEnabled() { return mirrorEnabled; }
    public void setMirrorEnabled(boolean v) { this.mirrorEnabled = v; }

    public List<String> getProviders() { return providers; }
    public void setProviders(List<String> v) { this.providers = v == null ? new ArrayList<>() : v; }

    /** True when direct Deezer playback can be attempted. */
    public boolean canPlayDirect() {
        return !getArl().isBlank() && getMasterDecryptionKey().length() == 16;
    }
}
