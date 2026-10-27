/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.FlowProProperties;
import java.io.IOException;
import java.util.Arrays;
/**
 *
 * @author obublik
 */
public class SpalartAllmarasVelocityInlet extends SpalartAllmaras{
    
    double vInlet;
    
    @Override
    public void init(FlowProProperties props) throws IOException {
        super.init(props);
        
        // acceleration
        if(props.containsKey("vIn")){
            vInlet = props.getDouble("vIn");
        } else {
            throw new IOException("Velocity in inlet must be defined!");
        }
    }
    
    @Override
    public double[] boundaryValue(double[] WL, double[] n, int TT, ElementData elem) {
        WL[0] = limiteRho(WL[0]);
        double[] WR = new double[nEqs];
        double p = pressure(WL);

        switch (TT) {
            case (BoundaryType.WALL):
                if (isDiffusive) {
                    double[] u = elem.meshVelocity;
                    double absVelocity2 = .0;
                    for (int d = 0; d < dim; ++d) {
                        absVelocity2 += u[d] * u[d];
                    }

                    WR[0] = WL[0];
                    for (int d = 0; d < dim; ++d) {
                        WR[d + 1] = WR[0] * u[d];
                    }
                    WR[dim + 1] = p / (kapa - 1) + WR[0] * absVelocity2 / 2;
                    WR[dim + 2] = 0;
                    break;
                }
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
                    double mach;
                    if (p > pIn0) {
                        mach = 0;
                    } else {
                        mach = Math.sqrt((2 / (kapa - 1)) * (-1 + Math.pow(pIn0 / p, (kapa - 1) / kapa)));
                    }
                    double rhoIn = rhoIn0 * Math.pow((1 + ((kapa - 1) / 2) * mach * mach), 1 / (1 - kapa));
                    double normalVelocity = vInlet/velocityRef; //mach * Math.sqrt((kapa * p) / rhoIn);
                    double E = p / (kapa - 1) + 0.5 * rhoIn * normalVelocity * normalVelocity;

                    WR[0] = rhoIn;
                    for (int d = 0; d < dim; ++d) {
                        WR[d + 1] = -rhoIn * normalVelocity * n[d];
                    }
                    WR[dim + 1] = E;
                    WR[dim + 2] = rhoIn * vtIn;
                } else { // supersonic inlet
                    WR = Arrays.copyOf(WIn, nEqs);
                }
                break;

            case (BoundaryType.OUTLET):
                double rhoOut = WL[0];
                double absVelocity2 = .0;
                for (int d = 0; d < dim; ++d) {
                    absVelocity2 += WL[d + 1] * WL[d + 1];
                }
                absVelocity2 /= rhoOut * rhoOut;

                double a = Math.sqrt(kapa * pressure(WL) / WL[0]);
                double mach = Math.sqrt(absVelocity2) / a;

                if (mach < 1) {  // subsonic outlet
                    WR[0] = rhoOut;
                    for (int d = 0; d < dim; ++d) {
                        WR[d + 1] = rhoOut * WL[d + 1] / WL[0];
                    }
                    WR[dim + 1] = pOut / (kapa - 1) + rhoOut * absVelocity2 / 2;
                    WR[dim + 2] = WL[dim + 2];
                } else {  // supersonic outlet
                    WR = Arrays.copyOf(WL, nEqs);
                }
        }
        return WR;
    }
}
