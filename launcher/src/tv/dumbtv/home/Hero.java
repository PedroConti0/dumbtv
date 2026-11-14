package tv.dumbtv.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * The library banner: a wide picture of a title, cross-fading to the next one
 * every few seconds. OK opens the title in its app, left and right step
 * through the titles. Only the picture on screen and the one fading in are kept in memory.
 */
final class Hero extends FrameLayout {
    static final long INTERVAL_MS = 30000, AFTER_KEY_MS = 15000, FADE_MS = 900;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    interface TintListener { void onPosterTintChanged(int color); }

    private static final class BottomFade extends View {
        private final Paint paint = new Paint();
        private int posterHeight;

        BottomFade(Context c) {
            super(c);
        }

        void setPosterHeight(int height) {
            posterHeight = height;
            updateShader();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            updateShader();
        }

        private void updateShader() {
            int h = getHeight();
            if (h <= 0) return;
            float end = Math.min(h, Math.max(1, posterHeight));
            float start = end * 0.60f;
            paint.setShader(new LinearGradient(0, start, 0, end,
                    new int[] {0x00000000, 0xB8000000, Color.BLACK},
                    new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }

    private final Handler ui;
    private final ExecutorService io;
    private final FrameLayout artwork;
    private final ImageView[] images = new ImageView[2];
    private final View sideShade;
    private final BottomFade bottomFade;
    private final TextView title, description, sub;
    private final LinearLayout dots;
    private final LinearLayout caption;
    private final LinearLayout details;
    private List<Library.Entry> entries = new ArrayList<>();
    private String entriesKey = "";
    private String vpnLocation = "";
    private int posterTint = 0xFF25282D;
    private int posterHeight;
    private TintListener tintListener;
    private int index, top = -1, generation;
    private boolean running;
    private ObjectAnimator fade;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            if (!running || entries.size() < 2) return;
            show(index + 1, true);
            ui.postDelayed(this, INTERVAL_MS);
        }
    };

