package flowpro.core;

import flowpro.core.LinearSolvers.LinearSolver;
import flowpro.core.element.Element;
import flowpro.core.solver.JacobiAssembler;
import flowpro.core.solver.MasterSolver;
import java.io.File;
import java.util.Random;
import static org.junit.Assert.assertTrue;
import org.junit.AfterClass;
import org.junit.Test;

/**
 * GMRES with the preconditioners (ILU0, block ILU0, block Jacobi, none) and
 * both orderings of the unknowns compared with a direct solution (Gaussian
 * elimination of the dense matrix) of the system assembled by the implicit
 * method.
 */
public class LinearSolverTest {

    @AfterClass
    public static void cleanUp() {
        TestSimulation.deleteRecursively(new File(TestSimulation.ROOT + "linear"));
    }

    @Test
    public void preconditionedGmresMatchesDirectSolution() throws Exception {
        String geometry = "linear";
        TestSimulation.writeSquareMesh(geometry, 4, 1, -2, false);
        TestSimulation.writeParameters(geometry, "sim", TestSimulation.scalarParameters("DGpure", 2, 0.1));
        for (String ordering : new String[]{"rcm", "none"}) {
            MasterSolver solver = TestSimulation.solver(geometry, "sim", TestSimulation.overrides("elementOrdering", ordering));
            Element[] elems = solver.getMesh().getElems();
            Parameters par = solver.getMesh().getPar();
            Random random = new Random(2);
            for (Element elem : elems) {
                for (int i = 0; i < elem.W.length; i++) {
                    elem.W[i] = random.nextDouble();
                }
                elem.limiter(false);
            }
            new JacobiAssembler(elems, par).assemble(0.05, -1);

            for (String preconditioner : new String[]{"ilu0", "blockilu0", "blockjacobi", "none"}) {
                par.preconditioner = preconditioner;
                par.iterativeSolverTol = 1e-12;
                LinearSolver linSolver = LinearSolver.factory(elems, par);
                double[] x = new double[solver.getMesh().dofs];
                boolean converged = linSolver.solve(x);
                double[] xRef = directSolution();
                double diff = 0;
                double norm = 0;
                for (int i = 0; i < x.length; i++) {
                    diff = Math.max(diff, Math.abs(x[i] - xRef[i]));
                    norm = Math.max(norm, Math.abs(xRef[i]));
                }
                System.out.printf("ordering %s, %s: converged %b, max |x - x_direct| / max |x_direct| = %.3e%n",
                        ordering, preconditioner, converged, diff / norm);
                assertTrue(ordering + ", " + preconditioner, converged && diff / norm < 1e-8);
            }
        }
    }

    // Gaussian elimination with partial pivoting of the matrix and the right hand
    // side of the last solved system (LinearSolver.A, LinearSolver.b)
    private static double[] directSolution() {
        int n = LinearSolver.b.length;
        double[][] a = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            for (int idx = LinearSolver.A.Icrs[i]; idx < LinearSolver.A.Icrs[i + 1]; idx++) {
                a[i][LinearSolver.A.Jcrs[idx]] += LinearSolver.A.H[idx];
            }
            a[i][n] = LinearSolver.b[i];
        }
        for (int c = 0; c < n; c++) {
            int pivot = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[pivot][c])) {
                    pivot = r;
                }
            }
            double[] tmp = a[c];
            a[c] = a[pivot];
            a[pivot] = tmp;
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / a[c][c];
                for (int j = c; j <= n; j++) {
                    a[r][j] -= f * a[c][j];
                }
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double sum = a[r][n];
            for (int j = r + 1; j < n; j++) {
                sum -= a[r][j] * x[j];
            }
            x[r] = sum / a[r][r];
        }
        return x;
    }
}
