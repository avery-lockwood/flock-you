package dev.avery.flockyou;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1;
    private static final int REQ_GPX = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView feed;
    private Button toggleBtn;
    private EditText serverUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        feed = findViewById(R.id.feed);
        toggleBtn = findViewById(R.id.toggleBtn);
        serverUrl = findViewById(R.id.serverUrl);
        serverUrl.setText(Store.getServerUrl(this));

        toggleBtn.setOnClickListener(v -> {
            saveUrl();
            if (LoggerService.running) {
                stopService(new Intent(this, LoggerService.class));
            } else if (hasPerms()) {
                startForegroundService(new Intent(this, LoggerService.class));
            } else {
                requestPerms();
            }
        });

        findViewById(R.id.markBtn).setOnClickListener(v -> {
            Intent mark = new Intent(this, LoggerService.class)
                    .setAction(LoggerService.ACTION_MARK);
            startService(mark);
            Toast.makeText(this, "Marked", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.syncBtn).setOnClickListener(v -> {
            saveUrl();
            new Thread(() -> {
                String res = SyncClient.syncNow(this);
                ui.post(() -> Toast.makeText(this, res, Toast.LENGTH_LONG).show());
            }).start();
        });

        findViewById(R.id.exportBtn).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/gpx+xml")
                    .putExtra(Intent.EXTRA_TITLE, "flockyou-"
                            + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                                    .format(new Date()) + ".gpx");
            startActivityForResult(intent, REQ_GPX);
        });

        findViewById(R.id.clearBtn).setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setMessage("Delete the local track and event log? "
                                + "Synced copies stay on the server.")
                        .setPositiveButton("Delete", (d, w) -> Store.clearAll(this))
                        .setNegativeButton("Cancel", null)
                        .show());

        ui.post(refresh);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            int pts = Store.lineCount(MainActivity.this, Store.TRACK);
            int evts = Store.lineCount(MainActivity.this, Store.EVENTS);
            int unsyncedPts = pts - Store.syncedLines(MainActivity.this, Store.TRACK);
            int unsyncedEvts = evts - Store.syncedLines(MainActivity.this, Store.EVENTS);
            String s = (LoggerService.running
                    ? (LoggerService.bleLinked ? "LOGGING · detector linked"
                                               : "LOGGING · detector searching")
                    : "idle")
                    + "\ntrack " + pts + " pts (" + unsyncedPts + " unsynced)"
                    + "\nevents " + evts + " (" + unsyncedEvts + " unsynced)";
            if (!LoggerService.lastEventLine.isEmpty())
                s += "\nlast: " + LoggerService.lastEventLine;
            if (!LoggerService.lastSyncResult.isEmpty())
                s += "\n" + LoggerService.lastSyncResult;
            status.setText(s);
            toggleBtn.setText(LoggerService.running ? "Stop logging" : "Start logging");

            StringBuilder sb = new StringBuilder();
            List<String> recent = Store.readFrom(MainActivity.this, Store.EVENTS,
                    Math.max(0, evts - 12), 12);
            for (int i = recent.size() - 1; i >= 0; i--) {
                try {
                    JSONObject e = new JSONObject(recent.get(i));
                    JSONObject data = e.optJSONObject("data");
                    String kind = e.optString("kind");
                    String when = new SimpleDateFormat("HH:mm:ss", Locale.US)
                            .format(new Date(e.optLong("t")));
                    sb.append(when).append("  ").append(kind.toUpperCase(Locale.US));
                    if ("det".equals(kind) && data != null) {
                        sb.append("  ").append(data.optString("method"))
                          .append("  ").append(data.optString("mac"))
                          .append("  ").append(data.optInt("rssi")).append("dBm");
                    }
                    sb.append(e.isNull("fix") ? "  [no fix]" : "").append('\n');
                } catch (Exception ignored) {}
            }
            feed.setText(sb.toString());
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void saveUrl() {
        String url = serverUrl.getText().toString().trim();
        if (url.isEmpty()) url = "http://fagaceaeserver:5000";
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        Store.setServerUrl(this, url);
    }

    // ---------- permissions ----------

    private String[] neededPerms() {
        List<String> p = new ArrayList<>();
        p.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(android.Manifest.permission.BLUETOOTH_SCAN);
            p.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        return p.toArray(new String[0]);
    }

    private boolean hasPerms() {
        for (String p : neededPerms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void requestPerms() {
        requestPermissions(neededPerms(), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        if (req == REQ_PERMS && hasPerms()) {
            startForegroundService(new Intent(this, LoggerService.class));
        } else if (req == REQ_PERMS) {
            Toast.makeText(this, "Location + Bluetooth permissions are required "
                    + "to log detections", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- GPX export ----------

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_GPX || result != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        new Thread(() -> {
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(buildGpx().getBytes(StandardCharsets.UTF_8));
                ui.post(() -> Toast.makeText(this, "GPX saved", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Save failed: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String buildGpx() {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<gpx version=\"1.1\" creator=\"FlockYou Companion\" ")
          .append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        int evts = Store.lineCount(this, Store.EVENTS);
        for (String line : Store.readFrom(this, Store.EVENTS, 0, evts)) {
            try {
                JSONObject e = new JSONObject(line);
                JSONObject fix = e.optJSONObject("fix");
                if (fix == null) continue;
                JSONObject d = e.optJSONObject("data");
                String kind = e.optString("kind");
                String name = "mark".equals(kind) ? "MARK"
                        : (d != null ? d.optString("method", "det") : "det");
                String desc = "mark".equals(kind) ? "manual camera sighting"
                        : (d != null ? "mac=" + d.optString("mac")
                                + " rssi=" + d.optInt("rssi")
                                + " ch=" + d.optInt("ch") : "");
                sb.append("<wpt lat=\"").append(fix.optDouble("lat"))
                  .append("\" lon=\"").append(fix.optDouble("lon")).append("\">")
                  .append("<time>").append(iso.format(new Date(e.optLong("t"))))
                  .append("</time><name>").append(xml(name))
                  .append("</name><desc>").append(xml(desc))
                  .append("</desc></wpt>\n");
            } catch (Exception ignored) {}
        }
        sb.append("<trk><name>FlockYou track</name><trkseg>\n");
        int pts = Store.lineCount(this, Store.TRACK);
        for (String line : Store.readFrom(this, Store.TRACK, 0, pts)) {
            try {
                JSONObject p = new JSONObject(line);
                sb.append("<trkpt lat=\"").append(p.optDouble("lat"))
                  .append("\" lon=\"").append(p.optDouble("lon")).append("\">")
                  .append("<time>").append(iso.format(new Date(p.optLong("t"))))
                  .append("</time></trkpt>\n");
            } catch (Exception ignored) {}
        }
        sb.append("</trkseg></trk></gpx>\n");
        return sb.toString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
