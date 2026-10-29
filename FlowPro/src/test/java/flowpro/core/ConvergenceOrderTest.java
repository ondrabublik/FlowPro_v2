package flowpro.core;

import flowpro.core.solver.MasterSolver;
import static org.junit.Assert.assertTrue;
import org.junit.AfterClass;
import org.junit.Test;

/**
 * Order of convergence of the DG method for the manufactured solution of the
 * linear advection(-diffusion) equation TestScalarEquation: the L2 error must
 * decrease approximately as h^(p+1) on a sequence of refined meshes.
 */
public class ConvergenceOrderTest {

    private static final int[] N = {4, 8, 16};

    @AfterClass
    public static void cleanUp() {
        TestSimulation.deleteRecursively(new java.io.File(TestSimulation.ROOT + "order"));
    }

    // L2 errors on the meshes N, the rates printed and the last one returned
    private static double rate(int p, double k) throws Exception {
        double[] errors = new double[N.length];
        for (int m = 0; m < N.length; m++) {
            String geometry = "order/n" + N[m];
            TestSimulation.writeSquareMesh(geometry, N[m], 1, -2, false);
            TestSimulation.writeParameters(geometry, "sim", TestSimulation.scalarParameters("DGpure", p + 1, k));
            MasterSolver solver = TestSimulation.solver(geometry, "sim", null);
            solver.solve();
            errors[m] = TestSimulation.l2Error(solver.getMesh().getElems());
        }
        StringBuilder sb = new StringBuilder("p = " + p + ", k = " + k + ": errors");
        double rate = 0;
        for (int m = 0; m < N.length; m++) {
            sb.append(String.format(" %.3e", errors[m]));
            if (m > 0) {
                rate = Math.log(errors[m - 1] / errors[m]) / Math.log(2);
                sb.append(String.format(" (rate %.2f)", rate));
            }
        }
        System.out.println(sb);
        return rate;
    }

    @Test
    public void advectionOrder() throws Exception {
        for (int p = 0; p <= 3; p++) {
            double rate = rate(p, 0);
            assertTrue("order p = " + p + ", rate " + rate, rate > p + 0.7);
        }
    }

    @Test
    public void advectionDiffusionOrder() throws Exception {
        for (int p = 1; p <= 3; p++) {
            double rate = rate(p, 0.1);
            // DDG without the correction of the second derivatives is optimal for odd p,
            // one order lower (p) for even p
            assertTrue("order p = " + p + ", rate " + rate, rate > p + (p % 2 == 1 ? 0.7 : -0.3));
        }
    }
}
