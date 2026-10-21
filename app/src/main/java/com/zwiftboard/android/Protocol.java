package com.zwiftboard.android;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Faits du protocole Zwift Click V2, repris de zwiftboard (internal/zwift/protocol.go
 * et internal/ble/ble.go, licence MIT, Thomas Champagne).
 *
 * - activation : écrire "RideOn"+02 03, puis 00 08 00, puis 00 08 10 (100 ms d'écart)
 * - un pod déverrouillé renvoie "RideOn" sur SyncTX ; on répond ff 04 00
 * - le défi crypto 0xFF 0x03 arrive sur ASYNC : on répond ff 04 00
 * - keepalive : renvoyer "RideOn" toutes les 3 s (sonde de vie)
 * - après ~55 s sans activité : écrire 0x18 pour redémarrer le pod avant
 *   sa mise en veille (~65 s) ; il ré-émet en quelques secondes et on se reconnecte
 * - trames boutons : 0x23 + protobuf ; champ 1 = bitmap uint32, bit à 0 = appuyé
 * - côté du pod : 1er octet des données fabricant (société 0x094A) : 0x0B gauche, 0x0A droit
 */
public final class Protocol {
    private Protocol() {}

    public static final int COMPANY_ID = 0x094A;

    public static final UUID SERVICE = UUID.fromString("00000001-19ca-4651-86e5-fa29dcdd09d1");
    public static final UUID ASYNC = UUID.fromString("00000002-19ca-4651-86e5-fa29dcdd09d1");   // notify : boutons
    public static final UUID SYNC_RX = UUID.fromString("00000003-19ca-4651-86e5-fa29dcdd09d1"); // write : handshake / keepalive
    public static final UUID SYNC_TX = UUID.fromString("00000004-19ca-4651-86e5-fa29dcdd09d1"); // indicate : réponse handshake
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final int MSG_KEYPAD = 0x23;

    public static final int POD_RIGHT_ID = 0x0A;
    public static final int POD_LEFT_ID = 0x0B;

    public static final byte[] RIDE_ON = {'R', 'i', 'd', 'e', 'O', 'n'};
    public static final byte[] HANDSHAKE_START = {'R', 'i', 'd', 'e', 'O', 'n', 0x02, 0x03};
    public static final byte[] HANDSHAKE_2 = {0x00, 0x08, 0x00};
    public static final byte[] HANDSHAKE_3 = {0x00, 0x08, 0x10};
    public static final byte[] ACK = {(byte) 0xFF, 0x04, 0x00};
    public static final byte[] RESET_POD = {0x18};

    public static final long KEEPALIVE_MS = 3000;
    public static final long IDLE_RESET_MS = 55000;

    public enum Side { LEFT, RIGHT, UNKNOWN }

    /** Boutons du Click V2 : bit -> nom. Ordre d'affichage : module droit d'abord (toujours utilisable). */
    public static final Map<Integer, String> BUTTONS = new LinkedHashMap<>();
    static {
        BUTTONS.put(0x01000, "PLUS");
        BUTTONS.put(0x00010, "A");
        BUTTONS.put(0x00020, "B");
        BUTTONS.put(0x00040, "Y");
        BUTTONS.put(0x00080, "Z");
        BUTTONS.put(0x00100, "MIN");
        BUTTONS.put(0x00001, "LEFT");
        BUTTONS.put(0x00002, "UP");
        BUTTONS.put(0x00004, "RIGHT");
        BUTTONS.put(0x00008, "DOWN");
    }

    public static final int KNOWN_MASK;
    static {
        int m = 0;
        for (int k : BUTTONS.keySet()) m |= k;
        KNOWN_MASK = m;
    }

    /** Noms affichés à l'utilisateur. */
    public static String label(String button) {
        switch (button) {
            case "PLUS": return "+ (droit)";
            case "A": return "A (droit)";
            case "B": return "B (droit)";
            case "Y": return "Y (droit)";
            case "Z": return "Z (droit)";
            case "MIN": return "− (gauche)";
            case "LEFT": return "◀ (gauche)";
            case "UP": return "▲ (gauche)";
            case "RIGHT": return "▶ (gauche)";
            case "DOWN": return "▼ (gauche)";
            default: return button;
        }
    }

    public static Side side(int deviceId) {
        if (deviceId == POD_LEFT_ID) return Side.LEFT;
        if (deviceId == POD_RIGHT_ID) return Side.RIGHT;
        return Side.UNKNOWN;
    }

    /**
     * Extrait le champ protobuf 1 (varint) = bitmap des boutons, à partir de la charge
     * utile qui suit l'octet 0x23. Retourne null si la trame est invalide.
     */
    public static Long bitmap(byte[] p, int offset) {
        int i = offset;
        while (i < p.length) {
            long[] tag = uvarint(p, i);
            if (tag == null) return null;
            i += (int) tag[1];
            long num = tag[0] >>> 3;
            int wt = (int) (tag[0] & 7);
            switch (wt) {
                case 0: {
                    long[] v = uvarint(p, i);
                    if (v == null) return null;
                    i += (int) v[1];
                    if (num == 1) return v[0] & 0xFFFFFFFFL;
                    break;
                }
                case 1: i += 8; break;
                case 2: {
                    long[] l = uvarint(p, i);
                    if (l == null) return null;
                    i += (int) l[1] + (int) l[0];
                    break;
                }
                case 5: i += 4; break;
                default: return null;
            }
        }
        return null;
    }

    /** Retourne {valeur, nombre d'octets lus} ou null. */
    static long[] uvarint(byte[] b, int start) {
        long x = 0;
        for (int i = 0; start + i < b.length; i++) {
            if (i == 10) return null;
            int c = b[start + i] & 0xFF;
            x |= (long) (c & 0x7F) << (7 * i);
            if (c < 0x80) return new long[]{x, i + 1};
        }
        return null;
    }

    public static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", x & 0xFF));
        }
        return sb.toString();
    }
}
