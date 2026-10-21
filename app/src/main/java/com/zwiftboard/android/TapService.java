package com.zwiftboard.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Service d'accessibilité : simule un appui tactile à la position associée à chaque
 * bouton du Click, affiche la bulle flottante et l'écran de placement des points.
 */
public class TapService extends AccessibilityService {

    static volatile TapService instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable stateListener = this::refreshBubble;
    private final Map<String, Runnable> repeaters = new HashMap<>();
    private final Set<String> warnedNoPos = new HashSet<>();
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private PlacementView placement;
    private WindowManager.LayoutParams placementLp;

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        AppState.I.addListener(stateListener);
        updateBubbleVisibility();
        AppState.I.log("Service d'accessibilité actif");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // rien : on n'observe pas l'écran, on ne fait qu'injecter des appuis
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        if (instance != this) return;
        instance = null;
        AppState.I.removeListener(stateListener);
        for (Runnable r : repeaters.values()) h.removeCallbacks(r);
        repeaters.clear();
        hidePlacement();
        removeBubble();
        AppState.I.log("Service d'accessibilité arrêté");
    }

    // ------------------------------------------------------------------ écran

    /** Taille réelle de l'écran dans l'orientation courante (barres système comprises). */
    @SuppressWarnings("deprecation")
    Point screenSize() {
        Point p = new Point();
        DisplayManager dm = getSystemService(DisplayManager.class);
        Display d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
        if (d != null) {
            d.getRealSize(p);
        } else {
            p.x = getResources().getDisplayMetrics().widthPixels;
            p.y = getResources().getDisplayMetrics().heightPixels;
        }
        return p;
    }

    boolean isLandscape() {
        Point p = screenSize();
        return p.x > p.y;
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------ boutons

    void onButtonPress(String button) {
        if (placement != null) {
            placement.select(button);
            return;
        }
        if (Prefs.get(this, Prefs.ONLY_WHOOSH)) {
            String pkg = activePackage();
            if (pkg == null || !pkg.toLowerCase().contains("whoosh")) {
                AppState.I.log("Bouton " + Protocol.label(button) + " ignoré : MyWhoosh n'est pas au premier plan (" + pkg + ")");
                return;
            }
        }
        float[] pos = Prefs.getPos(this, button, isLandscape());
        if (pos == null) {
            if (warnedNoPos.add(button + isLandscape())) {
                AppState.I.log("Bouton " + Protocol.label(button) + " : aucun point placé dans cette orientation");
            }
            return;
        }
        tap(pos, button);
        if (Prefs.get(this, Prefs.REPEAT)) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    if (!ClickService.isHeld(button) || placement != null) {
                        repeaters.remove(button);
                        return;
                    }
                    float[] p = Prefs.getPos(TapService.this, button, isLandscape());
                    if (p != null) tap(p, null);
                    h.postDelayed(this, 250);
                }
            };
            Runnable old = repeaters.put(button, r);
            if (old != null) h.removeCallbacks(old);
            h.postDelayed(r, 450);
        }
    }

    void onButtonRelease(String button) {
        Runnable r = repeaters.remove(button);
        if (r != null) h.removeCallbacks(r);
    }

    private String activePackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            CharSequence p = root.getPackageName();
            return p == null ? null : p.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void tap(float[] frac, String button) {
        Point s = screenSize();
        float x = Math.max(0, Math.min(s.x - 1, frac[0] * s.x));
        float y = Math.max(0, Math.min(s.y - 1, frac[1] * s.y));
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                AppState.I.log("Appui annulé par le système (doigt sur l'écran au même moment ?)");
            }
        }, null);
        if (button != null) {
            AppState.I.log("Bouton " + Protocol.label(button) + " → appui en " + Math.round(x) + "," + Math.round(y) + (ok ? "" : " (refusé)"));
            flashBubble();
        }
    }

    // ------------------------------------------------------------------ bulle flottante

    void updateBubbleVisibility() {
        if (wm == null) return;
        if (Prefs.get(this, Prefs.BUBBLE)) showBubble();
        else removeBubble();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showBubble() {
        if (bubble != null) return;
        bubble = new TextView(this);
        bubble.setText("ZB");
        bubble.setTextColor(Color.WHITE);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bubble.setGravity(Gravity.CENTER);
        bubble.setContentDescription("ZwiftBoard : placer les points");
        int size = dp(48);
        bubbleLp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = Prefs.getInt(this, "bubble_x", dp(12));
        bubbleLp.y = Prefs.getInt(this, "bubble_y", dp(160));
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = bubbleLp.x;
                        startY = bubbleLp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            bubbleLp.x = startX + Math.round(dx);
                            bubbleLp.y = startY + Math.round(dy);
                            try {
                                wm.updateViewLayout(bubble, bubbleLp);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            Prefs.setInt(TapService.this, "bubble_x", bubbleLp.x);
                            Prefs.setInt(TapService.this, "bubble_y", bubbleLp.y);
                        } else {
                            togglePlacement();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        try {
            wm.addView(bubble, bubbleLp);
        } catch (Exception e) {
            AppState.I.log("Impossible d'afficher la bulle : " + e.getMessage());
            bubble = null;
            return;
        }
        refreshBubble();
    }

    private void removeBubble() {
        if (bubble == null) return;
        try {
            wm.removeView(bubble);
        } catch (Exception ignored) {
        }
        bubble = null;
    }

    private int bubbleColor() {
        if (!AppState.I.bleRunning) return 0xFF757575;    // gris : service Bluetooth arrêté
        int n = AppState.I.connectedCount();
        if (n >= 2) return 0xFF2E7D32;                      // vert : les deux pods
        if (n == 1) return 0xFFEF6C00;                      // orange : un seul pod
        return 0xFFC62828;                                  // rouge : aucun pod
    }

    private void refreshBubble() {
        if (bubble == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(bubbleColor());
        bg.setStroke(dp(2), Color.WHITE);
        bubble.setBackground(bg);
        bubble.setAlpha(0.85f);
    }

    private void flashBubble() {
        if (bubble == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFF1565C0);
        bg.setStroke(dp(2), Color.WHITE);
        bubble.setBackground(bg);
        h.postDelayed(this::refreshBubble, 150);
    }

    // ------------------------------------------------------------------ placement des points

    void togglePlacement() {
        if (placement != null) hidePlacement();
        else showPlacement();
    }

    @SuppressWarnings("deprecation")
    void showPlacement() {
        if (placement != null || wm == null) return;
        placement = new PlacementView(this);
        placementLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        placementLp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            placementLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(placement, placementLp);
        } catch (Exception e) {
            AppState.I.log("Impossible d'afficher l'écran de placement : " + e.getMessage());
            placement = null;
            return;
        }
        // la bulle reste au-dessus pour pouvoir refermer
        if (bubble != null) {
            try {
                wm.removeView(bubble);
                wm.addView(bubble, bubbleLp);
            } catch (Exception ignored) {
            }
        }
        AppState.I.log("Placement des points ouvert (" + (isLandscape() ? "paysage" : "portrait") + ")");
    }

    void hidePlacement() {
        if (placement == null) return;
        try {
            wm.removeView(placement);
        } catch (Exception ignored) {
        }
        placement = null;
        warnedNoPos.clear();
        AppState.I.log("Placement des points terminé");
        AppState.I.changed();
    }
}
