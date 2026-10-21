package com.zwiftboard.android;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * État partagé entre le service Bluetooth, le service d'accessibilité et l'écran principal.
 * Tout est dans le même processus. Les écouteurs sont appelés sur le thread principal.
 */
public final class AppState {
    public static final AppState I = new AppState();

    public enum PodState { ABSENT, SEEN, CONNECTING, CONNECTED }

    public static final class PodView {
        public PodState state = PodState.ABSENT;
        public boolean unlocked;
        public long lastSeen;
        public String addr;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Protocol.Side, PodView> pods = new EnumMap<>(Protocol.Side.class);
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
    private boolean notifyPosted;

    public volatile boolean bleRunning;
    public volatile String lastButton;

    private AppState() {
        for (Protocol.Side s : Protocol.Side.values()) pods.put(s, new PodView());
    }

    // --- pods -------------------------------------------------------------

    public synchronized PodView pod(Protocol.Side side) {
        PodView src = pods.get(side);
        PodView copy = new PodView();
        copy.state = src.state;
        copy.unlocked = src.unlocked;
        copy.lastSeen = src.lastSeen;
        copy.addr = src.addr;
        // un pod vu il y a plus de 30 s n'est plus « détecté »
        if (copy.state == PodState.SEEN && System.currentTimeMillis() - copy.lastSeen > 30000) {
            copy.state = PodState.ABSENT;
        }
        return copy;
    }

    public void podSeen(Protocol.Side side, String addr) {
        synchronized (this) {
            PodView p = pods.get(side);
            p.lastSeen = System.currentTimeMillis();
            if (p.state == PodState.ABSENT) {
                p.state = PodState.SEEN;
                p.addr = addr;
            } else {
                return; // pas de changement visible
            }
        }
        changed();
    }

    public void podState(Protocol.Side side, String addr, PodState state) {
        synchronized (this) {
            PodView p = pods.get(side);
            p.state = state;
            p.addr = addr;
            if (state != PodState.CONNECTED) p.unlocked = false;
        }
        changed();
    }

    public void podUnlocked(Protocol.Side side) {
        synchronized (this) {
            pods.get(side).unlocked = true;
        }
        changed();
    }

    public synchronized void resetPods() {
        for (PodView p : pods.values()) {
            p.state = PodState.ABSENT;
            p.unlocked = false;
        }
        main.post(this::fire);
    }

    /** Nombre de pods connectés (pour la couleur de la bulle et la notification). */
    public synchronized int connectedCount() {
        int n = 0;
        for (PodView p : pods.values()) if (p.state == PodState.CONNECTED) n++;
        return n;
    }

    public static String describe(PodView p) {
        switch (p.state) {
            case CONNECTED: return p.unlocked ? "connecté ✓ (déverrouillé)" : "connecté";
            case CONNECTING: return "connexion…";
            case SEEN: return "détecté";
            default: return "non détecté";
        }
    }

    // --- journal ----------------------------------------------------------

    public void log(String msg) {
        String line = fmt.format(new Date()) + "  " + msg;
        synchronized (log) {
            log.addLast(line);
            while (log.size() > 200) log.removeFirst();
        }
        android.util.Log.i("ZwiftBoard", msg);
        changed();
    }

    public String logText(int lines) {
        synchronized (log) {
            List<String> all = new ArrayList<>(log);
            int from = Math.max(0, all.size() - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = all.size() - 1; i >= from; i--) { // plus récent en haut
                sb.append(all.get(i)).append('\n');
            }
            return sb.toString();
        }
    }

    // --- boutons ----------------------------------------------------------

    /** Appelé par le service Bluetooth (n'importe quel thread). */
    public void buttonPressed(String button) {
        lastButton = button;
        main.post(() -> {
            TapService s = TapService.instance;
            if (s != null) {
                s.onButtonPress(button);
            } else {
                log("Bouton " + Protocol.label(button) + " : service d'accessibilité inactif, aucune touche envoyée");
            }
            fire();
        });
    }

    public void buttonReleased(String button) {
        main.post(() -> {
            TapService s = TapService.instance;
            if (s != null) s.onButtonRelease(button);
        });
    }

    // --- écouteurs --------------------------------------------------------

    public void addListener(Runnable r) {
        main.post(() -> listeners.add(r));
    }

    public void removeListener(Runnable r) {
        main.post(() -> listeners.remove(r));
    }

    /** Signale un changement ; regroupe les notifications (max ~10/s). */
    public void changed() {
        synchronized (this) {
            if (notifyPosted) return;
            notifyPosted = true;
        }
        main.postDelayed(() -> {
            synchronized (AppState.this) {
                notifyPosted = false;
            }
            fire();
        }, 100);
    }

    private void fire() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }
}
