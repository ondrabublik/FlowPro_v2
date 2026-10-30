package flowpro.control;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JPanel;

/**
 * Shows a 2D mesh (vertices.txt, elements.txt, boundaryType.txt) and a scalar
 * field as a colour map: either the element averages of the solution (W.txt,
 * one colour per element), or values in the points of the elements divided
 * into smaller polygons (exported by FlowPro postprocessing -dN), drawn with a
 * linear colour interpolation (smooth) or one colour per polygon.
 *
 * @author obublik
 */
public class ResultView extends JPanel {

    private double[][] points;
    private int[][] elements;
    private int[][] boundary;
    private double[][] values; // element averages, one row per element
    public int component = -1; // column of W.txt shown when there is no field, -1 = mesh only
    public boolean showEdges = true;
    private String message = "select a simulation and press 'Load'";

    // field in the points of the divided elements
    private double[][] fieldPoints;
    private int[][] fieldPolygons;
    private double[] fieldValues;
    private boolean fieldSmooth;
    private String fieldLabel;

    private double scale = 1, offsetX, offsetY;
    private int lastX, lastY;
    private double vmin, vmax;

    private static final int[] LUT = new int[256];

    static {
        for (int i = 0; i < LUT.length; i++) {
            LUT[i] = colormap(i / (LUT.length - 1.0)).getRGB();
        }
    }

