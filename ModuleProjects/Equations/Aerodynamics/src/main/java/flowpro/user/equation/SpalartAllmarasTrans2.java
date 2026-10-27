/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.FlowProProperties;
import java.io.IOException;

/**
 *
 * @author obublik
 */
public class SpalartAllmarasTrans2 extends SpalartAllmaras {

    double xt;
    double lt;

    @Override
    public void init(FlowProProperties props) throws IOException {
        super.init(props);

        xt = props.getDouble("xt");
        lt = props.getDouble("lt");
    }

    @Override
    public double[] sourceTerm(double[] W, double[] dW, ElementData elem) { // zdrojovy clen
        
        double x = elem.currentX[0];
        double PTM = 1/(1 + Math.exp(-5*(x-xt)/lt));
        
        W[0] = limiteRho(W[0]);
        double rho = W[0];

        double lam = -2. / 3; // Stokesuv vztah

        double[] velocity = new double[dim];
        for (int d = 0; d < dim; ++d) {
            velocity[d] = W[d + 1] / rho;
        }

        double[] velocityJac = new double[dim * dim];
        for (int d = 0; d < dim; ++d) {
            for (int f = 0; f < dim; ++f) {
                velocityJac[dim * d + f] = (dW[f * nEqs + d + 1] - dW[f * nEqs] * velocity[d]) / rho;
            }
        }

        // stress tensor calculation
        double[] stress = new double[dim * dim];
        double trace = .0;
        for (int d = 0; d < dim; ++d) {
            trace += velocityJac[dim * d + d];
            for (int f = 0; f < dim; ++f) {
                stress[dim * d + f] = velocityJac[dim * d + f] + velocityJac[dim * f + d];
            }
        }
        for (int d = 0; d < dim; ++d) {
            stress[dim * d + d] += lam * trace;
        }
        double Smag = matrixMagnitude(stress) / 2;

        double vt = max(0, W[dim + 2] / rho);
        double vtDerMag = 0;
        for (int d = 0; d < dim; ++d) {
            double vtDer = 1 / rho * (dW[d * nEqs + dim + 2] - dW[d * nEqs] * vt);
            vtDerMag += vtDer * vtDer;
        }

        // turbulence limit
        if (vt < 0) {
            vt = 0;
        }

        double D = elem.currentWallDistance;
        double xi = rho*vt; // vt/v 
        double ft2 = ct3*Math.exp(-ct4*xi*xi);
        double fv1 = xi * xi * xi / (xi * xi * xi + cv1 * cv1 * cv1);
        double fv2 = 1 - xi / (1 + xi * fv1);
        double Om = rotationMagnitude(velocityJac);
        //double S = Om + C_prod * min(0, Smag - Om) + 1 / Re * vt / (ka * ka * D * D) * fv2;
        double S = Om + 1 / Re * vt / (ka * ka * D * D) * fv2;
        double rt = Math.min(vt / (Re * S * ka * ka * D * D), 10);

        double g = rt + cw2 * (Math.pow(rt, 6.0) - rt);
        double fw = g * Math.pow((1 + Math.pow(cw3, 6.0)) / (Math.pow(g, 6.0) + Math.pow(cw3, 6.0)), 1.0 / 6);

        double[] source = new double[nEqs];
        source[dim + 2] = limitDestruction(1 / Re * rho * cb2 * vtDerMag + PTM*cb1 * (1 - ft2) * rho * S * vt - 1 / Re * (cw1 * fw - cb1 / (ka * ka) * ft2) / rho * (rho * vt / D) * (rho * vt / D));
        return source;
    }
}
