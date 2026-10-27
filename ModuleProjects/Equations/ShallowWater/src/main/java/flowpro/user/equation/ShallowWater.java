package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.Equation;
import flowpro.api.FlowProProperties;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class ShallowWater implements Equation {

    protected class BoundaryType {

        static final int WALL = -1;
        static final int INLET = -2;
        static final int OUTLET = -3;
        static final int INVISCID_WALL = -4;
    }

    int dim;
    int nEqs;

    double gravityAcceleration;

    // reference values
    protected double lRef;
    protected double velocityRef;
    protected double tRef;  // nepouziva se !!!???

    // inlet boundary condition
    protected boolean isInletSupersonic;
    // subsonic inlet boundary condition 
    protected double VIn; // velocity
    protected double attackAngle; // angle of attack       
    // supersonic inlet boundary condition
    protected double hIn; // velocity
    protected double[] WIn;

    // outlet boundary condition
    protected double hOut; // pressure

    // tolerance pro hodnotu hustoty a tlaku
    protected static final double hTol = 1e-1;
    protected static final double pTol = 1e-1;

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
        return true;
    }

    @Override
    public boolean isDiffusive() {
        return false;
    }

    @Override
    public void init(FlowProProperties props) throws IOException {

        dim = props.getInt("dimension");
        nEqs = dim + 1;

        gravityAcceleration = 9.81;

        // inlet type
        isInletSupersonic = props.getBoolean("isInletSupersonic");

        // reference values from inlet
        VIn = props.getDouble("VIn");
        if (isInletSupersonic) {
            hIn = props.getDouble("hIn");
        } else {
            hIn = 1;
        }

        // other reference values
        if (props.containsKey("lRef")) {
            lRef = props.getDouble("lRef");
        } else {
            lRef = 1;
        }
        velocityRef = VIn;
        tRef = lRef / velocityRef;

        // inlet
        attackAngle = props.getDouble("attackAngle");
        WIn = new double[nEqs];
        if (isInletSupersonic) {
            WIn[0] = hIn;
            WIn[1] = hIn * VIn * Math.cos(attackAngle);;
            WIn[2] = hIn * VIn * Math.sin(attackAngle);;
        } else {
            WIn[0] = -1;   // temporarely                
        }

        // outlet
        if (props.containsKey("hOut")) {
            hOut = props.getDouble("hOut");
        } else {
            throw new IOException("outlet boundary condition is not specified");
        }
    }

    @Override
    public void setState(double dt, double t) {  
    }
    
    @Override
    public double[] constInitCondition() {
        if (isInletSupersonic) {
            return WIn;
        } else {
            double uIn = VIn * Math.cos(attackAngle);
            double vIn = VIn * Math.sin(attackAngle);

            return new double[]{hOut, hOut * uIn, hOut * vIn};
        }
    }

    //  nevazky tok stenou _____________________________________________________
    @Override
    public double[] numericalConvectiveFlux(double WL[], double WR[], double[] n, int TT, ElementData elem) {
        limite(WL);
        limite(WR);
        
        double[] f = new double[nEqs];

        switch (TT) {
            case (BoundaryType.WALL):
            case (BoundaryType.INVISCID_WALL):
                double p = pressure(WL);
                f[0] = 0;
                for (int d = 0; d < dim; ++d) {
                    f[d + 1] = p * n[d];
                }
                break;

            case (BoundaryType.INLET):
            case (BoundaryType.OUTLET):
                f = convectiveFlux(WR, n, elem);
                break;

            default: // vnitrni stena
                double[] fL = convectiveFlux(WL, n, elem);
                double[] fR = convectiveFlux(WR, n, elem);
                double maxEigenValue = Math.max(maxEigenvalue(WL, elem), maxEigenvalue(WR, elem));
                for (int j = 0; j < nEqs; j++) {
                    f[j] = (fL[j] + fR[j] - maxEigenValue * (WR[j] - WL[j])) / 2;
                }
                break;
        }
        return f;
    }

    @Override
    public double[] convectiveFlux(double[] W, double[] n, ElementData elem) {
        limite(W);

        double[] f = new double[nEqs];

        double V = .0;
        for (int d = 0; d < dim; ++d) {
            V += W[d + 1] * n[d];
        }
        V /= W[0];

        double p = pressure(W);
        f[0] = W[0] * V;
        for (int d = 0; d < dim; ++d) {
            f[d + 1] = W[d + 1] * V + p * n[d];
        }

        return f;
    }

    @Override
    public boolean isEquationsJacobian(){
        return false;
    }
    
    @Override
    public double[] diffusiveFlux(double[] W, double[] dW, double[] n, ElementData elem) {
        throw new UnsupportedOperationException("diffusion is not present");
    }

    @Override
    public double[] numericalDiffusiveFlux(double Wc[], double dWc[], double[] n, int TT, ElementData elem) {
        throw new UnsupportedOperationException("diffusion is not present");
    }

    @Override
    public boolean isSourcePresent() {
        return false;
    }

    @Override
    public double[] sourceTerm(double[] W, double[] dW, ElementData elem) { // zdrojovy clen
        throw new UnsupportedOperationException("source is not present");
    }

    @Override
    public double[] boundaryValue(double[] WL, double[] n, int TT, ElementData elem) {
        limite(WL);

        double[] WR = new double[nEqs];
        switch (TT) {
            case (BoundaryType.WALL):
            case (BoundaryType.INVISCID_WALL):
                System.arraycopy(WL, 0, WR, 0, nEqs);
                break;

            case (BoundaryType.INLET):
                if (WIn[0] == -1) { // subsonic inlet
                    double hinl = WL[0];

                    WR[0] = hinl;
                    for (int i = 0; i < dim; i++) {
                        WR[i + 1] = -hinl * VIn * n[i];
                    }

                } else { // supersonic inlet
                    System.arraycopy(WIn, 0, WR, 0, nEqs);
                }
                break;

            case (BoundaryType.OUTLET):
                double ho = WL[0];
                double[] uo = new double[dim];
                double uo2 = 0;
                for(int i = 0; i < dim; i++){
                    uo[i] = WL[i+1] / ho;
                    uo2 += uo[i]*uo[i];
                }
                double ao = Math.sqrt(gravityAcceleration * ho);
                double Fro = Math.sqrt(uo2) / ao;
                if (Fro < 1) { // subsonicky vystup
                    ho = hOut;
                } 
                WR[0] = ho;
                for(int i = 0; i < dim; i++){
                    WR[i+1] = ho * uo[i];
                }
                break;
        }
        return WR;
    }

    @Override
    public double pressure(double[] W) {
        limite(W);

        double p = 0.5 * gravityAcceleration * W[0] * W[0];
        if (p < pTol) {
            p = pTol * Math.exp((p - pTol) / pTol);
        }
        return p;
    }

    @Override
	public double[] stressVector(double[] W, double[] dW, double[] normal) {	
		throw new UnsupportedOperationException("normal stress is not present");
	}
    
    @Override
    public double maxEigenvalue(double[] W, ElementData elem) {
        limite(W);
        double a = Math.sqrt(gravityAcceleration * W[0]);
        double u = Math.sqrt(W[1] / W[0] * W[1] / W[0] + W[2] / W[0] * W[2] / W[0]);
        return u + a;
    }
    
    @Override
    public double[] convectiveFluxJacobian(double[] W, double[] n, ElementData elemData){
        throw new UnsupportedOperationException("operation not supported");
    }
    
    @Override
    public double[] diffusiveFluxJacobian(double[] W, double[] dW, double n[], ElementData elemData){
        throw new UnsupportedOperationException("operation not supported");
    }
    
    @Override
    public double[] sourceTermJacobian(double[] W, double[] dW, ElementData elemData){
        throw new UnsupportedOperationException("operation not supported");
    }

    void limite(double[] W) {
        if (W[0] < hTol) {
            W[0] = hTol * Math.exp((W[0] - hTol) / hTol);
            for(int i = 0; i < dim; i++){
                W[i+1] = 0;
            }
        }
    }

    @Override
    public boolean isIPFace(int TT) {
        return TT == BoundaryType.WALL;
    }

    @Override
    public double[] combineShockSensors(double[] shock){
        for(int m = 1; m < nEqs; m++){
            shock[m] = shock[0]; // all shock sensors are acording water slope
        }
        return shock;
    }
    
    @Override
    public void saveReferenceValues(String filePath) throws IOException {
        FlowProProperties output = new FlowProProperties();

        output.setProperty("l", Double.toString(lRef));
        output.setProperty("v", Double.toString(velocityRef));
        output.setProperty("t", Double.toString(tRef));

        output.store(new FileOutputStream(filePath), null);
    }

    @Override
    public double[] getReferenceValues() {
        return new double[]{lRef, velocityRef, tRef};
    }

    @Override
    public double[] getResults(double[] W, double[] dW, double[] X, String name) {
        switch (name) {
            case "h":
                return new double[]{lRef * W[0]};

            case "xVelocity":
                return new double[]{velocityRef * W[1] / W[0]};

            case "yVelocity":
                if (dim > 1) {
                    return new double[]{velocityRef * W[2] / W[0]};
                } else {
                    throw new UnsupportedOperationException("undefined value" + name);
                }

            case "zVelocity":
                if (dim > 3) {
                    return new double[]{velocityRef * W[3] / W[0]};
                } else {
                    throw new UnsupportedOperationException("undefined value" + name);
                }

            case "velocity":
                double[] velocity = new double[dim];
                for (int i = 0; i < dim; i++) {
                    velocity[i] = velocityRef * W[i + 1] / W[0];
                }
                return velocity;

            default:
                throw new UnsupportedOperationException("undefined value " + name);
        }
    }
}
