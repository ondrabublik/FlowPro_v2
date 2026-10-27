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
public class KOmegaSST extends Aerodynamics {

    protected static final double P_TOL = 1e-1;

    double kIn;
    double omIn;
    double omWall;
    
    double kref;
    double omref;
    double muref;

    double LIM_TOL;

    // Sutherland constant
    double Suth;
    
    // parameters of turbulence model
    double a1;
    double sk1;
    double som1;
    double alpha1;
    double beta1;
    double betast;   // beta star 
    double sk2;
    double som2;
    double alpha2;
    double beta2;
    double Prt;
 

    @Override
    public void init(FlowProProperties props) throws IOException {
        int dimension = props.getInt("dimension");
        super.init(props, dimension, dimension + 4, true);

        // inlet
        kIn = props.getDouble("kIn");
        omIn = props.getDouble("omIn");
        if (isInletSupersonic) {
            WIn[dimension + 2] = kIn;
            WIn[dimension + 3] = omIn;
        }
        
        // Sutherland
        Suth = props.getDouble("SuthConst");
        
        // parameters of turbulence model
        a1 = props.getDouble("a1");
        sk1 = props.getDouble("sk1");
        som1 = props.getDouble("som1");
        alpha1 = props.getDouble("alpha1");
        beta1 = props.getDouble("beta1");
        betast = props.getDouble("betast");
        sk2 = props.getDouble("sk2");
        som2 = props.getDouble("som2");
        alpha2 = props.getDouble("alpha2");
        beta2 = props.getDouble("beta2");
        Prt = props.getDouble("Prt");
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
            W[dim + 2] = rhoIn * kIn;
            W[dim + 3] = rhoIn * omIn;

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

                for (int j = 0; j < nEqs; j++) {
                    f[j] += V * WR[j];
                }
                break;

            case (Aerodynamics.BoundaryType.INLET):
            case (Aerodynamics.BoundaryType.OUTLET):
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
        f[dim + 3] = W[dim + 3] * V;
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
                    WR[dim + 2] = 0;
                    WR[dim + 3] = WL[dim + 3];
                    //WR[dim + 3] = omWall;
                    break;
                }
            case (Aerodynamics.BoundaryType.INVISCID_WALL):
                WR = Arrays.copyOf(WL, nEqs);
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
                    WR[dim + 2] = rhoIn * kIn;
                    WR[dim + 3] = rhoIn * omIn;
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
                    WR[dim + 2] = WL[dim + 2];
                    WR[dim + 3] = WL[dim + 3];
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

        // turbulentStress tensor calculation
        double[] stress = new double[dim * dim];
        //double[] vortic = new double[dim * dim];
        double trace = .0;
        for (int d = 0; d < dim; ++d) {
            trace += velocityJac[dim * d + d];
            for (int f = 0; f < dim; ++f) {
                stress[dim * d + f] = velocityJac[dim * d + f] + velocityJac[dim * f + d];
            //    vortic[dim * d + f] = (velocityJac[dim * d + f] - velocityJac[dim * f + d]);
            }
        }
        for (int d = 0; d < dim; ++d) {
            stress[dim * d + d] += lam * trace;
        }

        double k = max(W[dim + 2]/rho,1e-10);
        double om = W[dim + 3] / rho;
        
        // sutherland relation
        double etaTemp = sutherland(rho, p);
        
        double vortic = Math.abs(velocityJac[1]-velocityJac[2]);
        double walldist = Math.abs(elem.currentWallDistance);  
        if (walldist < 1e-5){                // zero at wall makes problems - adjust value according to mesh
            walldist = 1e-5;
        }
        double arg2 = max(2*Math.sqrt(k)/(0.09*Math.exp(om)*walldist)/Math.sqrt(Re),500*etaTemp/rho/(walldist*walldist*Math.exp(om))/Re);
        double F2 = Math.tanh(arg2*arg2);
        
        double mut = max(0, rho*a1*k / max(a1*Math.exp(om),vortic*F2));

        double[] turbulentStress = new double[dim * dim];
        for (int i = 0; i < stress.length; i++) {
            turbulentStress[i] = stress[i] * mut;
        }
        double kMax = 2.0 / 3 * max(0, rho*k);
        for (int d = 0; d < dim; ++d) {
            turbulentStress[dim * d + d] -= kMax;
        }

        double[] kDer = new double[dim];
        double[] omDer = new double[dim];
        for (int d = 0; d < dim; ++d) {
            kDer[d] = (dW[d * nEqs + dim + 2] - dW[d * nEqs] * k) / rho;
            omDer[d] = (dW[d * nEqs + dim + 3] - dW[d * nEqs] * om) / rho;
        }

        double constant = kapa / (kapa - 1) * (etaTemp / Pr + mut / Prt);
        
        double omkDer = 0;
        for (int d = 0; d < dim; ++d) {
            omkDer += omDer[d] * kDer[d];
        }
        
        double CD = max(2*som2*rho*omkDer,1e-10);
        double arg11 = max(Math.sqrt(k)/(0.09*Math.exp(om)*walldist)/Math.sqrt(Re),500*etaTemp/rho/(Math.exp(om)*walldist*walldist)/Re);
        double arg12 = 4*rho*som2*k/(CD*walldist*walldist);
        double arg1 = min(arg11,arg12);   
        double F1 = Math.tanh(Math.pow(arg1,4));
        
        double sk0 = F1*sk1 + (1-F1)*sk2;
        double som0 = F1*som1 + (1-F1)*som2;
        //double alpha0 = F1*alpha1 + (1-F1)*alpha2;
        //double beta0 = F1*beta1 + (1-F1)*beta2;

