package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.FlowProProperties;
import java.io.IOException;
import java.util.Arrays;

public class AdiabaticEulers extends Aerodynamics {

    protected static final double P_TOL = 1e-1;

    @Override
    public void init(FlowProProperties props) throws IOException {
        int dimension = props.getInt("dimension");
        super.init(props, dimension, dimension + 1, false);
    }
    
    @Override
    public double pressure(double[] W) {
        W[0] = limiteRho(W[0]);

        double p = Math.pow(W[0], kapa);
        if (p < P_TOL) {
            p = P_TOL * Math.exp((p - P_TOL) / P_TOL);
        }
        return p;
    }

    @Override
    public double[] boundaryValue(double[] WL, double[] n, int TT, ElementData elem) {
        WL[0] = limiteRho(WL[0]);
        double[] WR = new double[nEqs];

        switch (TT) {
            case (BoundaryType.WALL):
            case (BoundaryType.INVISCID_WALL):
                WR = Arrays.copyOf(WL, nEqs);
                double nu = 0;
                for (int d = 0; d < dim; ++d) {
                    nu += WL[d + 1] * n[d];
                }
                for (int d = 0; d < dim; ++d) { //tangent to wall
                    WR[d + 1] = WL[d + 1] + n[d] * nu;
                }
                break;

            case (BoundaryType.INLET):
                if (!isInletSupersonic) { // subsonic inlet
                    double p = pressure(WL);
                    double mach;
                    if (p > pIn0) {
                        mach = 0;
                    } else {
                        mach = Math.sqrt((2 / (kapa - 1)) * (-1 + Math.pow(pIn0 / p, (kapa - 1) / kapa)));
                    }
                    double rhoIn = rhoIn0 * Math.pow((1 + ((kapa - 1) / 2) * mach * mach), 1 / (1 - kapa));
                    double normalVelocity = mach * Math.sqrt((kapa * p) / rhoIn);

                    WR[0] = rhoIn;
                    for (int d = 0; d < dim; ++d) {
                        WR[d + 1] = -rhoIn * normalVelocity * n[d];
                    }
                } else { // supersonic inlet
                    WR = Arrays.copyOf(WIn, nEqs);
                }
                break;

            case (BoundaryType.OUTLET):
                double absVelocity = .0;
                for (int d = 0; d < dim; ++d) {
                    absVelocity += WL[d + 1] * WL[d + 1];
                }
                absVelocity = Math.sqrt(absVelocity) / WL[0];

                double a = Math.sqrt(kapa * pressure(WL) / WL[0]);
                double mach = absVelocity / a;

                if (mach < 1) {  // subsonic outlet
                    double rhoOut = Math.pow(pOut, 1. / kapa);
                    WR[0] = rhoOut;
                    for (int d = 0; d < dim; ++d) {
                        WR[d + 1] = rhoOut * WL[d + 1] / WL[0];
                    }
                } else {  // supersonic outlet
                    WR = Arrays.copyOf(WL, nEqs);
                }
                break;
        }
        return WR;
    }

    @Override
    public double[] constInitCondition() {
        if (isInletSupersonic) {
            return WIn;
        } else {
            double machIn = Math.sqrt(2 / (kapa - 1) * (Math.pow((1 / pOut), (kapa - 1) / kapa) - 1));
            double rhoIn = Math.pow(1 + ((kapa - 1) / 2) * machIn * machIn, 1 / (1 - kapa));
            double VIn = machIn * Math.sqrt((kapa * pOut) / rhoIn);

            double[] vIn = new double[dim];
            switch (dim) {
                case 1:
                    vIn[0] = VIn;
                    break;
                case 2:
                    vIn[0] = VIn * Math.cos(attackAngle[0]);
                    vIn[1] = VIn * Math.sin(attackAngle[0]);
                    break;
                case 3:
                    vIn[0] = VIn * Math.cos(attackAngle[0]) * Math.cos(attackAngle[1]);
                    vIn[1] = VIn * Math.sin(attackAngle[0]) * Math.cos(attackAngle[1]);
                    vIn[2] = VIn * Math.sin(attackAngle[1]);
            }

            double[] W = new double[nEqs];
            W[0] = rhoIn;
            for (int d = 0; d < dim; ++d) {
                W[d + 1] = rhoIn * vIn[d];
            }

            return W;
        }
    }

    //  nevazky tok stenou _____________________________________________________
    @Override
    public double[] numericalConvectiveFlux(double WL[], double WR[], double[] n, int TT, ElementData elem) {
        WL[0] = limiteRho(WL[0]);
        WR[0] = limiteRho(WR[0]);

        double[] f = new double[nEqs];

        switch (TT) {
            case (BoundaryType.WALL):
            case (BoundaryType.INVISCID_WALL):
                double p = pressure(WR);
                f[0] = 0;
                double V = .0;
                for (int d = 0; d < dim; ++d) {
                    f[d + 1] = p * n[d];
                    V += elem.meshVelocity[d] * n[d];
                }
                
                for (int j = 0; j < nEqs; j++) {
                    f[j] += V*WR[j];
                }
                break;

            case (BoundaryType.INLET):
            case (BoundaryType.OUTLET):
                f = convectiveFlux(WR, n, elem);
                break;

            default: // interior edge
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
        W[0] = limiteRho(W[0]);

        double V = 0.;
        for (int d = 0; d < dim; ++d) {
            V += W[d + 1] * n[d];
        }
        V /= W[0];

        double p = pressure(W);
        double[] f = new double[nEqs];
        f[0] = W[0] * V;
        for (int d = 0; d < dim; ++d) {
            f[d + 1] = W[d + 1] * V + p * n[d];
        }

        return f;
    }

    @Override
    public double[] numericalDiffusiveFlux(double Wc[], double dWc[], double[] n, int TT, ElementData elem) {
        throw new UnsupportedOperationException("operation not supported");
    }

    @Override
    public double[] diffusiveFlux(double[] W, double[] dW, double[] n, ElementData elem) {
        throw new UnsupportedOperationException("operation not supported");
    }

    @Override
    public double[] sourceTerm(double[] W, double[] dW, ElementData elem) {
        throw new UnsupportedOperationException("operation not supported");
    }

    @Override
    public boolean isSourcePresent() {
        return false;
    }
}
