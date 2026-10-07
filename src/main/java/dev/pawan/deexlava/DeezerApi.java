package dev.pawan.deexlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Deezer public REST API + gateway/media calls used to get a direct stream URL. */
public class DeezerApi {

    private static final Logger log = LoggerFactory.getLogger(DeezerApi.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final long CRED_TTL_MS = 12L * 60 * 60 * 1000;

    private record Creds(String cookie, String csrf, String license, long createdAt) {}

    /** Direct stream descriptor. */
    public record StreamInfo(String url, boolean flac, String songId) {}

    private final DeeXLavaConfig cfg;
    private final HttpClient http;
    private final Object credLock = new Object();
    private volatile Creds creds;

    public DeezerApi(DeeXLavaConfig cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ---------------------------------------------------------------- public API

    /** GET https://api.deezer.com{path}. Returns null when Deezer says "no data" (error 800). */
    public JsonNode publicGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.deezer.com" + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = JSON.readTree(res.body());
        JsonNode err = root.path("error");
        if (err.isObject()) {
            if (err.path("code").asInt() == 800) return null;
            throw new IllegalStateException("Deezer API error: " + err.path("message").asText("unknown"));
        }
        return root;
    }

    public JsonNode search(String query, int limit) throws Exception {
        return publicGet("/search/track?q=" + enc(query) + "&limit=" + limit);
    }

    /** Follows redirects (link.deezer.com short links) and returns the final URL. */
    public String followRedirect(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return res.uri().toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- gateway / stream

    /** Resolves an encrypted direct stream URL for a Deezer track id. Retries once with fresh credentials. */
    public StreamInfo resolveStream(String trackId) throws Exception {
        try {
            return resolveStream0(trackId, false);
        } catch (Exception first) {
            log.debug("Deezer stream resolve failed ({}), retrying with fresh credentials", first.getMessage());
            return resolveStream0(trackId, true);
        }
    }

    private StreamInfo resolveStream0(String trackId, boolean forceRefresh) throws Exception {
        Creds c = credentials(forceRefresh);

        ObjectNode body = JSON.createObjectNode();
        body.putArray("sng_ids").add(trackId);
        JsonNode list = gateway(c, "song.getListData", body);
        JsonNode data0 = list.path("results").path("data").path(0);
        String token = data0.path("TRACK_TOKEN").asText("");
        String songId = data0.path("SNG_ID").asText("");
        if (token.isEmpty() || songId.isEmpty()) {
            throw new IllegalStateException("Deezer track token not found for " + trackId);
        }

        ObjectNode req = JSON.createObjectNode();
        req.put("license_token", c.license());
        ArrayNode media = req.putArray("media");
        ObjectNode m = media.addObject();
        m.put("type", "FULL");
        ArrayNode formats = m.putArray("formats");
        for (String f : cfg.getFormats()) {
            ObjectNode fo = formats.addObject();
            fo.put("cipher", "BF_CBC_STRIPE");
            fo.put("format", f.trim().toUpperCase());
        }
        req.putArray("track_tokens").add(token);

        HttpRequest hr = HttpRequest.newBuilder(URI.create("https://media.deezer.com/v1/get_url"))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(req)))
                .build();
        HttpResponse<String> res = http.send(hr, HttpResponse.BodyHandlers.ofString());
        JsonNode root = JSON.readTree(res.body());
        JsonNode d0 = root.path("data").path(0);
        JsonNode m0 = d0.path("media").path(0);
        String url = m0.path("sources").path(0).path("url").asText("");
        String format = m0.path("format").asText("");
        if (url.isEmpty() || format.isEmpty()) {
            String why = d0.path("errors").path(0).path("message").asText("no playable media (account/format restriction?)");
            throw new IllegalStateException("Deezer media error: " + why);
        }
        return new StreamInfo(url, !format.toUpperCase().startsWith("MP3") && format.toUpperCase().contains("FLAC"), songId);
    }

    private JsonNode gateway(Creds c, String method, ObjectNode body) throws Exception {
        String url = "https://www.deezer.com/ajax/gw-light.php?method=" + method
                + "&input=3&api_version=1.0&api_token=" + enc(c.csrf());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Cookie", c.cookie())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = JSON.readTree(res.body());
        JsonNode err = root.path("error");
        boolean hasErr = (err.isObject() || err.isArray()) ? err.size() > 0 : (err.isTextual() && !err.asText().isBlank());
        if (hasErr) throw new IllegalStateException("Deezer gateway error: " + err);
        return root;
    }

    private Creds credentials(boolean force) throws Exception {
        Creds c = creds;
        if (!force && c != null && System.currentTimeMillis() - c.createdAt() < CRED_TTL_MS) return c;
        synchronized (credLock) {
            c = creds;
            if (!force && c != null && System.currentTimeMillis() - c.createdAt() < CRED_TTL_MS) return c;

            String arl = cfg.getArl();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(
                            "https://www.deezer.com/ajax/gw-light.php?method=deezer.getUserData&input=3&api_version=1.0&api_token="))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", UA)
                    .GET();
            if (!arl.isBlank()) b.header("Cookie", "arl=" + arl);

            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode results = JSON.readTree(res.body()).path("results");
            String csrf = results.path("checkForm").asText("");
            String license = results.path("USER").path("OPTIONS").path("license_token").asText("");

            List<String> parts = new ArrayList<>();
            if (!arl.isBlank()) parts.add("arl=" + arl);
            for (String sc : res.headers().allValues("set-cookie")) {
                String kv = sc.split(";", 2)[0].trim();
                if (!kv.isEmpty()) parts.add(kv);
            }
            String cookie = String.join("; ", parts);

            if (csrf.isEmpty() || license.isEmpty() || cookie.isEmpty()) {
                throw new IllegalStateException("Deezer login failed (check your arl)");
            }
            creds = new Creds(cookie, csrf, license, System.currentTimeMillis());
            log.info("Deezer credentials loaded");
            return creds;
        }
    }
}
