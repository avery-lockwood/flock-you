package dev.avery.flockyou;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only JSONL files in app-private storage, one line per track point
 * or event, plus per-file "lines already synced" counters in prefs. Sync
 * sends everything past the counter and advances it on server ack, so an
 * offline walk just accumulates lines until the phone sees the tailnet.
 */
public final class Store {
    public static final String TRACK = "track.jsonl";
    public static final String EVENTS = "events.jsonl";

    private static final Object LOCK = new Object();

    private Store() {}

    private static File file(Context c, String name) {
        return new File(c.getFilesDir(), name);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("fy", Context.MODE_PRIVATE);
    }

    public static void append(Context c, String name, JSONObject row) {
        synchronized (LOCK) {
            try (FileWriter w = new FileWriter(file(c, name), true)) {
                w.write(row.toString());
                w.write('\n');
            } catch (Exception ignored) {}
        }
    }

    public static int lineCount(Context c, String name) {
        synchronized (LOCK) {
            File f = file(c, name);
            if (!f.exists()) return 0;
            int n = 0;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                while (r.readLine() != null) n++;
            } catch (Exception ignored) {}
            return n;
        }
    }

    public static List<String> readFrom(Context c, String name, int fromLine, int max) {
        List<String> out = new ArrayList<>();
        synchronized (LOCK) {
            File f = file(c, name);
            if (!f.exists()) return out;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                int i = 0;
                while ((line = r.readLine()) != null) {
                    if (i++ >= fromLine) {
                        out.add(line);
                        if (out.size() >= max) break;
                    }
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    public static int syncedLines(Context c, String name) {
        return prefs(c).getInt("synced_" + name, 0);
    }

    public static void advanceSynced(Context c, String name, int by) {
        prefs(c).edit().putInt("synced_" + name,
                syncedLines(c, name) + by).apply();
    }

    public static void clearAll(Context c) {
        synchronized (LOCK) {
            file(c, TRACK).delete();
            file(c, EVENTS).delete();
        }
        prefs(c).edit().putInt("synced_" + TRACK, 0)
                .putInt("synced_" + EVENTS, 0).apply();
    }

    public static String getServerUrl(Context c) {
        return prefs(c).getString("server_url", "http://fagaceaeserver:5000");
    }

    public static void setServerUrl(Context c, String url) {
        prefs(c).edit().putString("server_url", url).apply();
    }
}
