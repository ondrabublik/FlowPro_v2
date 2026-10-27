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
public class SpalartAllmaras extends Aerodynamics {

    protected static final double P_TOL = 1e-1;

    double vtIn; // turbulence intensity at the inlet

    // parameters of turbulence model
    double sigma;
    double cb1;
    double cb2;
    double ka;
    double cw1;
    double cw2;
    double cw3;
    double cv1;
    double ct1;
    double ct2;
    double ct3;
    double ct4;
    double Prt;
    double C_prod;
    
    @Override
    public void init(FlowProProperties props) throws IOException {
        int dimension = props.getInt("dimension");
        super.init(props, dimension, dimension + 3, true);

        // inlet
        vtIn = props.getDouble("vtIn");
        if (isInletSupersonic) {
            WIn[dimension + 2] = vtIn;
        }

        // parameters of turbulence model
        sigma = props.getDouble("sigma");
        cb1 = props.getDouble("cb1");
        cb2 = props.getDouble("cb2");
        ka = props.getDouble("ka");
        cw1 = cb1 / (ka * ka) + (1 + cb2) / sigma;
        cw2 = props.getDouble("cw2");
        cw3 = props.getDouble("cw3");
        cv1 = props.getDouble("cv1");
        ct1 = props.getDouble("ct1");
        ct2 = props.getDouble("ct2");
        ct3 = props.getDouble("ct3");
        ct4 = props.getDouble("ct4");
        Prt = props.getDouble("Prt");
        C_prod = props.getDouble("C_prod");
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
            W[dim + 2] = rhoIn * vtIn;
            
            return W;
        }
    }

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
                f[dim + 1] = p * V;
                
                // for ALE
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
        f[dim + 2] = W[dim + 2] * V;
        return f;
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
            pOverRhoDer[d] = (rho * pDer - p * dW[d * nEqs]) / (rho * rho); // rho * rho * p * p !!!!!!!!!!!!!!!!
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

        double vt = max(0, W[dim + 2] / rho);
        double[] vtDer = new double[dim];
        for (int d = 0; d < dim; ++d) {
            vtDer[d] = 1 / rho * (dW[d * nEqs + dim + 2] - dW[d * nEqs] * vt);
        }

        double xi = rho*vt; // vt/v ????????
        double fv1 = xi * xi * xi / (xi * xi * xi + cv1 * cv1 * cv1);
        double mut = rho * vt * fv1;

        double constant = kapa / (kapa - 1) * (1 / Pr + mut / Prt);
        double[] flux = new double[nEqs];
        for (int d = 0; d < dim; ++d) {
            double tmp = .0;
            for (int f = 0; f < dim; ++f) {
                flux[f + 1] += (1 + mut) * stress[dim * d + f] * n[d] / Re;
                tmp += velocity[f] * (1 + mut) * stress[dim * d + f];
            }
            flux[dim + 1] += (tmp + constant * pOverRhoDer[d]) * n[d] / Re;
            flux[dim + 2] += 1 / (Re * sigma) * (1 + rho * vt) * vtDer[d] * n[d];
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
        return true;
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
        double ft2 = 0;//ct3*Math.exp(-ct4*xi*xi);
        double fv1 = xi * xi * xi / (xi * xi * xi + cv1 * cv1 * cv1);
        double fv2 = 1 - xi / (1 + xi * fv1);
        double Om = rotationMagnitude(velocityJac);
        //double S = Om + C_prod * Math.min(0, Smag - Om) + 1 / Re * vt / (ka * ka * D * D) * fv2;
        double S = Om + 1 / Re * vt / (ka * ka * D * D) * fv2;
        double rt = Math.min(vt / (Re * S * ka * ka * D * D), 10);

        double g = rt + cw2 * (Math.pow(rt, 6.0) - rt);
        double fw = g * Math.pow((1 + Math.pow(cw3, 6.0)) / (Math.pow(g, 6.0) + Math.pow(cw3, 6.0)), 1.0 / 6);

        double[] source = new double[nEqs];
        source[dim + 2] = limitDestruction(1 / Re * rho * cb2 * vtDerMag + cb1 * (1 - ft2) * rho * S * vt - 1 / Re * (cw1 * fw - cb1 / (ka * ka) * ft2) / rho * (rho * vt / D) * (rho * vt / D));
        return source;
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
                        
            case "mut":
                double xi = max(W[dim + 2],0); // vt/v
                double fv1 = xi * xi * xi / (xi * xi * xi + cv1 * cv1 * cv1);
                return new double[]{W[dim + 2]*fv1};
                
            default:
                return super.getResults(W, dW, X, name);
        }
    }

    public double max(double a, double b) {
        if (a > b) {
            return a;
        } else {
            return b;
        }
    }

    public double matrixMagnitude(double[] A) {
        double mag = 0;
        for (int i = 0; i < A.length; i++) {
            mag += A[i] * A[i];
        }
        return Math.sqrt(mag);
    }

    public double rotationMagnitude(double[] U) {
        double rotMag = 0;
        if (U.length == 4) {
            rotMag = Math.abs(U[1] - U[2]);
        } else if (U.length == 9) {
            rotMag = Math.sqrt((U[0*dim + 1] - U[1*dim + 0]) * (U[0*dim + 1] - U[1*dim + 0]) + (U[0*dim + 2] - U[2*dim + 0]) * (U[0*dim + 2] - U[2*dim + 0]) + (U[2*dim + 1] - U[1*dim + 2]) * (U[2*dim + 1] - U[1*dim + 2]));
        }
        return rotMag;
    }

    public double limitDestruction(double d) {
        if (d < -100) {
            d = -100;
        }
        return d;
    }
    
    @Override
	public double[] stressVector(double[] W, double[] dW, double[] normal) {
		if (isDiffusive) {		
			double rho = W[0];

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

			double[] stress = new double[dim * dim];
			double trace = .0;
			for (int d = 0; d < dim; ++d) {
				trace += velocityJac[dim * d + d];
				for (int f = 0; f < dim; ++f) {
					stress[dim * d + f] = velocityJac[dim * d + f] + velocityJac[dim * f + d];
				}
			}
			double lam = -2. / 3; // Stokesuv vztah
			for (int d = 0; d < dim; ++d) {
				stress[dim * d + d] += lam * trace;
			}

			double p = pressure(W);

			double[] normalStress = new double[dim];
			for (int d = 0; d < dim; ++d) {
				normalStress[d] -= p * normal[d];
				for (int f = 0; f < dim; ++f) {
					normalStress[d] += 1 / Re * stress[dim * d + f] * normal[f];
				}
			}

			return normalStress;
		} else {
			return super.stressVector(W, dW, normal);
		}
	}
}
