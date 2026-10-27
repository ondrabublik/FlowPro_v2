package flowpro.mesher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Generator of 2D unstructured triangular meshes.
 * <ol>
 * <li>the boundary loops are discretised according to the mesh size,</li>
 * <li>Delaunay triangulation of the boundary points (Bowyer-Watson),</li>
 * <li>recovery of the boundary segments (segments missing in the
 * triangulation are split - conforming Delaunay triangulation),</li>
 * <li>removal of the triangles outside the domain and inside the holes,</li>
 * <li>Delaunay refinement (Ruppert): circumcentres of triangles which are too
 * large or have a small angle are inserted, encroached segments are split,</li>
 * <li>Laplacian smoothing of the interior vertices.</li>
 * </ol>
 *
 * @author obublik
 */
public class Mesher {

    public interface Progress {

        void report(String message);

        boolean isCancelled();
    }

    public static class MeshingException extends Exception {

        public MeshingException(String message) {
            super(message);
        }
    }

    /**
     * constrained (boundary) segment
     */
    private static final class Seg {

        final int a, b;
        final int type;
        final int ale;

        Seg(int a, int b, int type, int ale) {
            this.a = a;
            this.b = b;
            this.type = type;
            this.ale = ale;
        }
    }

    private final Geometry geo;
    private final Progress progress;
    public int maxPoints = 2_000_000;

    // vertices
    private double[] px = new double[1024];
    private double[] py = new double[1024];
    private int np = 0;

    // triangles: vertices tv[3t..3t+2] counter-clockwise, tn[3t+i] is the
    // neighbour across the edge opposite to vertex i (-1 = none)
    private int[] tv = new int[3 * 2048];
    private int[] tn = new int[3 * 2048];
    private boolean[] dead = new boolean[2048];
    private int nt = 0;
    private final ArrayDeque<Integer> freeTriangles = new ArrayDeque<>();

    private final Map<Long, Seg> segs = new HashMap<>();
    private final List<double[]> sizedSegments = new ArrayList<>(); // x1, y1, x2, y2, h
    private double scale;
    private double minSegmentLength;
    private int lastTriangle = 0;
    private final Random random = new Random(1);

    // work arrays for the cavity search
    private int[] mark = new int[2048];
    private int stamp = 0;
    private int blockedTriangle, blockedEdge;

    public Mesher(Geometry geo, Progress progress) {
        this.geo = geo;
        this.progress = progress;
    }

    // ------------------------------------------------------------------ basics
    private int addPoint(double x, double y) {
        if (np == px.length) {
            px = Arrays.copyOf(px, 2 * np);
            py = Arrays.copyOf(py, 2 * np);
        }
        px[np] = x;
        py[np] = y;
        return np++;
    }

    private int newTriangle(int a, int b, int c) {
        int t;
        if (!freeTriangles.isEmpty()) {
            t = freeTriangles.pop();
        } else {
            if (nt == dead.length) {
                int cap = 2 * nt;
                tv = Arrays.copyOf(tv, 3 * cap);
                tn = Arrays.copyOf(tn, 3 * cap);
                dead = Arrays.copyOf(dead, cap);
                mark = Arrays.copyOf(mark, cap);
            }
            t = nt++;
        }
        tv[3 * t] = a;
        tv[3 * t + 1] = b;
        tv[3 * t + 2] = c;
        tn[3 * t] = tn[3 * t + 1] = tn[3 * t + 2] = -1;
        dead[t] = false;
        lastTriangle = t;
        return t;
    }

    private void killTriangle(int t) {
        dead[t] = true;
        freeTriangles.push(t);
    }

    private static long key(int a, int b) {
        return a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
    }

    private double orient(int a, int b, double x, double y) {
        return (px[b] - px[a]) * (y - py[a]) - (py[b] - py[a]) * (x - px[a]);
    }

    private double orient(int a, int b, int c) {
        return orient(a, b, px[c], py[c]);
    }

    /**
     * > 0 if (x, y) lies inside the circumcircle of triangle t
     */
    private double inCircle(int t, double x, double y) {
        int a = tv[3 * t], b = tv[3 * t + 1], c = tv[3 * t + 2];
        double ax = px[a] - x, ay = py[a] - y;
        double bx = px[b] - x, by = py[b] - y;
        double cx = px[c] - x, cy = py[c] - y;
        return (ax * ax + ay * ay) * (bx * cy - cx * by)
                - (bx * bx + by * by) * (ax * cy - cx * ay)
                + (cx * cx + cy * cy) * (ax * by - bx * ay);
    }

