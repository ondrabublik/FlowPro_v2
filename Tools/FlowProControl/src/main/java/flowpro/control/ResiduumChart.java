package flowpro.control;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
 * Convergence history read from file residuum.txt (residuum, physical time,
 * CPU time on each line), logarithmic scale.
 *
 * @author obublik
 */
public class ResiduumChart extends JPanel {

    private final List<double[]> data = new ArrayList<>();
    private File file;
    private long lastModified = -1;
    private long lastLength = -1;
    public boolean timeOnXAxis = false;
    private int hoverIndex = -1;

    private static final int LEFT = 70, RIGHT = 20, TOP = 30, BOTTOM = 45;

    public ResiduumChart() {
        setBackground(Color.WHITE);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hoverIndex = indexAt(e.getX());
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverIndex = -1;
                repaint();
            }
        };
        addMouseMotionListener(mouse);
        addMouseListener(mouse);
    }

    public void setFile(File file) {
        this.file = file;
        lastModified = -1;
        lastLength = -1;
        data.clear();
        reload();
    }

    /**
     * Rereads the file if it has changed.
     */
    public void reload() {
        if (file == null || !file.isFile()) {
            if (!data.isEmpty()) {
                data.clear();
                repaint();
            }
            return;
        }
        if (file.lastModified() == lastModified && file.length() == lastLength) {
            return;
        }
        lastModified = file.lastModified();
        lastLength = file.length();
        List<double[]> newData = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                if (t.length >= 2) {
                    try {
                        newData.add(new double[]{Double.parseDouble(t[0]), Double.parseDouble(t[1]),
                            t.length > 2 ? Double.parseDouble(t[2]) : 0});
                    } catch (NumberFormatException ex) {
                        // incomplete line being written
                    }
                }
            }
        } catch (IOException ex) {
            return;
        }
        data.clear();
        data.addAll(newData);
        repaint();
    }

    public int numberOfSteps() {
        return data.size();
    }

    public double[] last() {
        return data.isEmpty() ? null : data.get(data.size() - 1);
    }

    private double x(int i) {
        return timeOnXAxis ? data.get(i)[1] : i + 1;
    }

    private int indexAt(int px) {
        if (data.isEmpty()) {
            return -1;
        }
        int w = getWidth() - LEFT - RIGHT;
        double x0 = x(0), x1 = x(data.size() - 1);
        if (x1 <= x0 || w <= 0) {
            return data.size() - 1;
        }
        double xv = x0 + (px - LEFT) * (x1 - x0) / w;
        int best = 0;
        for (int i = 0; i < data.size(); i++) {
            if (Math.abs(x(i) - xv) < Math.abs(x(best) - xv)) {
                best = i;
            }
        }
        return best;
    }

    private static double niceStep(double raw) {
        if (!(raw > 0)) {
            return 1;
        }
        double p = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / p;
        return (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * p;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth() - LEFT - RIGHT, h = getHeight() - TOP - BOTTOM;
        g.setColor(Color.DARK_GRAY);
        g.setFont(getFont().deriveFont(Font.BOLD, 13f));
        g.drawString("Residuum", LEFT, 18);
        g.setFont(getFont().deriveFont(11f));
        if (data.isEmpty()) {
            g.drawString("no data (file residuum.txt does not exist yet)", LEFT + 20, TOP + h / 2);
            return;
        }

        double ymin = Double.POSITIVE_INFINITY, ymax = Double.NEGATIVE_INFINITY;
        for (double[] d : data) {
            if (d[0] > 0 && Double.isFinite(d[0])) {
                ymin = Math.min(ymin, Math.log10(d[0]));
                ymax = Math.max(ymax, Math.log10(d[0]));
            }
        }
        if (!Double.isFinite(ymin)) {
            ymin = -1;
            ymax = 1;
        }
        ymin = Math.floor(ymin);
        ymax = Math.ceil(ymax);
        if (ymax <= ymin) {
            ymax = ymin + 1;
        }
        double x0 = x(0), x1 = x(data.size() - 1);
        if (x1 <= x0) {
            x1 = x0 + 1;
        }

        // axes and grid
        g.setStroke(new BasicStroke(1f));
        for (int e = (int) ymin; e <= ymax; e++) {
            int py = (int) (TOP + h - (e - ymin) / (ymax - ymin) * h);
            g.setColor(new Color(232, 232, 232));
            g.drawLine(LEFT, py, LEFT + w, py);
            g.setColor(Color.DARK_GRAY);
            g.drawString("1e" + e, 8, py + 4);
        }
        // "nice" ticks: 1, 2 or 5 times a power of ten, whole steps on the step axis
        double tick = niceStep((x1 - x0) / 6);
        if (!timeOnXAxis) {
            tick = Math.max(1, Math.round(tick));
        }
        for (double xv = Math.ceil(x0 / tick) * tick; xv <= x1 + 1e-9 * tick; xv += tick) {
            int px = (int) (LEFT + (xv - x0) / (x1 - x0) * w);
            g.setColor(new Color(232, 232, 232));
            g.drawLine(px, TOP, px, TOP + h);
            g.setColor(Color.DARK_GRAY);
            String label = timeOnXAxis ? String.format(Locale.ROOT, "%.4g", xv) : Long.toString(Math.round(xv));
            g.drawString(label, px - g.getFontMetrics().stringWidth(label) / 2, TOP + h + 16);
        }
        g.drawRect(LEFT, TOP, w, h);
        String xLabel = timeOnXAxis ? "physical time" : "time step";
        g.drawString(xLabel, LEFT + w / 2 - g.getFontMetrics().stringWidth(xLabel) / 2, TOP + h + 34);

        // curve
        Path2D path = new Path2D.Double();
        boolean started = false;
        for (int i = 0; i < data.size(); i++) {
            double r = data.get(i)[0];
            if (!(r > 0) || !Double.isFinite(r)) {
                started = false;
                continue;
            }
            double px = LEFT + (x(i) - x0) / (x1 - x0) * w;
            double py = TOP + h - (Math.log10(r) - ymin) / (ymax - ymin) * h;
            if (started) {
                path.lineTo(px, py);
            } else {
                path.moveTo(px, py);
                started = true;
            }
        }
        g.setColor(new Color(20, 90, 200));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(path);
        if (data.size() < 60) { // markers, otherwise a single step would not be visible
            for (int i = 0; i < data.size(); i++) {
                double r = data.get(i)[0];
                if (r > 0 && Double.isFinite(r)) {
                    int px = (int) (LEFT + (x(i) - x0) / (x1 - x0) * w);
                    int py = (int) (TOP + h - (Math.log10(r) - ymin) / (ymax - ymin) * h);
                    g.fillOval(px - 3, py - 3, 6, 6);
                }
            }
        }

        if (hoverIndex >= 0 && hoverIndex < data.size()) {
            double[] d = data.get(hoverIndex);
            int px = (int) (LEFT + (x(hoverIndex) - x0) / (x1 - x0) * w);
            g.setColor(new Color(200, 60, 0));
            g.drawLine(px, TOP, px, TOP + h);
            String text = String.format(Locale.ROOT, "step %d   residuum %.3e   t = %.5g   CPU %.0f s",
                    hoverIndex + 1, d[0], d[1], d[2] / 1000);
            g.drawString(text, LEFT + 120, 18);
        }
    }
}
