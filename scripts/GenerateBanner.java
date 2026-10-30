import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Regenerate the TV tile: the house logo (same shapes as ic_logo.xml), followed by the EasyCast wordmark. */
class GenerateBanner {
    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new Color(255, 246, 236)); g.fillRect(0, 0, 320, 180);
        g.setColor(new Color(253, 230, 212)); g.fillOval(214, -70, 170, 170); g.fillOval(-50, 128, 110, 110);
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        String title = "EasyCast";
        FontMetrics metrics = g.getFontMetrics();
        float scale = 0.78f; int symbolWidth = Math.round(70 * scale), gap = 12;
        int left = (320 - symbolWidth - gap - metrics.stringWidth(title)) / 2;
        Graphics2D logo = (Graphics2D) g.create();
        logo.translate(left - 19 * scale, 90 - 56 * scale); logo.scale(scale, scale);
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
        g.setColor(new Color(62, 44, 35));
        int baseline = (180 - metrics.getAscent() - metrics.getDescent()) / 2 + metrics.getAscent();
        g.drawString(title, left + symbolWidth + gap, baseline);
        g.dispose();
        File file = new File("app/src/main/res/drawable-xhdpi/banner.png"); file.getParentFile().mkdirs(); ImageIO.write(image, "png", file);
    }
}
