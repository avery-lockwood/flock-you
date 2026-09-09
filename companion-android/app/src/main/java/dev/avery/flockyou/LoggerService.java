package dev.avery.flockyou;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

/**
 * Foreground service: holds the BLE link to the detector and a GPS watch,
 * appends everything to the Store, and syncs to the tailnet server every
 * few minutes. The notification carries a MARK action so a camera sighting
 * can be logged from the lock screen.
 */
public class LoggerService extends Service {
    public static final String ACTION_MARK = "dev.avery.flockyou.MARK";
    private static final String CHANNEL = "flockyou";
    private static final int NOTIF_ID = 1;
    private static final long SYNC_EVERY_MS = 5 * 60 * 1000;
    private static final long RESCAN_DELAY_MS = 4000;

    private static final UUID SVC_UUID =
            UUID.fromString("8f1d0001-6f9c-43fa-9f9a-4d5c1a2b3c4d");
    private static final UUID CHR_UUID =
            UUID.fromString("8f1d0002-6f9c-43fa-9f9a-4d5c1a2b3c4d");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Read by MainActivity for its status panel.
    public static volatile boolean running = false;
    public static volatile boolean bleLinked = false;
    public static volatile String lastEventLine = "";
    public static volatile String lastSyncResult = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private LocationListener locationListener;
    private BluetoothGatt gatt;
    private ScanCallback scanCallback;
    private Location lastFix;
    private Location lastStored;
    private long lastStoredAt;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_MARK.equals(intent.getAction())) {
            recordMark("notification");
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        running = true;

        createChannel();
        startForeground(NOTIF_ID, buildNotification("Starting…"));
        startLocation();
        startBleScan();
        handler.postDelayed(this::syncTick, SYNC_EVERY_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        bleLinked = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (locationListener != null)
                locationManager.removeUpdates(locationListener);
        } catch (Exception ignored) {}
        stopBle();
        super.onDestroy();
    }

    // ---------- notification ----------

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "FlockYou logging",
                NotificationManager.IMPORTANCE_LOW));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE);

        Intent mark = new Intent(this, LoggerService.class).setAction(ACTION_MARK);
        PendingIntent markPi = PendingIntent.getService(this, 1, mark,
                PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("FlockYou logging")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "MARK CAMERA", markPi).build())
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ---------- location ----------

    private void startLocation() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location loc) { onFix(loc); }
        };
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 3000, 0, locationListener);
        } catch (SecurityException ignored) {}
    }

    private void onFix(Location loc) {
        lastFix = loc;
        long now = System.currentTimeMillis();
        boolean keep = lastStored == null
                || loc.distanceTo(lastStored) > 8
                || now - lastStoredAt > 15000;
        if (!keep) return;
        lastStored = loc;
        lastStoredAt = now;
        try {
            JSONObject row = new JSONObject();
            row.put("t", now);
            row.put("lat", loc.getLatitude());
            row.put("lon", loc.getLongitude());
            row.put("acc", loc.getAccuracy());
            Store.append(this, Store.TRACK, row);
        } catch (Exception ignored) {}
        updateNotification(statusLine());
    }

    private String statusLine() {
        String gps = lastFix == null ? "no fix"
                : "±" + Math.round(lastFix.getAccuracy()) + "m";
        return (bleLinked ? "detector linked" : "detector searching")
                + " · GPS " + gps;
    }

    // ---------- events ----------

    private void recordEvent(String kind, JSONObject data) {
        try {
            JSONObject row = new JSONObject();
            row.put("kind", kind);
            row.put("t", System.currentTimeMillis());
            if (lastFix != null) {
                JSONObject fix = new JSONObject();
                fix.put("lat", lastFix.getLatitude());
                fix.put("lon", lastFix.getLongitude());
                fix.put("acc", lastFix.getAccuracy());
                row.put("fix", fix);
            } else {
                row.put("fix", JSONObject.NULL);
            }
            row.put("data", data == null ? new JSONObject() : data);
            Store.append(this, Store.EVENTS, row);
            lastEventLine = kind + " @ " + android.text.format.DateFormat
                    .format("HH:mm:ss", System.currentTimeMillis());
        } catch (Exception ignored) {}
        buzz(kind.equals("mark"));
        updateNotification(statusLine() + " · " + lastEventLine);
    }

    public void recordMark(String source) {
        try {
            recordEvent("mark", new JSONObject().put("source", source));
        } catch (Exception ignored) {}
    }

    private void buzz(boolean isMark) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            long[] pattern = isMark ? new long[]{0, 80, 60, 80} : new long[]{0, 150};
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } catch (Exception ignored) {}
    }

    // ---------- bluetooth ----------

    private void startBleScan() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            handler.postDelayed(this::startBleScan, RESCAN_DELAY_MS);
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            handler.postDelayed(this::startBleScan, RESCAN_DELAY_MS);
            return;
        }
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SVC_UUID)).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                try {
                    scanner.stopScan(this);
                } catch (Exception ignored) {}
                scanCallback = null;
                connect(result.getDevice());
            }
        };
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } catch (SecurityException ignored) {}
    }

    private void connect(BluetoothDevice device) {
        try {
            gatt = device.connectGatt(this, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try { g.requestMtu(247); } catch (SecurityException ignored) {}
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        bleLinked = false;
                        try { g.close(); } catch (Exception ignored) {}
                        gatt = null;
                        if (running) {
                            handler.postDelayed(LoggerService.this::startBleScan,
                                    RESCAN_DELAY_MS);
                            handler.post(() -> updateNotification(statusLine()));
                        }
                    }
                }

                @Override
                public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                    try { g.discoverServices(); } catch (SecurityException ignored) {}
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    try {
                        BluetoothGattCharacteristic chr =
                                g.getService(SVC_UUID).getCharacteristic(CHR_UUID);
                        g.setCharacteristicNotification(chr, true);
                        BluetoothGattDescriptor cccd = chr.getDescriptor(CCCD_UUID);
                        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(cccd);
                        bleLinked = true;
                        handler.post(() -> updateNotification(statusLine()));
                    } catch (Exception e) {
                        try { g.disconnect(); } catch (Exception ignored) {}
                    }
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt g,
                                                    BluetoothGattCharacteristic chr) {
                    byte[] value = chr.getValue();
                    if (value == null) return;
                    handleDeviceLine(new String(value, StandardCharsets.UTF_8));
                }
            });
        } catch (SecurityException ignored) {}
    }

    private void handleDeviceLine(String line) {
        try {
            JSONObject msg = new JSONObject(line);
            String event = msg.optString("event");
            if ("det".equals(event)) {
                handler.post(() -> recordEvent("det", msg));
            } else if ("mark".equals(event)) {
                handler.post(() -> recordEvent("mark", msg));
            }
        } catch (Exception ignored) {}
    }

    private void stopBle() {
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (scanCallback != null && bm != null && bm.getAdapter() != null
                    && bm.getAdapter().getBluetoothLeScanner() != null) {
                bm.getAdapter().getBluetoothLeScanner().stopScan(scanCallback);
            }
        } catch (Exception ignored) {}
        try {
            if (gatt != null) gatt.close();
        } catch (Exception ignored) {}
        gatt = null;
    }

    // ---------- sync ----------

    private void syncTick() {
        new Thread(() -> {
            lastSyncResult = SyncClient.syncNow(this);
        }).start();
        if (running) handler.postDelayed(this::syncTick, SYNC_EVERY_MS);
    }
}