    private int edgeA(int t, int i) {
        return tv[3 * t + (i + 1) % 3];
    }

    private int edgeB(int t, int i) {
        return tv[3 * t + (i + 2) % 3];
    }

    private boolean isConstrained(int t, int i) {
        return segs.containsKey(key(edgeA(t, i), edgeB(t, i)));
    }

    private int edgeIndex(int t, int a, int b) {
        for (int i = 0; i < 3; i++) {
            int ea = edgeA(t, i), eb = edgeB(t, i);
            if ((ea == a && eb == b) || (ea == b && eb == a)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Walks from triangle start towards point (x, y).
     *
     * @param stopAtConstraints stop when a constrained edge should be crossed
     * @return the triangle containing the point, or -1 when the walk was
     * blocked (blockedTriangle, blockedEdge are set)
     */
    private int locate(double x, double y, int start, boolean stopAtConstraints) {
        int t = start;
        if (t < 0 || t >= nt || dead[t]) {
            t = lastTriangle;
            if (dead[t]) {
                for (t = 0; t < nt && dead[t]; t++) {
                }
            }
        }
        int maxSteps = 4 * nt + 100;
        for (int step = 0; step < maxSteps; step++) {
            int r = random.nextInt(3);
            boolean moved = false;
            for (int k = 0; k < 3; k++) {
                int i = (r + k) % 3;
                if (orient(edgeA(t, i), edgeB(t, i), x, y) < 0) {
                    int nb = tn[3 * t + i];
                    if (nb < 0 || (stopAtConstraints && isConstrained(t, i))) {
                        blockedTriangle = t;
                        blockedEdge = i;
                        return -1;
                    }
                    t = nb;
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                return t;
            }
        }
        // should not happen, linear search as a fallback
        for (int s = 0; s < nt; s++) {
            if (!dead[s] && orient(tv[3 * s], tv[3 * s + 1], x, y) >= 0
                    && orient(tv[3 * s + 1], tv[3 * s + 2], x, y) >= 0
                    && orient(tv[3 * s + 2], tv[3 * s], x, y) >= 0) {
                return s;
            }
        }
        blockedTriangle = t;
        blockedEdge = 0;
        return -1;
    }

    /**
     * Collects the triangles whose circumcircle contains (x, y) and which are
     * reachable from t0 without crossing a constrained edge (except the split
     * segment).
     */
    private List<Integer> cavity(double x, double y, int t0, Seg split) {
        stamp++;
        List<Integer> cavity = new ArrayList<>();
        cavity.add(t0);
        mark[t0] = stamp;
        if (split != null) {
            int i = edgeIndex(t0, split.a, split.b);
            int nb = i >= 0 ? tn[3 * t0 + i] : -1;
            if (nb >= 0) {
                cavity.add(nb);
                mark[nb] = stamp;
            }
        }
        for (int c = 0; c < cavity.size(); c++) {
            int t = cavity.get(c);
            for (int i = 0; i < 3; i++) {
                int nb = tn[3 * t + i];
                if (nb < 0 || mark[nb] == stamp) {
                    continue;
                }
                if (isConstrained(t, i)) {
                    continue;
                }
                if (inCircle(nb, x, y) > 0) {
                    mark[nb] = stamp;
                    cavity.add(nb);
                }
            }
        }
        return cavity;
    }

    /**
     * Bowyer-Watson insertion of a new vertex.
     *
     * @param split segment on which the new vertex lies (it is replaced by two
     * segments), or null
     * @param created the new triangles are added into this list (may be null)
     * @return false if the point could not be inserted
     */
    private boolean insert(double x, double y, int t0, Seg split, List<Integer> created) {
        // do not insert points too close to the existing vertices
        for (int k = 0; k < 3; k++) {
            int v = tv[3 * t0 + k];
            if (Math.abs(px[v] - x) + Math.abs(py[v] - y) < 1e-12 * scale) {
                return false;
            }
        }
        List<Integer> cavity = cavity(x, y, t0, split);

        // boundary of the cavity
        List<int[]> boundary = new ArrayList<>();
        double tol = 1e-14 * scale * scale;
        for (int t : cavity) {
            for (int i = 0; i < 3; i++) {
                int nb = tn[3 * t + i];
                if (nb >= 0 && mark[nb] == stamp) {
                    continue;
                }
                int a = edgeA(t, i), b = edgeB(t, i);
                if (split != null && nb < 0 && key(a, b) == key(split.a, split.b)) {
                    continue; // the split segment lies on the boundary of the domain
                }
                if (orient(a, b, x, y) <= tol) {
                    return false; // the cavity is not star-shaped
                }
                boundary.add(new int[]{a, b, nb});
            }
        }

        int p = addPoint(x, y);
        for (int t : cavity) {
            killTriangle(t);
        }
        Map<Integer, Integer> startsAt = new HashMap<>();
        Map<Integer, Integer> endsAt = new HashMap<>();
        List<Integer> fan = new ArrayList<>(boundary.size());
        for (int[] e : boundary) {
            int t = newTriangle(e[0], e[1], p);
            tn[3 * t + 2] = e[2];
            if (e[2] >= 0) {
                int j = edgeIndex(e[2], e[0], e[1]);
                tn[3 * e[2] + j] = t;
            }
            startsAt.put(e[0], t);
            endsAt.put(e[1], t);
            fan.add(t);
        }
        for (int t : fan) {
            int a = tv[3 * t], b = tv[3 * t + 1];
            tn[3 * t] = startsAt.getOrDefault(b, -1); // edge (b, p)
            tn[3 * t + 1] = endsAt.getOrDefault(a, -1); // edge (p, a)
        }
        if (split != null) {
            segs.remove(key(split.a, split.b));
            segs.put(key(split.a, p), new Seg(split.a, p, split.type, split.ale));
            segs.put(key(p, split.b), new Seg(p, split.b, split.type, split.ale));
        }
        if (created != null) {
            created.addAll(fan);
        }
        return true;
    }

    // ------------------------------------------------------------------ sizing
    private double sizeAt(double x, double y) {
        double h = geo.meshSize;
        for (double[] s : sizedSegments) {
            double d = distanceToSegment(x, y, s[0], s[1], s[2], s[3]);
            h = Math.min(h, s[4] + geo.grading * d);
        }
        return h;
    }

    private static double distanceToSegment(double x, double y, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double len2 = dx * dx + dy * dy;
        double s = len2 > 0 ? ((x - x1) * dx + (y - y1) * dy) / len2 : 0;
        s = Math.max(0, Math.min(1, s));
        return Math.hypot(x - x1 - s * dx, y - y1 - s * dy);
    }

    // ------------------------------------------------------------------ meshing
    public TriMesh generate() throws MeshingException {
        String problem = geo.validate();
        if (problem != null) {
            throw new MeshingException(problem);
        }
        double[] bb = geo.boundingBox();
        scale = Math.hypot(bb[2] - bb[0], bb[3] - bb[1]);
        double hMin = geo.meshSize;
        for (Geometry.Loop loop : geo.loops) {
            for (int i = 0; i < loop.size(); i++) {
                double h = loop.sizes.get(i);
                if (!Double.isNaN(h) && h > 0) {
                    double[] p = loop.point(i), q = loop.point(i + 1);
                    sizedSegments.add(new double[]{p[0], p[1], q[0], q[1], h});
                    hMin = Math.min(hMin, h);
                }
            }
        }
        minSegmentLength = 1e-3 * hMin;

        // super triangle
        double cx = (bb[0] + bb[2]) / 2, cy = (bb[1] + bb[3]) / 2;
        double r = 20 * scale;
        addPoint(cx - r, cy - r);
        addPoint(cx + r, cy - r);
        addPoint(cx, cy + r);
        newTriangle(0, 1, 2);

        report("discretising the boundary");
        List<Seg> pending = discretiseBoundary();

        report("recovering the boundary");
        recoverSegments(pending);

        report("removing triangles outside the domain");
        removeExterior();

        report("refining");
        refine();

        report("smoothing");
        TriMesh mesh = buildResult();
        mesh.smooth(geo.smoothingIterations);
        mesh.computeNeighbours();
        report(mesh.statistics());
        return mesh;
    }

    private void report(String msg) {
        if (progress != null) {
            progress.report(msg);
        }
    }

    private void checkCancelled() throws MeshingException {
        if (progress != null && progress.isCancelled()) {
            throw new MeshingException("cancelled");
        }
    }

    private List<Seg> discretiseBoundary() throws MeshingException {
        List<Seg> pending = new ArrayList<>();
        for (Geometry.Loop loop : geo.loops) {
            int n = loop.size();
            int[] index = new int[n];
            for (int i = 0; i < n; i++) {
                double[] p = loop.point(i);
                index[i] = insertFree(p[0], p[1]);
            }
            for (int i = 0; i < n; i++) {
                double[] p = loop.point(i), q = loop.point(i + 1);
                double len = Math.hypot(q[0] - p[0], q[1] - p[1]);
                double h = loop.sizes.get(i);
                if (Double.isNaN(h) || h <= 0) {
                    h = geo.meshSize;
                }
                h = Math.min(h, Math.min(sizeAt(p[0], p[1]), sizeAt(q[0], q[1])) * 1.0);
                h = Math.min(h, sizeAt((p[0] + q[0]) / 2, (p[1] + q[1]) / 2));
                int k = Math.max(1, (int) Math.ceil(len / h - 1e-9));
                int prev = index[i];
                for (int j = 1; j <= k; j++) {
                    int cur;
                    if (j == k) {
                        cur = index[(i + 1) % n];
                    } else {
                        double s = (double) j / k;
                        cur = insertFree(p[0] + s * (q[0] - p[0]), p[1] + s * (q[1] - p[1]));
                    }
                    pending.add(new Seg(prev, cur, loop.types.get(i), loop.aleTags.get(i)));
                    prev = cur;
                }
                checkCancelled();
            }
        }
        return pending;
    }

    /**
     * inserts a point into the unconstrained triangulation
     */
    private int insertFree(double x, double y) throws MeshingException {
        int t = locate(x, y, lastTriangle, false);
        if (t < 0) {
            throw new MeshingException("point [" + x + ", " + y + "] lies outside of the triangulation");
        }
        if (!insert(x, y, t, null, null)) {
            // the point already exists
            for (int k = 0; k < 3; k++) {
                int v = tv[3 * t + k];
                if (Math.abs(px[v] - x) + Math.abs(py[v] - y) < 1e-9 * scale) {
                    return v;
                }
            }
            throw new MeshingException("point [" + x + ", " + y + "] could not be inserted");
        }
        return np - 1;
    }

    private void recoverSegments(List<Seg> pending) throws MeshingException {
        for (int pass = 0; pass < 200; pass++) {
            Set<Long> edges = new HashSet<>();
            for (int t = 0; t < nt; t++) {
                if (!dead[t]) {
                    for (int i = 0; i < 3; i++) {
                        edges.add(key(edgeA(t, i), edgeB(t, i)));
                    }
                }
            }
            List<Seg> next = new ArrayList<>();
            boolean allPresent = true;
            for (Seg s : pending) {
                if (edges.contains(key(s.a, s.b))) {
                    next.add(s);
                } else {
                    allPresent = false;
                    double mx = (px[s.a] + px[s.b]) / 2, my = (py[s.a] + py[s.b]) / 2;
                    int m = insertFree(mx, my);
                    next.add(new Seg(s.a, m, s.type, s.ale));
                    next.add(new Seg(m, s.b, s.type, s.ale));
                }
            }
            pending = next;
            if (allPresent) {
                for (Seg s : pending) {
                    segs.put(key(s.a, s.b), s);
                }
                return;
            }
            checkCancelled();
        }
        throw new MeshingException("the boundary could not be recovered (segments too close to each other?)");
    }

    private void removeExterior() {
        boolean[] remove = new boolean[nt];
        for (int t = 0; t < nt; t++) {
            if (dead[t]) {
                continue;
            }
            int a = tv[3 * t], b = tv[3 * t + 1], c = tv[3 * t + 2];
            if (a < 3 || b < 3 || c < 3) {
                remove[t] = true;
                continue;
            }
            double x = (px[a] + px[b] + px[c]) / 3, y = (py[a] + py[b] + py[c]) / 3;
            remove[t] = !geo.contains(x, y);
        }
        for (int t = 0; t < nt; t++) {
            if (!dead[t] && remove[t]) {
                for (int i = 0; i < 3; i++) {
                    int nb = tn[3 * t + i];
                    if (nb >= 0 && !remove[nb]) {
                        tn[3 * nb + edgeIndex(nb, edgeA(t, i), edgeB(t, i))] = -1;
                    }
                }
                killTriangle(t);
            }
        }
        for (int t = 0; t < nt; t++) {
            if (!dead[t]) {
                lastTriangle = t;
                break;
            }
        }
    }

    private boolean isBad(int t, double maxRatio) {
        int a = tv[3 * t], b = tv[3 * t + 1], c = tv[3 * t + 2];
        double la = Math.hypot(px[b] - px[c], py[b] - py[c]);
        double lb = Math.hypot(px[a] - px[c], py[a] - py[c]);
        double lc = Math.hypot(px[a] - px[b], py[a] - py[b]);
        double area = orient(a, b, c) / 2;
        if (area <= 0) {
            return false;
        }
        double R = la * lb * lc / (4 * area);
        double lmin = Math.min(la, Math.min(lb, lc));
        if (R / lmin > maxRatio && lmin > minSegmentLength) {
            return true;
        }
        double h = sizeAt((px[a] + px[b] + px[c]) / 3, (py[a] + py[b] + py[c]) / 3);
        return R > 1.1 * h / Math.sqrt(3);
    }

    private double[] circumcentre(int t) {
        int a = tv[3 * t], b = tv[3 * t + 1], c = tv[3 * t + 2];
        double bx = px[b] - px[a], by = py[b] - py[a];
        double cx = px[c] - px[a], cy = py[c] - py[a];
        double d = 2 * (bx * cy - by * cx);
        double b2 = bx * bx + by * by, c2 = cx * cx + cy * cy;
        return new double[]{px[a] + (cy * b2 - by * c2) / d, py[a] + (bx * c2 - cx * b2) / d};
    }

    private void refine() throws MeshingException {
        double maxRatio = 1 / (2 * Math.sin(Math.toRadians(Math.max(1, Math.min(geo.minAngle, 33)))));
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int t = 0; t < nt; t++) {
            if (!dead[t]) {
                queue.add(t);
            }
        }
        List<Integer> created = new ArrayList<>();
        long iterations = 0;
        while (!queue.isEmpty()) {
            if (np >= maxPoints) {
                report("maximal number of points reached, refinement stopped");
                break;
            }
            if (++iterations % 20000 == 0) {
                checkCancelled();
                report("refining: " + (np - 3) + " points");
            }
            int t = queue.poll();
            if (dead[t] || !isBad(t, maxRatio)) {
                continue;
            }
            double[] cc = circumcentre(t);
            created.clear();

            // segment encroached by the circumcentre
            Seg encroached = null;
            int encroachedTriangle = -1;
            int tc = locate(cc[0], cc[1], t, true);
            if (tc < 0) {
                encroached = segs.get(key(edgeA(blockedTriangle, blockedEdge), edgeB(blockedTriangle, blockedEdge)));
                encroachedTriangle = blockedTriangle;
            } else {
                for (int ct : cavity(cc[0], cc[1], tc, null)) {
                    for (int i = 0; i < 3 && encroached == null; i++) {
                        int nb = tn[3 * ct + i];
                        if ((nb < 0 || mark[nb] != stamp) && isConstrained(ct, i)) {
                            int a = edgeA(ct, i), b = edgeB(ct, i);
                            double mx = (px[a] + px[b]) / 2, my = (py[a] + py[b]) / 2;
                            double r2 = ((px[a] - px[b]) * (px[a] - px[b]) + (py[a] - py[b]) * (py[a] - py[b])) / 4;
                            if ((cc[0] - mx) * (cc[0] - mx) + (cc[1] - my) * (cc[1] - my) < r2) {
                                encroached = segs.get(key(a, b));
                                encroachedTriangle = ct;
                            }
                        }
                    }
                    if (encroached != null) {
                        break;
                    }
                }
            }

            if (encroached != null) {
                double len = Math.hypot(px[encroached.a] - px[encroached.b], py[encroached.a] - py[encroached.b]);
                if (len < 2 * minSegmentLength) {
                    continue; // small input angle, the triangle cannot be improved
                }
                double mx = (px[encroached.a] + px[encroached.b]) / 2;
                double my = (py[encroached.a] + py[encroached.b]) / 2;
                if (insert(mx, my, encroachedTriangle, encroached, created)) {
                    queue.addAll(created);
                    if (!dead[t]) {
                        queue.add(t);
                    }
                }
            } else if (tc >= 0) {
                if (insert(cc[0], cc[1], tc, null, created)) {
                    queue.addAll(created);
                }
            }
        }
    }

    private TriMesh buildResult() {
        int[] map = new int[np];
        Arrays.fill(map, -1);
        List<double[]> points = new ArrayList<>();
        List<int[]> tris = new ArrayList<>();
        for (int t = 0; t < nt; t++) {
            if (dead[t]) {
                continue;
            }
            int[] tri = new int[3];
            for (int k = 0; k < 3; k++) {
                int v = tv[3 * t + k];
                if (map[v] < 0) {
                    map[v] = points.size();
                    points.add(new double[]{px[v], py[v]});
                }
                tri[k] = map[v];
            }
            tris.add(tri);
        }
        List<int[]> boundary = new ArrayList<>();
        for (Seg s : segs.values()) {
            if (map[s.a] >= 0 && map[s.b] >= 0) {
                boundary.add(new int[]{s.type, map[s.a], map[s.b], s.ale});
            }
        }
        return new TriMesh(points.toArray(new double[0][]), tris.toArray(new int[0][]), boundary);
    }
}
