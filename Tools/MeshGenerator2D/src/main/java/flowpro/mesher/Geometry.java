package flowpro.mesher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Geometry of a 2D domain: a set of closed polygonal loops. The loop with the
 * largest area is the outer boundary, the others are holes (bodies). Every
 * segment of a loop carries the FlowPro boundary type, an optional local mesh
 * size and an optional ALE body tag.
 *
 * @author obublik
 */
public class Geometry {

    /**
     * Boundary types used by the FlowPro aerodynamic models.
     */
    public static final int WALL = -1;
    public static final int INLET = -2;
    public static final int OUTLET = -3;
    public static final int INVISCID_WALL = -4;

    public static String typeName(int type) {
        switch (type) {
            case WALL:
                return "wall";
            case INLET:
                return "inlet";
            case OUTLET:
                return "outlet";
            case INVISCID_WALL:
                return "inviscid wall";
            default:
                return "type " + type;
        }
    }

    public static class Loop {

        public final List<double[]> points = new ArrayList<>();
        // attributes of segment i (from point i to point i+1)
        public final List<Integer> types = new ArrayList<>();
        public final List<Double> sizes = new ArrayList<>(); // NaN = global mesh size
        public final List<Integer> aleTags = new ArrayList<>(); // 0 = no ALE body

        public int size() {
            return points.size();
        }

        public void add(double x, double y, int type, double h, int ale) {
            points.add(new double[]{x, y});
            types.add(type);
            sizes.add(h);
            aleTags.add(ale);
        }

        public void insert(int index, double x, double y) {
            int prev = Math.floorMod(index - 1, size());
            points.add(index, new double[]{x, y});
            types.add(index, types.get(prev));
            sizes.add(index, sizes.get(prev));
            aleTags.add(index, aleTags.get(prev));
        }

        public void remove(int index) {
            points.remove(index);
            types.remove(index);
            sizes.remove(index);
            aleTags.remove(index);
        }

        public double[] point(int i) {
            return points.get(Math.floorMod(i, size()));
        }

        /**
         * signed area, positive for counter-clockwise loops
         */
        public double signedArea() {
            double a = 0;
            for (int i = 0; i < size(); i++) {
                double[] p = point(i);
                double[] q = point(i + 1);
                a += p[0] * q[1] - q[0] * p[1];
            }
            return a / 2;
        }

        public void setAll(int type, double h, int ale) {
            for (int i = 0; i < size(); i++) {
                types.set(i, type);
                sizes.set(i, h);
                aleTags.set(i, ale);
            }
        }
    }

    /**
     * Region (rectangle or circle) where the mesh size is limited, the size
     * grows with the distance from the region according to 'grading'.
     */
    public static class Region {

        public boolean circle;
        // rectangle: corners (x0, y0), (x1, y1); circle: centre (x0, y0), radius r
        public double x0, y0, x1, y1, r;
        public double h;

        public static Region box(double xa, double ya, double xb, double yb, double h) {
            Region reg = new Region();
            reg.x0 = Math.min(xa, xb);
            reg.y0 = Math.min(ya, yb);
            reg.x1 = Math.max(xa, xb);
            reg.y1 = Math.max(ya, yb);
            reg.h = h;
            return reg;
        }

        public static Region circle(double cx, double cy, double r, double h) {
            Region reg = new Region();
            reg.circle = true;
            reg.x0 = cx;
            reg.y0 = cy;
            reg.r = r;
            reg.h = h;
            return reg;
        }

        /**
         * distance of a point from the region, 0 inside
         */
        public double distance(double x, double y) {
            if (circle) {
                return Math.max(0, Math.hypot(x - x0, y - y0) - r);
            }
            double dx = Math.max(0, Math.max(x0 - x, x - x1));
            double dy = Math.max(0, Math.max(y0 - y, y - y1));
            return Math.hypot(dx, dy);
        }

        public void move(double dx, double dy) {
            x0 += dx;
            y0 += dy;
            if (!circle) {
                x1 += dx;
                y1 += dy;
            }
        }

