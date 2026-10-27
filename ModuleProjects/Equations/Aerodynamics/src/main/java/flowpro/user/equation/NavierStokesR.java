package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.FlowProProperties;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 *
 * @author ales
 */
public class NavierStokesR extends Aerodynamics {

    protected static final double P_TOL = 1e-1;

    @Override
    public void init(FlowProProperties props) throws IOException {
        int dimension = props.getInt("dimension");
        super.init(props, dimension, dimension + 2, props.getBoolean("isFlowViscous"));
    }

    @Override
    public double pressure(double[] W) {
        W[0] = limiteRho(W[0]);

        double momentum2 = .0;
        for (int d = 0; d < dim; ++d) {
            momentum2 += W[d + 1] * W[d + 1];
        }

        double p = (kapa - 1) * (W[dim + 1] - momentum2 / (2 * W[0]));
        if (p < P_TOL) {
            p = P_TOL * Math.exp((p - P_TOL) / P_TOL);
        }
        return p;
    }

    @Override
    public void saveReferenceValues(String filePath) throws IOException {
        FlowProProperties output = new FlowProProperties();

        output.setProperty("l", Double.toString(lRef));
        output.setProperty("p", Double.toString(pRef));
        output.setProperty("rho", Double.toString(rhoRef));
        output.setProperty("v", Double.toString(velocityRef));
        output.setProperty("t", Double.toString(tRef));

        output.store(new FileOutputStream(filePath), null);
    }