    public ResultView() {
        setBackground(Color.WHITE);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastX = e.getX();
                lastY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                offsetX += e.getX() - lastX;
                offsetY += e.getY() - lastY;
                lastX = e.getX();
                lastY = e.getY();
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                double factor = Math.pow(1.15, -e.getPreciseWheelRotation());
                double wx = (e.getX() - offsetX) / scale, wy = (offsetY - e.getY()) / scale;
                scale *= factor;
                offsetX = e.getX() - scale * wx;
                offsetY = e.getY() + scale * wy;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    static List<String[]> readRows(File file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    rows.add(line.split("\\s+"));
                }
            }
        }
        return rows;
    }

    static double[][] readDoubles(File file) throws IOException {
        List<String[]> rows = readRows(file);
        double[][] a = new double[rows.size()][];
        for (int i = 0; i < a.length; i++) {
            String[] row = rows.get(i);
            a[i] = new double[row.length];
            for (int j = 0; j < row.length; j++) {
                a[i][j] = Double.parseDouble(row[j]);
            }
        }
        return a;
    }

    static int[][] readInts(File file) throws IOException {
        List<String[]> rows = readRows(file);
        int[][] a = new int[rows.size()][];
        for (int i = 0; i < a.length; i++) {
            String[] row = rows.get(i);
            a[i] = new int[row.length];
            for (int j = 0; j < row.length; j++) {
                a[i][j] = Integer.parseInt(row[j]);
            }
        }
        return a;
    }

    /**
     * @return number of solution components (columns of W.txt), 0 if there is
     * no solution
     */
    public int load(File meshDir, File simulationDir) {
        points = null;
        elements = null;
        values = null;
        boundary = null;
        clearField();
        try {
            double[][] p = readDoubles(new File(meshDir, "vertices.txt"));
            points = new double[p.length][2];
            for (int i = 0; i < p.length; i++) {
                points[i][0] = p[i][0];
                points[i][1] = p[i].length > 1 ? p[i][1] : 0;
            }
            elements = readInts(new File(meshDir, "elements.txt"));
            File bt = new File(meshDir, "boundaryType.txt");
            if (bt.isFile()) {
                boundary = readInts(bt);
            }
        } catch (IOException | RuntimeException ex) {
            message = "mesh cannot be read: " + ex.getMessage();
            points = null;
            elements = null;
            repaint();
            return 0;
        }
        int nComponents = 0;
        File w = new File(simulationDir, "W.txt");
        if (w.isFile()) {
            try {
                double[][] v = readDoubles(w);
                if (v.length == elements.length) {
                    values = v;
                    nComponents = values[0].length;
                }
            } catch (IOException | RuntimeException ex) {
                values = null;
            }
        }
        if (component >= nComponents) {
            component = nComponents > 0 ? 0 : -1;
        }
        message = points.length + " vertices, " + elements.length + " elements"
                + (values != null ? ", solution W.txt loaded" : ", no solution (W.txt) yet");
        zoomToFit();
        return nComponents;
    }

    /**
     * Shows a field in the points of the divided elements instead of the
     * element averages.
     *
     * @param polygons triangles or quadrilaterals (indexes of the points)
     * @param smooth linear interpolation of the colour inside the polygons,
     * otherwise each polygon has the colour of the mean of its points
     */
    public void setField(double[][] fieldPoints, int[][] polygons, double[] values, boolean smooth, String label) {
        this.fieldPoints = fieldPoints;
        this.fieldPolygons = polygons;
        this.fieldValues = values;
        this.fieldSmooth = smooth;
        this.fieldLabel = label;
        repaint();
    }

    public void clearField() {
        fieldPoints = null;
        fieldPolygons = null;
        fieldValues = null;
        fieldLabel = null;
        repaint();
    }

    public boolean hasMesh() {
        return points != null && elements != null;
    }

    public String getMessage() {
        return message;
    }

    public void zoomToFit() {
        if (points == null || points.length == 0 || getWidth() == 0) {
            repaint();
            return;
        }
        double x0 = Double.POSITIVE_INFINITY, y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
        for (double[] p : points) {
            x0 = Math.min(x0, p[0]);
            y0 = Math.min(y0, p[1]);
            x1 = Math.max(x1, p[0]);
            y1 = Math.max(y1, p[1]);
        }
        int w = getWidth() - 100;
        scale = 0.92 * Math.min(w / Math.max(x1 - x0, 1e-12), getHeight() / Math.max(y1 - y0, 1e-12));
        offsetX = w / 2.0 - scale * (x0 + x1) / 2;
        offsetY = getHeight() / 2.0 + scale * (y0 + y1) / 2;
        repaint();
    }

    private static Color colormap(double s) {
        // blue - cyan - green - yellow - red
        s = Math.max(0, Math.min(1, s));
        float[][] c = {{0.19f, 0.07f, 0.73f}, {0.0f, 0.6f, 0.9f}, {0.2f, 0.8f, 0.3f}, {0.98f, 0.85f, 0.1f}, {0.85f, 0.1f, 0.1f}};
        double t = s * (c.length - 1);
        int i = Math.min((int) t, c.length - 2);
        double f = t - i;
        return new Color((float) (c[i][0] + f * (c[i + 1][0] - c[i][0])),
                (float) (c[i][1] + f * (c[i + 1][1] - c[i][1])),
                (float) (c[i][2] + f * (c[i + 1][2] - c[i][2])));
    }

    private Path2D polygon(double[][] pts, int[] indexes) {
        Path2D path = new Path2D.Double();
        for (int j = 0; j < indexes.length; j++) {
            double[] p = pts[indexes[j]];
            double sx = offsetX + scale * p[0], sy = offsetY - scale * p[1];
            if (j == 0) {
                path.moveTo(sx, sy);
            } else {
                path.lineTo(sx, sy);
            }
        }
        path.closePath();
        return path;
    }

    private void range(double[] v) {
        vmin = Double.POSITIVE_INFINITY;
        vmax = Double.NEGATIVE_INFINITY;
        for (double x : v) {
            if (Double.isFinite(x)) {
                vmin = Math.min(vmin, x);
                vmax = Math.max(vmax, x);
            }
        }
        if (!(vmax > vmin)) {
            vmax = vmin + 1e-12;
        }
    }

    private int lutColor(double v) {
        int k = (int) Math.round((v - vmin) / (vmax - vmin) * (LUT.length - 1));
        return LUT[Math.max(0, Math.min(LUT.length - 1, k))];
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        if (points == null || elements == null) {
            g.setColor(Color.DARK_GRAY);
            g.drawString(message, 20, 30);
            return;
        }
        boolean field = fieldValues != null && fieldPoints != null && fieldPolygons != null;
        boolean averages = !field && values != null && component >= 0;
        String label = null;
        if (field) {
            range(fieldValues);
            label = fieldLabel;
            if (fieldSmooth) {
                g.drawImage(renderSmooth(), 0, 0, null);
            } else {
                for (int[] poly : fieldPolygons) {
                    double mean = 0;
                    for (int j : poly) {
                        mean += fieldValues[j];
                    }
                    g.setColor(new Color(lutColor(mean / poly.length)));
                    Path2D path = polygon(fieldPoints, poly);
                    g.fill(path);
                    g.draw(path); // closes the hairline gaps between the polygons
                }
            }
        } else if (averages) {
            double[] v = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                v[i] = component < values[i].length ? values[i][component] : Double.NaN;
            }
            range(v);
            label = "W[" + component + "] (element averages)";
            for (int i = 0; i < elements.length; i++) {
                if (Double.isFinite(v[i])) {
                    g.setColor(new Color(lutColor(v[i])));
                    Path2D path = polygon(points, elements[i]);
                    g.fill(path);
                    g.draw(path);
                }
            }
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(0.5f));
        boolean colour = field || averages;
        if (showEdges || !colour) {
            g.setColor(colour ? new Color(0, 0, 0, 50) : new Color(110, 130, 160));
            for (int[] el : elements) {
                g.draw(polygon(points, el));
            }
        }
        if (boundary != null) {
            g.setStroke(new BasicStroke(2f));
            for (int[] b : boundary) {
                if (b.length < 3) {
                    continue;
                }
                g.setColor(boundaryColor(b[0]));
                for (int j = 1; j + 1 < b.length; j++) {
                    double[] p = points[b[j]], q = points[b[j + 1]];
                    g.draw(new Line2D.Double(offsetX + scale * p[0], offsetY - scale * p[1],
                            offsetX + scale * q[0], offsetY - scale * q[1]));
                }
            }
        }
        if (colour) {
            int x = getWidth() - 70, y0 = 40, h = Math.max(100, getHeight() - 120);
            for (int k = 0; k < h; k++) {
                g.setColor(colormap(1 - (double) k / h));
                g.drawLine(x, y0 + k, x + 18, y0 + k);
            }
            g.setColor(Color.DARK_GRAY);
            g.drawRect(x, y0, 18, h);
            g.drawString(String.format(Locale.ROOT, "%.4g", vmax), x - 10, y0 - 6);
            g.drawString(String.format(Locale.ROOT, "%.4g", vmin), x - 10, y0 + h + 16);
            int labelWidth = g.getFontMetrics().stringWidth(label);
            g.drawString(label, Math.max(4, getWidth() - labelWidth - 8), 18);
        }
    }

    /**
     * Rasterises the polygons of the field (quadrilaterals as two triangles)
     * with the value interpolated linearly in each triangle.
     */
    private BufferedImage renderSmooth() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int n = fieldPoints.length;
        double[] sx = new double[n], sy = new double[n];
        for (int i = 0; i < n; i++) {
            sx[i] = offsetX + scale * fieldPoints[i][0];
            sy[i] = offsetY - scale * fieldPoints[i][1];
        }
        for (int[] poly : fieldPolygons) {
            for (int t = 1; t + 1 < poly.length; t++) {
                fillTriangle(pixels, w, h, sx, sy, poly[0], poly[t], poly[t + 1]);
            }
        }
        return img;
    }

    private void fillTriangle(int[] pixels, int w, int h, double[] sx, double[] sy, int a, int b, int c) {
        double x0 = sx[a], y0 = sy[a], x1 = sx[b], y1 = sy[b], x2 = sx[c], y2 = sy[c];
        double det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (det == 0 || !Double.isFinite(det)) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        double va = fieldValues[a], vb = fieldValues[b], vc = fieldValues[c];
        // a small tolerance, so that no gaps remain between neighbouring triangles
        double eps = -1e-3;
        for (int py = minY; py <= maxY; py++) {
            double y = py + 0.5;
            for (int px = minX; px <= maxX; px++) {
                double x = px + 0.5;
                double l0 = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / det;
                double l1 = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / det;
                double l2 = 1 - l0 - l1;
                if (l0 >= eps && l1 >= eps && l2 >= eps) {
                    pixels[py * w + px] = lutColor(l0 * va + l1 * vb + l2 * vc);
                }
            }
        }
    }

    static Color boundaryColor(int type) {
        switch (type) {
            case -1:
                return new Color(30, 30, 30);
            case -2:
                return new Color(0, 90, 220);
            case -3:
                return new Color(210, 30, 30);
            case -4:
                return new Color(0, 150, 60);
            default:
                return new Color(160, 0, 160);
        }
    }
}