        public double[] boundingBox() {
            return circle ? new double[]{x0 - r, y0 - r, x0 + r, y0 + r} : new double[]{x0, y0, x1, y1};
        }
    }

    /**
     * Block of a structured mesh (as in blockMesh of OpenFOAM): a curved
     * quadrilateral given by 4 block vertices in counter-clockwise order. Its
     * face k goes from vertex k to vertex k+1. Faces 0 and 2 are divided into
     * nx cells, faces 1 and 3 into ny cells. gx is the ratio of the last and
     * the first cell in the direction v0 -> v1, gy in the direction v1 -> v2.
     * Faces which are not shared with another block are boundaries of type
     * types[k].
     */
    public static class Block {

        public final int[] v = new int[4];
        public int nx = 10, ny = 10;
        public double gx = 1, gy = 1;
        public final int[] types = {WALL, WALL, WALL, WALL};
        public final int[] aleTags = new int[4];

        public int cells(int face) {
            return face % 2 == 0 ? nx : ny;
        }

        public void setCells(int face, int n) {
            if (face % 2 == 0) {
                nx = n;
            } else {
                ny = n;
            }
        }

        /**
         * grading of a face in the direction from its vertex k to vertex k+1
         */
        public double grading(int face) {
            double g = face % 2 == 0 ? gx : gy;
            return face < 2 ? g : 1 / g;
        }

        public void setGrading(int face, double g) {
            double value = face < 2 ? g : 1 / g;
            if (face % 2 == 0) {
                gx = value;
            } else {
                gy = value;
            }
        }
    }

    /**
     * Edge between block vertices a and b curved as a circular arc through
     * point (x, y).
     */
    public static class Arc {

        public int a, b;
        public double x, y;

        public Arc(int a, int b, double x, double y) {
            this.a = a;
            this.b = b;
            this.x = x;
            this.y = y;
        }

        public boolean is(int p, int q) {
            return (a == p && b == q) || (a == q && b == p);
        }
    }

    public final List<Loop> loops = new ArrayList<>();
    public final List<Region> regions = new ArrayList<>();
    public final List<double[]> blockVertices = new ArrayList<>();
    public final List<Block> blocks = new ArrayList<>();
    public final List<Arc> arcs = new ArrayList<>();

    // meshing parameters (stored together with the geometry)
    public double meshSize = 0.1;
    public double minAngle = 25; // degrees
    public double grading = 0.3; // growth of the local mesh size with the distance from a refined segment
    public int smoothingIterations = 5;
    public int refinementLevels = 0; // every level halves the size of the elements
    public boolean blockTriangles = false; // blocks are divided into triangles instead of quadrilaterals

    public boolean isEmpty() {
        return loops.isEmpty() && blocks.isEmpty();
    }

    public boolean hasBlocks() {
        return !blocks.isEmpty();
    }

    public Arc arc(int a, int b) {
        for (Arc arc : arcs) {
            if (arc.is(a, b)) {
                return arc;
            }
        }
        return null;
    }

    /**
     * Point of the edge between block vertices a and b (straight or arc) at
     * the parameter s in [0, 1] (proportional to the length).
     */
    public double[] edgePoint(int a, int b, double s) {
        double[] p = blockVertices.get(a), q = blockVertices.get(b);
        Arc arc = arc(a, b);
        if (arc != null) {
            double[] c = circumcentre(p, q, new double[]{arc.x, arc.y});
            if (c != null) {
                double r = Math.hypot(p[0] - c[0], p[1] - c[1]);
                double phiP = Math.atan2(p[1] - c[1], p[0] - c[0]);
                double phiQ = Math.atan2(q[1] - c[1], q[0] - c[0]);
                double phiM = Math.atan2(arc.y - c[1], arc.x - c[0]);
                // the sweep from p to q passing through the point of the arc
                double sweep = normalise(phiQ - phiP);
                if (normalise(phiM - phiP) > sweep) {
                    sweep -= 2 * Math.PI;
                }
                double phi = phiP + s * sweep;
                return new double[]{c[0] + r * Math.cos(phi), c[1] + r * Math.sin(phi)};
            }
        }
        return new double[]{p[0] + s * (q[0] - p[0]), p[1] + s * (q[1] - p[1])};
    }

