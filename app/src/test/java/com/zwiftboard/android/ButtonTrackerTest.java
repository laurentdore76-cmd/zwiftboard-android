package com.zwiftboard.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class ButtonTrackerTest {

    private static final long IDLE = 0xFFFFFFFFL;
    private final List<String> events = new ArrayList<>();
    private ButtonTracker.Registry reg;

    /** 0x23 + champ protobuf 1 (varint) = bitmap, encadré d'autres champs. */
    private static byte[] frame(long bitmap) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x23);
        b.write(0x12); b.write(2); b.write(9); b.write(9); // champ 2, longueur 2
        b.write(0x08);
        long v = bitmap;
        while (true) {
            int c = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) b.write(c | 0x80); else { b.write(c); break; }
        }
        b.write(0x18); b.write(5); // champ 3, varint
        return b.toByteArray();
    }

    @Before
    public void setUp() {
        events.clear();
        reg = new ButtonTracker.Registry(new ButtonTracker.Listener() {
            @Override public void onPress(String b) { events.add("+" + b); }
            @Override public void onRelease(String b) { events.add("-" + b); }
        });
    }

    @Test
    public void decodesBitmap() {
        long plus = IDLE & ~0x1000L;
        assertEquals(Long.valueOf(plus), Protocol.bitmap(frame(plus), 1));
        assertNull(Protocol.bitmap(new byte[]{0x23, (byte) 0xFF}, 1));
    }

    @Test
    public void mirroredPressFiresOnce() {
        ButtonTracker left = new ButtonTracker(reg), right = new ButtonTracker(reg);
        long plus = IDLE & ~0x1000L;
        right.onFrame(frame(IDLE));
        right.onFrame(frame(plus));
        left.onFrame(frame(plus));
        right.onFrame(frame(plus));
        assertEquals(Arrays.asList("+PLUS"), events);
        right.onFrame(frame(IDLE));
        assertEquals(1, events.size());
        left.onFrame(frame(IDLE));
        assertEquals(Arrays.asList("+PLUS", "-PLUS"), events);
    }

    @Test
    public void disconnectReleasesHeldButtons() {
        ButtonTracker t = new ButtonTracker(reg);
        t.onFrame(frame(IDLE & ~0x10L & ~0x20L));
        assertEquals(2, events.size());
        t.releaseAll();
        assertEquals(4, events.size());
        assertFalse(reg.isHeld("A"));
    }

    @Test
    public void ignoresNonKeypadFrames() {
        assertFalse(new ButtonTracker(reg).onFrame(new byte[]{(byte) 0xFF, 0x03}));
    }
}
