package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.FlowProProperties;
import java.io.IOException;
import java.util.Arrays;

/**
 *
 * @author ales
 */
public class VelocityBreak extends NavierStokes {

    double xBreak;
    double uStator;
    
    @Override
    public void init(FlowProProperties props) throws IOException {
        if (props.containsKey("xBreak")){
            xBreak = props.getDouble("xBreak");
        } else {
            xBreak = -1e10;
        }
        
        if (props.containsKey("uStator")){
            uStator = props.getDouble("uStator");
            System.out.println(uStator);
        } else{
            uStator = 0;
        }
        super.init(props);
    }
    
    @Override
    public double[] numericalConvectiveFlux(double WL[], double WR[], double[] n, int TT, ElementData elem) {
        if(Math.abs(elem.currentX[0] - xBreak) < 1e-6){
            if(n[0] > 0){            
                WR[3] += WR[0]*uStator*uStator/2 + WR[2]*uStator;
                WR[2] += WR[0]*uStator;
            } else {
                WR[3] += WR[0]*uStator*uStator/2 - WR[2]*uStator;
                WR[2] -= WR[0]*uStator;
            }
            double p1 = pressure(WR);
        }
        
        WL[0] = limiteRho(WL[0]);
        WR[0] = limiteRho(WR[0]);

        double[] f = new double[nEqs];

        switch (TT) {
            case (BoundaryType.WALL):
            case (BoundaryType.INVISCID_WALL):
                //double p = pressure(WR);
                double p = pressure(WL);
                f[0] = 0;
                double V = .0;
                for (int d = 0; d < dim; ++d) {
                    f[d + 1] = p * n[d];
                    V += elem.meshVelocity[d] * n[d];
                }
                f[dim + 1] = p * V;

                // for ALE
                for (int j = 0; j < nEqs; j++) {
                    f[j] += V * WR[j];
                }
                break;

            case (BoundaryType.INLET):
            case (BoundaryType.OUTLET):
                f = convectiveFlux(WR, n, elem);
                break;

            default: // interior edge
                switch (numericalFluxType) {
                    case "vanLeer":
                        double[] fp = fplus(WL, pressure(WL), n);
                        double[] fm = fminus(WR, pressure(WR), n);
                        for (int j = 0; j < nEqs; j++) {
                            f[j] = fp[j] + fm[j];
                        }
                        break;
                    default:
                        double[] fL = convectiveFlux(WL, n, elem);
                        double[] fR = convectiveFlux(WR, n, elem);
                        double maxEigenValue = Math.max(maxEigenvalue(WL, elem), maxEigenvalue(WR, elem));
                        for (int j = 0; j < nEqs; j++) {
                            f[j] = (fL[j] + fR[j] - maxEigenValue * (WR[j] - WL[j])) / 2;
                        }
                        break;
                }
        }
        return f;
    }

    @Override
    public double[] numericalDiffusiveFlux(double Wc[], double dWc[], double[] n, int TT, ElementData elem) {
        Wc[0] = limiteRho(Wc[0]);

        double[] flux = diffusiveFlux(Wc, dWc, n, elem);
        if (TT < 0) {
            if (TT == BoundaryType.WALL) {
                flux[dim + 1] = .0;
            } else {
                Arrays.fill(flux, .0);
            }
        }
        return flux;
    }
}