    /**
     * angle in [0, 2 pi)
     */
    private static double normalise(double phi) {
        double t = phi % (2 * Math.PI);
        return t < 0 ? t + 2 * Math.PI : t;
    }

    private static double[] circumcentre(double[] a, double[] b, double[] c) {
        double bx = b[0] - a[0], by = b[1] - a[1];
        double cx = c[0] - a[0], cy = c[1] - a[1];
        double d = 2 * (bx * cy - by * cx);
        if (Math.abs(d) < 1e-12 * (bx * bx + by * by + cx * cx + cy * cy)) {
            return null; // collinear points, straight edge
        }
        double b2 = bx * bx + by * by, c2 = cx * cx + cy * cy;
        return new double[]{a[0] + (cy * b2 - by * c2) / d, a[1] + (bx * c2 - cx * b2) / d};
    }

    /**
     * Blocks sharing the edge (a, b), as pairs {block, face}.
     */
    public List<int[]> blocksAt(int a, int b) {
        List<int[]> result = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            for (int k = 0; k < 4; k++) {
                int p = block.v[k], q = block.v[(k + 1) % 4];
                if ((p == a && q == b) || (p == b && q == a)) {
                    result.add(new int[]{i, k});
                }
            }
        }
        return result;
    }

    /**
     * Sets the number of cells and the grading of face 'face' of a block and
     * copies them to all the faces which must have the same division (the
     * opposite face of the block and the faces of the neighbouring blocks,
     * transitively). The grading is not changed if g is NaN.
     */
    public void setDivision(int blockIndex, int face, int n, double g) {
        boolean[][] done = new boolean[blocks.size()][2];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{blockIndex, face});
        // grading of face k of block b in the direction of its vertex k -> k+1
        double[] gradingOf = new double[blocks.size() * 4];
        gradingOf[blockIndex * 4 + face] = g;
        while (!queue.isEmpty()) {
            int[] item = queue.poll();
            Block block = blocks.get(item[0]);
            int k = item[1];
            if (done[item[0]][k % 2]) {
                continue;
            }
            done[item[0]][k % 2] = true;
            double gk = gradingOf[item[0] * 4 + k];
            block.setCells(k, n);
            if (!Double.isNaN(gk)) {
                block.setGrading(k, gk);
            }
            for (int f : new int[]{k % 2, k % 2 + 2}) {
                double gf = Double.isNaN(gk) ? Double.NaN : block.grading(f);
                int a = block.v[f], b = block.v[(f + 1) % 4];
                for (int[] other : blocksAt(a, b)) {
                    if (other[0] == item[0] || done[other[0]][other[1] % 2]) {
                        continue;
                    }
                    Block ob = blocks.get(other[0]);
                    boolean same = ob.v[other[1]] == a;
                    gradingOf[other[0] * 4 + other[1]] = Double.isNaN(gf) ? Double.NaN : (same ? gf : 1 / gf);
                    queue.add(other);
                }
            }
        }
    }

    /**
     * Removes the block vertices which are not used by any block and the arcs
     * of edges which do not exist any more.
     */
    public void removeUnusedBlockVertices() {
        int[] map = new int[blockVertices.size()];
        Arrays.fill(map, -1);
        for (Block block : blocks) {
            for (int v : block.v) {
                map[v] = 0;
            }
        }
        List<double[]> kept = new ArrayList<>();
        for (int i = 0; i < map.length; i++) {
            if (map[i] >= 0) {
                map[i] = kept.size();
                kept.add(blockVertices.get(i));
            }
        }
        blockVertices.clear();
        blockVertices.addAll(kept);
        for (Block block : blocks) {
            for (int k = 0; k < 4; k++) {
                block.v[k] = map[block.v[k]];
            }
        }
        List<Arc> keptArcs = new ArrayList<>();
        for (Arc arc : arcs) {
            if (map[arc.a] >= 0 && map[arc.b] >= 0) {
                arc.a = map[arc.a];
                arc.b = map[arc.b];
                if (!blocksAt(arc.a, arc.b).isEmpty()) {
                    keptArcs.add(arc);
                }
            }
        }
        arcs.clear();
        arcs.addAll(keptArcs);
    }

    public Loop outerLoop() {
        Loop outer = null;
        for (Loop loop : loops) {
            if (outer == null || Math.abs(loop.signedArea()) > Math.abs(outer.signedArea())) {
                outer = loop;
            }
        }
        return outer;
    }

    /**
     * even-odd rule over all the loops (outer boundary and holes)
     */
    public boolean contains(double x, double y) {
        boolean inside = false;
        for (Loop loop : loops) {
            int n = loop.size();
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double[] pi = loop.points.get(i);
                double[] pj = loop.points.get(j);
                if ((pi[1] > y) != (pj[1] > y)
                        && x < (pj[0] - pi[0]) * (y - pi[1]) / (pj[1] - pi[1]) + pi[0]) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    public double[] boundingBox() {
        double[] bb = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        List<double[]> points = new ArrayList<>();
        for (Loop loop : loops) {
            points.addAll(loop.points);
        }
        points.addAll(blockVertices);
        for (Arc arc : arcs) {
            points.add(new double[]{arc.x, arc.y});
        }
        for (double[] p : points) {
            bb[0] = Math.min(bb[0], p[0]);
            bb[1] = Math.min(bb[1], p[1]);
            bb[2] = Math.max(bb[2], p[0]);
            bb[3] = Math.max(bb[3], p[1]);
        }
        return bb;
    }

    /**
     * @return description of the first problem found, null if the geometry can
     * be meshed
     */
    public String validate() {
        if (hasBlocks()) {
            return validateBlocks();
        }
        if (loops.isEmpty()) {
            return "the geometry is empty, draw the outer boundary (or blocks) first";
        }
        for (int l = 0; l < loops.size(); l++) {
            Loop loop = loops.get(l);
            if (loop.size() < 3) {
                return "loop " + (l + 1) + " has less than 3 points";
            }
            if (Math.abs(loop.signedArea()) < 1e-14) {
                return "loop " + (l + 1) + " has zero area";
            }
        }
        // segments must not intersect each other
        List<double[][]> segments = new ArrayList<>();
        for (Loop loop : loops) {
            for (int i = 0; i < loop.size(); i++) {
                segments.add(new double[][]{loop.point(i), loop.point(i + 1)});
            }
        }
        for (int i = 0; i < segments.size(); i++) {
            for (int j = i + 1; j < segments.size(); j++) {
                double[][] s = segments.get(i);
                double[][] t = segments.get(j);
                if (s[0] == t[0] || s[0] == t[1] || s[1] == t[0] || s[1] == t[1]) {
                    continue; // neighbouring segments
                }
                if (properIntersection(s[0], s[1], t[0], t[1])) {
                    return String.format(Locale.ROOT, "segments intersect near [%.4g, %.4g]", s[0][0], s[0][1]);
                }
            }
        }
        if (meshSize <= 0) {
            return "the mesh size must be positive";
        }
        return null;
    }

    private String validateBlocks() {
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            for (int k = 0; k < 4; k++) {
                for (int m = k + 1; m < 4; m++) {
                    if (block.v[k] == block.v[m]) {
                        return "block " + (i + 1) + " has a repeated vertex";
                    }
                }
            }
            if (block.nx < 1 || block.ny < 1) {
                return "block " + (i + 1) + " must have at least one cell in each direction";
            }
            if (!(block.gx > 0) || !(block.gy > 0)) {
                return "the grading of block " + (i + 1) + " must be positive";
            }
            for (int k = 0; k < 4; k++) {
                if (orient(blockVertices.get(block.v[(k + 3) % 4]), blockVertices.get(block.v[k]),
                        blockVertices.get(block.v[(k + 1) % 4])) <= 0) {
                    return "block " + (i + 1) + " is not convex (or its vertices are not counter-clockwise)";
                }
            }
            for (int k = 0; k < 4; k++) {
                List<int[]> shared = blocksAt(block.v[k], block.v[(k + 1) % 4]);
                if (shared.size() > 2) {
                    return "face " + (k + 1) + " of block " + (i + 1) + " is shared by more than two blocks";
                }
                for (int[] other : shared) {
                    if (other[0] != i && blocks.get(other[0]).cells(other[1]) != block.cells(k)) {
                        return "blocks " + (i + 1) + " and " + (other[0] + 1) + " have a different number of cells "
                                + "on their common face (set the division of one of them again)";
                    }
                }
            }
        }
        if (meshSize <= 0) {
            return "the mesh size must be positive";
        }
        return null;
    }

    private static double orient(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private static boolean properIntersection(double[] a, double[] b, double[] c, double[] d) {
        double o1 = orient(a, b, c);
        double o2 = orient(a, b, d);
        double o3 = orient(c, d, a);
        double o4 = orient(c, d, b);
        return o1 * o2 < 0 && o3 * o4 < 0;
    }

    // ---------------------------------------------------------------- primitives
    public static Loop rectangle(double x0, double y0, double x1, double y1, int type) {
        Loop loop = new Loop();
        double xa = Math.min(x0, x1), xb = Math.max(x0, x1);
        double ya = Math.min(y0, y1), yb = Math.max(y0, y1);
        loop.add(xa, ya, type, Double.NaN, 0);
        loop.add(xb, ya, type, Double.NaN, 0);
        loop.add(xb, yb, type, Double.NaN, 0);
        loop.add(xa, yb, type, Double.NaN, 0);
        return loop;
    }

    public static Loop circle(double cx, double cy, double r, int n, int type) {
        Loop loop = new Loop();
        for (int i = 0; i < n; i++) {
            double phi = 2 * Math.PI * i / n;
            loop.add(cx + r * Math.cos(phi), cy + r * Math.sin(phi), type, Double.NaN, 0);
        }
        return loop;
    }

    /**
     * NACA 4-digit airfoil (closed trailing edge), cosine spacing.
     *
     * @param code e.g. "0012" or "2412"
     * @param chord chord length
     * @param x0 leading edge position
     * @param y0 leading edge position
     * @param angleDeg angle of attack (rotation around the leading edge,
     * positive = nose up)
     * @param n number of points on each side
     */
    public static Loop naca4(String code, double chord, double x0, double y0, double angleDeg, int n, int type) {
        if (code.length() != 4) {
            throw new IllegalArgumentException("NACA code must have 4 digits");
        }
        double m = (code.charAt(0) - '0') / 100.0;
        double p = (code.charAt(1) - '0') / 10.0;
        double t = Integer.parseInt(code.substring(2)) / 100.0;
        double alpha = -Math.toRadians(angleDeg);
        Loop loop = new Loop();
        // from the trailing edge along the lower side to the leading edge and back along the upper side
        List<double[]> pts = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < n; i++) {
                int k = side == 0 ? n - i : i; // lower: TE -> LE, upper: LE -> TE
                if (side == 1 && i == 0) {
                    continue; // the leading edge only once
                }
                double beta = Math.PI * k / n;
                double x = 0.5 * (1 - Math.cos(beta));
                double yt = 5 * t * (0.2969 * Math.sqrt(x) - 0.1260 * x - 0.3516 * x * x + 0.2843 * x * x * x - 0.1036 * x * x * x * x);
                double yc = 0, dyc = 0;
                if (m > 0 && p > 0) {
                    if (x < p) {
                        yc = m / (p * p) * (2 * p * x - x * x);
                        dyc = 2 * m / (p * p) * (p - x);
                    } else {
                        yc = m / ((1 - p) * (1 - p)) * (1 - 2 * p + 2 * p * x - x * x);
                        dyc = 2 * m / ((1 - p) * (1 - p)) * (p - x);
                    }
                }
                double th = Math.atan(dyc);
                double sign = side == 0 ? -1 : 1;
                double xs = x - sign * yt * Math.sin(th);
                double ys = yc + sign * yt * Math.cos(th);
                pts.add(new double[]{xs, ys});
            }
        }
        for (double[] q : pts) {
            double xr = q[0] * Math.cos(alpha) - q[1] * Math.sin(alpha);
            double yr = q[0] * Math.sin(alpha) + q[1] * Math.cos(alpha);
            loop.add(x0 + chord * xr, y0 + chord * yr, type, Double.NaN, 0);
        }
        return loop;
    }

    // ---------------------------------------------------------------- file format
    public void save(File file) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("# FlowPro 2D geometry (MeshGenerator2D)\n");
            w.write("# point x y : the segment to the next point has boundary type, local mesh size (NaN = global) and ALE tag\n");
            w.write(String.format(Locale.ROOT, "meshSize %s%n", meshSize));
            w.write(String.format(Locale.ROOT, "minAngle %s%n", minAngle));
            w.write(String.format(Locale.ROOT, "grading %s%n", grading));
            w.write(String.format(Locale.ROOT, "smoothing %d%n", smoothingIterations));
            w.write(String.format(Locale.ROOT, "refinement %d%n", refinementLevels));
            w.write(String.format(Locale.ROOT, "blockTriangles %d%n", blockTriangles ? 1 : 0));
            for (Region reg : regions) {
                if (reg.circle) {
                    w.write(String.format(Locale.ROOT, "region circle %.17g %.17g %.17g %s%n", reg.x0, reg.y0, reg.r, reg.h));
                } else {
                    w.write(String.format(Locale.ROOT, "region box %.17g %.17g %.17g %.17g %s%n",
                            reg.x0, reg.y0, reg.x1, reg.y1, reg.h));
                }
            }
            if (!blocks.isEmpty()) {
                w.write("# block v0 v1 v2 v3 (vertex indices from 0, counter-clockwise) nx ny gradingX gradingY "
                        + "boundary types of faces 0-3, ALE tags of faces 0-3\n");
            }
            for (double[] p : blockVertices) {
                w.write(String.format(Locale.ROOT, "vertex %.17g %.17g%n", p[0], p[1]));
            }
            for (Block b : blocks) {
                w.write(String.format(Locale.ROOT, "block %d %d %d %d %d %d %s %s %d %d %d %d %d %d %d %d%n",
                        b.v[0], b.v[1], b.v[2], b.v[3], b.nx, b.ny, b.gx, b.gy,
                        b.types[0], b.types[1], b.types[2], b.types[3],
                        b.aleTags[0], b.aleTags[1], b.aleTags[2], b.aleTags[3]));
            }
            for (Arc arc : arcs) {
                w.write(String.format(Locale.ROOT, "arc %d %d %.17g %.17g%n", arc.a, arc.b, arc.x, arc.y));
            }
            for (Loop loop : loops) {
                w.write("loop\n");
                for (int i = 0; i < loop.size(); i++) {
                    double[] p = loop.points.get(i);
                    w.write(String.format(Locale.ROOT, "point %.17g %.17g %d %s %d%n", p[0], p[1],
                            loop.types.get(i), loop.sizes.get(i), loop.aleTags.get(i)));
                }
                w.write("end\n");
            }
        }
    }

    public static Geometry load(File file) throws IOException {
        Geometry geo = new Geometry();
        Loop loop = null;
        int lineNumber = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] t = line.split("\\s+");
                try {
                    switch (t[0]) {
                        case "meshSize":
                            geo.meshSize = Double.parseDouble(t[1]);
                            break;
                        case "minAngle":
                            geo.minAngle = Double.parseDouble(t[1]);
                            break;
                        case "grading":
                            geo.grading = Double.parseDouble(t[1]);
                            break;
                        case "smoothing":
                            geo.smoothingIterations = Integer.parseInt(t[1]);
                            break;
                        case "refinement":
                            geo.refinementLevels = Integer.parseInt(t[1]);
                            break;
                        case "blockTriangles":
                            geo.blockTriangles = !t[1].equals("0");
                            break;
                        case "region":
                            if (t[1].equals("circle")) {
                                geo.regions.add(Region.circle(Double.parseDouble(t[2]), Double.parseDouble(t[3]),
                                        Double.parseDouble(t[4]), Double.parseDouble(t[5])));
                            } else {
                                geo.regions.add(Region.box(Double.parseDouble(t[2]), Double.parseDouble(t[3]),
                                        Double.parseDouble(t[4]), Double.parseDouble(t[5]), Double.parseDouble(t[6])));
                            }
                            break;
                        case "vertex":
                            geo.blockVertices.add(new double[]{Double.parseDouble(t[1]), Double.parseDouble(t[2])});
                            break;
                        case "block": {
                            Block b = new Block();
                            for (int k = 0; k < 4; k++) {
                                b.v[k] = Integer.parseInt(t[1 + k]);
                                if (b.v[k] < 0 || b.v[k] >= geo.blockVertices.size()) {
                                    throw new IOException("block vertex " + b.v[k] + " is not defined");
                                }
                            }
                            b.nx = Integer.parseInt(t[5]);
                            b.ny = Integer.parseInt(t[6]);
                            b.gx = t.length > 7 ? Double.parseDouble(t[7]) : 1;
                            b.gy = t.length > 8 ? Double.parseDouble(t[8]) : 1;
                            for (int k = 0; k < 4; k++) {
                                b.types[k] = t.length > 9 + k ? Integer.parseInt(t[9 + k]) : WALL;
                                b.aleTags[k] = t.length > 13 + k ? Integer.parseInt(t[13 + k]) : 0;
                            }
                            geo.blocks.add(b);
                            break;
                        }
                        case "arc":
                            geo.arcs.add(new Arc(Integer.parseInt(t[1]), Integer.parseInt(t[2]),
                                    Double.parseDouble(t[3]), Double.parseDouble(t[4])));
                            break;
                        case "loop":
                            loop = new Loop();
                            break;
                        case "point":
                            if (loop == null) {
                                throw new IOException("point outside of a loop");
                            }
                            loop.add(Double.parseDouble(t[1]), Double.parseDouble(t[2]),
                                    t.length > 3 ? Integer.parseInt(t[3]) : WALL,
                                    t.length > 4 ? Double.parseDouble(t[4]) : Double.NaN,
                                    t.length > 5 ? Integer.parseInt(t[5]) : 0);
                            break;
                        case "end":
                            if (loop != null && loop.size() > 0) {
                                geo.loops.add(loop);
                            }
                            loop = null;
                            break;
                        default:
                            throw new IOException("unknown keyword " + t[0]);
                    }
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
                    throw new IOException("file " + file + ", line " + lineNumber + ": " + ex.getMessage(), ex);
                } catch (IOException ex) {
                    throw new IOException("file " + file + ", line " + lineNumber + ": " + ex.getMessage(), ex);
                }
            }
        }
        if (loop != null && loop.size() > 0) {
            geo.loops.add(loop);
        }
        return geo;
    }

    /**
     * Reads points "x y" (separated by spaces, tabs, commas or semicolons) from
     * a text file, e.g. an airfoil exported from another program.
     */
    public static Loop importPoints(File file, int type) throws IOException {
        Loop loop = new Loop();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("[\\s,;]+");
                if (t.length < 2) {
                    continue;
                }
                try {
                    double x = Double.parseDouble(t[0]);
                    double y = Double.parseDouble(t[1]);
                    loop.add(x, y, type, Double.NaN, 0);
                } catch (NumberFormatException ex) {
                    // header line
                }
            }
        }
        // closed curves often repeat the first point at the end
        if (loop.size() > 1) {
            double[] a = loop.points.get(0);
            double[] b = loop.points.get(loop.size() - 1);
            if (Math.hypot(a[0] - b[0], a[1] - b[1]) < 1e-12) {
                loop.remove(loop.size() - 1);
            }
        }
        return loop;
    }
}