    Hero(Context c, Handler ui, ExecutorService io) {
        super(c);
        this.ui = ui;
        this.io = io;
        setFocusable(true);
        setClickable(true);
        setBackgroundColor(Color.BLACK);
        setClipChildren(false);
        setClipToPadding(false);

        artwork = new FrameLayout(c);
        artwork.setBackgroundColor(Color.BLACK);
        artwork.setClipChildren(true);
        artwork.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRect(0, 0, v.getWidth(), v.getHeight());
            }
        });
        artwork.setClipToOutline(true);
        posterHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        addView(artwork, new LayoutParams(MATCH, posterHeight));

        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(c);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            images[i] = v;
            artwork.addView(v, new LayoutParams(MATCH, posterHeight));
        }

        View side = new View(c);
        side.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x70000000, 0x28000000, 0x00000000}));
        sideShade = side;
        artwork.addView(sideShade, new LayoutParams(MATCH, posterHeight));
        bottomFade = new BottomFade(c);
        bottomFade.setPosterHeight(posterHeight);
        artwork.addView(bottomFade, new LayoutParams(MATCH, posterHeight));

        title = Ui.text(c, 28, Ui.TEXT, Ui.MEDIUM);
        title.setSingleLine();
        title.setShadowLayer(dp(6), 0, 0, 0x99000000);

        caption = new LinearLayout(c);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.addView(title, new LinearLayout.LayoutParams(WRAP, WRAP));

        details = new LinearLayout(c);
        details.setOrientation(LinearLayout.VERTICAL);
        description = Ui.text(c, 16, Ui.TEXT, Ui.MEDIUM);
        description.setMaxLines(2);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        description.setShadowLayer(dp(5), 0, 0, 0x99000000);
        description.setPadding(dp(9), dp(7), dp(9), dp(7));
        description.setFocusable(true);
        description.setClickable(true);
        Ui.frostedHoverStyle(description, 10);
        sub = Ui.text(c, 13, Ui.MUTED, Ui.MEDIUM);
        sub.setLetterSpacing(0.06f);
        sub.setPadding(0, dp(6), 0, 0);
        details.addView(description, new LinearLayout.LayoutParams(MATCH, WRAP));
        details.addView(sub, new LinearLayout.LayoutParams(WRAP, WRAP));
        caption.addView(details, new LinearLayout.LayoutParams(MATCH, WRAP));

        dots = new LinearLayout(c);
        LayoutParams dotLp = new LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END);
        dotLp.rightMargin = dp(24);
        dotLp.bottomMargin = dp(26);
        addView(dots, dotLp);

        setVisibility(GONE);
    }

    void setDescriptionClickListener(OnClickListener listener) {
        description.setOnClickListener(listener);
    }

    boolean ownsCarouselFocus(View focused) {
        return focused == this || focused == description;
    }

    void setAccent(int accent) {
        refreshDots();
        invalidate();
    }

    void setTintListener(TintListener listener) {
        tintListener = listener;
        if (listener != null) listener.onPosterTintChanged(posterTint);
    }

    /** The full caption and page dots scroll with the page, over the moving artwork layer. */
    void attachContentTo(FrameLayout slot) {
        removeView(caption);
        removeView(dots);
        FrameLayout.LayoutParams captionLp = new FrameLayout.LayoutParams(
                dp(520), WRAP, Gravity.BOTTOM | Gravity.START);
        captionLp.leftMargin = dp(28);
        captionLp.bottomMargin = dp(22);
        slot.addView(caption, captionLp);
        FrameLayout.LayoutParams dotsLp = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.BOTTOM | Gravity.END);
        dotsLp.rightMargin = dp(24);
        dotsLp.bottomMargin = dp(26);
        slot.addView(dots, dotsLp);
    }

    void setArtworkBottom(int bottom) {
        int height = Math.max(1, bottom);
        LayoutParams lp = (LayoutParams) artwork.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            artwork.setLayoutParams(lp);
        }
        LayoutParams sideLp = (LayoutParams) sideShade.getLayoutParams();
        if (sideLp.height != posterHeight) {
            sideLp.height = posterHeight;
            sideShade.setLayoutParams(sideLp);
        }
        LayoutParams fadeLp = (LayoutParams) bottomFade.getLayoutParams();
        if (fadeLp.height != height) {
            fadeLp.height = height;
            bottomFade.setLayoutParams(fadeLp);
        }
    }

    void setPosterHeight(int height) {
        posterHeight = Math.max(1, height);
        for (ImageView image : images) {
            LayoutParams lp = (LayoutParams) image.getLayoutParams();
            if (lp.height != posterHeight) {
                lp.height = posterHeight;
                image.setLayoutParams(lp);
            }
        }
        LayoutParams sideLp = (LayoutParams) sideShade.getLayoutParams();
        sideLp.height = posterHeight;
        sideShade.setLayoutParams(sideLp);
        bottomFade.setPosterHeight(posterHeight);
    }

    void setArtworkScroll(int scrollY) {
        artwork.setTranslationY(-scrollY);
    }

    void setVpnLocation(String location) {
        String next = location == null ? "" : location.trim();
        if (next.equals(vpnLocation)) return;
        vpnLocation = next;
        updateCaption();
    }

    /** Swaps in a new list; nothing reloads when the titles and pictures are the same. */
    void setEntries(List<Library.Entry> list) {
        StringBuilder k = new StringBuilder();
        for (Library.Entry e : list) k.append(e.key()).append('\n');
        String key = k.toString();
        if (key.equals(entriesKey)) return;
        entriesKey = key;
        entries = list;
        generation++;
        rebuildDots();
        if (list.isEmpty()) {
            setVisibility(GONE);
            for (ImageView v : images) v.setImageDrawable(null);
            top = -1;
            return;
        }
        setVisibility(VISIBLE);
        index = 0;
        show(0, false);
        reschedule(INTERVAL_MS);
    }

    boolean hasEntries() {
        return !entries.isEmpty();
    }

    void start() {
        running = true;
        reschedule(INTERVAL_MS);
    }

    void stop() {
        running = false;
        ui.removeCallbacks(advance);
    }

    void openCurrent() {
        if (entries.isEmpty()) return;
        Library.open(getContext(), entries.get(index));
    }

    private void reschedule(long delay) {
        ui.removeCallbacks(advance);
        if (running && entries.size() > 1) ui.postDelayed(advance, delay);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (entries.size() > 1
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            show(index + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1), true);
            reschedule(AFTER_KEY_MS);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onFocusChanged(boolean has, int direction, Rect previous) {
        super.onFocusChanged(has, direction, previous);
        updateCaption();
    }

    private void show(int i, boolean animate) {
        int n = entries.size();
        if (n == 0) return;
        index = ((i % n) + n) % n;
        final Library.Entry e = entries.get(index);
        final int g = ++generation;
        final int w = Math.max(getWidth(), getResources().getDisplayMetrics().widthPixels - dp(112));
        final int h = Math.max(posterHeight, dp(220));
        updateCaption();
        refreshDots();
        try {
            io.execute(() -> {
                final Bitmap b = decode(e.file, w, h);
                ui.post(() -> {
                    if (g == generation) present(b, animate);
                });
            });
        } catch (RejectedExecutionException closing) {
            // the activity is going away
        }
    }

    private void present(Bitmap b, boolean animate) {
        if (b == null) return;
        int sampled = dominantColor(b);
        if (sampled != posterTint) {
            posterTint = sampled;
            if (tintListener != null) tintListener.onPosterTintChanged(sampled);
        }
        int next = top < 0 ? 0 : 1 - top;
        final ImageView in = images[next];
        final ImageView out = top < 0 ? null : images[top];
        in.setImageBitmap(b);
        // The incoming picture is drawn over the outgoing one while it fades in.
        artwork.removeView(in);
        artwork.addView(in, 1, new LayoutParams(MATCH, posterHeight));
        if (out != null && artwork.indexOfChild(out) != 0) {
            artwork.removeView(out);
            artwork.addView(out, 0, new LayoutParams(MATCH, posterHeight));
        }
        top = next;
        if (fade != null) fade.cancel();
        if (!animate || out == null) {
            in.setAlpha(1f);
            if (out != null) {
                out.setAlpha(0f);
                out.setImageDrawable(null);
            }
        } else {
            // A fade cut short by a quick key press leaves the picture below half see-through.
            out.setAlpha(1f);
            in.setAlpha(0f);
            fade = ObjectAnimator.ofFloat(in, View.ALPHA, 0f, 1f);
            fade.setDuration(FADE_MS);
            fade.setInterpolator(new DecelerateInterpolator());
            fade.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator a) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator a) {
                    if (cancelled) return;
                    out.setAlpha(0f);
                    out.setImageDrawable(null);
                }
            });
            fade.start();
        }
    }

    /** Finds the most common coarse RGB color from a small grid of the already-scaled poster. */
    private static int dominantColor(Bitmap bitmap) {
        int[] bins = new int[4096];
        int sx = Math.max(1, bitmap.getWidth() / 48);
        int sy = Math.max(1, bitmap.getHeight() / 48);
        for (int y = sy / 2; y < bitmap.getHeight(); y += sy) {
            for (int x = sx / 2; x < bitmap.getWidth(); x += sx) {
                int c = bitmap.getPixel(x, y);
                int r = (Color.red(c) >> 4), g = (Color.green(c) >> 4), b = (Color.blue(c) >> 4);
                bins[(r << 8) | (g << 4) | b]++;
            }
        }
        int best = 0;
        for (int i = 1; i < bins.length; i++) if (bins[i] > bins[best]) best = i;
        return Color.rgb(((best >> 8) & 15) * 16 + 8,
                ((best >> 4) & 15) * 16 + 8, (best & 15) * 16 + 8);
    }

    private void updateCaption() {
        if (entries.isEmpty()) return;
        Library.Entry e = entries.get(index);
        title.setText(e.title);
        description.setText(e.description);
        description.setVisibility(e.description.isEmpty() ? GONE : VISIBLE);
        boolean netflix = "com.netflix.ninja".equals(e.pkg)
                || e.uri.toLowerCase(java.util.Locale.ROOT).contains("netflix.com/");
        String app = netflix ? "Netflix"
                : (e.appLabel == null ? e.pkg : e.appLabel.toString());
        String source = e.extras.get("source");
        String provider = (source == null || source.isEmpty() ? app : source)
                .toUpperCase(java.util.Locale.ROOT);
        if (!vpnLocation.isEmpty()) {
            provider += "   ·   " + vpnLocation.toUpperCase(java.util.Locale.ROOT);
        }
        sub.setText(provider);
        caption.setAlpha(0f);
        caption.animate().alpha(1f).setDuration(350).start();
    }

    private void rebuildDots() {
        dots.removeAllViews();
        if (entries.size() < 2) return;
        for (int i = 0; i < entries.size(); i++) {
            View d = new View(getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
            lp.leftMargin = dp(7);
            dots.addView(d, lp);
        }
        refreshDots();
    }

    private void refreshDots() {
        for (int i = 0; i < dots.getChildCount(); i++) {
            boolean on = i == index;
            dots.getChildAt(i).setBackground(Ui.round(on ? 0xFFFFFFFF : 0x55FFFFFF, dp(4)));
        }
    }

    /** Decodes at about the size shown, without alpha: half the memory of a full-colour bitmap. */
    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
            o.inSampleSize *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }
}
