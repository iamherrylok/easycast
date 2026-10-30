import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.*;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;

/**
 * Render the 20s vertical (1080x1920) promo video.
 * Default: stream raw BGR frames to stdout, e.g. `java scripts/PromoVideo.java | ffmpeg -f rawvideo -pix_fmt bgr24 -s 1080x1920 -r 30 -i - ...`.
 * `java scripts/PromoVideo.java png <dir> <seconds...>` writes still frames for review.
 */
class PromoVideo {
    static final int W = 1080, H = 1920, FPS = 30;
    static final double DURATION = 20;
    static final Color CREAM = new Color(255, 246, 236), PEACH = new Color(253, 230, 212), CORAL = new Color(232, 113, 74),
            CORAL_LIGHT = new Color(244, 137, 95), ROOF = new Color(216, 97, 61), BROWN = new Color(62, 44, 35), MUTED = new Color(140, 118, 106),
            PILL = new Color(255, 240, 228), OK = new Color(92, 170, 108), MUSTARD = new Color(242, 179, 91), DAD = new Color(107, 78, 63),
            LINE = new Color(238, 226, 216);
    static final BufferedImage layer = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);

    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        if (args.length > 0 && args[0].equals("png")) {
            for (int i = 2; i < args.length; i++) { render(img, Double.parseDouble(args[i])); ImageIO.write(img, "png", new File(args[1], "frame-" + args[i] + ".png")); }
            return;
        }
        OutputStream out = new BufferedOutputStream(System.out, 1 << 20);
        byte[] data = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int f = 0, frames = (int) Math.round(DURATION * FPS); f < frames; f++) { render(img, f / (double) FPS); out.write(data); }
        out.flush();
    }

    // ---- timing ----
    static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
    static double p(double t, double start, double dur) { return clamp((t - start) / dur); }
    static double out(double x) { return 1 - Math.pow(1 - x, 3); }
    static double inOut(double x) { return x < .5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; }
    static double back(double x) { double c = 1.70158; return x <= 0 ? 0 : 1 + (c + 1) * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2); }
    static double lerp(double a, double b, double x) { return a + (b - a) * x; }
    /** Fade in over 0.4s from `in`, fade out over the 0.4s before `end`. */
    static double window(double t, double in, double end) { return Math.min(out(p(t, in, .4)), 1 - p(t, end - .4, .4)); }

    // ---- drawing helpers ----
    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }
    static Font font(double size, boolean bold) { return new Font("PingFang SC", bold ? Font.BOLD : Font.PLAIN, (int) Math.round(size)); }
    static void alpha(Graphics2D g, double a) { g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamp(a))); }
    static double width(Graphics2D g, String s, Font f) { return g.getFontMetrics(f).getStringBounds(s, g).getWidth(); }
    static void text(Graphics2D g, String s, Font f, Color c, double cx, double y, double a) {
        if (a <= 0) return;
        alpha(g, a); g.setFont(f); g.setColor(c); g.drawString(s, (float) (cx - width(g, s, f) / 2), (float) (y + (1 - a) * 40)); alpha(g, 1);
    }
    static void textLeft(Graphics2D g, String s, Font f, Color c, double x, double y) { g.setFont(f); g.setColor(c); g.drawString(s, (float) x, (float) y); }
    static void rr(Graphics2D g, double x, double y, double w, double h, double r, Color c) { g.setColor(c); g.fill(new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2)); }
    static Shape round(Rectangle2D s, double r) { return new RoundRectangle2D.Double(s.getX(), s.getY(), s.getWidth(), s.getHeight(), r * 2, r * 2); }
    static void circle(Graphics2D g, double cx, double cy, double r, Color c) { g.setColor(c); g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2)); }
    static void pill(Graphics2D g, String s, Font f, Color bg, Color fg, double cx, double cy, double a) {
        if (a <= 0) return;
        double w = width(g, s, f) + f.getSize() * 1.6, h = f.getSize() * 2, dy = (1 - a) * 30;
        alpha(g, a); rr(g, cx - w / 2, cy - h / 2 + dy, w, h, h / 2, bg);
        FontMetrics m = g.getFontMetrics(f); g.setFont(f); g.setColor(fg);
        g.drawString(s, (float) (cx - width(g, s, f) / 2), (float) (cy + dy + (m.getAscent() - m.getDescent()) / 2.0)); alpha(g, 1);
    }

    /** Same shapes as app/src/main/res/drawable/ic_logo.xml (108-unit viewport). */
    static void logo(Graphics2D g0, double cx, double cy, double size, double a) {
        if (a <= 0 || size <= 0) return;
        Graphics2D g = (Graphics2D) g0.create(); alpha(g, a);
        double s = size / 108; g.translate(cx - 54 * s, cy - 56 * s); g.scale(s, s);
        Path2D body = new Path2D.Double();
        body.moveTo(31, 50); body.lineTo(54, 31); body.lineTo(77, 50); body.lineTo(77, 81); body.quadTo(77, 86, 72, 86);
        body.lineTo(36, 86); body.quadTo(31, 86, 31, 81); body.closePath();
        g.setColor(CORAL_LIGHT); g.fill(body);
        Path2D chimney = new Path2D.Double(); chimney.moveTo(68, 30); chimney.lineTo(75, 30); chimney.lineTo(75, 42); chimney.lineTo(68, 36); chimney.closePath();
        g.setColor(ROOF); g.fill(chimney);
        g.setStroke(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D ridge = new Path2D.Double(); ridge.moveTo(21, 53); ridge.lineTo(54, 25); ridge.lineTo(87, 53); g.draw(ridge);
        g.setColor(new Color(255, 243, 228)); g.fill(new RoundRectangle2D.Double(37, 52, 34, 24, 8, 8));
        Path2D play = new Path2D.Double(); play.moveTo(50.5, 58.5); play.lineTo(50.5, 69.5); play.lineTo(59.5, 64); play.closePath();
        g.setColor(CORAL_LIGHT); g.fill(play);
        g.dispose();
    }

    /** A warm cartoon sunset, standing in for "the video" being cast. */
    static void videoArt(Graphics2D g0, Rectangle2D r, double t) {
        Graphics2D g = (Graphics2D) g0.create(); g.clip(r);
        double x = r.getX(), y = r.getY(), w = r.getWidth(), h = r.getHeight();
        g.setPaint(new GradientPaint((float) x, (float) y, new Color(255, 214, 170), (float) x, (float) (y + h), new Color(250, 150, 120))); g.fill(r);
        double sr = Math.min(w, h) * .17;
        circle(g, x + w * .66, y + h * (.5 - .04 * Math.sin(t * .8)), sr, new Color(255, 244, 214));
        g.setColor(new Color(255, 255, 255, 210));
        for (int i = 0; i < 2; i++) {
            double cw = w * .2, cx = x - w * .1 + ((w * (.15 + .5 * i) + t * w * .05) % (w * 1.2)), cy = y + h * (.18 + .14 * i);
            g.fill(new Ellipse2D.Double(cx - cw / 2, cy - cw * .14, cw, cw * .32));
            g.fill(new Ellipse2D.Double(cx - cw * .22, cy - cw * .3, cw * .45, cw * .4));
        }
        hills(g, r, .66, CORAL, t * .2, .07);
        hills(g, r, .8, new Color(143, 185, 139), t * .35, .06);
        g.dispose();
    }
    static void hills(Graphics2D g, Rectangle2D r, double level, Color c, double phase, double amp) {
        double x = r.getX(), y = r.getY(), w = r.getWidth(), h = r.getHeight();
        Path2D path = new Path2D.Double(); path.moveTo(x, y + h);
        for (int i = 0; i <= 48; i++) { double u = i / 48.0; path.lineTo(x + u * w, y + h * level + h * amp * Math.sin(u * Math.PI * 2.2 + phase)); }
        path.lineTo(x + w, y + h); path.closePath(); g.setColor(c); g.fill(path);
    }

    static void phone(Graphics2D g, double cx, double cy, double w, double h, BiConsumer<Graphics2D, Rectangle2D> content) {
        rr(g, cx - w / 2, cy - h / 2, w, h, w * .14, BROWN);
        double inset = w * .035;
        Rectangle2D screen = new Rectangle2D.Double(cx - w / 2 + inset, cy - h / 2 + inset, w - 2 * inset, h - 2 * inset);
        Graphics2D g2 = (Graphics2D) g.create(); g2.clip(round(screen, w * .11)); content.accept(g2, screen); g2.dispose();
        rr(g, cx - w * .14, cy - h / 2 + inset + w * .03, w * .28, w * .07, w * .035, BROWN);
    }
    static void tv(Graphics2D g, double cx, double cy, double w, double h, BiConsumer<Graphics2D, Rectangle2D> content) {
        double bezel = w * .022;
        rr(g, cx - w * .035, cy + h / 2, w * .07, h * .1, 0, BROWN);
        rr(g, cx - w * .17, cy + h / 2 + h * .09, w * .34, h * .04, h * .02, BROWN);
        rr(g, cx - w / 2 - bezel, cy - h / 2 - bezel, w + 2 * bezel, h + 2 * bezel, bezel * 1.6, BROWN);
        Rectangle2D screen = new Rectangle2D.Double(cx - w / 2, cy - h / 2, w, h);
        Graphics2D g2 = (Graphics2D) g.create(); g2.clip(round(screen, bezel * .6)); content.accept(g2, screen); g2.dispose();
    }
    /** A family member seen from behind; the body runs off below `base`. */
    static void person(Graphics2D g, double cx, double base, double r, Color c) {
        rr(g, cx - r * 1.4, base - r * 1.25, r * 2.8, r * 5, r * .9, c);
        circle(g, cx, base - r * 2, r, c);
    }
    static void wifi(Graphics2D g, double cx, double cy, double s, double t) {
        g.setStroke(new BasicStroke((float) (s * .11), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        circle(g, cx, cy, s * .09, CORAL);
        for (int i = 1; i <= 3; i++) {
            double lit = clamp(Math.sin(t * 4 - i * .9) * .5 + .7), r = s * .3 * i;
            alpha(g, lit); g.setColor(CORAL); g.draw(new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 45, 90, Arc2D.OPEN)); alpha(g, 1);
        }
    }

    // ---- screens ----
    static void tvHome(Graphics2D g, Rectangle2D r, double t) {
        double x = r.getX(), y = r.getY(), w = r.getWidth(), h = r.getHeight();
        g.setColor(CREAM); g.fill(r);
        circle(g, x + w * .95, y + h * .05, w * .25, PEACH);
        logo(g, x + w / 2, y + h * .36, h * .44, 1);
        text(g, "EasyCast", font(h * .12, true), BROWN, x + w / 2, y + h * .74, 1);
        Font f = font(h * .06, false); String s = "等待手机连接";
        double dot = h * .035, gap = h * .025, sx = x + w / 2 - (dot + gap + width(g, s, f)) / 2, base = y + h * .87;
        alpha(g, .55 + .45 * Math.abs(Math.sin(t * 3))); circle(g, sx + dot / 2, base - f.getSize() * .36, dot / 2, OK); alpha(g, 1);
        textLeft(g, s, f, MUTED, sx + dot + gap, base);
    }
    static void wifiScreen(Graphics2D g, Rectangle2D r, double t) {
        double x = r.getX(), y = r.getY(), w = r.getWidth();
        g.setColor(Color.WHITE); g.fill(r);
        textLeft(g, "Wi-Fi", font(44, true), BROWN, x + 34, y + 130);
        rr(g, x + w - 34 - 80, y + 94, 80, 46, 23, CORAL); circle(g, x + w - 34 - 23, y + 117, 18, Color.WHITE);
        String[] names = { "家里的 Wi-Fi", "邻居的 Wi-Fi", "访客网络" };
        for (int i = 0; i < names.length; i++) {
            double ry = y + 190 + i * 104;
            rr(g, x + 22, ry, w - 44, 88, 22, i == 0 ? PILL : new Color(248, 244, 240));
            textLeft(g, names[i], font(28, i == 0), i == 0 ? BROWN : MUTED, x + 50, ry + 56);
        }
        double c = out(p(t, 9.0, .35));
        if (c > 0) {
            Graphics2D k = (Graphics2D) g.create(); k.setColor(CORAL); k.setStroke(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double cx = x + w - 52, cy = y + 234; k.translate(cx, cy); k.scale(c, c);
            Path2D check = new Path2D.Double(); check.moveTo(-16, 0); check.lineTo(-4, 12); check.lineTo(18, -12); k.draw(check); k.dispose();
        }
    }
    static void videoApp(Graphics2D g, Rectangle2D r, double t, boolean tapTv) {
        double x = r.getX(), y = r.getY(), w = r.getWidth();
        g.setColor(Color.WHITE); g.fill(r);
        Rectangle2D video = new Rectangle2D.Double(x, y + 70, w, w * 9 / 16);
        if (t >= 15.0) {
            g.setColor(PILL); g.fill(video);
            logo(g, x + w / 2, video.getCenterY() - 22, 90, 1);
            text(g, "正在电视上播放", font(26, true), CORAL, x + w / 2, video.getCenterY() + 58, 1);
        } else videoArt(g, video, t);
        double bx = video.getMaxX() - 82, by = video.getY() + 22;
        double hit = tapTv ? out(p(t, 11.4, .3)) : 0;
        if (hit > 0) { alpha(g, hit); rr(g, bx - 6, by - 6, 72, 50, 25, CORAL); alpha(g, 1); }
        g.setColor(Color.WHITE); g.setStroke(new BasicStroke(3));
        g.draw(new RoundRectangle2D.Double(bx, by, 60, 38, 19, 19));
        Font f = font(22, true); textLeft(g, "TV", f, Color.WHITE, bx + 30 - width(g, "TV", f) / 2, by + 27);
        if (tapTv) {
            double rp = p(t, 11.4, .8);
            if (rp > 0 && rp < 1) { alpha(g, 1 - rp); g.setColor(CORAL); g.setStroke(new BasicStroke(6)); double rad = 30 + rp * 70; g.draw(new Ellipse2D.Double(bx + 30 - rad, by + 19 - rad, rad * 2, rad * 2)); alpha(g, 1); }
        }
        double ty = video.getMaxY();
        textLeft(g, "周末家庭电影", font(34, true), BROWN, x + 28, ty + 64);
        rr(g, x + 28, ty + 96, w * .55, 16, 8, LINE);
        rr(g, x + 28, ty + 128, w * .38, 16, 8, LINE);
        for (int i = 0; i < 2; i++) { rr(g, x + 28, ty + 180 + i * 130, w * .4, 100, 16, PEACH); rr(g, x + 28 + w * .44, ty + 196 + i * 130, w * .4, 16, 8, LINE); rr(g, x + 28 + w * .44, ty + 226 + i * 130, w * .26, 16, 8, LINE); }
    }
    static void pickerScreen(Graphics2D g, Rectangle2D r, double t) {
        videoApp(g, r, t, false);
        double x = r.getX(), w = r.getWidth(), sheet = out(p(t, 13.3, .45)) * (1 - inOut(p(t, 14.7, .4)));
        if (sheet <= 0) return;
        alpha(g, sheet * .35); g.setColor(Color.BLACK); g.fill(r); alpha(g, 1);
        double top = r.getMaxY() - 330 * sheet;
        rr(g, x, top, w, 400, 34, Color.WHITE);
        rr(g, x + w / 2 - 36, top + 16, 72, 10, 5, LINE);
        textLeft(g, "选择投屏设备", font(32, true), BROWN, x + 32, top + 86);
        double row = top + 120, on = out(p(t, 13.9, .3));
        rr(g, x + 20, row, w - 40, 110, 24, on > 0 ? mix(new Color(248, 244, 240), PILL, on) : new Color(248, 244, 240));
        if (on > 0) { alpha(g, on); g.setColor(CORAL); g.setStroke(new BasicStroke(4)); g.draw(new RoundRectangle2D.Double(x + 20, row, w - 40, 110, 48, 48)); alpha(g, 1); }
        logo(g, x + 82, row + 55, 84, 1);
        textLeft(g, "EasyCast", font(36, true), BROWN, x + 140, row + 52);
        textLeft(g, "客厅电视", font(24, false), MUTED, x + 140, row + 88);
    }
    static Color mix(Color a, Color b, double k) {
        return new Color((int) lerp(a.getRed(), b.getRed(), k), (int) lerp(a.getGreen(), b.getGreen(), k), (int) lerp(a.getBlue(), b.getBlue(), k));
    }

    // ---- scenes ----
    interface Scene { void draw(Graphics2D g, double t); }
    static void render(BufferedImage img, double t) {
        Graphics2D g = img.createGraphics(); hints(g);
        g.setColor(CREAM); g.fillRect(0, 0, W, H);
        circle(g, W + 20 + 30 * Math.sin(t * .5), 60, 400, PEACH);
        circle(g, -80, H - 260 + 30 * Math.cos(t * .4), 280, PEACH);
        scene(g, t, 0, 4.0, PromoVideo::problem);
        scene(g, t, 3.8, 8.0, PromoVideo::brand);
        scene(g, t, 7.8, DURATION, PromoVideo::steps);
        g.dispose();
    }
    static void scene(Graphics2D g, double t, double start, double end, Scene s) {
        if (t < start || t >= end) return;
        double a = Math.min(start == 0 ? 1 : clamp((t - start) / .4), end >= DURATION ? 1 : clamp((end - t) / .4));
        if (a >= 1) { s.draw(g, t); return; }
        Graphics2D l = layer.createGraphics(); l.setComposite(AlphaComposite.Clear); l.fillRect(0, 0, W, H); l.setComposite(AlphaComposite.SrcOver); hints(l);
        s.draw(l, t); l.dispose();
        alpha(g, a); g.drawImage(layer, 0, 0, null); alpha(g, 1);
    }

    /** 0–4s: the family squeezed around a phone. */
    static void problem(Graphics2D g, double t) {
        text(g, "手机屏幕太小，", font(96, true), BROWN, W / 2.0, 340, out(p(t, .15, .5)));
        text(g, "一家人挤着看？", font(96, true), CORAL, W / 2.0, 480, out(p(t, .7, .5)));
        double pop = back(p(t, 0, .6));
        phone(g, W / 2.0, 1090 + (1 - pop) * 80, 420, 860, (g2, r) -> videoArt(g2, r, t));
        double rise = back(p(t, .9, .6)), base = 1800 + (1 - rise) * 600, wob = Math.sin(t * 6) * 8;
        person(g, 335 + wob, base, 105, DAD);
        person(g, 745 - wob, base, 98, CORAL);
        person(g, 540, base + 70, 80, MUSTARD);
    }

    /** 4–8s: logo reveal. */
    static void brand(Graphics2D g, double t) {
        double s = back(p(t, 4.05, .6));
        logo(g, W / 2.0, 800, 460 * s, clamp(s * 2));
        text(g, "EasyCast", font(150, true), BROWN, W / 2.0, 1200, out(p(t, 4.5, .5)));
        text(g, "把手机里的精彩，投到客厅大屏", font(54, false), MUTED, W / 2.0, 1320, out(p(t, 5.0, .5)));
        String[] chips = { "免费", "无广告", "开源" };
        Font f = font(42, true); double gap = 28, total = -gap;
        for (String c : chips) total += width(g, c, f) + f.getSize() * 1.6 + gap;
        double x = W / 2.0 - total / 2;
        for (int i = 0; i < chips.length; i++) {
            double cw = width(g, chips[i], f) + f.getSize() * 1.6;
            pill(g, chips[i], f, Color.WHITE, CORAL, x + cw / 2, 1480, out(p(t, 5.5 + i * .18, .4)));
            x += cw + gap;
        }
    }

    /** 8–20s: the three steps, then the family on the sofa and the end card. */
    static void steps(Graphics2D g, double t) {
        String[] titles = { "连上同一个家庭 Wi-Fi", "点击视频里的「TV」", "选择「EasyCast」" };
        double[] starts = { 7.9, 10.6, 13.2, 16.5 };
        for (int i = 0; i < 3; i++) {
            double a = window(t, starts[i], starts[i + 1]);
            pill(g, "第 " + (i + 1) + " 步", font(40, true), CORAL, Color.WHITE, W / 2.0, 250, a);
            text(g, titles[i], font(80, true), BROWN, W / 2.0, 410, a);
        }
        text(g, "一家人，一起看大屏", font(96, true), BROWN, W / 2.0, 360, out(p(t, 16.7, .5)));

        double finale = inOut(p(t, 16.3, .8)), tvIn = out(p(t, 7.9, .6));
        double tvW = lerp(880, 940, finale), tvH = tvW * 9 / 16, tvCy = lerp(800, 780, finale) - (1 - tvIn) * 160;
        double reveal = out(p(t, 15.0, .6));
        tv(g, W / 2.0, tvCy, tvW, tvH, (g2, r) -> {
            tvHome(g2, r, t);
            if (reveal > 0) {
                double rad = reveal * Math.hypot(r.getWidth(), r.getHeight());
                Graphics2D v = (Graphics2D) g2.create(); v.clip(new Ellipse2D.Double(r.getCenterX() - rad, r.getMaxY() - rad, rad * 2, rad * 2));
                videoArt(v, r, t); v.dispose();
            }
        });

        double phoneIn = out(p(t, 8.1, .6)), phoneCy = 1520 + (1 - phoneIn) * 900 + finale * 1000;
        if (phoneCy < H + 400) {
            if (t < 10.6) { wifi(g, 180, 1450, 150, t); wifi(g, W - 180, 1450, 150, t + .5); }
            phone(g, W / 2.0, phoneCy, 350, 720, (g2, r) -> {
                if (t < 10.6) wifiScreen(g2, r, t);
                else if (t < 13.2) videoApp(g2, r, t, true);
                else pickerScreen(g2, r, t);
            });
        }
        double fly = p(t, 14.4, .6);
        if (fly > 0 && fly < 1) {
            double e = inOut(fly), cw = lerp(240, 420, e), ch = cw * 9 / 16, cx = W / 2.0 + Math.sin(e * Math.PI) * 120, cy = lerp(phoneCy - 120, tvCy, e);
            Rectangle2D card = new Rectangle2D.Double(cx - cw / 2, cy - ch / 2, cw, ch);
            alpha(g, 1 - p(t, 14.85, .15));
            Graphics2D c = (Graphics2D) g.create(); c.clip(round(card, 18)); videoArt(c, card, t); c.dispose();
            alpha(g, 1);
        }

        double sit = back(p(t, 16.8, .6));
        if (sit > 0) {
            double dy = (1 - sit) * 700;
            person(g, 360, 1400 + dy, 92, DAD);
            person(g, 720, 1400 + dy, 88, CORAL);
            person(g, 540, 1440 + dy, 70, MUSTARD);
            rr(g, 130, 1330 + dy, 820, 230, 60, CORAL_LIGHT);
            rr(g, 100, 1420 + dy, 140, 170, 50, ROOF);
            rr(g, 840, 1420 + dy, 140, 170, 50, ROOF);
            rr(g, 190, 1470 + dy, 700, 110, 30, ROOF);
        }
        double end = out(p(t, 17.5, .5));
        if (end > 0) {
            Font name = font(84, true); double nw = width(g, "EasyCast", name), lx = W / 2.0 - (110 + 20 + nw) / 2, dy = (1 - end) * 40;
            alpha(g, end); rr(g, 70, 1620 + dy, W - 140, 260, 50, Color.WHITE); alpha(g, 1);
            logo(g, lx + 55, 1712 + dy, 120, end);
            alpha(g, end); textLeft(g, "EasyCast", name, BROWN, lx + 130, 1744 + dy); alpha(g, 1);
            text(g, "github.com/iamherrylok/easycast", font(38, false), CORAL, W / 2.0, 1836, end);
        }
    }
}
