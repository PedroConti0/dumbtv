package tv.dumbtv.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

final class Ui {
    static final int TEXT = 0xFFF2F2F3;
    static final int MUTED = 0x99FFFFFF;
    static final int FAINT = 0x66FFFFFF;
    static final int SURFACE = 0xFF1C1D21;
    static final int HOME_CANVAS = 0xFF0B1220;
    static final int HOME_CARD_TOP = 0xFF202B42;
    static final int HOME_CARD_BOTTOM = 0xFF111724;
    static final int HOME_SECONDARY = 0xFFB5C0D0;
    static final int HOME_ACCENT = 0xFF3D8BFF;
    static final int CLOCK_START = 0xFFF7FAFF;
    static final int CLOCK_END = 0xFFBBD2FF;
    static final int CLOCK_DATE = 0xFFCBD5E5;
    static final int DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int sp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().scaledDensity);
    }

    /** Black on light accents, white on dark ones. */
    static int ink(int color) {
        // WCAG AA body text passes with black once the background luminance is ~0.18;
        // choosing white up to 0.5 left blue focus chips below 4.5:1.
        return Color.luminance(color) > 0.179f ? 0xFF000000 : 0xFFFFFFFF;
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    /** Oversized, tabular clock numerals with a restrained cool highlight on the dark shelf. */
    static final class TimeTextView extends TextView {
        private boolean signature;

        TimeTextView(Context c) {
            super(c);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 80);
            setTextScaleX(0.86f);
            setTextColor(CLOCK_START);
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            setIncludeFontPadding(false);
            setLetterSpacing(0.005f);
            setFontFeatureSettings("'tnum'");
        }

        void setSignature(boolean enabled) {
            signature = enabled;
            updateShader();
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updateShader();
        }

        private void updateShader() {
            getPaint().setShader(signature && getWidth() > 1
                    ? new LinearGradient(0, 0, getWidth(), 0, CLOCK_START, CLOCK_END, Shader.TileMode.CLAMP)
                    : null);
            invalidate();
        }
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Translucent functional chrome for the paired header controls. */
    static GradientDrawable frostedPanel(Context c, float radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xF02A303A, 0xF0181C24});
        d.setCornerRadius(dp(c, radius));
        d.setStroke(dp(c, 1), 0x48FFFFFF);
        return d;
    }

    /** Menu rows are quiet until focused or hovered, then invert for clear contrast. */
    static void menuFocusStyle(TextView v, float radius) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, round(Color.WHITE, radius));
        bg.addState(new int[] {android.R.attr.state_hovered}, round(Color.WHITE, radius));
        bg.addState(new int[0], round(Color.TRANSPARENT, radius));
        v.setBackground(bg);
        v.setTextColor(new ColorStateList(new int[][] {
                {android.R.attr.state_focused}, {android.R.attr.state_hovered}, {}
        }, new int[] {0xFF111318, 0xFF111318, Color.WHITE}));
    }

    /** Accent fill when focused, a faint fill otherwise. */
    static void focusStyle(TextView v, int accent, int idle, float radius) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, round(accent, radius));
        bg.addState(new int[0], round(idle, radius));
        v.setBackground(bg);
        v.setTextColor(focused(ink(accent), 0xD9FFFFFF));
    }

    /**
     * A chip icon to go with {@link #focusStyle}: {@code shape} in the chip's text colour and
     * {@code mark} in the accent, both turning the ink colour on the accent fill when focused.
     */
    static Drawable chipIcon(Drawable shape, Drawable mark, int accent, int size) {
        shape.mutate().setTintList(focused(ink(accent), 0xD9FFFFFF));
        mark.mutate().setTintList(focused(ink(accent), accent));
        LayerDrawable d = new LayerDrawable(new Drawable[] {shape, mark});
        d.setBounds(0, 0, size, size);
        return d;
    }

    private static ColorStateList focused(int on, int off) {
        return new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {on, off});
    }

    static ColorStateList focusedColor(int accent, int off) {
        return focused(ink(accent), off);
    }
}
