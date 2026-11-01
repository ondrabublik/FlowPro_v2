package flowpro.mesher;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Drawing area: shows the geometry and the mesh, allows to draw and edit the
 * boundary loops, the blocks of structured meshes and the refinement regions.
 *
 * @author obublik
 */
public class MeshCanvas extends JPanel {

    public enum Mode {
        SELECT("Select"), POLYGON("Polygon"), RECTANGLE("Rectangle"), CIRCLE("Circle"), BLOCK("Block"),
        REFINE_BOX("Refine box"), REFINE_CIRCLE("Refine circle");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private Geometry geo;
    private TriMesh mesh;
    private Mode mode = Mode.SELECT;

    // view transformation: screen = offset + scale * world (y flipped)
    private double scale = 200, offsetX = 100, offsetY = 400;

    // primary selection (shown in the side panel)
    private int selLoop = -1, selPoint = -1, selSegment = -1;
    private int selBlock = -1, selFace = -1, selVertex = -1, selRegion = -1;
    // selection by a box (or a click on a point)
    private final Set<Long> selLoopPoints = new HashSet<>(); // loop << 32 | point
    private final Set<Integer> selLoops = new HashSet<>();
    private final Set<Integer> selVertices = new HashSet<>();
    private final Set<Integer> selBlocks = new HashSet<>();
    private final Set<Integer> selRegions = new HashSet<>();
    private final Set<Long> selSegments = new HashSet<>(); // loop << 32 | segment
    private final Set<Long> selFaces = new HashSet<>(); // block << 32 | face

    private boolean dragging = false; // moving of the selected points
    private boolean moved = false;
    private Geometry.Arc draggedArc;
    private int[] boxStart, boxEnd; // selection box (screen)
    private boolean pressedOnEmpty = false;
    private boolean boxAdds = false;

    // drawing in progress
    private final List<double[]> drawing = new ArrayList<>();
    private final List<Integer> drawingVertices = new ArrayList<>(); // block vertex of each point, -1 = new
    private double[] dragStart, dragCurrent;
    private double[] mouseWorld = {0, 0};
    private double[] lastWorld;
    private int lastScreenX, lastScreenY, pressX, pressY;
    private boolean fitPending = false;

    public boolean showMesh = true;
    public boolean snapToGrid = false;
    public double gridStep = 0.1;
    public int circlePoints = 64;
    public int defaultType = Geometry.WALL;
    public double regionSize = Double.NaN; // mesh size of new refinement regions, NaN = meshSize / 4

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
        drawingVertices.clear();
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

    public int getSelectedBlock() {
        return selBlock;
    }

    /**
     * @return selected face of the selected block, -1 = the whole block
     */
    public int getSelectedFace() {
        return selFace;
    }

    public int getSelectedRegion() {
        return selRegion;
    }

    /**
     * @return number of the entities selected by a box
     */
    public int multiSelectionSize() {
        return selLoopPoints.size() + selVertices.size() + selBlocks.size() + selRegions.size();
    }

    /**
     * Segments of the loops (pairs {loop, segment}) selected by a click or by
     * a box.
     */
    public List<int[]> selectedSegments() {
        List<int[]> result = new ArrayList<>();
        for (int l = 0; l < geo.loops.size(); l++) {
            for (int i = 0; i < geo.loops.get(l).size(); i++) {
                if (isSegmentSelected(l, i)) {
                    result.add(new int[]{l, i});
                }
            }
        }
        return result;
    }

    private boolean isSegmentSelected(int l, int i) {
        return (l == selLoop && (i == selSegment || (selSegment < 0 && selPoint < 0)))
                || selLoops.contains(l) || selSegments.contains(pointKey(l, i));
    }

    /**
     * Faces of the blocks (pairs {block, face}) selected by a click or by a
     * box.
     */
    public List<int[]> selectedFaces() {
        List<int[]> result = new ArrayList<>();
        for (int b = 0; b < geo.blocks.size(); b++) {
            for (int k = 0; k < 4; k++) {
                if (isFaceSelected(b, k)) {
                    result.add(new int[]{b, k});
                }
            }
        }
        return result;
    }

    private boolean isFaceSelected(int b, int k) {
        return (b == selBlock && (selFace == k || selFace < 0)) || selBlocks.contains(b)
                || selFaces.contains(pointKey(b, k));
    }

    public Set<Integer> selectedRegions() {
        Set<Integer> result = new TreeSet<>(selRegions);
        if (selRegion >= 0) {
            result.add(selRegion);
        }
        return result;
    }

    private static long pointKey(int loop, int point) {
        return ((long) loop << 32) | point;
    }

