package flowpro.mesher;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Drawing area: shows the geometry and the mesh, allows to draw and edit the
 * boundary loops.
 *
 * @author obublik
 */
public class MeshCanvas extends JPanel {

    public enum Mode {
        SELECT, POLYGON, RECTANGLE, CIRCLE
    }

    private Geometry geo;
    private TriMesh mesh;
    private Mode mode = Mode.SELECT;

    // view transformation: screen = offset + scale * world (y flipped)
    private double scale = 200, offsetX = 100, offsetY = 400;

    // selection
    private int selLoop = -1, selPoint = -1, selSegment = -1;
    private boolean dragging = false;

    // drawing in progress
    private final List<double[]> drawing = new ArrayList<>();
    private double[] dragStart, dragCurrent;
    private double[] mouseWorld = {0, 0};
    private int lastScreenX, lastScreenY;

    public boolean showMesh = true;
    public boolean snapToGrid = false;
    public double gridStep = 0.1;
    public int circlePoints = 64;
    public int defaultType = Geometry.WALL;

    private Consumer<String> statusListener = s -> {
    };
    private Runnable selectionListener = () -> {
    };
    private Runnable geometryListener = () -> {
    };

    public MeshCanvas(Geometry geo) {
        this.geo = geo;
        setBackground(Color.WHITE);
        setFocusable(true);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                onPress(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                onRelease(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                onDrag(e);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                mouseWorld = toWorld(e.getX(), e.getY());
                statusListener.accept(String.format(Locale.ROOT, "x = %.5g, y = %.5g", mouseWorld[0], mouseWorld[1]));
                if (mode != Mode.SELECT) {
                    repaint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                onClick(e);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                double factor = Math.pow(1.15, -e.getPreciseWheelRotation());
                double[] w = toWorld(e.getX(), e.getY());
                scale *= factor;
                offsetX = e.getX() - scale * w[0];
                offsetY = e.getY() + scale * w[1];
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                onKey(e);
            }
        });
    }

    // ---------------------------------------------------------------- accessors
    public void setGeometry(Geometry geo) {
        this.geo = geo;
        mesh = null;
        clearSelection();
        zoomToFit();
    }

    public Geometry getGeometry() {
        return geo;
    }

    public void setMesh(TriMesh mesh) {
        this.mesh = mesh;
        repaint();
    }

    public TriMesh getMesh() {
        return mesh;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        drawing.clear();
        dragStart = null;
        repaint();
    }

    public void setStatusListener(Consumer<String> listener) {
        statusListener = listener;
    }

    public void setSelectionListener(Runnable listener) {
        selectionListener = listener;
    }

    public void setGeometryListener(Runnable listener) {
        geometryListener = listener;
    }

    public int getSelectedLoop() {
        return selLoop;
    }

    public int getSelectedSegment() {
        return selSegment;
    }

    public int getSelectedPoint() {
        return selPoint;
    }

    public void clearSelection() {
        selLoop = selPoint = selSegment = -1;
        selectionListener.run();
        repaint();
    }

    private void geometryChanged() {
        mesh = null; // the mesh does not correspond to the geometry any more
        geometryListener.run();
        repaint();
    }

    // ---------------------------------------------------------------- view
    private double[] toWorld(int sx, int sy) {
        return new double[]{(sx - offsetX) / scale, (offsetY - sy) / scale};
    }

    private double sx(double x) {
        return offsetX + scale * x;
    }

    private double sy(double y) {
        return offsetY - scale * y;
    }

    private double[] snap(double[] w) {
        if (snapToGrid && gridStep > 0) {
            return new double[]{Math.round(w[0] / gridStep) * gridStep, Math.round(w[1] / gridStep) * gridStep};
        }
        return w;
    }

    public void zoomToFit() {
        if (geo.loops.isEmpty() || getWidth() == 0) {
            scale = 200;
            offsetX = 60;
            offsetY = Math.max(getHeight() - 60, 300);
            repaint();
            return;
        }
        double[] bb = geo.boundingBox();
        double w = Math.max(bb[2] - bb[0], 1e-12), h = Math.max(bb[3] - bb[1], 1e-12);
        scale = 0.9 * Math.min(getWidth() / w, getHeight() / h);
        offsetX = getWidth() / 2.0 - scale * (bb[0] + bb[2]) / 2;
        offsetY = getHeight() / 2.0 + scale * (bb[1] + bb[3]) / 2;
        repaint();
    }

