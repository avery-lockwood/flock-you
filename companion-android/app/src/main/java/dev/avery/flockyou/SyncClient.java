package dev.avery.flockyou;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * POSTs unsynced lines to <server>/api/companion/sync in batches and
 * advances the synced counters only on a 2xx ack. Call from a background
 * thread only.
 */
public final class SyncClient {
    private static final int BATCH = 500;

    private SyncClient() {}

    /** @return human-readable result, e.g. "synced 42 pts, 3 events" */
    public static String syncNow(Context c) {
        int sentTrack = 0, sentEvents = 0;
        try {
            while (true) {
                List<String> track = Store.readFrom(c, Store.TRACK,
                        Store.syncedLines(c, Store.TRACK), BATCH);
                List<String> events = Store.readFrom(c, Store.EVENTS,
                        Store.syncedLines(c, Store.EVENTS), BATCH);
                if (track.isEmpty() && events.isEmpty()) break;

                JSONObject body = new JSONObject();
                body.put("device", Build.MODEL);
                body.put("track", toArray(track));
                body.put("events", toArray(events));

                if (!post(Store.getServerUrl(c) + "/api/companion/sync", body)) {
                    return "sync failed (server unreachable?)";
                }
                Store.advanceSynced(c, Store.TRACK, track.size());
                Store.advanceSynced(c, Store.EVENTS, events.size());
                sentTrack += track.size();
                sentEvents += events.size();
            }
        } catch (Exception e) {
            return "sync error: " + e.getMessage();
        }
        if (sentTrack == 0 && sentEvents == 0) return "nothing new to sync";
        return "synced " + sentTrack + " pts, " + sentEvents + " events";
    }

    private static JSONArray toArray(List<String> lines) {
        JSONArray arr = new JSONArray();
        for (String line : lines) {
            try {
                arr.put(new JSONObject(line));
            } catch (Exception ignored) {}
        }
        return arr;
    }

    private static boolean post(String url, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
