package dev.pawan.deexlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lavalink AudioSourceManager for Deezer.
 *
 * Inputs:  dzsearch:QUERY   dzisrc:ISRC   deezer.com track / album / playlist / artist URLs   link.deezer.com short links
 */
public class DeezerSourceManager implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(DeezerSourceManager.class);

    static final String SEARCH_PREFIX = "dzsearch:";
    static final String ISRC_PREFIX = "dzisrc:";

    private static final Pattern URL = Pattern.compile(
            "^https?://(?:www\\.)?deezer\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(track|album|playlist|artist)/(\\d+)(?:[/?#].*)?$");
    private static final Pattern SHORT = Pattern.compile(
            "^https?://(?:link\\.deezer\\.com/s/|deezer\\.page\\.link/)[A-Za-z0-9]+.*$");
    private static final int MAX_MIRROR_CANDIDATES = 5;

    private final DeeXLavaConfig config;
    private final DeezerApi api;
    private final HttpInterfaceManager httpManager;
    private volatile AudioPlayerManager playerManager;

    public DeezerSourceManager(DeeXLavaConfig config) {
        this.config = config;
        this.api = new DeezerApi(config);
        this.httpManager = HttpClientTools.createDefaultThreadLocalManager();
    }

    public void setPlayerManager(AudioPlayerManager manager) {
        this.playerManager = manager;
    }

    public DeeXLavaConfig getConfig() {
        return config;
    }

    public DeezerApi getApi() {
        return api;
    }

    public HttpInterface getHttpInterface() {
        return httpManager.getInterface();
    }

    @Override
    public String getSourceName() {
        return config.getSourceName();
    }

    // ------------------------------------------------------------------ loading

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference ref) {
        String id = ref.identifier;
        if (id == null) return null;
        try {
            if (id.startsWith(SEARCH_PREFIX)) {
                return search(id.substring(SEARCH_PREFIX.length()).trim());
            }
            if (id.startsWith(ISRC_PREFIX)) {
                return byIsrc(id.substring(ISRC_PREFIX.length()).trim());
            }
            Matcher m = URL.matcher(id);
            if (m.matches()) {
                return resolve(m.group(1), m.group(2));
            }
            if (SHORT.matcher(id).matches()) {
                Matcher r = URL.matcher(api.followRedirect(id));
                if (r.matches()) return resolve(r.group(1), r.group(2));
                return AudioReference.NO_TRACK;
            }
            return null; // not ours
        } catch (Exception e) {
            log.warn("Deezer load failed for {}: {}", id, e.getMessage());
            throw new FriendlyException("Deezer load failed: " + e.getMessage(),
                    FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    private AudioItem search(String query) throws Exception {
        if (query.isEmpty()) return AudioReference.NO_TRACK;
        JsonNode root = api.search(query, config.getSearchLimit());
        List<AudioTrack> tracks = tracksFrom(root == null ? null : root.path("data"), null);
        if (tracks.isEmpty()) return AudioReference.NO_TRACK;
        return new BasicAudioPlaylist("Deezer search: " + query, tracks, null, true);
    }

    private AudioItem byIsrc(String isrc) throws Exception {
        String clean = isrc.replace("-", "").toUpperCase();
        JsonNode node = api.publicGet("/track/isrc:" + clean);
        if (node == null) return AudioReference.NO_TRACK;
        AudioTrack t = buildTrack(node, null);
        return t == null ? AudioReference.NO_TRACK : t;
    }

    private AudioItem resolve(String type, String id) throws Exception {
        JsonNode entity = api.publicGet("/" + type + "/" + id);
        if (entity == null) return AudioReference.NO_TRACK;

        switch (type) {
            case "track": {
                AudioTrack t = buildTrack(entity, null);
                return t == null ? AudioReference.NO_TRACK : t;
            }
            case "album":
            case "playlist": {
                String artwork = firstText(entity.path("cover_xl"), entity.path("picture_xl"));
                JsonNode list = api.publicGet("/" + type + "/" + id + "/tracks?limit=" + config.getPlaylistLimit());
                List<AudioTrack> tracks = tracksFrom(list == null ? null : list.path("data"), artwork);
                if (tracks.isEmpty()) return AudioReference.NO_TRACK;
                return new BasicAudioPlaylist(entity.path("title").asText("Deezer " + type), tracks, null, false);
            }
            case "artist": {
                String artwork = firstText(entity.path("picture_xl"));
                JsonNode top = api.publicGet("/artist/" + id + "/top?limit=" + config.getArtistTopLimit());
                List<AudioTrack> tracks = tracksFrom(top == null ? null : top.path("data"), artwork);
                if (tracks.isEmpty()) return AudioReference.NO_TRACK;
                return new BasicAudioPlaylist(entity.path("name").asText("Artist") + "'s Top Tracks", tracks, null, false);
            }
            default:
                return AudioReference.NO_TRACK;
        }
    }

    private List<AudioTrack> tracksFrom(JsonNode data, String artworkOverride) {
        List<AudioTrack> out = new ArrayList<>();
        if (data == null || !data.isArray()) return out;
        for (JsonNode item : data) {
            if (item.has("readable") && !item.path("readable").asBoolean(true)) continue;
            AudioTrack t = buildTrack(item, artworkOverride);
            if (t != null) out.add(t);
        }
        return out;
    }

    private AudioTrack buildTrack(JsonNode item, String artworkOverride) {
        String id = item.path("id").asText("");
        if (id.isEmpty()) return null;

        String title = item.path("title").asText("").strip();
        if (title.isEmpty()) title = "Unknown Title";
        String author = item.path("artist").path("name").asText("").strip();
        if (author.isEmpty()) author = "Unknown Artist";
        long len = item.path("duration").asLong(0) * 1000L;
        if (len <= 0) len = Units.DURATION_MS_UNKNOWN;
        String uri = item.path("link").asText("");
        if (uri.isEmpty()) uri = "https://www.deezer.com/track/" + id;
        String artwork = artworkOverride != null ? artworkOverride
                : firstText(item.path("album").path("cover_xl"), item.path("album").path("cover_big"),
                        item.path("album").path("cover_medium"));
        String isrc = item.path("isrc").asText("");

        AudioTrackInfo info = new AudioTrackInfo(title, author, len, id, false, uri,
                artwork, isrc.isEmpty() ? null : isrc);
        return new DeezerTrack(info, this);
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n != null && !n.isMissingNode() && !n.isNull()) {
                String s = n.asText("");
                if (!s.isBlank()) return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ mirror

    /** Finds a playable equivalent of a Deezer track on another source (e.g. YouTube) using the configured providers. */
    public InternalAudioTrack findMirror(AudioTrackInfo info) {
        AudioPlayerManager pm = playerManager;
        if (pm == null || !config.isMirrorEnabled() || config.getProviders().isEmpty()) return null;

        String query = ((info.title == null ? "" : info.title) + " " + (info.author == null ? "" : info.author)).strip();
        boolean hasIsrc = info.isrc != null && !info.isrc.isBlank();

        for (String provider : config.getProviders()) {
            String identifier;
            if (provider.contains("%ISRC%")) {
                if (!hasIsrc) continue;
                identifier = provider.replace("%ISRC%", info.isrc);
            } else {
                identifier = provider.replace("%QUERY%", query);
            }

            List<AudioTrack> results = searchOther(pm, identifier);
            AudioTrack best = null;
            int seen = 0;
            for (AudioTrack c : results) {
                if (seen++ >= MAX_MIRROR_CANDIDATES) break;
                if (best == null || closer(c, best, info.length)) best = c;
            }
            if (best != null) {
                AudioTrack clone = best.makeClone();
                if (clone instanceof InternalAudioTrack internal) return internal;
            }
        }
        return null;
    }

    private static boolean closer(AudioTrack cand, AudioTrack best, long target) {
        if (target <= 0 || target == Units.DURATION_MS_UNKNOWN) return false; // keep search rank
        return Math.abs(cand.getDuration() - target) < Math.abs(best.getDuration() - target);
    }

    private List<AudioTrack> searchOther(AudioPlayerManager pm, String identifier) {
        CompletableFuture<List<AudioTrack>> future = new CompletableFuture<>();
        pm.loadItem(identifier, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) { future.complete(List.of(track)); }
            @Override public void playlistLoaded(AudioPlaylist playlist) { future.complete(new ArrayList<>(playlist.getTracks())); }
            @Override public void noMatches() { future.complete(List.of()); }
            @Override public void loadFailed(FriendlyException exception) { future.complete(List.of()); }
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.debug("Deezer mirror search '{}' failed: {}", identifier, e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ encoding

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
        // nothing extra: identifier / uri / isrc live in AudioTrackInfo
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
        return new DeezerTrack(trackInfo, this);
    }

    @Override
    public void shutdown() {
        try {
            httpManager.close();
        } catch (IOException e) {
            log.error("Failed closing Deezer http manager", e);
        }
    }
}