    private void clearSelectionSilently() {
        selLoop = selPoint = selSegment = -1;
        selBlock = selFace = selVertex = selRegion = -1;
        selLoopPoints.clear();
        selLoops.clear();
        selVertices.clear();
        selBlocks.clear();
        selRegions.clear();
        selSegments.clear();
        selFaces.clear();
    }

    public void clearSelection() {
        clearSelectionSilently();
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
        // the canvas has no size yet (e.g. a hidden tab), fit when it is painted
        fitPending = getWidth() == 0;
        double[] bb = geo.isEmpty() ? meshBoundingBox() : geo.boundingBox();
        if (bb == null || getWidth() == 0) {
            scale = 200;
            offsetX = 60;
            offsetY = Math.max(getHeight() - 60, 300);
            repaint();
            return;
        }
        double w = Math.max(bb[2] - bb[0], 1e-12), h = Math.max(bb[3] - bb[1], 1e-12);
        scale = 0.9 * Math.min(getWidth() / w, getHeight() / h);
        offsetX = getWidth() / 2.0 - scale * (bb[0] + bb[2]) / 2;
        offsetY = getHeight() / 2.0 + scale * (bb[1] + bb[3]) / 2;
        repaint();
    }

    private double[] meshBoundingBox() {
        if (mesh == null || mesh.points.length == 0) {
            return null;
        }
        double[] bb = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double[] p : mesh.points) {
            bb[0] = Math.min(bb[0], p[0]);
            bb[1] = Math.min(bb[1], p[1]);
            bb[2] = Math.max(bb[2], p[0]);
            bb[3] = Math.max(bb[3], p[1]);
        }
        return bb;
    }

    // ---------------------------------------------------------------- picking
    private static final int PICK = 7; // pixels
    private static final int ARC_SAMPLES = 24;

    private boolean near(double[] p, int x, int y) {
        return Math.abs(sx(p[0]) - x) <= PICK && Math.abs(sy(p[1]) - y) <= PICK;
    }

    private int[] pickPoint(int x, int y) {
        for (int l = 0; l < geo.loops.size(); l++) {
            Geometry.Loop loop = geo.loops.get(l);
            for (int i = 0; i < loop.size(); i++) {
                if (near(loop.points.get(i), x, y)) {
                    return new int[]{l, i};
                }
            }
        }
        return null;
    }

