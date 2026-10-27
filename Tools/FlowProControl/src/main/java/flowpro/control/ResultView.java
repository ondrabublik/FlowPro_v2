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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JPanel;

/**
 * Shows a 2D mesh (vertices.txt, elements.txt, boundaryType.txt) and the
 * element averages of the solution (W.txt) as a colour map.
 *
 * @author obublik
 */
public class ResultView extends JPanel {

    private double[][] points;
    private int[][] elements;
    private int[][] boundary;
    private double[][] values; // element averages, one row per element
    public int component = -1; // -1 = mesh only
    public boolean showEdges = true;
    private String message = "select a simulation and press 'Load'";

    private double scale = 1, offsetX, offsetY;
    private int lastX, lastY;
    private double vmin, vmax;

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

    private static List<String[]> readRows(File file) throws IOException {
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

    /**
     * @return number of solution components (columns of W.txt), 0 if there is
     * no solution
     */
    public int load(File meshDir, File simulationDir) {
        points = null;
        elements = null;
        values = null;
        boundary = null;
        try {
            List<String[]> rows = readRows(new File(meshDir, "vertices.txt"));
            points = new double[rows.size()][2];
            for (int i = 0; i < rows.size(); i++) {
                points[i][0] = Double.parseDouble(rows.get(i)[0]);
                points[i][1] = rows.get(i).length > 1 ? Double.parseDouble(rows.get(i)[1]) : 0;
            }
            rows = readRows(new File(meshDir, "elements.txt"));
            elements = new int[rows.size()][];
            for (int i = 0; i < rows.size(); i++) {
                elements[i] = new int[rows.get(i).length];
                for (int j = 0; j < elements[i].length; j++) {
                    elements[i][j] = Integer.parseInt(rows.get(i)[j]);
                }
            }
            File bt = new File(meshDir, "boundaryType.txt");
            if (bt.isFile()) {
                rows = readRows(bt);
                boundary = new int[rows.size()][];
                for (int i = 0; i < rows.size(); i++) {
                    boundary[i] = new int[rows.get(i).length];
                    for (int j = 0; j < boundary[i].length; j++) {
                        boundary[i][j] = Integer.parseInt(rows.get(i)[j]);
                    }
                }
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
                List<String[]> rows = readRows(w);
                if (rows.size() == elements.length) {
                    values = new double[rows.size()][];
                    for (int i = 0; i < rows.size(); i++) {
                        values[i] = new double[rows.get(i).length];
                        for (int j = 0; j < values[i].length; j++) {
                            values[i][j] = Double.parseDouble(rows.get(i)[j]);
                        }
                    }
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

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (points == null || elements == null) {
            g.setColor(Color.DARK_GRAY);
            g.drawString(message, 20, 30);
            return;
        }
        boolean colour = values != null && component >= 0;
        if (colour) {
            vmin = Double.POSITIVE_INFINITY;
            vmax = Double.NEGATIVE_INFINITY;
            for (double[] v : values) {
                if (component < v.length && Double.isFinite(v[component])) {
                    vmin = Math.min(vmin, v[component]);
                    vmax = Math.max(vmax, v[component]);
                }
            }
            if (!(vmax > vmin)) {
                vmax = vmin + 1e-12;
            }
        }
        g.setStroke(new BasicStroke(0.5f));
        for (int i = 0; i < elements.length; i++) {
            int[] el = elements[i];
            Path2D path = new Path2D.Double();
            for (int j = 0; j < el.length; j++) {
                double[] p = points[el[j]];
                double sx = offsetX + scale * p[0], sy = offsetY - scale * p[1];
                if (j == 0) {
                    path.moveTo(sx, sy);
                } else {
                    path.lineTo(sx, sy);
                }
            }
            path.closePath();
            if (colour && component < values[i].length) {
                g.setColor(colormap((values[i][component] - vmin) / (vmax - vmin)));
                g.fill(path);
            }
            if (showEdges) {
                g.setColor(colour ? new Color(0, 0, 0, 50) : new Color(110, 130, 160));
                g.draw(path);
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
            g.drawString("W[" + component + "]", x - 4, 18);
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
