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
public class SpalartAllmarasTrans extends SpalartAllmaras {

    double tuInf;
    double xi1;
    double xi2;

    @Override
    public void init(FlowProProperties props) throws IOException {
        super.init(props);

        tuInf = props.getDouble("tuInf");
        xi1 = props.getDouble("xi1");
        xi2 = props.getDouble("xi2");
    }

    @Override
    public double[] sourceTerm(double[] W, double[] dW, ElementData elem) { // zdrojovy clen
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

        // strain rate tensor calculation
        double[] strain = new double[dim * dim];
        double trace = .0;
        for (int d = 0; d < dim; ++d) {
            trace += velocityJac[dim * d + d];
            for (int f = 0; f < dim; ++f) {
                strain[dim * d + f] = 0.5*(velocityJac[dim * d + f] + velocityJac[dim * f + d]);
            }
        }
        for (int d = 0; d < dim; ++d) {
            strain[dim * d + d] -= trace/3;
        }

        // rotation calculation
        double[] Wrot = new double[dim * dim];
        for (int d = 0; d < dim; ++d) {
            for (int f = 0; f < dim; ++f) {
                Wrot[dim * d + f] = 0.5*(velocityJac[dim * d + f] - velocityJac[dim * f + d]);
            }
        }

        double vt = Math.max(W[dim + 2] / rho, 0);
        double vtDerSqr = 0;
        double rhoDerVtDer = 0;
        for (int d = 0; d < dim; ++d) {
            double rhoDer = dW[d * nEqs];
            double vtDer = 1 / rho * (dW[d * nEqs + dim + 2] - dW[d * nEqs] * vt);
            vtDerSqr += vtDer * vtDer;
            rhoDerVtDer += rhoDer*vtDer;
        }

        // turbulence limit
        if (vt < 0) {
            vt = 0;
        }

        double xi = rho * vt; // vt/v 
        double fv1 = xi * xi * xi / (xi * xi * xi + cv1 * cv1 * cv1);
        double fv2 = 1 - xi / (1 + xi * fv1);

        double D = elem.currentWallDistance;

        double Om = matrixMagnitude(Wrot);
        double Smag = matrixMagnitude(strain);
        double S = Om + C_prod * Math.min(0, Smag - Om) + 1 / Re * vt / (ka * ka * D * D) * fv2;
        double rt = vt / (Re * S * ka * ka * D * D);
        if (rt > 10) {
            rt = 10;
        }
        double g = rt + cw2 * (Math.pow(rt, 6.0) - rt);
        double fw = g * Math.pow((1 + Math.pow(cw3, 6.0)) / (Math.pow(g, 6.0) + Math.pow(cw3, 6.0)), 1.0 / 6);

        double Rev = rho * D * D * Om * Re;
        double ReTheta = Rev / 2.193;
        double ReThetaC = 803.73 * Math.pow(tuInf + 0.6067, -1.027);
        double T1 = Math.max(ReThetaC - ReTheta, 0) / (xi1 * ReThetaC);
        double T2 = Math.max(rho* vt / xi2, 0);
        double gammaBC = 1 - Math.exp(-(Math.sqrt(T1) + Math.sqrt(T2)));

        double[] source = new double[nEqs];
        source[dim + 2] = gammaBC * cb1 * rho * S * vt;
        source[dim + 2] -= 1 / Re * rho * cw1 * fw * (vt / D) * (vt / D);
        source[dim + 2] += 1 / Re * rho * cb2 * vtDerSqr;
        source[dim + 2] -= 1 / (Re * sigma) * (1 / rho + vt) * rhoDerVtDer;
        return source;
    }
    
    public double matrixMagnitude(double[] A) {
        double mag = 0;
        for (int i = 0; i < A.length; i++) {
            mag += 2 * A[i] * A[i];
        }
        return Math.sqrt(mag);
    }

}
