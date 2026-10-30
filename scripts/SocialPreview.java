import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Render the 1280x640 GitHub social preview (link card on X, WeChat, etc.): wordmark + tagline on the left, the TV home screen on the right. */
class SocialPreview {
    static final int W = 1280, H = 640;
    static final Color CREAM = new Color(255, 246, 236), PEACH = new Color(253, 230, 212), CORAL = new Color(232, 113, 74),
            BROWN = new Color(62, 44, 35), MUTED = new Color(140, 118, 106), PILL = new Color(255, 236, 222), BEZEL = new Color(46, 34, 28);

    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(CREAM); g.fillRect(0, 0, W, H);
        g.setColor(PEACH); g.fillOval(900, -260, 560, 560); g.fillOval(-160, 460, 360, 360);

        // Left column: logo + wordmark, tagline, feature pills.
        int left = 80;
        drawLogo(g, left - 14, 92, 1.2f);
        g.setColor(BROWN); g.setFont(font(Font.BOLD, 72)); g.drawString("EasyCast", left + 112, 172);
        g.setFont(font(Font.BOLD, 46)); g.drawString("让 Android 电视", left, 290); g.drawString("变成投屏接收器", left, 352);
        g.setColor(MUTED); g.setFont(font(Font.PLAIN, 26)); g.drawString("手机视频 App 点「TV」，一键投到客厅大屏", left, 410);
        int x = left;
        g.setFont(font(Font.BOLD, 24));
        for (String pill : new String[] {"免费", "无广告", "不用注册", "DLNA"}) {
            int w = g.getFontMetrics().stringWidth(pill) + 40;
            g.setColor(PILL); g.fill(new RoundRectangle2D.Float(x, 450, w, 48, 48, 48));
            g.setColor(CORAL); g.drawString(pill, x + 20, 482);
            x += w + 12;
        }
        g.setColor(MUTED); g.setFont(font(Font.PLAIN, 22)); g.drawString("github.com/iamherrylok/easycast", left, 572);

        // Right column: the real TV home screen inside a TV bezel with a stand.
        BufferedImage shot = ImageIO.read(new File("docs/screenshot.png"));
        int sw = 560, sh = sw * shot.getHeight() / shot.getWidth(), sx = 650, sy = 150, pad = 14;
        g.setColor(new Color(0, 0, 0, 40)); g.fill(new RoundRectangle2D.Float(sx - pad + 6, sy - pad + 14, sw + 2 * pad, sh + 2 * pad, 28, 28));
        g.setColor(BEZEL); g.fill(new RoundRectangle2D.Float(sx - pad, sy - pad, sw + 2 * pad, sh + 2 * pad, 24, 24));
        Shape screen = new RoundRectangle2D.Float(sx, sy, sw, sh, 8, 8);
        Graphics2D s = (Graphics2D) g.create(); s.clip(screen); s.drawImage(shot, sx, sy, sw, sh, null); s.dispose();
        int cx = sx + sw / 2, by = sy + sh + pad;
        g.setColor(BEZEL); g.fillRect(cx - 10, by, 20, 34); g.fill(new RoundRectangle2D.Float(cx - 90, by + 30, 180, 12, 12, 12));
        g.dispose();
        File file = new File("docs/social-preview.png"); ImageIO.write(image, "png", file);
    }

    static Font font(int style, int size) {
        for (String name : new String[] {"PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei"})
            if (new Font(name, style, size).getFamily().equals(name)) return new Font(name, style, size);
        return new Font("SansSerif", style, size);
    }

    /** Same house logo as ic_logo.xml / GenerateBanner, drawn with its 108-unit viewport scaled and placed at (x, y). */
    static void drawLogo(Graphics2D g, float x, float y, float scale) {
        Graphics2D logo = (Graphics2D) g.create();
        logo.translate(x, y); logo.scale(scale, scale);
        Color coral = new Color(244, 137, 95), roof = new Color(216, 97, 61), cream = new Color(255, 243, 228);
        Path2D body = new Path2D.Float();
        body.moveTo(31, 50); body.lineTo(54, 31); body.lineTo(77, 50); body.lineTo(77, 81); body.quadTo(77, 86, 72, 86);
        body.lineTo(36, 86); body.quadTo(31, 86, 31, 81); body.closePath();
        logo.setColor(coral); logo.fill(body);
        Path2D chimney = new Path2D.Float(); chimney.moveTo(68, 30); chimney.lineTo(75, 30); chimney.lineTo(75, 42); chimney.lineTo(68, 36); chimney.closePath();
        logo.setColor(roof); logo.fill(chimney);
        logo.setStroke(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D ridge = new Path2D.Float(); ridge.moveTo(21, 53); ridge.lineTo(54, 25); ridge.lineTo(87, 53); logo.draw(ridge);
        logo.setColor(cream); logo.fill(new RoundRectangle2D.Float(37, 52, 34, 24, 8, 8));
        Path2D play = new Path2D.Float(); play.moveTo(50.5, 58.5); play.lineTo(50.5, 69.5); play.lineTo(59.5, 64); play.closePath();
        logo.setColor(coral); logo.fill(play);
        logo.dispose();
    }
}