    private int pickVertex(int x, int y) {
        for (int i = 0; i < geo.blockVertices.size(); i++) {
            if (near(geo.blockVertices.get(i), x, y)) {
                return i;
            }
        }
        return -1;
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

    /**
     * points of a block face (sampled, if it is an arc) from its vertex k to
     * vertex k+1
     */
    private List<double[]> facePoints(Geometry.Block block, int k) {
        int a = block.v[k], b = block.v[(k + 1) % 4];
        List<double[]> pts = new ArrayList<>();
        int n = geo.arc(a, b) != null ? ARC_SAMPLES : 1;
        for (int i = 0; i <= n; i++) {
            pts.add(geo.edgePoint(a, b, (double) i / n));
        }
        return pts;
    }

    private Path2D blockPath(Geometry.Block block) {
        Path2D path = new Path2D.Double();
        for (int k = 0; k < 4; k++) {
            List<double[]> pts = facePoints(block, k);
            for (int i = 0; i < pts.size() - 1; i++) {
                double[] p = pts.get(i);
                if (k == 0 && i == 0) {
                    path.moveTo(sx(p[0]), sy(p[1]));
                } else {
                    path.lineTo(sx(p[0]), sy(p[1]));
                }
            }
        }
        path.closePath();
        return path;
    }

    private int[] pickFace(int x, int y) {
        int[] best = null;
        double bestDist = PICK;
        boolean bestInside = false;
        for (int b = 0; b < geo.blocks.size(); b++) {
            Geometry.Block block = geo.blocks.get(b);
            // a shared face is picked in the block on the side of the cursor
            boolean inside = blockPath(block).contains(x, y);
            for (int k = 0; k < 4; k++) {
                List<double[]> pts = facePoints(block, k);
                for (int i = 0; i + 1 < pts.size(); i++) {
                    double[] p = pts.get(i), q = pts.get(i + 1);
                    double d = Line2D.ptSegDist(sx(p[0]), sy(p[1]), sx(q[0]), sy(q[1]), x, y);
                    if (d < bestDist - 0.5 || (d <= bestDist + 0.5 && inside && !bestInside && d <= PICK)) {
                        bestDist = d;
                        bestInside = inside;
                        best = new int[]{b, k};
                    }
                }
            }
        }
        return best;
    }

    private int pickBlock(int x, int y) {
        for (int b = geo.blocks.size() - 1; b >= 0; b--) {
            if (blockPath(geo.blocks.get(b)).contains(x, y)) {
                return b;
            }
        }
        return -1;
    }

    private int pickRegion(int x, int y) {
        double[] w = toWorld(x, y);
        for (int r = geo.regions.size() - 1; r >= 0; r--) {
            Geometry.Region reg = geo.regions.get(r);
            double d;
            if (reg.circle) {
                d = Math.abs(Math.hypot(w[0] - reg.x0, w[1] - reg.y0) - reg.r);
            } else {
                double inside = Math.min(Math.min(w[0] - reg.x0, reg.x1 - w[0]), Math.min(w[1] - reg.y0, reg.y1 - w[1]));
                d = inside >= 0 ? inside : reg.distance(w[0], w[1]);
            }
            if (d * scale <= PICK) {
                return r;
            }
        }
        return -1;
    }

    /**
     * the point of the selected face where its arc can be dragged
     */
    private double[] arcHandle() {
        if (selBlock < 0 || selFace < 0 || selBlock >= geo.blocks.size()) {
            return null;
        }
        Geometry.Block block = geo.blocks.get(selBlock);
        int a = block.v[selFace], b = block.v[(selFace + 1) % 4];
        Geometry.Arc arc = geo.arc(a, b);
        return arc != null ? new double[]{arc.x, arc.y} : geo.edgePoint(a, b, 0.5);
    }

    // ---------------------------------------------------------------- mouse
    private boolean isPan(MouseEvent e) {
        return SwingUtilities.isMiddleMouseButton(e) || SwingUtilities.isRightMouseButton(e)
                || (SwingUtilities.isLeftMouseButton(e) && e.isControlDown());
    }

    private boolean isSelectedPoint(int[] pp, int vertex) {
        return pp != null ? selLoopPoints.contains(pointKey(pp[0], pp[1])) : selVertices.contains(vertex);
    }

    private void onPress(MouseEvent e) {
        lastScreenX = pressX = e.getX();
        lastScreenY = pressY = e.getY();
        if (isPan(e)) {
            return;
        }
        double[] w = snap(toWorld(e.getX(), e.getY()));
        switch (mode) {
            case SELECT:
                pressSelect(e, w);
                break;
            case RECTANGLE:
            case CIRCLE:
            case REFINE_BOX:
            case REFINE_CIRCLE:
                dragStart = w;
                dragCurrent = w;
                break;
            default:
                break;
        }
    }

    private void pressSelect(MouseEvent e, double[] w) {
        int x = e.getX(), y = e.getY();
        lastWorld = w;
        moved = false;
        pressedOnEmpty = false;
        double[] handle = arcHandle();
        if (handle != null && near(handle, x, y)) {
            Geometry.Block block = geo.blocks.get(selBlock);
            int a = block.v[selFace], b = block.v[(selFace + 1) % 4];
            draggedArc = geo.arc(a, b);
            if (draggedArc == null) {
                draggedArc = new Geometry.Arc(a, b, handle[0], handle[1]);
                geo.arcs.add(draggedArc);
            }
            return;
        }
        int vertex = pickVertex(x, y);
        int[] pp = vertex < 0 ? pickPoint(x, y) : null;
        if (vertex >= 0 || pp != null) {
            boolean keepGroup = multiSelectionSize() > 1 && isSelectedPoint(pp, vertex);
            if (!keepGroup) {
                clearSelectionSilently();
                if (pp != null) {
                    selLoop = pp[0];
                    selPoint = pp[1];
                    selLoopPoints.add(pointKey(pp[0], pp[1]));
                } else {
                    selVertex = vertex;
                    selVertices.add(vertex);
                }
            }
            dragging = true;
            selectionListener.run();
            repaint();
            return;
        }
        int[] ps = pickSegment(x, y);
        int[] pf = ps == null ? pickFace(x, y) : null;
        int region = ps == null && pf == null ? pickRegion(x, y) : -1;
        if (ps != null || pf != null || region >= 0) {
            clearSelectionSilently();
            if (ps != null) {
                selLoop = ps[0];
                selSegment = ps[1];
            } else if (pf != null) {
                selBlock = pf[0];
                selFace = pf[1];
            } else {
                selRegion = region;
                dragging = true; // the region can be moved
            }
            selectionListener.run();
            repaint();
            return;
        }
        // empty space or the inside of a block: a click selects, a drag draws a selection box
        pressedOnEmpty = true;
        boxAdds = e.isShiftDown();
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
        if (mode == Mode.SELECT) {
            if (draggedArc != null) {
                draggedArc.x = w[0];
                draggedArc.y = w[1];
                moved = true;
                mesh = null;
                repaint();
            } else if (dragging) {
                moveSelection(w);
            } else if (pressedOnEmpty && Math.hypot(e.getX() - pressX, e.getY() - pressY) > 4) {
                boxStart = new int[]{pressX, pressY};
                boxEnd = new int[]{e.getX(), e.getY()};
                repaint();
            }
        } else if (dragStart != null) {
            dragCurrent = w;
            repaint();
        }
    }

    /**
     * Moves the selected points: a single point snaps to the grid, a group is
     * shifted.
     */
    private void moveSelection(double[] w) {
        double dx = w[0] - lastWorld[0], dy = w[1] - lastWorld[1];
        if (dx == 0 && dy == 0) {
            return;
        }
        if (multiSelectionSize() <= 1 && selRegion < 0) {
            double[] p = selPoint >= 0 ? geo.loops.get(selLoop).points.get(selPoint)
                    : (selVertex >= 0 ? geo.blockVertices.get(selVertex) : null);
            if (p != null) {
                dx = w[0] - p[0];
                dy = w[1] - p[1];
            }
        }
        for (long key : selLoopPoints) {
            double[] p = geo.loops.get((int) (key >> 32)).points.get((int) key);
            p[0] += dx;
            p[1] += dy;
        }
        Set<Integer> vertices = new HashSet<>(selVertices);
        for (int b : selBlocks) {
            for (int v : geo.blocks.get(b).v) {
                vertices.add(v);
            }
        }
        for (int v : vertices) {
            double[] p = geo.blockVertices.get(v);
            p[0] += dx;
            p[1] += dy;
        }
        for (Geometry.Arc arc : geo.arcs) {
            if (vertices.contains(arc.a) && vertices.contains(arc.b)) {
                arc.x += dx;
                arc.y += dy;
            }
        }
        for (int r : selectedRegions()) {
            geo.regions.get(r).move(dx, dy);
        }
        lastWorld = w;
        moved = true;
        mesh = null;
        repaint();
    }

    private void onRelease(MouseEvent e) {
        if (mode == Mode.SELECT) {
            if ((dragging || draggedArc != null) && moved) {
                geometryChanged();
                selectionListener.run();
            }
            dragging = false;
            draggedArc = null;
            if (boxStart != null) {
                selectInBox(boxAdds);
            } else if (pressedOnEmpty && !isPan(e)) {
                // a click inside a block selects it
                clearSelectionSilently();
                selBlock = pickBlock(e.getX(), e.getY());
                selectionListener.run();
                repaint();
            }
            boxStart = boxEnd = null;
            pressedOnEmpty = false;
        }
        if (dragStart != null && dragCurrent != null && !isPan(e)) {
            double dx = dragCurrent[0] - dragStart[0], dy = dragCurrent[1] - dragStart[1];
            if (Math.hypot(dx, dy) * scale > 5) {
                double h = Double.isNaN(regionSize) || regionSize <= 0 ? geo.meshSize / 4 : regionSize;
                clearSelectionSilently();
                switch (mode) {
                    case RECTANGLE:
                        geo.loops.add(Geometry.rectangle(dragStart[0], dragStart[1], dragCurrent[0], dragCurrent[1], defaultType));
                        selLoop = geo.loops.size() - 1;
                        break;
                    case CIRCLE:
                        geo.loops.add(Geometry.circle(dragStart[0], dragStart[1], Math.hypot(dx, dy), Math.max(3, circlePoints), defaultType));
                        selLoop = geo.loops.size() - 1;
                        break;
                    case REFINE_BOX:
                        geo.regions.add(Geometry.Region.box(dragStart[0], dragStart[1], dragCurrent[0], dragCurrent[1], h));
                        selRegion = geo.regions.size() - 1;
                        break;
                    case REFINE_CIRCLE:
                        geo.regions.add(Geometry.Region.circle(dragStart[0], dragStart[1], Math.hypot(dx, dy), h));
                        selRegion = geo.regions.size() - 1;
                        break;
                    default:
                        break;
                }
                selectionListener.run();
                geometryChanged();
            }
        }
        dragStart = dragCurrent = null;
    }

    private void selectInBox(boolean add) {
        if (!add) {
            clearSelectionSilently();
        }
        double[] a = toWorld(boxStart[0], boxStart[1]), b = toWorld(boxEnd[0], boxEnd[1]);
        double x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
        double y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
        Predicate<double[]> inBox = p -> p[0] >= x0 && p[0] <= x1 && p[1] >= y0 && p[1] <= y1;
        // segments, faces, loops and blocks are selected when they lie in one box
        for (int l = 0; l < geo.loops.size(); l++) {
            Geometry.Loop loop = geo.loops.get(l);
            int inside = 0;
            for (int i = 0; i < loop.size(); i++) {
                if (inBox.test(loop.points.get(i))) {
                    selLoopPoints.add(pointKey(l, i));
                    inside++;
                    if (inBox.test(loop.point(i + 1))) {
                        selSegments.add(pointKey(l, i));
                    }
                }
            }
            if (inside == loop.size()) {
                selLoops.add(l);
            }
        }
        for (int v = 0; v < geo.blockVertices.size(); v++) {
            if (inBox.test(geo.blockVertices.get(v))) {
                selVertices.add(v);
            }
        }
        for (int bl = 0; bl < geo.blocks.size(); bl++) {
            Geometry.Block block = geo.blocks.get(bl);
            int inside = 0;
            for (int k = 0; k < 4; k++) {
                if (inBox.test(geo.blockVertices.get(block.v[k]))) {
                    inside++;
                    if (inBox.test(geo.blockVertices.get(block.v[(k + 1) % 4]))) {
                        selFaces.add(pointKey(bl, k));
                    }
                }
            }
            if (inside == 4) {
                selBlocks.add(bl);
            }
        }
        for (int r = 0; r < geo.regions.size(); r++) {
            double[] bb = geo.regions.get(r).boundingBox();
            if (bb[0] >= x0 && bb[2] <= x1 && bb[1] >= y0 && bb[3] <= y1) {
                selRegions.add(r);
            }
        }
        // the primary selection for the side panel
        if (selLoops.size() == 1 && selLoopPoints.size() == geo.loops.get(selLoops.iterator().next()).size()) {
            selLoop = selLoops.iterator().next();
        } else if (selBlocks.size() == 1) {
            selBlock = selBlocks.iterator().next();
        } else if (selRegions.size() == 1 && multiSelectionSize() == 1) {
            selRegion = selRegions.iterator().next();
        }
        selectionListener.run();
        statusListener.accept(selectionSummary());
        repaint();
    }

    private String selectionSummary() {
        List<String> parts = new ArrayList<>();
        if (!selLoopPoints.isEmpty()) {
            parts.add(selLoopPoints.size() + " points of loops");
        }
        if (!selVertices.isEmpty()) {
            parts.add(selVertices.size() + " block vertices");
        }
        if (!selBlocks.isEmpty()) {
            parts.add(selBlocks.size() + " blocks");
        }
        if (!selRegions.isEmpty()) {
            parts.add(selRegions.size() + " refinement regions");
        }
        return parts.isEmpty() ? "nothing selected" : "selected: " + String.join(", ", parts)
                + " (Delete removes them, drag a selected point to move them)";
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
        } else if (mode == Mode.BLOCK) {
            addBlockCorner(e.getX(), e.getY());
        } else if (mode == Mode.SELECT && e.getClickCount() == 2) {
            // double click on a segment inserts a new point
            int[] ps = pickSegment(e.getX(), e.getY());
            if (ps != null && pickPoint(e.getX(), e.getY()) == null) {
                double[] w = snap(toWorld(e.getX(), e.getY()));
                Geometry.Loop loop = geo.loops.get(ps[0]);
                loop.insert(ps[1] + 1, w[0], w[1]);
                clearSelectionSilently();
                selLoop = ps[0];
                selPoint = ps[1] + 1;
                selLoopPoints.add(pointKey(selLoop, selPoint));
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
            clearSelectionSilently();
            selLoop = geo.loops.size() - 1;
            selectionListener.run();
            geometryChanged();
        }
        drawing.clear();
        repaint();
    }

    // ---------------------------------------------------------------- blocks
    /**
     * A click in the Block mode: the corners of a new block, existing block
     * vertices are reused (the blocks are connected through them).
     */
    private void addBlockCorner(int x, int y) {
        int vertex = pickVertex(x, y);
        double[] p = vertex >= 0 ? geo.blockVertices.get(vertex).clone() : snap(toWorld(x, y));
        for (int i = 0; i < drawing.size(); i++) {
            double[] q = drawing.get(i);
            if ((vertex >= 0 && drawingVertices.get(i) == vertex) || Math.hypot(p[0] - q[0], p[1] - q[1]) * scale < 3) {
                return; // the same corner again (e.g. a double click)
            }
        }
        drawing.add(p);
        drawingVertices.add(vertex);
        if (drawing.size() == 4) {
            createBlock();
        }
        repaint();
    }

    private void createBlock() {
        double area = 0;
        for (int k = 0; k < 4; k++) {
            double[] p = drawing.get(k), q = drawing.get((k + 1) % 4);
            area += p[0] * q[1] - q[0] * p[1];
        }
        int[] order = area >= 0 ? new int[]{0, 1, 2, 3} : new int[]{0, 3, 2, 1};
        Geometry.Block block = new Geometry.Block();
        for (int k = 0; k < 4; k++) {
            int v = drawingVertices.get(order[k]);
            if (v < 0) {
                geo.blockVertices.add(drawing.get(order[k]));
                v = geo.blockVertices.size() - 1;
            }
            block.v[k] = v;
            block.types[k] = defaultType;
        }
        geo.blocks.add(block);
        int index = geo.blocks.size() - 1;
        // the division is taken over from the neighbouring blocks, otherwise given by the mesh size
        for (int dir = 0; dir < 2; dir++) {
            boolean inherited = false;
            for (int k : new int[]{dir, dir + 2}) {
                int a = block.v[k], b = block.v[(k + 1) % 4];
                for (int[] other : geo.blocksAt(a, b)) {
                    if (other[0] != index && !inherited) {
                        Geometry.Block ob = geo.blocks.get(other[0]);
                        double g = ob.grading(other[1]);
                        block.setCells(k, ob.cells(other[1]));
                        block.setGrading(k, ob.v[other[1]] == a ? g : 1 / g);
                        inherited = true;
                    }
                }
            }
            if (!inherited) {
                double length = 0;
                for (int k : new int[]{dir, dir + 2}) {
                    double[] p = geo.blockVertices.get(block.v[k]), q = geo.blockVertices.get(block.v[(k + 1) % 4]);
                    length += Math.hypot(q[0] - p[0], q[1] - p[1]) / 2;
                }
                block.setCells(dir, Math.max(1, (int) Math.round(length / geo.meshSize)));
            }
        }
        drawing.clear();
        drawingVertices.clear();
        clearSelectionSilently();
        selBlock = index;
        selectionListener.run();
        geometryChanged();
    }

    // ---------------------------------------------------------------- keys, deleting
    private void onKey(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ENTER:
                if (mode == Mode.POLYGON) {
                    finishPolygon();
                }
                break;
            case KeyEvent.VK_ESCAPE:
                drawing.clear();
                drawingVertices.clear();
                dragStart = null;
                if (mode == Mode.SELECT) {
                    clearSelection();
                }
                repaint();
                break;
            case KeyEvent.VK_DELETE:
            case KeyEvent.VK_BACK_SPACE:
                if ((mode == Mode.POLYGON || mode == Mode.BLOCK) && !drawing.isEmpty()) {
                    drawing.remove(drawing.size() - 1);
                    if (!drawingVertices.isEmpty()) {
                        drawingVertices.remove(drawingVertices.size() - 1);
                    }
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
     * Deletes the selection: the selected points (a loop with less than 3
     * points is removed), the loop of a selected segment, the selected blocks
     * (and the blocks of the selected block vertices) and refinement regions.
     */
    public void deleteSelection() {
        Set<Integer> loopsToRemove = new TreeSet<>(selLoops);
        if (selLoop >= 0 && selPoint < 0) {
            loopsToRemove.add(selLoop); // a segment or the whole loop is selected
        }
        Set<Integer> blocksToRemove = new TreeSet<>(selBlocks);
        if (selBlock >= 0) {
            blocksToRemove.add(selBlock);
        }
        // a selected vertex removes its blocks, unless it is a vertex of a removed block (a box
        // around a block selects also the vertices it shares with its neighbours)
        Set<Integer> vertices = new HashSet<>(selVertices);
        for (int b : blocksToRemove) {
            for (int v : geo.blocks.get(b).v) {
                vertices.remove(v);
            }
        }
        for (int b = 0; b < geo.blocks.size(); b++) {
            for (int v : geo.blocks.get(b).v) {
                if (vertices.contains(v)) {
                    blocksToRemove.add(b);
                }
            }
        }
        Set<Integer> regionsToRemove = selectedRegions();
        if (loopsToRemove.isEmpty() && selLoopPoints.isEmpty() && blocksToRemove.isEmpty() && regionsToRemove.isEmpty()) {
            return;
        }
        // points from the end of each loop, so that the indices stay valid
        List<Long> points = new ArrayList<>(selLoopPoints);
        points.sort((p, q) -> Long.compare(q, p));
        for (long key : points) {
            int l = (int) (key >> 32);
            Geometry.Loop loop = geo.loops.get(l);
            if (loopsToRemove.contains(l)) {
                continue;
            }
            if (loop.size() > 3) {
                loop.remove((int) key);
            } else {
                loopsToRemove.add(l);
            }
        }
        removeIndices(geo.loops, loopsToRemove);
        removeIndices(geo.blocks, blocksToRemove);
        removeIndices(geo.regions, regionsToRemove);
        geo.removeUnusedBlockVertices();
        clearSelection();
        geometryChanged();
    }

    private static void removeIndices(List<?> list, Set<Integer> indices) {
        List<Integer> sorted = new ArrayList<>(indices);
        sorted.sort((a, b) -> b - a);
        for (int i : sorted) {
            if (i < list.size()) {
                list.remove(i);
            }
        }
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

    private static final Color SELECTED = new Color(255, 140, 0);

    @Override
    protected void paintComponent(Graphics g0) {
        if (fitPending && getWidth() > 0) {
            zoomToFit();
        }
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawGrid(g);
        drawBlocks(g, true);

        if (showMesh && mesh != null) {
            g.setStroke(new BasicStroke(0.6f));
            Color fill = new Color(225, 238, 255), poor = new Color(255, 200, 120), line = new Color(110, 130, 160);
            Path2D edges = new Path2D.Double();
            for (int[] t : mesh.elements) {
                Path2D path = new Path2D.Double();
                path.moveTo(sx(mesh.points[t[0]][0]), sy(mesh.points[t[0]][1]));
                for (int k = 1; k < t.length; k++) {
                    path.lineTo(sx(mesh.points[t[k]][0]), sy(mesh.points[t[k]][1]));
                }
                path.closePath();
                g.setColor(mesh.isPoor(t) ? poor : fill);
                g.fill(path);
                edges.append(path, false);
            }
            // the edges after all the fills, so that no fill covers a part of an edge
            g.setColor(line);
            g.draw(edges);
        }

        drawRegions(g);
        drawBlocks(g, false);

        // geometry
        for (int l = 0; l < geo.loops.size(); l++) {
            Geometry.Loop loop = geo.loops.get(l);
            for (int i = 0; i < loop.size(); i++) {
                double[] p = loop.point(i), q = loop.point(i + 1);
                boolean selected = isSegmentSelected(l, i);
                g.setStroke(new BasicStroke(selected ? 4f : 2.2f));
                g.setColor(selected ? SELECTED : typeColor(loop.types.get(i)));
                g.draw(new Line2D.Double(sx(p[0]), sy(p[1]), sx(q[0]), sy(q[1])));
            }
            for (int i = 0; i < loop.size(); i++) {
                double[] p = loop.points.get(i);
                boolean selected = selLoopPoints.contains(pointKey(l, i));
                // markers of densely sampled curves (airfoils) are drawn only when zoomed in
                double[] q = loop.point(i + 1);
                double gap = Math.hypot(sx(q[0]) - sx(p[0]), sy(q[1]) - sy(p[1]));
                if (!selected && gap < 10) {
                    continue;
                }
                int r = selected ? 5 : 3;
                g.setColor(selected ? SELECTED : Color.DARK_GRAY);
                g.fillRect((int) sx(p[0]) - r, (int) sy(p[1]) - r, 2 * r, 2 * r);
            }
        }

        // drawing in progress
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 4}, 0));
        g.setColor(new Color(255, 120, 0));
        if ((mode == Mode.POLYGON || mode == Mode.BLOCK) && !drawing.isEmpty()) {
            for (int i = 0; i < drawing.size(); i++) {
                double[] p = drawing.get(i);
                double[] q = i + 1 < drawing.size() ? drawing.get(i + 1) : snap(mouseWorld);
                g.draw(new Line2D.Double(sx(p[0]), sy(p[1]), sx(q[0]), sy(q[1])));
            }
            if (mode == Mode.BLOCK && drawing.size() == 3) {
                double[] p = drawing.get(0), q = snap(mouseWorld);
                g.draw(new Line2D.Double(sx(q[0]), sy(q[1]), sx(p[0]), sy(p[1])));
            }
        }
        if (dragStart != null && dragCurrent != null) {
            if (mode == Mode.RECTANGLE || mode == Mode.REFINE_BOX) {
                double x0 = Math.min(sx(dragStart[0]), sx(dragCurrent[0])), y0 = Math.min(sy(dragStart[1]), sy(dragCurrent[1]));
                g.draw(new Rectangle2D.Double(x0, y0, Math.abs(sx(dragCurrent[0]) - sx(dragStart[0])),
                        Math.abs(sy(dragCurrent[1]) - sy(dragStart[1]))));
            } else if (mode == Mode.CIRCLE || mode == Mode.REFINE_CIRCLE) {
                double r = Math.hypot(dragCurrent[0] - dragStart[0], dragCurrent[1] - dragStart[1]) * scale;
                g.draw(new Ellipse2D.Double(sx(dragStart[0]) - r, sy(dragStart[1]) - r, 2 * r, 2 * r));
            }
        }
        if (boxStart != null && boxEnd != null) {
            Rectangle2D box = new Rectangle2D.Double(Math.min(boxStart[0], boxEnd[0]), Math.min(boxStart[1], boxEnd[1]),
                    Math.abs(boxEnd[0] - boxStart[0]), Math.abs(boxEnd[1] - boxStart[1]));
            g.setColor(new Color(0, 110, 255, 35));
            g.fill(box);
            g.setColor(new Color(0, 110, 255));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{4, 3}, 0));
            g.draw(box);
        }

