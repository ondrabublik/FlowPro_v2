package flowpro.core;

import flowpro.core.element.Element;
import flowpro.core.solver.MasterSolver;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Creates simulations for the verification tests in simulations/_test (the
 * tests run in the project directory of FlowPro) and prepares their solvers.
 */
final class TestSimulation {

    static final String ROOT = "simulations/_test/";

    private TestSimulation() {
    }

    /**
     * Square [0, size]^2 divided into n x n cells, each split into two
     * triangles (alternating diagonals), all boundary edges of type
     * boundaryType. With ale, file boundaryTypeALE.txt marks one edge with body
     * index 1, i.e. no moving bodies.
     */
    static void writeSquareMesh(String geometry, int n, double size, int boundaryType, boolean ale) throws IOException {
        File dir = new File(ROOT + geometry + "/mesh");
        deleteRecursively(dir);
        dir.mkdirs();
        double h = size / n;
        try (PrintWriter out = new PrintWriter(new File(dir, "vertices.txt"))) {
            for (int j = 0; j <= n; j++) {
                for (int i = 0; i <= n; i++) {
                    out.println(i * h + " " + j * h);
                }
            }
        }
        int nTriangles = 2 * n * n;
        try (PrintWriter out = new PrintWriter(new File(dir, "elements.txt"))) {
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int a = vertex(n, i, j), b = vertex(n, i + 1, j), c = vertex(n, i + 1, j + 1), d = vertex(n, i, j + 1);
                    if ((i + j) % 2 == 0) {
                        out.println(a + " " + b + " " + c);
                        out.println(a + " " + c + " " + d);
                    } else {
                        out.println(a + " " + b + " " + d);
                        out.println(b + " " + c + " " + d);
                    }
                }
            }
        }
        try (PrintWriter out = new PrintWriter(new File(dir, "elementType.txt"))) {
            for (int e = 0; e < nTriangles; e++) {
                out.println(3);
            }
        }
        try (PrintWriter out = new PrintWriter(new File(dir, "boundaryType.txt"))) {
            for (int i = 0; i < n; i++) {
                out.println(boundaryType + " " + vertex(n, i, 0) + " " + vertex(n, i + 1, 0));
                out.println(boundaryType + " " + vertex(n, n, i) + " " + vertex(n, n, i + 1));
                out.println(boundaryType + " " + vertex(n, i + 1, n) + " " + vertex(n, i, n));
                out.println(boundaryType + " " + vertex(n, 0, i + 1) + " " + vertex(n, 0, i));
            }
        }
        if (ale) {
            try (PrintWriter out = new PrintWriter(new File(dir, "boundaryTypeALE.txt"))) {
                out.println("1 " + vertex(n, 0, 0) + " " + vertex(n, 1, 0));
            }
        }
        try (PrintWriter out = new PrintWriter(new File(dir, "wallDistance.txt"))) {
            for (int j = 0; j <= n; j++) {
                for (int i = 0; i <= n; i++) {
                    out.println(Math.min(Math.min(i, j), Math.min(n - i, n - j)) * h);
                }
            }
        }
    }

    private static int vertex(int n, int i, int j) {
        return j * (n + 1) + i;
    }

    static void writeParameters(String geometry, String simulation, String... lines) throws IOException {
        File dir = new File(ROOT + geometry + "/" + simulation);
        deleteRecursively(dir);
        dir.mkdirs();
        try (PrintWriter out = new PrintWriter(new File(dir, "parameters.txt"))) {
            for (String line : lines) {
                out.println(line);
            }
        }
    }

    /**
     * Parameters of the linear scalar test equation (TestScalarEquation),
     * implicit method with a large fixed time step.
     */
    static String[] scalarParameters(String spatialMethod, int order, double k) {
        return new String[]{
            "model = TestScalarEquation",
            "dimension = 2",
            "a = 1, 0.5",
            k > 0 ? "k = " + k : "% no diffusion",
            "continueComputation = false",
            "steps = 4",
            "saveRate = 1000",
            "spatialMethod = " + spatialMethod,
            "timeMethod = BDF",
            "timeOrder = 1",
            "dt = 1e8",
            "linearSolver = gmres",
            "preconditioner = ilu0",
            "iterativeSolverTol = 1e-13",
            "order = " + order,
            "threads = 2",
            "newtonIters = 1",
            "penalty = 0",
            "dampTol = 0",
            "dampConst = 0"};
    }

    static MasterSolver solver(String geometry, String simulation, Map<String, String> overrides) throws Exception {
        return FlowProMain.createLocalSolver("_test/" + geometry, simulation,
                overrides == null ? new HashMap<>() : overrides);
    }

    static Map<String, String> overrides(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * L2 norm of the difference between the solution and TestScalarEquation.exact.
     */
    static double l2Error(Element[] elems) {
        double error = 0;
        for (Element elem : elems) {
            double[][] x = elem.Int.transform.getX(elem.Int.quadVolume.getCoords());
            for (int p = 0; p < elem.Int.nIntVolume; p++) {
                double u = 0;
                for (int j = 0; j < elem.nBasis; j++) {
                    u += elem.W[j] * elem.Int.basisVolume[p][j];
                }
                double diff = u - flowpro.user.equation.TestScalarEquation.exact(x[p]);
                error += elem.Int.JacobianVolume[p] * elem.Int.weightsVolume[p] * diff * diff;
            }
        }
        return Math.sqrt(error);
    }

    static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
