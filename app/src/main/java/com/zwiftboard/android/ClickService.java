package com.zwiftboard.android;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service de premier plan : recherche les Zwift Click V2, se connecte aux deux pods,
 * fait le handshake, maintient la liaison et transmet les appuis à AppState.
 *
 * Tout l'état Bluetooth est manipulé sur un seul thread (« ble ») : les rappels GATT
 * arrivent sur des threads binder et y sont reroutés.
 */
@SuppressLint("MissingPermission") // les autorisations sont vérifiées dans hasPermissions()
public class ClickService extends Service {

    public static final String ACTION_STOP = "com.zwiftboard.android.STOP";
    private static final String CHANNEL = "zwiftboard";
    private static final int NOTIF_ID = 1;
    private static final long RETRY_BACKOFF_MS = 2000;
    private static final long CONNECT_TIMEOUT_MS = 15000;
    private static final long OP_TIMEOUT_MS = 2000;

    static volatile ClickService instance;

    private HandlerThread thread;
    private Handler ble;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private final Map<String, Pod> pods = new HashMap<>();
    private final Map<String, Long> lastEnd = new HashMap<>();
    private final ButtonTracker.Registry registry = new ButtonTracker.Registry(new ButtonTracker.Listener() {
        @Override
        public void onPress(String button) {
            AppState.I.buttonPressed(button);
        }

        @Override
        public void onRelease(String button) {
            AppState.I.buttonReleased(button);
        }
    });
    private int lastNotifCount = -1;

    // ------------------------------------------------------------------ cycle de vie

