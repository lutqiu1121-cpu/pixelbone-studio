import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 生成应用图标：居中白色骨形（斜置 45°）+ 主题蓝圆角方块底。
 * 纯 AWT 绘制，4 倍超采样后降采样，不需要任何外部素材。
 *
 * 用法: java IconGen <resDir> [previewDir]
 */
public class IconGen {

    static final Color C1 = new Color(0x5C, 0xB3, 0xFF);   // 顶部亮蓝
    static final Color C2 = new Color(0x00, 0x57, 0xD8);   // 底部深蓝

    public static void main(String[] a) throws Exception {
        File res = new File(a[0]);
        File preview = a.length > 1 ? new File(a[1]) : null;

        String[] dirs = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};
        int[] sizes = {48, 72, 96, 144, 192};

        for (int i = 0; i < dirs.length; i++) {
            File d = new File(res, dirs[i]);
            d.mkdirs();
            ImageIO.write(render(sizes[i], false), "png", new File(d, "ic_launcher.png"));
            ImageIO.write(render(sizes[i], true), "png", new File(d, "ic_launcher_round.png"));
        }
        if (preview != null) {
            preview.mkdirs();
            ImageIO.write(render(512, false), "png", new File(preview, "icon-512.png"));
        }
        System.out.println("icons ok");
    }

    /** 按目标边长渲染：先 4x 超采样再降采样，小尺寸也不发糊。 */
    static BufferedImage render(int size, boolean round) {
        int ss = 4;
        int S = size * ss;
        BufferedImage big = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // ---- 背景：主题蓝渐变 + 圆角 / 圆形 ----
        Shape bg = round
                ? (Shape) new Ellipse2D.Double(0, 0, S, S)
                : (Shape) new RoundRectangle2D.Double(0, 0, S, S, S * 0.235, S * 0.235);
        g.setPaint(new GradientPaint(0f, 0f, C1, (float) (S * 0.35), (float) S, C2));
        g.fill(bg);

        // 左上角柔光，避免纯平
        g.setClip(bg);
        g.setPaint(new RadialGradientPaint(
                new Point2D.Double(S * 0.26, S * 0.18), S * 0.72f,
                new float[]{0f, 1f},
                new Color[]{new Color(255, 255, 255, 54), new Color(255, 255, 255, 0)}));
        g.fill(bg);
        g.setClip(null);

        // ---- 前景：居中的白色骨形（-45°） ----
        Area bone = bone(S * 0.5, S * 0.5, S * 0.30, S * 0.036, S * 0.078, -45);

        // 轻微投影增加立体感
        Area shadow = bone.createTransformedArea(
                AffineTransform.getTranslateInstance(S * 0.012, S * 0.016));
        g.setColor(new Color(0, 0, 0, 42));
        g.fill(shadow);

        g.setColor(Color.WHITE);
        g.fill(bone);

        g.dispose();

        // ---- 降采样 ----
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D og = out.createGraphics();
        og.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        og.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        og.drawImage(big, 0, 0, size, size, null);
        og.dispose();
        return out;
    }

    /** 骨形：圆角骨干 + 四个端头圆，绕自身中心旋转。 */
    static Area bone(double cx, double cy, double halfLen, double halfThick,
                     double knobR, double angleDeg) {
        Area a = new Area(new RoundRectangle2D.Double(
                cx - halfLen, cy - halfThick,
                halfLen * 2, halfThick * 2,
                halfThick * 2, halfThick * 2));
        double[][] off = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
        for (double[] o : off) {
            double kx = cx + o[0] * halfLen;
            double ky = cy + o[1] * halfThick;
            a.add(new Area(new Ellipse2D.Double(kx - knobR, ky - knobR, knobR * 2, knobR * 2)));
        }
        return a.createTransformedArea(
                AffineTransform.getRotateInstance(Math.toRadians(angleDeg), cx, cy));
    }
}