        drawLegend(g);
    }

    /**
     * @param fill the background of the blocks (below the mesh), otherwise
     * their faces, vertices and labels
     */
    private void drawBlocks(Graphics2D g, boolean fill) {
        if (geo.blocks.isEmpty()) {
            return;
        }
        if (fill) {
            for (int b = 0; b < geo.blocks.size(); b++) {
                boolean selected = selBlocks.contains(b) || (b == selBlock && selFace < 0);
                g.setColor(selected ? new Color(255, 220, 170) : new Color(232, 246, 232));
                g.fill(blockPath(geo.blocks.get(b)));
            }
            return;
        }
        for (int b = 0; b < geo.blocks.size(); b++) {
            Geometry.Block block = geo.blocks.get(b);
            for (int k = 0; k < 4; k++) {
                int a = block.v[k], c = block.v[(k + 1) % 4];
                boolean boundary = geo.blocksAt(a, c).size() == 1;
                boolean selected = isFaceSelected(b, k);
                g.setStroke(new BasicStroke(selected ? 4f : (boundary ? 2.2f : 1.2f)));
                g.setColor(selected ? SELECTED : (boundary ? typeColor(block.types[k]) : new Color(120, 150, 120)));
                List<double[]> pts = facePoints(block, k);
                Path2D path = new Path2D.Double();
                path.moveTo(sx(pts.get(0)[0]), sy(pts.get(0)[1]));
                for (int i = 1; i < pts.size(); i++) {
                    path.lineTo(sx(pts.get(i)[0]), sy(pts.get(i)[1]));
                }
                g.draw(path);
            }
            if (!showMesh || mesh == null) {
                double cx = 0, cy = 0;
                for (int v : block.v) {
                    cx += geo.blockVertices.get(v)[0] / 4;
                    cy += geo.blockVertices.get(v)[1] / 4;
                }
                g.setColor(new Color(60, 100, 60));
                String label = (b + 1) + ": " + block.nx + " x " + block.ny;
                g.drawString(label, (float) sx(cx) - g.getFontMetrics().stringWidth(label) / 2f, (float) sy(cy) + 4);
            }
        }
        for (int v = 0; v < geo.blockVertices.size(); v++) {
            double[] p = geo.blockVertices.get(v);
            boolean selected = selVertices.contains(v);
            int r = selected ? 5 : 3;
            g.setColor(selected ? SELECTED : new Color(40, 90, 40));
            g.fillRect((int) sx(p[0]) - r, (int) sy(p[1]) - r, 2 * r, 2 * r);
        }
        double[] handle = arcHandle();
        if (handle != null) {
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(sx(handle[0]) - 5, sy(handle[1]) - 5, 10, 10));
            g.setColor(SELECTED);
            g.draw(new Ellipse2D.Double(sx(handle[0]) - 5, sy(handle[1]) - 5, 10, 10));
        }
    }

    private void drawRegions(Graphics2D g) {
        Set<Integer> selected = selectedRegions();
        for (int r = 0; r < geo.regions.size(); r++) {
            Geometry.Region reg = geo.regions.get(r);
            Shape shape = reg.circle
                    ? new Ellipse2D.Double(sx(reg.x0) - reg.r * scale, sy(reg.y0) - reg.r * scale, 2 * reg.r * scale, 2 * reg.r * scale)
                    : new Rectangle2D.Double(sx(reg.x0), sy(reg.y1), (reg.x1 - reg.x0) * scale, (reg.y1 - reg.y0) * scale);
            boolean sel = selected.contains(r);
            g.setColor(new Color(170, 0, 200, 20));
            g.fill(shape);
            g.setColor(sel ? SELECTED : new Color(170, 0, 200));
            g.setStroke(new BasicStroke(sel ? 3f : 1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{7, 4}, 0));
            g.draw(shape);
            double[] bb = reg.boundingBox();
            g.drawString(String.format(Locale.ROOT, "h = %.3g", reg.h), (float) sx(bb[0]) + 4, (float) sy(bb[3]) + 14);
        }
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