    public static boolean hasPermissions(Context c) {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_SCAN);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        for (String p : need) {
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean isHeld(String button) {
        ClickService s = instance;
        return s != null && s.registry.isHeld(button);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("ble");
        thread.start();
        ble = new Handler(thread.getLooper());
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btStateReceiver, f, Context.RECEIVER_NOT_EXPORTED); // diffusion système : reçue quand même
        } else {
            registerReceiver(btStateReceiver, f);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = buildNotification("Recherche des Zwift Click…");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            AppState.I.log("Impossible de démarrer le service : " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (instance == null) {
            instance = this;
            AppState.I.bleRunning = true;
            AppState.I.log("Service Bluetooth démarré");
            ble.post(this::startScan);
        }
        AppState.I.changed();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        AppState.I.bleRunning = false;
        try {
            unregisterReceiver(btStateReceiver);
        } catch (Exception ignored) {
        }
        ble.post(() -> {
            stopScan();
            for (Pod p : new ArrayList<>(pods.values())) p.finish("arrêt du service");
            registry.clear();
            thread.quitSafely();
        });
        AppState.I.resetPods();
        AppState.I.log("Service Bluetooth arrêté");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (st == BluetoothAdapter.STATE_ON) {
                AppState.I.log("Bluetooth activé : reprise de la recherche");
                ble.post(ClickService.this::startScan);
            } else if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                ble.post(() -> {
                    scanning = false;
                    scanner = null;
                    for (Pod p : new ArrayList<>(pods.values())) p.finish("Bluetooth désactivé");
                });
                if (st == BluetoothAdapter.STATE_OFF) AppState.I.log("Bluetooth désactivé : activez-le pour continuer");
            }
        }
    };

    // ------------------------------------------------------------------ notification

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Connexion Zwift Click", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ClickService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("ZwiftBoard")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Arrêter", stop).build())
                .build();
    }

    private void updateNotification() {
        int n = 0;
        for (Pod p : pods.values()) if (p.ready) n++;
        if (n == lastNotifCount) return;
        lastNotifCount = n;
        String text = n == 0 ? "Recherche des Zwift Click…" : (n == 1 ? "1 pod connecté" : n + " pods connectés");
        NotificationManager nm = getSystemService(NotificationManager.class);
        try {
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ recherche

    private void startScan() {
        if (scanning) return;
        if (!hasPermissions(this)) {
            AppState.I.log("Autorisations Bluetooth manquantes : touchez « 1. Autoriser » dans l'appli");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            AppState.I.log("Bluetooth désactivé : activez-le pour continuer");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            AppState.I.log("Scanner Bluetooth indisponible");
            return;
        }
        List<ScanFilter> filters = new ArrayList<>();
        // Filtre sur l'identifiant fabricant Zwift (0x094A), quelles que soient les données.
        filters.add(new ScanFilter.Builder().setManufacturerData(Protocol.COMPANY_ID, new byte[0]).build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(filters, settings, scanCallback);
            scanning = true;
            AppState.I.log("Recherche des Zwift Click (appuyez sur un bouton pour les réveiller)");
        } catch (Exception e) {
            AppState.I.log("Échec du démarrage de la recherche : " + e.getMessage());
        }
    }

    private void stopScan() {
        if (!scanning || scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (Exception ignored) {
        }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult r) {
            ble.post(() -> onFound(r));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) ble.post(() -> onFound(r));
        }

        @Override
        public void onScanFailed(int errorCode) {
            ble.post(() -> {
                scanning = false;
                AppState.I.log("Recherche Bluetooth en échec (code " + errorCode + "), nouvel essai dans 5 s");
                ble.postDelayed(ClickService.this::startScan, 5000);
            });
        }
    };

    private void onFound(ScanResult r) {
        if (instance == null) return;
        ScanRecord rec = r.getScanRecord();
        if (rec == null) return;
        byte[] md = rec.getManufacturerSpecificData(Protocol.COMPANY_ID);
        String name = rec.getDeviceName();
        boolean zwift = md != null || (name != null && name.toLowerCase().startsWith("zwift"));
        if (!zwift) return;
        int id = (md != null && md.length > 0) ? (md[0] & 0xFF) : -1;
        Protocol.Side side = Protocol.side(id);
        String addr = r.getDevice().getAddress();
        AppState.I.podSeen(side, addr);

        if (pods.containsKey(addr)) return;
        Long ended = lastEnd.get(addr);
        if (ended != null && System.currentTimeMillis() - ended < RETRY_BACKOFF_MS) return;

        String sideName = side == Protocol.Side.LEFT ? "gauche" : (side == Protocol.Side.RIGHT ? "droit" : "inconnu");
        AppState.I.log("Pod " + sideName + " trouvé (" + addr + ", id 0x" + Integer.toHexString(id) + ", " + r.getRssi() + " dBm)");
        Pod p = new Pod(r.getDevice(), side, sideName);
        pods.put(addr, p);
        p.start();
    }

    // ------------------------------------------------------------------ connexion d'un pod

    private final class Pod extends BluetoothGattCallback {
        final BluetoothDevice dev;
        final String addr;
        final Protocol.Side side;
        final String sideName;
        final ButtonTracker tracker = new ButtonTracker(registry);
        final ArrayDeque<Runnable> ops = new ArrayDeque<>();
        BluetoothGatt gatt;
        BluetoothGattCharacteristic async, rx, tx;
        boolean busy, ready, acked, resetSent, closed;
        long lastActivity;
        int writeFailures;
        final Runnable opTimeout = this::opDone;
        final Runnable connectTimeout = () -> finish("délai de connexion dépassé");
        final Runnable keepalive = this::keepaliveTick;

        Pod(BluetoothDevice dev, Protocol.Side side, String sideName) {
            this.dev = dev;
            this.addr = dev.getAddress();
            this.side = side;
            this.sideName = sideName;
        }

        void start() {
            AppState.I.podState(side, addr, AppState.PodState.CONNECTING);
            ble.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
            gatt = dev.connectGatt(ClickService.this, false, this, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) finish("connectGatt a échoué");
        }

        void finish(String reason) {
            if (closed) return;
            closed = true;
            ble.removeCallbacks(connectTimeout);
            ble.removeCallbacks(keepalive);
            ble.removeCallbacks(opTimeout);
            ops.clear();
            tracker.releaseAll();
            if (gatt != null) {
                try {
                    gatt.disconnect();
                } catch (Exception ignored) {
                }
                try {
                    gatt.close();
                } catch (Exception ignored) {
                }
            }
            if (pods.get(addr) == this) pods.remove(addr);
            lastEnd.put(addr, System.currentTimeMillis());
            AppState.I.podState(side, addr, AppState.PodState.SEEN);
            AppState.I.log("Pod " + sideName + " déconnecté : " + reason);
            updateNotification();
        }

        // --- file d'opérations GATT (une seule à la fois sur Android) ---

        void enqueue(Runnable op) {
            ops.addLast(op);
            if (!busy) next();
        }

        void next() {
            if (closed) return;
            Runnable op = ops.pollFirst();
            if (op == null) {
                busy = false;
                return;
            }
            busy = true;
            ble.postDelayed(opTimeout, OP_TIMEOUT_MS);
            op.run();
        }

        void opDone() {
            ble.removeCallbacks(opTimeout);
            busy = false;
            next();
        }

        /** Pause entre deux écritures (le handshake demande 100 ms d'écart). */
        void enqueueDelay(long ms) {
            enqueue(() -> {
                ble.removeCallbacks(opTimeout);
                ble.postDelayed(this::opDone, ms);
            });
        }

        void enqueueWrite(byte[] value, String what) {
            enqueue(() -> {
                if (rx == null || !write(rx, value)) {
                    writeFailures++;
                    if (Prefs.get(ClickService.this, Prefs.VERBOSE)) {
                        AppState.I.log("Écriture « " + what + " » refusée (pod " + sideName + ")");
                    }
                    if (writeFailures >= 3) {
                        ble.post(() -> finish("écritures en échec"));
                    }
                    opDone();
                } else {
                    writeFailures = 0;
                }
            });
        }

        @SuppressWarnings("deprecation")
        boolean write(BluetoothGattCharacteristic c, byte[] value) {
            int props = c.getProperties();
            int type = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            if (Build.VERSION.SDK_INT >= 33) {
                return gatt.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS;
            }
            c.setWriteType(type);
            c.setValue(value);
            return gatt.writeCharacteristic(c);
        }

        @SuppressWarnings("deprecation")
        void enqueueSubscribe(BluetoothGattCharacteristic c, String what) {
            enqueue(() -> {
                gatt.setCharacteristicNotification(c, true);
                BluetoothGattDescriptor d = c.getDescriptor(Protocol.CCCD);
                if (d == null) {
                    AppState.I.log("Pod " + sideName + " : pas de descripteur CCCD sur " + what);
                    opDone();
                    return;
                }
                boolean indicate = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
                        && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                byte[] v = indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                boolean ok;
                if (Build.VERSION.SDK_INT >= 33) {
                    ok = gatt.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS;
                } else {
                    d.setValue(v);
                    ok = gatt.writeDescriptor(d);
                }
                if (!ok) {
                    AppState.I.log("Pod " + sideName + " : abonnement à " + what + " refusé");
                    opDone();
                }
            });
        }

        // --- rappels GATT (threads binder → thread ble) ---

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            ble.post(() -> {
                if (closed) return;
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    // petite pause avant la découverte des services : plus fiable sur certains téléphones
                    ble.postDelayed(() -> {
                        if (!closed && !g.discoverServices()) finish("découverte des services refusée");
                    }, 300);
                } else {
                    finish(newState == BluetoothProfile.STATE_DISCONNECTED
                            ? "liaison perdue (statut " + status + ")"
                            : "échec de connexion (statut " + status + ")");
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            ble.post(() -> {
                if (closed) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish("découverte des services en échec (statut " + status + ")");
                    return;
                }
                // Recherche des caractéristiques par UUID dans TOUS les services (comme zwiftboard).
                for (BluetoothGattService s : g.getServices()) {
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        if (Protocol.ASYNC.equals(c.getUuid()) && async == null) async = c;
                        else if (Protocol.SYNC_RX.equals(c.getUuid()) && rx == null) rx = c;
                        else if (Protocol.SYNC_TX.equals(c.getUuid()) && tx == null) tx = c;
                    }
                }
                if (async == null || rx == null) {
                    finish("caractéristiques Zwift absentes (ce n'est pas un Click ?)");
                    return;
                }
                enqueueSubscribe(async, "ASYNC");
                if (tx != null) enqueueSubscribe(tx, "SYNC_TX");
                // Séquence d'activation (vérifiée sur Click V2 par zwiftboard)
                enqueueWrite(Protocol.HANDSHAKE_START, "RideOn 02 03");
                enqueueDelay(100);
                enqueueWrite(Protocol.HANDSHAKE_2, "00 08 00");
                enqueueDelay(100);
                enqueueWrite(Protocol.HANDSHAKE_3, "00 08 10");
                enqueueDelay(100);
                enqueue(() -> {
                    ble.removeCallbacks(connectTimeout);
                    ready = true;
                    lastActivity = System.currentTimeMillis();
                    AppState.I.podState(side, addr, AppState.PodState.CONNECTED);
                    AppState.I.log("Pod " + sideName + " connecté, handshake envoyé");
                    updateNotification();
                    ble.postDelayed(keepalive, Protocol.KEEPALIVE_MS);
                    opDone();
                });
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            ble.post(this::opDone);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            ble.post(this::opDone);
        }

        // Ce rappel (déprécié en API 33) est appelé sur toutes les versions d'Android.
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            final byte[] value = v.clone();
            final java.util.UUID uuid = c.getUuid();
            ble.post(() -> onFrame(uuid, value));
        }

        void onFrame(java.util.UUID uuid, byte[] b) {
            if (closed || b.length == 0) return;
            lastActivity = System.currentTimeMillis();
            resetSent = false;
            boolean verbose = Prefs.get(ClickService.this, Prefs.VERBOSE);
            if (verbose) {
                String ch = Protocol.ASYNC.equals(uuid) ? "async" : (Protocol.SYNC_TX.equals(uuid) ? "sync-tx" : uuid.toString());
                AppState.I.log("[" + sideName + " " + ch + "] " + Protocol.hex(b));
            }
            if (Protocol.SYNC_TX.equals(uuid)) {
                // Seul l'écho « RideOn » arrive sur SYNC_TX : le pod est déverrouillé.
                if (b[0] == 'R' && !acked) {
                    acked = true;
                    AppState.I.podUnlocked(side);
                    AppState.I.log("Pod " + sideName + " déverrouillé (RideOn reçu)");
                    enqueueWrite(Protocol.ACK, "ack");
                }
                return;
            }
            if (Protocol.ASYNC.equals(uuid)) {
                // Défi crypto 0xFF 0x03 : un ack ff 04 00 garde le flux de boutons actif.
                if ((b[0] & 0xFF) == 0xFF && b.length > 1 && b[1] == 0x03) {
                    enqueueWrite(Protocol.ACK, "réponse au défi");
                }
                tracker.onFrame(b);
            }
        }

        void keepaliveTick() {
            if (closed) return;
            long idle = System.currentTimeMillis() - lastActivity;
            if (Prefs.get(ClickService.this, Prefs.IDLE_RESET) && !resetSent && idle >= Protocol.IDLE_RESET_MS) {
                // Redémarrage préventif du pod avant sa mise en veille (~65 s) : il ré-émet
                // en quelques secondes et la recherche le reconnecte.
                resetSent = true;
                if (Prefs.get(ClickService.this, Prefs.VERBOSE)) {
                    AppState.I.log("Pod " + sideName + " inactif depuis 55 s : redémarrage préventif (0x18)");
                }
                enqueueWrite(Protocol.RESET_POD, "reset");
            } else {
                enqueueWrite(Protocol.RIDE_ON, "keepalive");
            }
            ble.postDelayed(keepalive, Protocol.KEEPALIVE_MS);
        }
    }
}
