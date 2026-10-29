package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.Equation;
import flowpro.api.FlowProProperties;
import java.io.IOException;

/**
 * Linear scalar advection-diffusion equation for the verification tests,
 * u_t + a . grad u - k laplace u = s, with the manufactured steady solution
 * u = sin(2x + y): the source s is chosen so that u is the exact solution and
 * the boundary values (all boundaries) are given by u. The numerical flux is
 * the Lax-Friedrichs flux with the constant |a|, as assumed by the analytic
 * Jacobi matrix of DGjacobi. The equation is linear, i.e. the finite difference
 * Jacobi matrix is exact up to rounding.
 */
public class TestScalarEquation implements Equation {

    int dim;
    double[] a;
    double k;
    double lambda;

    public static double exact(double[] x) {
        return Math.sin(2 * x[0] + x[1]);
    }

    @Override
    public void init(FlowProProperties props) throws IOException {
        dim = props.getInt("dimension");
        a = props.getDoubleArray("a");
        k = props.containsKey("k") ? props.getDouble("k") : 0;
        double norm = 0;
        for (double ad : a) {
            norm += ad * ad;
        }
        lambda = Math.sqrt(norm);
    }

    @Override
    public int nEqs() {
        return 1;
    }

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public double[] boundaryValue(double[] WL, double[] n, int TT, ElementData elem) {
        return new double[]{exact(elem.currentX)};
    }

    @Override
    public double[] constInitCondition() {
        return new double[]{0};
    }

    @Override
    public void setState(double t, double dt) {
    }

    @Override
    public boolean isEquationsJacobian() {
        return true;
    }

    @Override
    public boolean isConvective() {
        return true;
    }

    @Override
    public double[] convectiveFlux(double[] W, double[] n, ElementData elemData) {
        double an = 0;
        for (int d = 0; d < dim; d++) {
            an += a[d] * n[d];
        }
        return new double[]{an * W[0]};
    }

    @Override
    public double[] numericalConvectiveFlux(double[] WL, double[] WR, double[] n, int TT, ElementData elemData) {
        double fL = convectiveFlux(WL, n, elemData)[0];
        double fR = convectiveFlux(WR, n, elemData)[0];
        return new double[]{(fL + fR) / 2 - lambda * (WR[0] - WL[0]) / 2};
    }

    @Override
    public double[] convectiveFluxJacobian(double[] W, double[] n, ElementData elemData) {
        return convectiveFlux(new double[]{1}, n, elemData);
    }

    @Override
    public boolean isDiffusive() {
        return k > 0;
    }

    @Override
    public double[] diffusiveFlux(double[] W, double[] dW, double[] n, ElementData elemData) {
        double flux = 0;
        for (int d = 0; d < dim; d++) {
            flux += k * dW[d] * n[d];
        }
        return new double[]{flux};
    }

    @Override
    public double[] numericalDiffusiveFlux(double[] W, double[] dW, double[] n, int TT, ElementData elemData) {
        return diffusiveFlux(W, dW, n, elemData);
    }

    // derivatives with respect to W and to the components of the gradient
    @Override
    public double[] diffusiveFluxJacobian(double[] W, double[] dW, double[] n, ElementData elemData) {
        double[] jac = new double[dim + 1];
        for (int d = 0; d < dim; d++) {
            jac[d + 1] = k * n[d];
        }
        return jac;
    }

    @Override
    public boolean isSourcePresent() {
        return true;
    }

    // s = a . grad u - k laplace u for u = sin(2x + y)
    @Override
    public double[] sourceTerm(double[] W, double[] dW, ElementData elemData) {
        double[] x = elemData.currentX;
        double phase = 2 * x[0] + x[1];
        return new double[]{(2 * a[0] + a[1]) * Math.cos(phase) + 5 * k * Math.sin(phase)};
    }

    @Override
    public double[] sourceTermJacobian(double[] W, double[] dW, ElementData elemData) {
        return new double[dim + 1];
    }

    @Override
    public double maxEigenvalue(double[] W, ElementData elemData) {
        return lambda;
    }

    @Override
    public boolean isIPFace(int TT) {
        return true;
    }

    @Override
    public double pressure(double[] W) {
        return 0;
    }

    @Override
    public double[] stressVector(double[] W, double[] dW, double[] normal) {
        return new double[dim];
    }

    @Override
    public void saveReferenceValues(String filePath) throws IOException {
    }

    @Override
    public double[] getReferenceValues() {
        return new double[]{1, 1, 1, 1};
    }

    @Override
    public double[] getResults(double[] W, double[] dW, double[] X, String name) {
        return new double[]{W[0]};
    }

    @Override
    public double[] combineShockSensors(double[] shock) {
        return shock;
    }
}
