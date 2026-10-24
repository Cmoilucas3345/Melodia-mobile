package com.melodia.app;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class YTMusicClient {
    private static final String TAG = "YTMusicClient";
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    public static class TrackInfo {
        public String title;
        public String artist;
        public String duration;
        public String videoId;
        public String thumb;

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("title", title != null ? title : "Unknown");
                obj.put("artist", artist != null ? artist : "Unknown");
                obj.put("duration", duration != null ? duration : "");
                obj.put("video_id", videoId != null ? videoId : "");
                obj.put("thumb", thumb != null ? thumb : "");
            } catch (Exception ignored) {}
            return obj;
        }
    }

    public static class StreamResult {
        public String url;
        public String errorReason;

        public StreamResult(String url, String errorReason) {
            this.url = url;
            this.errorReason = errorReason;
        }
    }

    public static List<TrackInfo> searchSongs(String query) {
        List<TrackInfo> list = new ArrayList<>();
        try {
            JSONObject reqBody = new JSONObject();
            JSONObject context = new JSONObject();
            JSONObject clientObj = new JSONObject();
            clientObj.put("clientName", "WEB_REMIX");
            clientObj.put("clientVersion", "1.20231204.01.00");
            context.put("client", clientObj);
            reqBody.put("context", context);
            reqBody.put("query", query);
            reqBody.put("params", "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D");

            Request req = new Request.Builder()
                    .url("https://music.youtube.com/youtubei/v1/search")
                    .post(RequestBody.create(reqBody.toString(), JSON_MEDIA))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return list;
                String resStr = resp.body().string();
                JSONObject root = new JSONObject(resStr);
                JSONArray sections = root.getJSONObject("contents")
                        .getJSONObject("tabbedSearchResultsRenderer")
                        .getJSONArray("tabs").getJSONObject(0)
                        .getJSONObject("tabRenderer")
                        .getJSONObject("content")
                        .getJSONObject("sectionListRenderer")
                        .getJSONArray("contents");

                for (int s = 0; s < sections.length(); s++) {
                    JSONObject sec = sections.getJSONObject(s);
                    if (sec.has("musicShelfRenderer")) {
                        JSONArray items = sec.getJSONObject("musicShelfRenderer").getJSONArray("contents");
                        for (int i = 0; i < items.length(); i++) {
                            try {
                                JSONObject item = items.getJSONObject(i).getJSONObject("musicResponsiveListItemRenderer");
                                TrackInfo t = new TrackInfo();

                                JSONArray flex = item.getJSONArray("flexColumns");
                                t.title = flex.getJSONObject(0).getJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                        .getJSONObject("text").getJSONArray("runs").getJSONObject(0).getString("text");

                                JSONArray runs2 = flex.getJSONObject(1).getJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                        .getJSONObject("text").getJSONArray("runs");
                                t.artist = runs2.getJSONObject(0).getString("text");
                                if (runs2.length() > 2) {
                                    t.duration = runs2.getJSONObject(runs2.length() - 1).getString("text");
                                }

                                if (item.has("playlistItemData")) {
                                    t.videoId = item.getJSONObject("playlistItemData").getString("videoId");
                                }

                                if (item.has("thumbnail")) {
                                    JSONArray thumbs = item.getJSONObject("thumbnail")
                                            .getJSONObject("musicThumbnailRenderer")
                                            .getJSONObject("thumbnail").getJSONArray("thumbnails");
                                    if (thumbs.length() > 0) {
                                        t.thumb = thumbs.getJSONObject(thumbs.length() - 1).getString("url");
                                    }
                                }
                                if (t.videoId != null && !t.videoId.isEmpty()) {
                                    list.add(t);
                                }
                            } catch (Exception ex) {
                                Log.w(TAG, "Item parse error: " + ex);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Search error: " + e.getMessage());
        }
        return list;
    }

    public static List<TrackInfo> getRecommendations() {
        List<TrackInfo> list = searchSongs("Top Hits 2026");
        if (list.isEmpty()) {
            list = searchSongs("Daft Punk");
        }
        return list;
    }

    public static StreamResult resolveStreamResult(String videoId) {
        StringBuilder errorLog = new StringBuilder();

        // 1. Invidious instances
        String[] invidiousInstances = {
                "https://yt.chocolatemoo53.com",
                "https://invidious.nerdvpn.de",
                "https://inv.bp.projectsegfau.lt"
        };
        for (String inst : invidiousInstances) {
            try {
                Request req = new Request.Builder()
                        .url(inst + "/api/v1/videos/" + videoId)
                        .header("User-Agent", "Mozilla/5.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        JSONObject root = new JSONObject(resp.body().string());
                        JSONArray formats = root.optJSONArray("adaptiveFormats");
                        if (formats != null) {
                            String m4aUrl = null;
                            String anyAudioUrl = null;
                            for (int i = 0; i < formats.length(); i++) {
                                JSONObject f = formats.getJSONObject(i);
                                String type = f.optString("type", "");
                                if (type.contains("audio") && f.has("url")) {
                                    if (type.contains("mp4") || "m4a".equalsIgnoreCase(f.optString("container", ""))) {
                                        m4aUrl = f.getString("url");
                                        break;
                                    }
                                    if (anyAudioUrl == null) {
                                        anyAudioUrl = f.getString("url");
                                    }
                                }
                            }
                            if (m4aUrl != null) {
                                Log.d(TAG, "Resolved M4A stream from " + inst);
                                return new StreamResult(m4aUrl, null);
                            }
                            if (anyAudioUrl != null) {
                                Log.d(TAG, "Resolved audio stream from " + inst);
                                return new StreamResult(anyAudioUrl, null);
                            }
                        } else if (root.has("error")) {
                            errorLog.append(inst).append(": ").append(root.getString("error")).append("; ");
                        }
                    } else {
                        errorLog.append(inst).append(" HTTP ").append(resp.code()).append("; ");
                    }
                }
            } catch (Exception e) {
                errorLog.append(inst).append(" : ").append(e.getMessage()).append("; ");
            }
        }

        // 2. Direct YouTube Android VR player API fallback
        try {
            JSONObject body = new JSONObject();
            JSONObject ctx = new JSONObject();
            JSONObject clientObj = new JSONObject();
            clientObj.put("clientName", "ANDROID_VR");
            clientObj.put("clientVersion", "1.61.48");
            clientObj.put("deviceMake", "Oculus");
            clientObj.put("deviceModel", "Quest 3");
            ctx.put("client", clientObj);
            body.put("context", ctx);
            body.put("videoId", videoId);

            Request req = new Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JSONObject root = new JSONObject(resp.body().string());
                    if (root.has("streamingData")) {
                        JSONArray formats = root.getJSONObject("streamingData").getJSONArray("adaptiveFormats");
                        for (int i = 0; i < formats.length(); i++) {
                            JSONObject f = formats.getJSONObject(i);
                            String mime = f.optString("mimeType", "");
                            if (mime.contains("audio") && f.has("url")) {
                                Log.d(TAG, "Resolved stream via ANDROID_VR");
                                return new StreamResult(f.getString("url"), null);
                            }
                        }
                    } else if (root.has("playabilityStatus")) {
                        String reason = root.getJSONObject("playabilityStatus").optString("reason", "Vidéo restreinte ou indisponible");
                        errorLog.append("YouTube: ").append(reason);
                    }
                }
            }
        } catch (Exception e) {
            errorLog.append("YouTube VR: ").append(e.getMessage());
        }

        String finalReason = errorLog.length() > 0 ? errorLog.toString() : "Aucun flux audio accessible pour ce titre (restreint ou hors ligne)";
        return new StreamResult(null, finalReason);
    }

    public static String getLyrics(String videoId, String title, String artist) {
        String cleanTitle = sanitizeTitle(title);
        String cleanArtist = sanitizeArtist(artist, title);

        Log.d(TAG, "getLyrics for: title='" + cleanTitle + "', artist='" + cleanArtist + "', videoId=" + videoId);

        // 1. Try LRCLIB exact get
        if (!cleanTitle.isEmpty()) {
            String lrc = fetchLrclibGet(cleanTitle, cleanArtist);
            if (lrc != null && !lrc.trim().isEmpty()) {
                Log.d(TAG, "Found lyrics via LRCLIB get");
                return lrc;
            }

            // 2. Try LRCLIB search query
            String query = (cleanArtist + " " + cleanTitle).trim();
            if (!query.isEmpty()) {
                lrc = fetchLrclibSearch(query);
                if (lrc != null && !lrc.trim().isEmpty()) {
                    Log.d(TAG, "Found lyrics via LRCLIB search");
                    return lrc;
                }
            }

            // Also try title only if artist was included in title or search failed
            if (!cleanArtist.isEmpty()) {
                lrc = fetchLrclibSearch(cleanTitle);
                if (lrc != null && !lrc.trim().isEmpty()) {
                    Log.d(TAG, "Found lyrics via LRCLIB title-only search");
                    return lrc;
                }
            }
        }

        // 3. Fallback to YouTube Music lyrics
        if (videoId != null && !videoId.trim().isEmpty()) {
            String ytm = fetchYouTubeMusicLyrics(videoId);
            if (ytm != null && !ytm.trim().isEmpty()) {
                Log.d(TAG, "Found lyrics via YouTube Music");
                return ytm;
            }
        }

        return "Lyrics not available for this track.";
    }

    private static String sanitizeTitle(String title) {
        if (title == null) return "";
        String s = title;
        s = s.replaceAll("(?i)\\s*[\\[\\(][^\\]\\)]*(official|video|audio|clip|paroles|lyrics|remix|version|hd|4k|visualizer|ft\\.|feat\\.)[^\\]\\)]*[\\]\\)]", "");
        if (s.contains(" - ")) {
            String[] parts = s.split(" - ", 2);
            s = parts[1];
        }
        s = s.replaceAll("[\"']", "");
        return s.trim();
    }

    private static String sanitizeArtist(String artist, String rawTitle) {
        if (artist != null && !artist.isEmpty() && !artist.equals("-") && !artist.equalsIgnoreCase("Unknown")) {
            String clean = artist.replaceAll("(?i)\\s*-\\s*topic$", "").trim();
            clean = clean.replaceAll("(?i)\\s*[\\[\\(][^\\]\\)]*[\\]\\)]", "").trim();
            if (!clean.isEmpty()) return clean;
        }
        if (rawTitle != null && rawTitle.contains(" - ")) {
            String[] parts = rawTitle.split(" - ", 2);
            String art = parts[0].replaceAll("(?i)\\s*[\\[\\(][^\\]\\)]*[\\]\\)]", "").trim();
            if (!art.isEmpty()) return art;
        }
        return "";
    }

    private static String fetchLrclibGet(String title, String artist) {
        try {
            StringBuilder url = new StringBuilder("https://lrclib.net/api/get?track_name=");
            url.append(URLEncoder.encode(title, "UTF-8"));
            if (artist != null && !artist.isEmpty()) {
                url.append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"));
            }
            Request req = new Request.Builder()
                    .url(url.toString())
                    .header("User-Agent", "Melodia/1.0.0 (https://github.com/melodia-app)")
                    .get()
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JSONObject obj = new JSONObject(resp.body().string());
                    String synced = obj.optString("syncedLyrics", "");
                    if (!synced.isEmpty()) return synced;
                    String plain = obj.optString("plainLyrics", "");
                    if (!plain.isEmpty()) return plain;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "LRCLIB get error: " + e.getMessage());
        }
        return null;
    }

    private static String fetchLrclibSearch(String query) {
        try {
            String url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8");
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Melodia/1.0.0 (https://github.com/melodia-app)")
                    .get()
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JSONArray arr = new JSONArray(resp.body().string());
                    String firstPlain = null;
                    for (int i = 0; i < Math.min(arr.length(), 6); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        String synced = item.optString("syncedLyrics", "");
                        if (!synced.isEmpty()) return synced;
                        if (firstPlain == null) {
                            String plain = item.optString("plainLyrics", "");
                            if (!plain.isEmpty()) firstPlain = plain;
                        }
                    }
                    if (firstPlain != null) return firstPlain;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "LRCLIB search error: " + e.getMessage());
        }
        return null;
    }

    private static String fetchYouTubeMusicLyrics(String videoId) {
        try {
            JSONObject body = new JSONObject();
            JSONObject ctx = new JSONObject();
            JSONObject clientObj = new JSONObject();
            clientObj.put("clientName", "WEB_REMIX");
            clientObj.put("clientVersion", "1.20231204.01.00");
            ctx.put("client", clientObj);
            body.put("context", ctx);
            body.put("videoId", videoId);

            Request req = new Request.Builder()
                    .url("https://music.youtube.com/youtubei/v1/next")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JSONObject root = new JSONObject(resp.body().string());
                    JSONArray tabs = root.getJSONObject("contents")
                            .getJSONObject("singleColumnMusicWatchNextResultsRenderer")
                            .getJSONObject("tabbedRenderer")
                            .getJSONObject("watchNextTabbedResultsRenderer")
                            .getJSONArray("tabs");

                    String lyricsBrowseId = null;
                    for (int i = 0; i < tabs.length(); i++) {
                        JSONObject tab = tabs.getJSONObject(i).getJSONObject("tabRenderer");
                        if ("Lyrics".equalsIgnoreCase(tab.optString("title", ""))) {
                            if (tab.has("endpoint")) {
                                lyricsBrowseId = tab.getJSONObject("endpoint").getJSONObject("browseEndpoint").getString("browseId");
                            }
                            break;
                        }
                    }

                    if (lyricsBrowseId != null) {
                        JSONObject lBody = new JSONObject();
                        lBody.put("context", ctx);
                        lBody.put("browseId", lyricsBrowseId);
                        Request lReq = new Request.Builder()
                                .url("https://music.youtube.com/youtubei/v1/browse")
                                .post(RequestBody.create(lBody.toString(), JSON_MEDIA))
                                .header("User-Agent", "Mozilla/5.0")
                                .build();
                        try (Response lResp = client.newCall(lReq).execute()) {
                            if (lResp.isSuccessful() && lResp.body() != null) {
                                JSONObject lRoot = new JSONObject(lResp.body().string());
                                JSONArray contents = lRoot.getJSONObject("contents")
                                        .getJSONObject("sectionListRenderer")
                                        .getJSONArray("contents");
                                if (contents.length() > 0) {
                                    JSONObject shelf = contents.getJSONObject(0).optJSONObject("musicDescriptionShelfRenderer");
                                    if (shelf != null && shelf.has("description")) {
                                        JSONObject desc = shelf.getJSONObject("description");
                                        if (desc.has("runs")) {
                                            JSONArray runs = desc.getJSONArray("runs");
                                            StringBuilder sb = new StringBuilder();
                                            for (int r = 0; r < runs.length(); r++) {
                                                sb.append(runs.getJSONObject(r).optString("text", ""));
                                            }
                                            return sb.toString().trim();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "YTM lyrics error: " + e.getMessage());
        }
        return null;
    }
}
