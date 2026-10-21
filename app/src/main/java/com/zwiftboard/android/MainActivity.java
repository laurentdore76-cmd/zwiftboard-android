package com.zwiftboard.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Écran principal : mise en route, état des pods, options, journal. */
public class MainActivity extends Activity {

    private TextView status;
    private TextView positions;
    private TextView logView;
    private Button startStop;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable listener = this::refresh;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("ZwiftBoard");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(header("Zwift Click V2 → MyWhoosh"));
        TextView intro = text("Chaque bouton du Click simule un appui sur l'écran, à l'endroit que vous avez choisi dans MyWhoosh.");
        root.addView(intro);

        // --- état
        root.addView(header("État"));
        status = text("");
        status.setTypeface(Typeface.MONOSPACE);
        root.addView(status);

        // --- mise en route
        root.addView(header("Mise en route (une seule fois)"));
        root.addView(button("1. Autoriser Bluetooth et notifications", v -> requestPerms()));
        root.addView(button("2. Activer le service d'accessibilité", v -> openAccessibility()));
        root.addView(text("Si l'interrupteur est grisé (« paramètre restreint ») : Paramètres → Applis → ZwiftBoard → menu ⋮ → « Autoriser les paramètres restreints », puis recommencez."));
        root.addView(button("3. Ne pas limiter la batterie", v -> askBattery()));
        root.addView(text("Xiaomi : dans Infos de l'appli, activez aussi « Démarrage automatique » et mettez l'économiseur de batterie sur « Aucune restriction »."));

        // --- utilisation
        root.addView(header("Utilisation"));
        startStop = button("", v -> toggleService());
        root.addView(startStop);
        root.addView(button("Ouvrir MyWhoosh", v -> openWhoosh(false)));
        root.addView(button("Placer les points dans MyWhoosh", v -> openWhoosh(true)));
        root.addView(text("Placement : la bulle « ZB » ouvre et ferme l'écran de placement. Choisissez un bouton (pastille, ou appui sur le Click) puis touchez l'endroit voulu. "
                + "Les points sont mémorisés séparément en paysage et en portrait. Couleur de la bulle : vert = 2 pods, orange = 1, rouge = aucun, gris = service arrêté."));

        positions = text("");
        positions.setTypeface(Typeface.MONOSPACE);
        root.addView(positions);
        root.addView(button("Effacer tous les points", v -> {
            Prefs.clearAllPos(this);
            AppState.I.log("Tous les points ont été effacés");
            refresh();
        }));

        // --- options
        root.addView(header("Options"));
        root.addView(option("Bulle flottante « ZB »", Prefs.BUBBLE, () -> {
            TapService s = TapService.instance;
            if (s != null) s.updateBubbleVisibility();
        }));
        root.addView(option("Répéter l'appui tant que le bouton est maintenu", Prefs.REPEAT, null));
        root.addView(option("N'agir que si MyWhoosh est au premier plan", Prefs.ONLY_WHOOSH, null));
        root.addView(option("Anti-veille des pods (redémarrage préventif après 55 s sans appui)", Prefs.IDLE_RESET, null));
        root.addView(option("Journal détaillé (trames Bluetooth brutes)", Prefs.VERBOSE, null));

        // --- journal
        root.addView(header("Journal"));
        logView = text("");
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        TextView credits = text("Protocole Click V2 repris de zwiftboard (Thomas Champagne, licence MIT).");
        credits.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        credits.setTextColor(Color.GRAY);
        root.addView(credits);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppState.I.addListener(listener);
        // Démarrage automatique quand tout est prêt (l'activité est au premier plan : autorisé).
        if (!ClickService.isRunning() && ClickService.hasPermissions(this) && bluetoothOn()) {
            startClickService();
        }
        refresh();
        h.postDelayed(ticker, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppState.I.removeListener(listener);
        h.removeCallbacks(ticker);
    }

