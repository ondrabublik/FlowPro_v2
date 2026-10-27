package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.Equation;
import flowpro.api.FlowProProperties;
import java.io.IOException;

public class Schrodinger implements Equation {

    protected class BoundaryType {

        static final int WALL = -1;
    }

    int dim;
    int nEqs;

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public int nEqs() {
        return nEqs;
    }

    @Override
    public boolean isConvective() {
        return false;
    }

    @Override
    public boolean isDiffusive() {
        return true;
    }

    @Override
    public boolean isSourcePresent() {
        return true;
    }

    @Override
    public void init(FlowProProperties props) throws IOException {

        dim = props.getInt("dimension");
        nEqs = 2;
    }

    @Override
    public void setState(double dt, double t) {
    }

    @Override
    public double[] constInitCondition() {
        return new double[]{0, 0};
    }

    //  nevazky tok stenou _____________________________________________________
    @Override
    public double[] numericalConvectiveFlux(double WL[], double WR[], double[] n, int TT, ElementData elem) {
        throw new UnsupportedOperationException("convection is not present");
    }

    @Override
    public double[] convectiveFlux(double[] W, double[] n, ElementData elem) {
        throw new UnsupportedOperationException("convection is not present");
    }

    @Override
    public double[] diffusiveFlux(double[] W, double[] dW, double[] n, ElementData elem) {
        return new double[]{-dW[1] / 2 * n[0], dW[0] / 2 * n[0]};
    }

    @Override
    public double[] numericalDiffusiveFlux(double Wc[], double dWc[], double[] n, int TT, ElementData elem) {
        return diffusiveFlux(Wc, dWc, n, elem);
    }

    @Override
    public double[] sourceTerm(double[] W, double[] dW, ElementData elem) { // zdrojovy clen
        return new double[]{0, 0};
    }

    @Override
    public double[] boundaryValue(double[] WL, double[] n, int TT, ElementData elem) {
        double[] WR = new double[nEqs];
        switch (TT) {
            case (BoundaryType.WALL):
                WR[0] = 0;
                WR[1] = 0;
                break;
        }
        return WR;
    }

    @Override
    public double pressure(double[] W) {
        return 0;
    }

    @Override
	public double[] stressVector(double[] W, double[] dW, double[] normal) {	
		throw new UnsupportedOperationException("normal stress is not present");
	}
    
    @Override
    public double maxEigenvalue(double[] W, ElementData elem) {
        return 1;
    }

    @Override
    public boolean isIPFace(int TT) {
        return false;
    }

    @Override
    public double[] combineShockSensors(double[] shock){
        for(int m = 1; m < nEqs; m++){
            shock[m] = shock[0];
        }
        return shock;
    }
    
    @Override
    public void saveReferenceValues(String filePath) throws IOException {
    }

    @Override
    public double[] getReferenceValues() {
        return new double[]{1};
    }

    @Override
    public double[] getResults(double[] W, double[] dW, double[] X, String name) {
        switch (name) {
            case "psi":
                return new double[]{W[0] * W[0] + W[1] * W[1]};

            default:
                throw new UnsupportedOperationException("undefined value " + name);
        }
    }

    @Override
    public boolean isEquationsJacobian() {
        return false;
    }

    @Override
    public double[] convectiveFluxJacobian(double[] W, double[] n, ElementData elemData) {
        throw new UnsupportedOperationException("operation not supported");
    }

    @Override
    public double[] diffusiveFluxJacobian(double[] W, double[] dW, double n[], ElementData elemData) {
        throw new UnsupportedOperationException("operation not supported");
    }

    @Override
    public double[] sourceTermJacobian(double[] W, double[] dW, ElementData elemData) {
        throw new UnsupportedOperationException("operation not supported");
    }
}