    @Override
    public double[] getReferenceValues() {
        return new double[]{lRef, pRef, rhoRef, velocityRef, tRef};
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
            double alpha = 0;
            double beta = 0;
            if (attackAngle != null) {
                alpha = attackAngle[0];
                if (dim == 3) {
                    beta = attackAngle[1];
                }
            }
            switch (dim) {
                case 1:
                    vIn[0] = VIn;
                    break;
                case 2:
                    vIn[0] = VIn * Math.cos(alpha);
                    vIn[1] = VIn * Math.sin(alpha);
                    break;
                case 3:
                    vIn[0] = VIn * Math.cos(alpha) * Math.cos(beta);
                    vIn[1] = VIn * Math.sin(alpha) * Math.cos(beta);
                    vIn[2] = VIn * Math.sin(beta);
            }

            double[] W = new double[nEqs];
            W[0] = rhoIn;
            for (int d = 0; d < dim; ++d) {
                W[d + 1] = rhoIn * vIn[d];
            }
            W[dim + 1] = pOut / (kapa - 1) + 0.5 * rhoIn * VIn * VIn;

            return W;
        }
    }

    @Override
    public double[] numericalConvectiveFlux(double WL[], double WR[], double[] n, int TT, ElementData elem) {
        WL[0] = limiteRho(WL[0]);
        WR[0] = limiteRho(WR[0]);

        double[] f = new double[nEqs];

        switch (TT) {
            case (Aerodynamics.BoundaryType.WALL):
            case (Aerodynamics.BoundaryType.INVISCID_WALL):
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
                    f[j] += V * WR[j];
                }
                break;

            case (Aerodynamics.BoundaryType.INLET):
            case (Aerodynamics.BoundaryType.OUTLET):
            case (Aerodynamics.BoundaryType.FREE_INLET_OUTLET):
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
    public double[] convectiveFlux(double[] W, double[] n, ElementData elem) {
        W[0] = limiteRho(W[0]);

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
        f[dim + 1] = (W[dim + 1] + p) * V;

        return f;
    }

    @Override
    public double[] boundaryValue(double[] WL, double[] n, int TT, ElementData elem) {
        WL[0] = limiteRho(WL[0]);
        double[] WR = new double[nEqs];
        double p = pressure(WL);

        switch (TT) {
            case (Aerodynamics.BoundaryType.WALL):
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
                    break;
                }
            case (Aerodynamics.BoundaryType.INVISCID_WALL):
                WR = Arrays.copyOf(WL, nEqs);
                double nu = 0;
                for (int d = 0; d < dim; ++d) {
                    nu += WL[d + 1] * n[d];
                }
                for (int d = 0; d < dim; ++d) { //tangent to wall
                    WR[d + 1] = WL[d + 1] + n[d] * nu;
                }
                break;

            case (Aerodynamics.BoundaryType.INLET):
                if (!isInletSupersonic) { // subsonic inlet
                    double mach;
                    if (p > pIn0) {
                        mach = 0;
                    } else {
                        mach = Math.sqrt((2 / (kapa - 1)) * (-1 + Math.pow(pIn0 / p, (kapa - 1) / kapa)));
                    }
                    double rhoIn = rhoIn0 * Math.pow((1 + ((kapa - 1) / 2) * mach * mach), 1 / (1 - kapa));
                    double inletVelocity = mach * Math.sqrt((kapa * p) / rhoIn);
                    double E = p / (kapa - 1) + 0.5 * rhoIn * inletVelocity * inletVelocity;

                    WR[0] = rhoIn;
                    if (attackAngle == null) {
                        for (int d = 0; d < dim; ++d) {
                            WR[d + 1] = -rhoIn * inletVelocity * n[d];
                        }
                    } else {
                        double[] dir;
                        if (dim == 2) {
                            dir = new double[]{Math.cos(attackAngle[0]), Math.sin(attackAngle[0])};
                        } else {
                            dir = new double[]{Math.cos(attackAngle[0]) * Math.cos(attackAngle[1]), Math.sin(attackAngle[0]) * Math.cos(attackAngle[1]), Math.sin(attackAngle[1])};
                        }
                        for (int d = 0; d < dim; ++d) {
                            WR[d + 1] = rhoIn * inletVelocity * dir[d];
                        }
                    }
                    WR[dim + 1] = E;
                } else { // supersonic inlet
                    WR = Arrays.copyOf(WIn, nEqs);
                }
                break;

            case (Aerodynamics.BoundaryType.OUTLET):
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
                } else {  // supersonic outlet
                    WR = Arrays.copyOf(WL, nEqs);
                }

            case (Aerodynamics.BoundaryType.FREE_INLET_OUTLET):
                WR = Arrays.copyOf(WL, nEqs);
                double[] u = elem.meshVelocity;
                double Vn = 0;
                for (int d = 0; d < dim; ++d) {
                    Vn += (u[d]-WL[d+1]/WL[0]) * n[d];
                }
                if (Vn > 0) {
                    WR[0] = rhoIn0;
                    for (int d = 0; d < dim; ++d) {
                        WR[d + 1] = 0;
                    }
                }
                break;

            default:
                WR = Arrays.copyOf(WL, nEqs);
                break;
        }
        return WR;
    }

    @Override
    public double[] diffusiveFlux(double[] W, double[] dW, double[] n, ElementData elem) {
        W[0] = limiteRho(W[0]);
        double rho = W[0];

        double lam = -2. / 3; // Stokesuv vztah

        double[] velocity = new double[dim];
        double velocity2 = .0;
        for (int d = 0; d < dim; ++d) {
            velocity[d] = W[d + 1] / rho;
            velocity2 += velocity[d] * velocity[d];
        }

        double[] velocityJac = new double[dim * dim];
        for (int d = 0; d < dim; ++d) {
            for (int f = 0; f < dim; ++f) {
                velocityJac[dim * d + f] = (dW[f * nEqs + d + 1] - dW[f * nEqs] * velocity[d]) / rho;
            }
        }

        double p = pressure(W);
        double[] pOverRhoDer = new double[dim];
        for (int d = 0; d < dim; ++d) {
            double temp = .0;
            for (int f = 0; f < dim; ++f) {
                temp += velocity[f] * velocityJac[dim * f + d];
            }
            double pDer = (kapa - 1) * (dW[d * nEqs + dim + 1] - dW[d * nEqs] * velocity2 / 2 - rho * temp);
            pOverRhoDer[d] = (rho * pDer - p * dW[d * nEqs]) / (rho * rho);
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

        double constant = kapa / (kapa - 1) / Pr;
        double[] flux = new double[nEqs];
        for (int d = 0; d < dim; ++d) {
            double tmp = .0;
            for (int f = 0; f < dim; ++f) {
                flux[f + 1] += stress[dim * d + f] * n[d] / Re;
                tmp += velocity[f] * stress[dim * d + f];
            }
            flux[dim + 1] += (tmp + constant * pOverRhoDer[d]) * n[d] / Re;
        }

        return flux;
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

    @Override
    public boolean isSourcePresent() {
        return false;
    }

    @Override
    public double[] sourceTerm(double[] W, double[] dW, ElementData elem) { // zdrojovy clen
        throw new UnsupportedOperationException("source is not present");
    }

    protected double[] fplus(double W[], double p, double[] n) {
        double[] f = new double[nEqs];
        double[] velocity = new double[dim];
        double Vn = 0;
        double q2 = 0;
        for (int d = 0; d < dim; ++d) {
            velocity[d] = W[d + 1] / W[0];
            Vn += velocity[d] * n[d];
            q2 += velocity[d] * velocity[d];
        }
        double a = Math.sqrt(kapa * p / W[0]); // rychlost zvuku
        double M = Vn / a;

        if (Math.abs(M) < 1) {
            double fm = W[0] * a * ((M + 1) * (M + 1)) / 4; // mass flux 
            f[0] = fm;
            for (int d = 0; d < dim; ++d) {
                f[d + 1] = fm * (velocity[d] + (-Vn + 2 * a) / kapa * n[d]);
            }
            f[dim + 1] = fm * ((q2 - Vn * Vn) / 2 + (((kapa - 1) * Vn + 2 * a)) * (((kapa - 1) * Vn + 2 * a)) / (2 * (kapa * kapa - 1)));
        } else {
            if (M >= 1) {
                f[0] = W[0] * Vn;
                for (int d = 0; d < dim; ++d) {
                    f[d + 1] = W[d + 1] * Vn + p * n[d];
                }
                f[dim + 1] = (W[dim + 1] + p) * Vn;
            }
        }
        return f;
    }

    protected double[] fminus(double W[], double p, double[] n) {
        double[] f = new double[nEqs];
        double[] velocity = new double[dim];
        double Vn = 0;
        double q2 = 0;
        for (int d = 0; d < dim; ++d) {
            velocity[d] = W[d + 1] / W[0];
            Vn += velocity[d] * n[d];
            q2 += velocity[d] * velocity[d];
        }
        double a = Math.sqrt(kapa * p / W[0]); // rychlost zvuku
        double M = Vn / a;

        if (Math.abs(M) < 1) {
            double fm = -W[0] * a * ((M - 1) * (M - 1)) / 4; // mass flux 
            f[0] = fm;
            for (int d = 0; d < dim; ++d) {
                f[d + 1] = fm * (velocity[d] + (-Vn - 2 * a) / kapa * n[d]);
            }
            f[dim + 1] = fm * ((q2 - Vn * Vn) / 2 + (((kapa - 1) * Vn - 2 * a) * ((kapa - 1) * Vn - 2 * a)) / (2 * (kapa * kapa - 1)));
        } else {
            if (M <= -1) {
                f[0] = W[0] * Vn;
                for (int d = 0; d < dim; ++d) {
                    f[d + 1] = W[d + 1] * Vn + p * n[d];
                }
                f[dim + 1] = (W[dim + 1] + p) * Vn;
            }
        }
        return f;
    }

    @Override
    public boolean isEquationsJacobian() {
        return true;
    }

    @Override
    public double[] convectiveFluxJacobian(double[] W, double[] n, ElementData elemData) {
        double[] a = new double[nEqs * nEqs];
        double r = W[0];
        double u = W[1] / r;
        double v = W[2] / r;
        double E = W[3];
        double q = u * u + v * v;
        double p = (kapa - 1) * (E - r * q / 2);
        double nx = n[0];
        double ny = n[1];
        double Vn = u * nx + v * ny;

        a[0] = 0;
        a[1] = nx;
        a[2] = ny;
        a[3] = 0;
        a[4] = -u * Vn + 0.5 * (kapa - 1) * q * nx;
        a[5] = u * nx + Vn - (kapa - 1) * u * nx;
        a[6] = u * ny - (kapa - 1) * v * nx;
        a[7] = (kapa - 1) * nx;
        a[8] = -v * Vn + 0.5 * (kapa - 1) * q * ny;
        a[9] = v * nx - (kapa - 1) * u * ny;
        a[10] = v * ny + Vn - (kapa - 1) * v * ny;
        a[11] = (kapa - 1) * ny;
        a[12] = -1 / r * Vn * (E + p) + 0.5 * Vn * (kapa - 1) * q;
        a[13] = 1 / r * nx * (E + p) - u * Vn * (kapa - 1);
        a[14] = 1 / r * ny * (E + p) - v * Vn * (kapa - 1);
        a[15] = Vn * kapa;
        return a;
    }

    @Override
    public double[] diffusiveFluxJacobian(double[] W, double[] dW, double n[], ElementData elemData) {
        double[] a = new double[nEqs * nEqs * 3];
        a[0] = 0;
        a[1] = 0;
        a[2] = 0;
        a[3] = 0;
        a[4] = (8 * dW[0] * n[0] * W[1] - 4 * dW[1] * n[0] * W[0] + 6 * dW[0] * n[1] * W[2] - 4 * dW[nEqs] * n[0] * W[2] + 6 * dW[nEqs] * n[1] * W[1] - 3 * dW[nEqs + 1] * n[1] * W[0] - 3 * dW[2] * n[1] * W[0] + 2 * dW[nEqs + 2] * n[0] * W[0]) / (3 * Re * (W[0] * W[0] * W[0]));
        a[5] = -(4 * dW[0] * n[0] + 3 * dW[nEqs] * n[1]) / (3 * Re * (W[0] * W[0]));
        a[6] = -(3 * dW[0] * n[1] - 2 * dW[nEqs] * n[0]) / (3 * Re * (W[0] * W[0]));
        a[7] = 0;
        a[8] = (6 * dW[0] * n[0] * W[2] - 4 * dW[0] * n[1] * W[1] + 6 * dW[nEqs] * n[0] * W[1] + 2 * dW[1] * n[1] * W[0] - 3 * dW[nEqs + 1] * n[0] * W[0] - 3 * dW[2] * n[0] * W[0] + 8 * dW[nEqs] * n[1] * W[2] - 4 * dW[nEqs + 2] * n[1] * W[0]) / (3 * Re * (W[0] * W[0] * W[0]));
        a[9] = (2 * dW[0] * n[1] - 3 * dW[nEqs] * n[0]) / (3 * Re * (W[0] * W[0]));
        a[10] = -(3 * dW[0] * n[0] + 4 * dW[nEqs] * n[1]) / (3 * Re * (W[0] * W[0]));
        a[11] = 0;
        a[12] = (12 * Pr * dW[0] * n[0] * (W[1] * W[1]) + 9 * Pr * dW[0] * n[0] * (W[2] * W[2]) + 9 * Pr * dW[nEqs] * n[1] * (W[1] * W[1]) + 12 * Pr * dW[nEqs] * n[1] * (W[2] * W[2]) - 9 * dW[0] * kapa * n[0] * (W[1] * W[1]) - 9 * dW[0] * kapa * n[0] * (W[2] * W[2]) - 9 * dW[nEqs] * kapa * n[1] * (W[1] * W[1]) - 3 * dW[3] * kapa * n[0] * (W[0] * W[0]) - 9 * dW[nEqs] * kapa * n[1] * (W[2] * W[2]) - 3 * dW[nEqs + 3] * kapa * n[1] * (W[0] * W[0]) - 8 * Pr * dW[1] * n[0] * W[0] * W[1] + 3 * Pr * dW[0] * n[1] * W[1] * W[2] + 3 * Pr * dW[nEqs] * n[0] * W[1] * W[2] + 4 * Pr * dW[1] * n[1] * W[0] * W[2] - 6 * Pr * dW[nEqs + 1] * n[0] * W[0] * W[2] - 6 * Pr * dW[nEqs + 1] * n[1] * W[0] * W[1] - 6 * Pr * dW[2] * n[0] * W[0] * W[2] - 6 * Pr * dW[2] * n[1] * W[0] * W[1] + 4 * Pr * dW[nEqs + 2] * n[0] * W[0] * W[1] - 8 * Pr * dW[nEqs + 2] * n[1] * W[0] * W[2] + 6 * dW[1] * kapa * n[0] * W[0] * W[1] + 6 * dW[0] * kapa * n[0] * W[0] * W[3] + 6 * dW[nEqs + 1] * kapa * n[1] * W[0] * W[1] + 6 * dW[2] * kapa * n[0] * W[0] * W[2] + 6 * dW[nEqs] * kapa * n[1] * W[0] * W[3] + 6 * dW[nEqs + 2] * kapa * n[1] * W[0] * W[2]) / (3 * Pr * Re * (W[0] * W[0] * W[0] * W[0]));
        a[13] = -(3 * dW[1] * kapa * n[0] * W[0] - 6 * dW[0] * kapa * n[0] * W[1] - 6 * dW[nEqs] * kapa * n[1] * W[1] + 3 * dW[nEqs + 1] * kapa * n[1] * W[0] + 8 * Pr * dW[0] * n[0] * W[1] - 4 * Pr * dW[1] * n[0] * W[0] + Pr * dW[0] * n[1] * W[2] + Pr * dW[nEqs] * n[0] * W[2] + 6 * Pr * dW[nEqs] * n[1] * W[1] - 3 * Pr * dW[nEqs + 1] * n[1] * W[0] - 3 * Pr * dW[2] * n[1] * W[0] + 2 * Pr * dW[nEqs + 2] * n[0] * W[0]) / (3 * Pr * Re * (W[0] * W[0] * W[0]));
        a[14] = -(3 * dW[2] * kapa * n[0] * W[0] - 6 * dW[0] * kapa * n[0] * W[2] - 6 * dW[nEqs] * kapa * n[1] * W[2] + 3 * dW[nEqs + 2] * kapa * n[1] * W[0] + 6 * Pr * dW[0] * n[0] * W[2] + Pr * dW[0] * n[1] * W[1] + Pr * dW[nEqs] * n[0] * W[1] + 2 * Pr * dW[1] * n[1] * W[0] - 3 * Pr * dW[nEqs + 1] * n[0] * W[0] - 3 * Pr * dW[2] * n[0] * W[0] + 8 * Pr * dW[nEqs] * n[1] * W[2] - 4 * Pr * dW[nEqs + 2] * n[1] * W[0]) / (3 * Pr * Re * (W[0] * W[0] * W[0]));
        a[15] = -(kapa * (dW[0] * n[0] + dW[nEqs] * n[1])) / (Pr * Re * (W[0] * W[0]));
        a[16] = 0;
        a[17] = 0;
        a[18] = 0;
        a[19] = 0;
        a[20] = -(4 * n[0] * W[1] + 3 * n[1] * W[2]) / (3 * Re * (W[0] * W[0]));
        a[21] = (4 * n[0]) / (3 * Re * W[0]);
        a[22] = n[1] / (Re * W[0]);
        a[23] = 0;
        a[24] = -(3 * n[0] * W[2] - 2 * n[1] * W[1]) / (3 * Re * (W[0] * W[0]));
        a[25] = -(2 * n[1]) / (3 * Re * W[0]);
        a[26] = n[0] / (Re * W[0]);
        a[27] = 0;
        a[28] = -(4 * Pr * n[0] * (W[1] * W[1]) - 3 * kapa * n[0] * (W[2] * W[2]) - 3 * kapa * n[0] * (W[1] * W[1]) + 3 * Pr * n[0] * (W[2] * W[2]) + Pr * n[1] * W[1] * W[2] + 3 * kapa * n[0] * W[0] * W[3]) / (3 * Pr * Re * (W[0] * W[0] * W[0]));
        a[29] = -(2 * Pr * n[1] * W[2] - 4 * Pr * n[0] * W[1] + 3 * kapa * n[0] * W[1]) / (3 * Pr * Re * (W[0] * W[0]));
        a[30] = (Pr * n[0] * W[2] + Pr * n[1] * W[1] - kapa * n[0] * W[2]) / (Pr * Re * (W[0] * W[0]));
        a[31] = (kapa * n[0]) / (Pr * Re * W[0]);
        a[32] = 0;
        a[33] = 0;
        a[34] = 0;
        a[35] = 0;
        a[36] = (2 * n[0] * W[2] - 3 * n[1] * W[1]) / (3 * Re * (W[0] * W[0]));
        a[37] = n[1] / (Re * W[0]);
        a[38] = -(2 * n[0]) / (3 * Re * W[0]);
        a[39] = 0;
        a[40] = -(3 * n[0] * W[1] + 4 * n[1] * W[2]) / (3 * Re * (W[0] * W[0]));
        a[41] = n[0] / (Re * W[0]);
        a[42] = (4 * n[1]) / (3 * Re * W[0]);
        a[43] = 0;
        a[44] = -(3 * Pr * n[1] * (W[1] * W[1]) - 3 * kapa * n[1] * (W[2] * W[2]) - 3 * kapa * n[1] * (W[1] * W[1]) + 4 * Pr * n[1] * (W[2] * W[2]) + Pr * n[0] * W[1] * W[2] + 3 * kapa * n[1] * W[0] * W[3]) / (3 * Pr * Re * (W[0] * W[0] * W[0]));
        a[45] = (Pr * n[0] * W[2] + Pr * n[1] * W[1] - kapa * n[1] * W[1]) / (Pr * Re * (W[0] * W[0]));
        a[46] = -(2 * Pr * n[0] * W[1] - 4 * Pr * n[1] * W[2] + 3 * kapa * n[1] * W[2]) / (3 * Pr * Re * (W[0] * W[0]));
        a[47] = (kapa * n[1]) / (Pr * Re * W[0]);

        return a;
    }
    
    @Override
    public double[] getResults(double[] W, double[] dW, double[] X, String name) {
        switch (name.toLowerCase()) {
            case "temperature":
                double velocity2 = 0;
                for (int i = 0; i < dim; i++) {
                    velocity2 += (W[i + 1] / W[0]) * (W[i + 1] / W[0]);
                }
                return new double[]{velocityRef * velocityRef * (W[dim + 1] / W[0] - velocity2 / 2) / cv};

            case "energy":
                return new double[]{pRef * W[dim + 1]};

            default:
                return super.getResults(W, dW, X, name);
        }
    }
}