    /** Rafraîchit l'affichage « détecté » qui expire après 30 s. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 2000);
        }
    };

    // ------------------------------------------------------------------ actions

    private void requestPerms() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(perms.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (ClickService.hasPermissions(this)) {
            AppState.I.log("Autorisations Bluetooth accordées");
            if (!bluetoothOn()) askBluetoothOn();
            else if (!ClickService.isRunning()) startClickService();
        } else {
            AppState.I.log("Autorisations Bluetooth refusées : l'appli ne peut pas trouver les Click");
        }
        refresh();
    }

    private void openAccessibility() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        Toast.makeText(this, "Ouvrez « ZwiftBoard » (souvent dans « Applis installées ») et activez-le", Toast.LENGTH_LONG).show();
    }

    @SuppressLint("BatteryLife")
    private void askBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Déjà fait : la batterie n'est pas limitée pour ZwiftBoard", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private boolean bluetoothOn() {
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
        return a != null && a.isEnabled();
    }

    @SuppressLint("MissingPermission")
    private void askBluetoothOn() {
        try {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (Exception e) {
            Toast.makeText(this, "Activez le Bluetooth", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleService() {
        if (ClickService.isRunning()) {
            startService(new Intent(this, ClickService.class).setAction(ClickService.ACTION_STOP));
            return;
        }
        if (!ClickService.hasPermissions(this)) {
            requestPerms();
            return;
        }
        if (!bluetoothOn()) {
            askBluetoothOn();
            return;
        }
        startClickService();
    }

    private void startClickService() {
        try {
            startForegroundService(new Intent(this, ClickService.class));
        } catch (Exception e) {
            AppState.I.log("Démarrage du service impossible : " + e.getMessage());
        }
    }

    /** Ouvre MyWhoosh ; avec placement=true, affiche ensuite l'écran de placement par-dessus. */
    private void openWhoosh(boolean placement) {
        TapService s = TapService.instance;
        if (placement && s == null) {
            Toast.makeText(this, "Activez d'abord le service d'accessibilité (étape 2)", Toast.LENGTH_LONG).show();
            return;
        }
        Intent launch = findWhoosh();
        if (launch == null) {
            Toast.makeText(this, "MyWhoosh introuvable : ouvrez-le vous-même puis touchez la bulle « ZB »", Toast.LENGTH_LONG).show();
            return;
        }
        if (!ClickService.isRunning() && ClickService.hasPermissions(this) && bluetoothOn()) startClickService();
        startActivity(launch);
        if (placement) {
            h.postDelayed(() -> {
                TapService t = TapService.instance;
                if (t != null) t.showPlacement();
            }, 2500);
        }
    }

    private Intent findWhoosh() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            CharSequence label = ri.loadLabel(pm);
            if (pkg.toLowerCase().contains("whoosh") || (label != null && label.toString().toLowerCase().contains("whoosh"))) {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(new ComponentName(pkg, ri.activityInfo.name));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return i;
            }
        }
        return null;
    }

    private boolean accessibilityEnabled() {
        if (TapService.instance != null) return true;
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        return enabled.contains(getPackageName() + "/");
    }

    // ------------------------------------------------------------------ affichage

    private void refresh() {
        boolean perms = ClickService.hasPermissions(this);
        boolean bt = bluetoothOn();
        boolean running = ClickService.isRunning();
        boolean a11y = TapService.instance != null;
        StringBuilder sb = new StringBuilder();
        sb.append("Autorisations   : ").append(perms ? "OK" : "à accorder (étape 1)").append('\n');
        sb.append("Bluetooth       : ").append(bt ? "activé" : "désactivé").append('\n');
        sb.append("Accessibilité   : ").append(a11y ? "active" : (accessibilityEnabled() ? "activée, en attente" : "inactive (étape 2)")).append('\n');
        sb.append("Service Click   : ").append(running ? "en marche" : "arrêté").append('\n');
        sb.append("Pod gauche      : ").append(AppState.describe(AppState.I.pod(Protocol.Side.LEFT))).append('\n');
        sb.append("Pod droit       : ").append(AppState.describe(AppState.I.pod(Protocol.Side.RIGHT))).append('\n');
        AppState.PodView unk = AppState.I.pod(Protocol.Side.UNKNOWN);
        if (unk.state != AppState.PodState.ABSENT) {
            sb.append("Autre Zwift     : ").append(AppState.describe(unk)).append('\n');
        }
        String last = AppState.I.lastButton;
        sb.append("Dernier bouton  : ").append(last == null ? "—" : Protocol.label(last));
        status.setText(sb.toString());

        startStop.setText(running ? "Arrêter la connexion aux Click" : "Démarrer la connexion aux Click");

        StringBuilder ps = new StringBuilder("Points placés (paysage / portrait) :\n");
        for (String b : Protocol.BUTTONS.values()) {
            boolean l = Prefs.getPos(this, b, true) != null;
            boolean p = Prefs.getPos(this, b, false) != null;
            ps.append(String.format("  %-12s %s / %s%n", Protocol.label(b), l ? "✓" : "—", p ? "✓" : "—"));
        }
        positions.setText(ps.toString());

        logView.setText(AppState.I.logText(60));
    }

    // ------------------------------------------------------------------ petites fabriques de vues

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private CheckBox option(String label, String key, Runnable after) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(Prefs.get(this, key));
        cb.setOnCheckedChangeListener((v, checked) -> {
            Prefs.set(this, key, checked);
            if (after != null) after.run();
        });
        return cb;
    }
}
