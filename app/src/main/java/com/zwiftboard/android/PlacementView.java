package com.zwiftboard.android;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Écran de placement, affiché par-dessus MyWhoosh.
 * 1) choisir un bouton : en touchant sa pastille, ou en appuyant dessus sur le Click ;
 * 2) toucher l'écran à l'endroit où l'appui doit être simulé.
 * Les positions sont enregistrées en fraction de l'écran, séparément en paysage et en portrait.
 */
@SuppressLint("ViewConstructor")
public class PlacementView extends FrameLayout {

    private final TapService svc;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextView title;
    private final LinearLayout panel;
    private final Map<String, TextView> chips = new LinkedHashMap<>();
    private final int[] loc = new int[2];
    private String selected;
    private boolean panelAtTop = true;

    public PlacementView(TapService svc) {
        super(svc);
        this.svc = svc;
        setWillNotDraw(false);
        setBackgroundColor(0x33000000);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(svc.dp(2));
        stroke.setColor(Color.WHITE);
        text.setColor(Color.BLACK);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(svc.dp(16));

        panel = new LinearLayout(svc);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true); // les touches sur le panneau ne placent pas de point
        int pad = svc.dp(12);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6202124);
        bg.setCornerRadius(svc.dp(14));
        panel.setBackground(bg);

        title = new TextView(svc);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        panel.addView(title);

        HorizontalScrollView hs = new HorizontalScrollView(svc);
        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String b : Protocol.BUTTONS.values()) {
            TextView chip = new TextView(svc);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setPadding(svc.dp(12), svc.dp(8), svc.dp(12), svc.dp(8));
            chip.setOnClickListener(v -> select(b));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, svc.dp(8), svc.dp(8), 0);
            row.addView(chip, lp);
            chips.put(b, chip);
        }
        hs.addView(row);
        panel.addView(hs);

        LinearLayout actions = new LinearLayout(svc);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.addView(action("Effacer ce point", v -> {
            if (selected != null) {
                Prefs.clearPos(svc, selected, svc.isLandscape());
                refresh();
            }
        }));
        actions.addView(action("Panneau ⇅", v -> {
            panelAtTop = !panelAtTop;
            placePanel();
        }));
        actions.addView(action("Terminer", v -> svc.hidePlacement()));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = svc.dp(10);
        panel.addView(actions, alp);

        addView(panel);
        placePanel();

        // présélection : premier bouton du module droit sans position
        String first = null;
        for (String b : Protocol.BUTTONS.values()) {
            if (Prefs.getPos(svc, b, svc.isLandscape()) == null) {
                first = b;
                break;
            }
        }
        select(first != null ? first : "PLUS");
    }

    private TextView action(String label, OnClickListener l) {
        TextView t = new TextView(svc);
        t.setText(label);
        t.setTextColor(0xFF8AB4F8);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(svc.dp(12), svc.dp(8), svc.dp(12), svc.dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private void placePanel() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(svc.dp(560), svc.screenSize().x - svc.dp(32)), LayoutParams.WRAP_CONTENT);
        lp.gravity = (panelAtTop ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = svc.dp(36);
        lp.bottomMargin = svc.dp(36);
        panel.setLayoutParams(lp);
    }

    /** Sélectionne le bouton à placer (depuis une pastille ou un appui sur le Click). */
    void select(String button) {
        selected = button;
        refresh();
    }

    private void refresh() {
        boolean land = svc.isLandscape();
        for (Map.Entry<String, TextView> e : chips.entrySet()) {
            String b = e.getKey();
            TextView chip = e.getValue();
            boolean has = Prefs.getPos(svc, b, land) != null;
            chip.setText(Protocol.label(b) + (has ? " ✓" : ""));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(svc.dp(16));
            if (b.equals(selected)) {
                bg.setColor(0xFFFBBC04);
                chip.setTextColor(Color.BLACK);
            } else {
                bg.setColor(has ? 0xFF1E8E3E : 0xFF3C4043);
                chip.setTextColor(Color.WHITE);
            }
            chip.setBackground(bg);
        }
        if (selected != null) {
            boolean has = Prefs.getPos(svc, selected, land) != null;
            title.setText((has ? "Déplacer le point de « " : "Touchez l'écran pour placer « ")
                    + Protocol.label(selected) + " ».\n"
                    + "Autre bouton : touchez sa pastille ou appuyez dessus sur le Click.");
        }
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
        if (e.getActionMasked() == MotionEvent.ACTION_UP && selected != null) {
            Point s = svc.screenSize();
            float fx = e.getRawX() / s.x;
            float fy = e.getRawY() / s.y;
            Prefs.setPos(svc, selected, svc.isLandscape(), fx, fy);
            AppState.I.log("Point « " + Protocol.label(selected) + " » placé en "
                    + Math.round(e.getRawX()) + "," + Math.round(e.getRawY()));
            refresh();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        placePanel();
        refresh();
    }

    private static String shortLabel(String b) {
        switch (b) {
            case "PLUS": return "+";
            case "MIN": return "−";
            case "LEFT": return "◀";
            case "UP": return "▲";
            case "RIGHT": return "▶";
            case "DOWN": return "▼";
            default: return b;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        getLocationOnScreen(loc);
        Point s = svc.screenSize();
        boolean land = svc.isLandscape();
        float r = svc.dp(20);
        for (String b : Protocol.BUTTONS.values()) {
            float[] p = Prefs.getPos(svc, b, land);
            if (p == null) continue;
            float x = p[0] * s.x - loc[0];
            float y = p[1] * s.y - loc[1];
            fill.setColor(b.equals(selected) ? 0xFFFBBC04 : 0xE68AB4F8);
            c.drawCircle(x, y, r, fill);
            c.drawCircle(x, y, r, stroke);
            c.drawText(shortLabel(b), x, y - (text.descent() + text.ascent()) / 2, text);
        }
    }
}
