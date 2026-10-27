package flowpro.mesher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
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

    public final List<Loop> loops = new ArrayList<>();

    // meshing parameters (stored together with the geometry)
    public double meshSize = 0.1;
    public double minAngle = 25; // degrees
    public double grading = 0.3; // growth of the local mesh size with the distance from a refined segment
    public int smoothingIterations = 5;

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
        for (Loop loop : loops) {
            for (double[] p : loop.points) {
                bb[0] = Math.min(bb[0], p[0]);
                bb[1] = Math.min(bb[1], p[1]);
                bb[2] = Math.max(bb[2], p[0]);
                bb[3] = Math.max(bb[3], p[1]);
            }
        }
        return bb;
    }

    /**
     * @return description of the first problem found, null if the geometry can
     * be meshed
     */
    public String validate() {
        if (loops.isEmpty()) {
            return "the geometry is empty, draw the outer boundary first";
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
