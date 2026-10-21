package com.zwiftboard.android;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Décodage des trames boutons d'UN pod, avec détection de fronts.
 *
 * Les deux pods du Click V2 renvoient chacun la même trame pour un même appui
 * (fonctionnement en miroir, cf. zwiftboard). Deux niveaux de dédoublonnage :
 * - par pod : seuls les bits qui ont changé depuis la trame précédente agissent ;
 * - entre pods : un compteur partagé (Registry) ne déclenche l'action qu'au
 *   premier appui vu et le relâchement qu'au dernier.
 *
 * Classe sans dépendance Android : testable sur la JVM.
 */
public final class ButtonTracker {

    /** Reçoit les appuis/relâchements dédoublonnés, tous pods confondus. */
    public interface Listener {
        void onPress(String button);
        void onRelease(String button);
    }

    /** Compteur partagé entre les pods connectés. */
    public static final class Registry {
        private final Map<String, Integer> held = new HashMap<>();
        private final Listener listener;

        public Registry(Listener listener) {
            this.listener = listener;
        }

        void press(String button) {
            boolean first;
            synchronized (held) {
                int n = held.getOrDefault(button, 0) + 1;
                held.put(button, n);
                first = n == 1;
            }
            if (first) listener.onPress(button);
        }

        void release(String button) {
            boolean last;
            synchronized (held) {
                Integer n = held.get(button);
                if (n == null) return;
                if (n <= 1) {
                    held.remove(button);
                    last = true;
                } else {
                    held.put(button, n - 1);
                    last = false;
                }
            }
            if (last) listener.onRelease(button);
        }

        public boolean isHeld(String button) {
            synchronized (held) {
                return held.containsKey(button);
            }
        }

        public void clear() {
            synchronized (held) {
                held.clear();
            }
        }
    }

    private final Registry registry;
    private long prev = 0xFFFFFFFFL; // tout relâché
    private final Set<String> own = new HashSet<>();

    public ButtonTracker(Registry registry) {
        this.registry = registry;
    }

    /** Traite une trame reçue sur la caractéristique ASYNC. Retourne true si c'était une trame boutons. */
    public synchronized boolean onFrame(byte[] b) {
        if (b == null || b.length == 0 || (b[0] & 0xFF) != Protocol.MSG_KEYPAD) return false;
        Long cur = Protocol.bitmap(b, 1);
        if (cur == null) return false;
        long changed = (cur ^ prev) & Protocol.KNOWN_MASK;
        if (changed != 0) {
            for (Map.Entry<Integer, String> e : Protocol.BUTTONS.entrySet()) {
                long bit = e.getKey();
                if ((changed & bit) == 0) continue;
                String name = e.getValue();
                boolean pressed = (cur & bit) == 0;
                if (pressed) {
                    if (own.add(name)) registry.press(name);
                } else {
                    if (own.remove(name)) registry.release(name);
                }
            }
        }
        prev = cur;
        return true;
    }

    /** À appeler quand la connexion de ce pod se termine : aucun bouton ne doit rester « appuyé ». */
    public synchronized void releaseAll() {
        for (String name : own) registry.release(name);
        own.clear();
        prev = 0xFFFFFFFFL;
    }
}
