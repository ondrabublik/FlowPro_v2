/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.FlowProProperties;
import flowpro.api.Mat;
import java.io.IOException;
import java.util.Arrays;

/**
 *
 * @author obublik
 */
public class SpalartAllmarasRotordynamics extends SpalartAllmaras {

    protected class BoundaryType {

        static final int WALL = -1;
        static final int INLET = -2;
        static final int OUTLET = -3;
        static final int INVISCID_WALL = -4;

        static final int STATOR = -5;
        static final int ROTOR = -6;
    }

    double[] omegaRotor;

    @Override
    public void init(FlowProProperties props) throws IOException {
        super.init(props);

        // rotor omega
        if (props.containsKey("omegaRotor")) {
            omegaRotor = Mat.times(props.getDoubleArray("omegaRotor"), tRef);
        } else {
            System.out.println("omegaRotor = [0,0,0]");
        }
    }

    @Override
    public double[] boundaryValue(double[] WL, double[] n, int TT, ElementData elem) {
        double[] WR = new double[nEqs];
        switch (TT) {
            case (BoundaryType.ROTOR):
                if (isDiffusive) {
                    WL[0] = limiteRho(WL[0]);
                    double p = pressure(WL);
                    double[] uROTOR = Mat.cross(omegaRotor, elem.currentX);
                    double[] u = elem.meshVelocity;
                    double absVelocity2 = .0;
                    for (int d = 0; d < dim; d++) {
                        absVelocity2 += (u[d] + uROTOR[d]) * (u[d] + uROTOR[d]);
                    }

                    WR[0] = WL[0];
                    for (int d = 0; d < dim; d++) {
                        WR[d + 1] = WR[0] * (u[d] + uROTOR[d]);
                    }
                    WR[dim + 1] = p / (kapa - 1) + WR[0] * absVelocity2 / 2;
                    WR[dim + 2] = 0;
                    break;
                }
                break;
            case (BoundaryType.STATOR):
                if (isDiffusive) {
                    WL[0] = limiteRho(WL[0]);
                    double p = pressure(WL);
                    double[] u = elem.meshVelocity;
                    double absVelocity2 = .0;
                    for (int d = 0; d < dim; d++) {
                        absVelocity2 += u[d] * u[d];
                    }

                    WR[0] = WL[0];
                    for (int d = 0; d < dim; d++) {
                        WR[d + 1] = WR[0] * u[d];
                    }
                    WR[dim + 1] = p / (kapa - 1) + WR[0] * absVelocity2 / 2;
                    WR[dim + 2] = 0;
                    break;
                }
                break;
            default:
                WR = super.boundaryValue(WL, n, TT, elem);
                break;
        }

        return WR;
    }
    
    @Override
    public double[] numericalConvectiveFlux(double WL[], double WR[], double[] n, int TT, ElementData elem) {
        WL[0] = limiteRho(WL[0]);
        WR[0] = limiteRho(WR[0]);

        double[] f = new double[nEqs];

        switch (TT) {
            case (BoundaryType.ROTOR):
            case (BoundaryType.STATOR):
                double p = pressure(WR);
                f[0] = 0;
                double V = .0;
                for (int d = 0; d < dim; ++d) {
                    f[d + 1] = p * n[d];
                    V += elem.meshVelocity[d] * n[d];
                }
                f[dim + 1] = p * V;
                
                // for ALE
                for (int j = 0; j < nEqs; j++) {
                    f[j] += V*WR[j];
                }
                break;
            default:
                f = super.numericalConvectiveFlux(WL, WR, n, TT, elem);
                break;
        }
        
        return f;
    }
    
    @Override
    public double[] numericalDiffusiveFlux(double Wc[], double dWc[], double[] n, int TT, ElementData elem) {
        Wc[0] = limiteRho(Wc[0]);

        double[] flux = diffusiveFlux(Wc, dWc, n, elem);
        if (TT < 0) {
            if (TT == BoundaryType.WALL || TT == BoundaryType.ROTOR || TT == BoundaryType.STATOR) {
                flux[dim + 1] = .0;
            } else {
                Arrays.fill(flux, .0);
            }
        }
        return flux;
    }
    
    @Override
    public boolean isIPFace(int TT) {
        return (TT == BoundaryType.WALL || TT == BoundaryType.ROTOR || TT == BoundaryType.STATOR);
    }
}