        double[] flux = new double[nEqs];
        for (int d = 0; d < dim; ++d) {
            double tmp = .0;
            for (int f = 0; f < dim; ++f) {
                flux[f + 1] += (etaTemp * stress[dim * d + f] + turbulentStress[dim * d + f]) * n[d] / Re;
                tmp += velocity[f] * (etaTemp * stress[dim * d + f] + turbulentStress[dim * d + f]);
            }
            flux[dim + 1] += (tmp + constant * pOverRhoDer[d]) * n[d] / Re;
            flux[dim + 2] += (etaTemp + sk0 * mut) / Re * kDer[d] * n[d];
            flux[dim + 3] += (etaTemp + som0 * mut) / Re * omDer[d] * n[d];
        }

        return flux;
    }

    @Override
    public double[] numericalDiffusiveFlux(double Wc[], double dWc[], double[] n, int TT, ElementData elem) {
        Wc[0] = limiteRho(Wc[0]);

        double[] flux = diffusiveFlux(Wc, dWc, n, elem);
        if (TT < 0) {
            if (TT == Aerodynamics.BoundaryType.WALL) {
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
        
        double p = pressure(W);

        double k = max(W[dim + 2]/rho,1e-10);
        double om = W[dim + 3] / rho;
        double expOm = Math.exp(om);
        
        double etaTemp = sutherland(rho, p);
        
        double vortic = Math.abs(velocityJac[1]-velocityJac[2]);
        double walldist = Math.abs(elem.currentWallDistance);  
        if (walldist < 1e-5){                // zero at wall makes problems - adjust value according to mesh
            walldist = 1e-5;
        }
        double arg2 = max(2*Math.sqrt(k)/(0.09*Math.exp(om)*walldist)/Math.sqrt(Re),500*etaTemp/rho/(walldist*walldist*Math.exp(om))/Re);
        double F2 = Math.tanh(arg2*arg2);
        
        double mut = max(0, rho*a1*k / max(a1*Math.exp(om),vortic*F2));      

        // turbulentStress tensor calculation
        double[] turbulentStress = new double[dim * dim];
        double trace = .0;
        for (int d = 0; d < dim; ++d) {
            trace += velocityJac[dim * d + d];
            for (int f = 0; f < dim; ++f) {
                turbulentStress[dim * d + f] = mut * (velocityJac[dim * d + f] + velocityJac[dim * f + d]);
            }
        }
        double kMax = 2.0 / 3 * max(0, rho*k);
        for (int d = 0; d < dim; ++d) {
            turbulentStress[dim * d + d] += mut * lam * trace - kMax;
        }

        double omkDer = 0;
        double omDerSqr = 0;
        for (int d = 0; d < dim; ++d) {
            double kDer = (dW[d * nEqs + dim + 2] - dW[d * nEqs] * k) / rho;
            double omDer = (dW[d * nEqs + dim + 3] - dW[d * nEqs] * om) / rho;
            omkDer += omDer * kDer;
            omDerSqr += omDer * omDer;
        }

        double Tv = 0;
        for (int d = 0; d < dim; d++) {
            for (int f = 0; f < dim; f++) {
                Tv += turbulentStress[dim * d + f] * velocityJac[dim * d + f];
            }
        }
              
        double CD = max(2*som2*rho*omkDer,1e-10);
        double arg11 = max(Math.sqrt(k)/(0.09*Math.exp(om)*walldist)/Math.sqrt(Re),500*etaTemp/rho/(Math.exp(om)*walldist*walldist)/Re);
        double arg12 = 4*rho*som2*k/(CD*walldist*walldist);
        double arg1 = min(arg11,arg12);   
        double F1 = Math.tanh(Math.pow(arg1,4));
        
        //double sk0 = F1*sk1 + (1-F1)*sk2;
        double som0 = F1*som1 + (1-F1)*som2;
        double alpha0 = F1*alpha1 + (1-F1)*alpha2;
        double beta0 = F1*beta1 + (1-F1)*beta2;

        if (Tv > 10 * betast * rho * k * expOm) {   
            Tv = 10 * betast * rho * k * expOm;
        }

        double Tvom = alpha0 * rho / mut /expOm * Tv;   
        if (Tvom > 10 * beta0 * rho * expOm) {     // moje zmena, opatrne (bylo 100*...)
            Tvom = 10 * beta0 * rho * expOm;
        }

        double[] source = new double[nEqs];
        source[dim + 2] = max(Tv - betast * rho * k * expOm, 0);  
        source[dim + 3] = max(Tvom - beta0 * rho * expOm + 1 / Re * som2*rho/expOm *omkDer *2*(1-F1) + 1 / Re * (etaTemp + som0 * mut) * omDerSqr, 0);
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

            case "k":
                return new double[]{W[dim + 2] / W[0]};

            case "omega":
                return new double[]{Math.exp(W[dim + 3] / W[0])};

            case "turbulentviscosity":
                return new double[]{W[dim + 2] / Math.exp(W[dim + 3] / W[0])};

            default:
                return super.getResults(W, dW, X, name);
        }
    }

    public double sutherland(double rho, double p) {
        double T = p / rho;
        double TRef = 1/ (cv*(kapa - 1)) * pRef / rhoRef;
        return Math.pow(T,1.5)*(1+Suth/TRef)/(T + Suth/TRef);
    }

    public double max(double a, double b) {
        if (a > b) {
            return a;
        } else {
            return b;
        }
    }
    public double min(double a, double b) {
        if (a < b) {
            return a;
        } else {
            return b;
        }
    }
}