    // ---------------------------------------------------------------- picking
    private static final int PICK = 7; // pixels

    private int[] pickPoint(int x, int y) {
        for (int l = 0; l < geo.loops.size(); l++) {
            Geometry.Loop loop = geo.loops.get(l);
            for (int i = 0; i < loop.size(); i++) {
                double[] p = loop.points.get(i);
                if (Math.abs(sx(p[0]) - x) <= PICK && Math.abs(sy(p[1]) - y) <= PICK) {
                    return new int[]{l, i};
                }
            }
        }
        return null;
    }

    private int[] pickSegment(int x, int y) {
        int[] best = null;
        double bestDist = PICK;
        for (int l = 0; l < geo.loops.size(); l++) {
            Geometry.Loop loop = geo.loops.get(l);
            for (int i = 0; i < loop.size(); i++) {
                double[] p = loop.point(i), q = loop.point(i + 1);
                double d = Line2D.ptSegDist(sx(p[0]), sy(p[1]), sx(q[0]), sy(q[1]), x, y);
                if (d < bestDist) {
                    bestDist = d;
                    best = new int[]{l, i};
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- mouse
    private boolean isPan(MouseEvent e) {
        return SwingUtilities.isMiddleMouseButton(e) || SwingUtilities.isRightMouseButton(e)
                || (SwingUtilities.isLeftMouseButton(e) && e.isControlDown());
    }

    private void onPress(MouseEvent e) {
        lastScreenX = e.getX();
        lastScreenY = e.getY();
        if (isPan(e)) {
            return;
        }
        double[] w = snap(toWorld(e.getX(), e.getY()));
        switch (mode) {
            case SELECT:
                int[] pp = pickPoint(e.getX(), e.getY());
                if (pp != null) {
                    selLoop = pp[0];
                    selPoint = pp[1];
                    selSegment = -1;
                    dragging = true;
                } else {
                    int[] ps = pickSegment(e.getX(), e.getY());
                    if (ps != null) {
                        selLoop = ps[0];
                        selSegment = ps[1];
                        selPoint = -1;
                    } else {
                        selLoop = selPoint = selSegment = -1;
                    }
                }
                selectionListener.run();
                repaint();
                break;
            case RECTANGLE:
            case CIRCLE:
                dragStart = w;
                dragCurrent = w;
                break;
            default:
                break;
        }
    }

    private void onDrag(MouseEvent e) {
        if (isPan(e)) {
            offsetX += e.getX() - lastScreenX;
            offsetY += e.getY() - lastScreenY;
            lastScreenX = e.getX();
            lastScreenY = e.getY();
            repaint();
            return;
        }
        double[] w = snap(toWorld(e.getX(), e.getY()));
        mouseWorld = w;
        if (mode == Mode.SELECT && dragging && selPoint >= 0) {
            double[] p = geo.loops.get(selLoop).points.get(selPoint);
            p[0] = w[0];
            p[1] = w[1];
            mesh = null;
            repaint();
        } else if (dragStart != null) {
            dragCurrent = w;
            repaint();
        }
    }

    private void onRelease(MouseEvent e) {
        if (mode == Mode.SELECT && dragging) {
            dragging = false;
            geometryChanged();
            selectionListener.run();
        }
        if (dragStart != null && dragCurrent != null && !isPan(e)) {
            double dx = dragCurrent[0] - dragStart[0], dy = dragCurrent[1] - dragStart[1];
            if (Math.hypot(dx, dy) * scale > 5) {
                if (mode == Mode.RECTANGLE) {
                    geo.loops.add(Geometry.rectangle(dragStart[0], dragStart[1], dragCurrent[0], dragCurrent[1], defaultType));
                } else if (mode == Mode.CIRCLE) {
                    geo.loops.add(Geometry.circle(dragStart[0], dragStart[1], Math.hypot(dx, dy), Math.max(3, circlePoints), defaultType));
                }
                selLoop = geo.loops.size() - 1;
                selPoint = selSegment = -1;
                selectionListener.run();
                geometryChanged();
            }
        }
        dragStart = dragCurrent = null;
    }

    private void onClick(MouseEvent e) {
        if (!SwingUtilities.isLeftMouseButton(e) || e.isControlDown()) {
            return;
        }
        if (mode == Mode.POLYGON) {
            if (e.getClickCount() >= 2) {
                finishPolygon();
            } else {
                drawing.add(snap(toWorld(e.getX(), e.getY())));
                repaint();
            }
        } else if (mode == Mode.SELECT && e.getClickCount() == 2) {
            // double click on a segment inserts a new point
            int[] ps = pickSegment(e.getX(), e.getY());
            if (ps != null && pickPoint(e.getX(), e.getY()) == null) {
                double[] w = snap(toWorld(e.getX(), e.getY()));
                Geometry.Loop loop = geo.loops.get(ps[0]);
                loop.insert(ps[1] + 1, w[0], w[1]);
                selLoop = ps[0];
                selPoint = ps[1] + 1;
                selSegment = -1;
                selectionListener.run();
                geometryChanged();
            }
        }
    }

    private void finishPolygon() {
        // the double click adds the last point twice
        while (drawing.size() >= 2) {
            double[] a = drawing.get(drawing.size() - 1), b = drawing.get(drawing.size() - 2);
            if (Math.hypot(a[0] - b[0], a[1] - b[1]) * scale < 3) {
                drawing.remove(drawing.size() - 1);
            } else {
                break;
            }
        }
        if (drawing.size() >= 3) {
            Geometry.Loop loop = new Geometry.Loop();
            for (double[] p : drawing) {
                loop.add(p[0], p[1], defaultType, Double.NaN, 0);
            }
            geo.loops.add(loop);
            selLoop = geo.loops.size() - 1;
            selPoint = selSegment = -1;
            selectionListener.run();
            geometryChanged();
        }
        drawing.clear();
        repaint();
    }

    private void onKey(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ENTER:
                if (mode == Mode.POLYGON) {
                    finishPolygon();
                }
                break;
            case KeyEvent.VK_ESCAPE:
                drawing.clear();
                dragStart = null;
                repaint();
                break;
            case KeyEvent.VK_DELETE:
            case KeyEvent.VK_BACK_SPACE:
                if (mode == Mode.POLYGON && !drawing.isEmpty()) {
                    drawing.remove(drawing.size() - 1);
                    repaint();
                } else {
                    deleteSelection();
                }
                break;
            default:
                break;
        }
    }

    /**
     * Deletes the selected point, or the whole loop if a segment is selected.
     */
    public void deleteSelection() {
        if (selLoop < 0) {
            return;
        }
        Geometry.Loop loop = geo.loops.get(selLoop);
        if (selPoint >= 0 && loop.size() > 3) {
            loop.remove(selPoint);
        } else {
            geo.loops.remove(selLoop);
        }
        clearSelection();
        geometryChanged();
    }

    // ---------------------------------------------------------------- painting
    public static Color typeColor(int type) {
        switch (type) {
            case Geometry.WALL:
                return new Color(30, 30, 30);
            case Geometry.INLET:
                return new Color(0, 90, 220);
            case Geometry.OUTLET:
                return new Color(210, 30, 30);
            case Geometry.INVISCID_WALL:
                return new Color(0, 150, 60);
            default:
                return new Color(160, 0, 160);
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawGrid(g);

        if (showMesh && mesh != null) {
            g.setStroke(new BasicStroke(0.6f));
            for (int[] t : mesh.triangles) {
                Path2D path = new Path2D.Double();
                path.moveTo(sx(mesh.points[t[0]][0]), sy(mesh.points[t[0]][1]));
                path.lineTo(sx(mesh.points[t[1]][0]), sy(mesh.points[t[1]][1]));
                path.lineTo(sx(mesh.points[t[2]][0]), sy(mesh.points[t[2]][1]));
                path.closePath();
                double q = mesh.minAngle(t);
                if (q < 20) {
                    g.setColor(new Color(255, 200, 120));
                    g.fill(path);
                } else {
                    g.setColor(new Color(225, 238, 255));
                    g.fill(path);
                }
                g.setColor(new Color(110, 130, 160));
                g.draw(path);
            }
        }

        // geometry
        for (int l = 0; l < geo.loops.size(); l++) {
            Geometry.Loop loop = geo.loops.get(l);
            for (int i = 0; i < loop.size(); i++) {
                double[] p = loop.point(i), q = loop.point(i + 1);
                boolean selected = l == selLoop && (i == selSegment || (selSegment < 0 && selPoint < 0));
                g.setStroke(new BasicStroke(selected ? 4f : 2.2f));
                g.setColor(selected ? new Color(255, 140, 0) : typeColor(loop.types.get(i)));
                g.draw(new Line2D.Double(sx(p[0]), sy(p[1]), sx(q[0]), sy(q[1])));
            }
            for (int i = 0; i < loop.size(); i++) {
                double[] p = loop.points.get(i);
                boolean selected = l == selLoop && i == selPoint;
                // markers of densely sampled curves (airfoils) are drawn only when zoomed in
                double[] q = loop.point(i + 1);
                double gap = Math.hypot(sx(q[0]) - sx(p[0]), sy(q[1]) - sy(p[1]));
                if (!selected && gap < 10) {
                    continue;
                }
                int r = selected ? 5 : 3;
                g.setColor(selected ? new Color(255, 140, 0) : Color.DARK_GRAY);
                g.fillRect((int) sx(p[0]) - r, (int) sy(p[1]) - r, 2 * r, 2 * r);
            }
        }

        // drawing in progress
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 4}, 0));
        g.setColor(new Color(255, 120, 0));
        if (mode == Mode.POLYGON && !drawing.isEmpty()) {
            for (int i = 0; i < drawing.size(); i++) {
                double[] p = drawing.get(i);
                double[] q = i + 1 < drawing.size() ? drawing.get(i + 1) : snap(mouseWorld);
                g.draw(new Line2D.Double(sx(p[0]), sy(p[1]), sx(q[0]), sy(q[1])));
            }
        }
        if (dragStart != null && dragCurrent != null) {
            if (mode == Mode.RECTANGLE) {
                double x0 = Math.min(sx(dragStart[0]), sx(dragCurrent[0])), y0 = Math.min(sy(dragStart[1]), sy(dragCurrent[1]));
                g.draw(new java.awt.geom.Rectangle2D.Double(x0, y0, Math.abs(sx(dragCurrent[0]) - sx(dragStart[0])),
                        Math.abs(sy(dragCurrent[1]) - sy(dragStart[1]))));
            } else if (mode == Mode.CIRCLE) {
                double r = Math.hypot(dragCurrent[0] - dragStart[0], dragCurrent[1] - dragStart[1]) * scale;
                g.draw(new java.awt.geom.Ellipse2D.Double(sx(dragStart[0]) - r, sy(dragStart[1]) - r, 2 * r, 2 * r));
            }
        }

        drawLegend(g);
    }

    private void drawGrid(Graphics2D g) {
        double[] w0 = toWorld(0, getHeight());
        double[] w1 = toWorld(getWidth(), 0);
        double step = Math.pow(10, Math.floor(Math.log10((w1[0] - w0[0]) / 8)));
        if (snapToGrid && gridStep > 0 && gridStep * scale > 6) {
            step = gridStep;
        }
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(242, 242, 242));
        for (double x = Math.floor(w0[0] / step) * step; x <= w1[0]; x += step) {
            g.draw(new Line2D.Double(sx(x), 0, sx(x), getHeight()));
        }
        for (double y = Math.floor(w0[1] / step) * step; y <= w1[1]; y += step) {
            g.draw(new Line2D.Double(0, sy(y), getWidth(), sy(y)));
        }
        g.setColor(new Color(200, 200, 200));
        g.draw(new Line2D.Double(sx(0), 0, sx(0), getHeight()));
        g.draw(new Line2D.Double(0, sy(0), getWidth(), sy(0)));
    }

    private void drawLegend(Graphics2D g) {
        int[] types = {Geometry.WALL, Geometry.INLET, Geometry.OUTLET, Geometry.INVISCID_WALL};
        int x = 10, y = getHeight() - 10 - 16 * types.length;
        g.setColor(new Color(255, 255, 255, 220));
        g.fillRect(x - 4, y - 12, 160, 16 * types.length + 6);
        g.setStroke(new BasicStroke(3f));
        for (int type : types) {
            g.setColor(typeColor(type));
            g.drawLine(x, y, x + 24, y);
            g.setColor(Color.DARK_GRAY);
            g.drawString(Geometry.typeName(type) + " (" + type + ")", x + 30, y + 4);
            y += 16;
        }
    }
}
