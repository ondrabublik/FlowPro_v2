/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.Equation;
import flowpro.api.FlowProProperties;
import flowpro.api.Mat;
import java.io.FileOutputStream;
import java.io.IOException;

public class NavierStokes3DRotFrame implements Equation {

    protected class BoundaryType {

        static final int WALL = -1;
        static final int INLET = -2;
        static final int OUTLET = -3;
        static final int INVISCID_WALL = -4;
        
        static final int STATOR = -5;
        static final int ROTOR = -6;
    }

    protected int dim;
    protected int nEqs;
    protected boolean isDiffusive;

    protected double kapa; // Poissonova konstanta
    protected double Re; // Reynoldsovo cislo
    protected double Pr; // Prandtlovo cislo

    // reference values
    protected double lRef;
    protected double pRef;
    protected double rhoRef;
    protected double velocityRef;
    protected double tRef;  // nepouziva se !!!???

    // inlet boundary condition
    protected boolean isInletSupersonic;
    // subsonic inlet boundary condition 
    protected final double pIn0 = 1; // static pressure
    protected final double rhoIn0 = 1; // static density
    protected double attackAngleAlfa; // angle of attack
    protected double attackAngleBeta; // angle of attack   
    // supersonic inlet boundary condition
    protected double[] WIn;

    // outlet boundary condition
    protected double pOut; // pressure

    // rotation of reference frame
    protected double[] omega;
    protected double[] omegaStator;
    protected double[] omegaRotor;
    protected double[] omegaInlet;
    protected double[] rotorExcentricity;
    protected double[] swirlProfileRadius;

    // time variables
    protected double t;
    protected double dt;

    // tolerance pro hodnotu hustoty a tlaku
    protected static final double rhoTol = 1e-1;
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
    public void setState(double dt, double t) {
        this.dt = dt;
        this.t = t;
    }

    @Override
    public boolean isEquationsJacobian() {
        return false;
    }

    @Override
    public double[] convectiveFluxJacobian(double[] W, double[] n, ElementData elemData) {
        throw new UnsupportedOperationException("operation not supported");
    }

    @Override
    public double[] diffusiveFluxJacobian(double[] W, double[] dW, double n[], ElementData elemData) {
        throw new UnsupportedOperationException("operation not supported");
    }

    @Override
    public double[] sourceTermJacobian(double[] W, double[] dW, ElementData elemData) {
        throw new UnsupportedOperationException("operation not supported");
    }

    @Override
    public boolean isConvective() {
        return true;
    }

    @Override
    public boolean isDiffusive() {
        return isDiffusive;
    }

    @Override
    public boolean isSourcePresent() {
        return true;
    }
    
    public void init(FlowProProperties props) throws IOException {
        this.nEqs = 5;
        this.isDiffusive = props.getBoolean("isFlowViscous");
        this.dim = props.getInt("dimension");
        
        // heat capacity ratio
        double cv, cp;
        if (props.containsKey("kappa") && props.containsKey("cv") && !props.containsKey("cp")) {
            kapa = props.getDouble("kappa");
            cv = props.getDouble("cv");
            cp = cv * kapa;
        } else if (props.containsKey("kappa") && props.containsKey("cp") && !props.containsKey("cv")) {
            kapa = props.getDouble("kappa");
            cp = props.getDouble("cp");
            cv = cp / kapa;
        } else if (props.containsKey("cv") && props.containsKey("cp") && !props.containsKey("kappa")) {
            cp = props.getDouble("cp");
            cv = props.getDouble("cv");
            kapa = cp / cv;
        } else {
            throw new IOException("exactly two out of the three following parameters must be defined: "
                    + "kappa, cp, cv");
        }

        // inlet type
        isInletSupersonic = props.getBoolean("isInletSupersonic");

        // reference values from inlet
        if (isInletSupersonic) {
            pRef = props.getDouble("pIn");

            if (props.containsKey("rhoIn")) {
                rhoRef = props.getDouble("rhoIn");
            } else if (props.containsKey("TIn")) {
                if (!props.containsKey("cv")) {
                    throw new IOException("if temperature is prescribed at the inlet, "
                            + " than the heat capacity cv also needs to be specified");
                }

                double TIn = props.getDouble("TIn");
                rhoRef = pRef / (cv * (kapa - 1) * TIn);
            } else {
                throw new IOException("either temperature or density "
                        + "must be prescribed at the inlet");
            }
        } else {
            pRef = props.getDouble("pIn0");

            if (props.containsKey("rhoIn0")) {
                rhoRef = props.getDouble("rhoIn0");
            } else if (props.containsKey("TIn0")) {
                double TIn0 = props.getDouble("TIn0");
                rhoRef = pRef / (cv * (kapa - 1) * TIn0);
            } else {
                throw new IOException("either temperature or density "
                        + "must be prescribed at the inlet");
            }
        }

        // other reference values
        if (props.containsKey("lRef")) {
            lRef = props.getDouble("lRef");
        } else {
            lRef = 1;
        }
        velocityRef = Math.sqrt(pRef / rhoRef);
        tRef = lRef / velocityRef;

        // inlet
        attackAngleAlfa = props.getDouble("attackAngleAlfa");
        attackAngleBeta = props.getDouble("attackAngleBeta");
        WIn = new double[nEqs];
        if (isInletSupersonic) {
            // rhoIn = 1, pIn = 1;
            double velocityIn = props.getDouble("vIn") / velocityRef;

            double uIn = velocityIn * Math.cos(attackAngleAlfa) * Math.cos(attackAngleBeta);
            double vIn = velocityIn * Math.sin(attackAngleAlfa) * Math.cos(attackAngleBeta);
            double wIn = velocityIn * Math.sin(attackAngleAlfa) * Math.sin(attackAngleBeta);
            double EIn = 1 / (kapa - 1) + 0.5 * (uIn * uIn + vIn * vIn + wIn * wIn);
            WIn[0] = 1;
            WIn[1] = uIn;
            WIn[2] = vIn;
            WIn[3] = wIn;
            WIn[4] = EIn;
        } else {
            WIn[0] = -1;   // temporarely                
        }

        // outlet
        if (props.containsKey("pOut")) {
            pOut = props.getDouble("pOut") / pRef;
        } else if (props.containsKey("machInf")) {
            double machInf = props.getDouble("machInf");
            pOut = 1 / Math.pow((1 + (kapa - 1) / 2 * machInf * machInf), (kapa / (kapa - 1)));
        } else if (isInletSupersonic) {
            pOut = 0;  // should not be used during computation
        } else {
            throw new IOException("outlet boundary condition is not specified");
        }

        // angular velocity of rotating frame
        omega = Mat.times(props.getDoubleArray("omega"), tRef);
        omegaStator = Mat.times(omega, -1.0); //Mat.minusVec(Mat.times(props.getDoubleArray("omegaStator"), tRef),omega);
        omegaRotor = Mat.minusVec(Mat.times(props.getDoubleArray("omegaRotor"), tRef), omega);
        omegaInlet = Mat.minusVec(Mat.times(props.getDoubleArray("omegaInlet"), tRef), omega);
        rotorExcentricity = Mat.times(props.getDoubleArray("rotorExcentricity"), 1/lRef); // chyba!!!!!!!!

        // swirl profile
        swirlProfileRadius = null;
        if (props.containsKey("swirlProfile")) {
            swirlProfileRadius = props.getDoubleArray("omegaRotor");
        }

        // parameters for the viscous flow
        double ReInf = -1;
        if (isDiffusive) {
            if (props.containsKey("reynolds") && props.containsKey("prandtl")
                    && !props.containsKey("vis"
                            + "cosity") && !props.containsKey("conductivity")) {

                Re = props.getDouble("reynolds");
                Pr = props.getDouble("prandtl");
            } else if (props.containsKey("viscosity") && props.containsKey("conductivity")) {
                if (!props.containsKey("cp")) {
                    throw new IOException("heat capacity cp needs to be specified "
                            + " in order to calculate the prandtl number");
                }
                double viscosity = props.getDouble("viscosity");
                double conductivity = props.getDouble("conductivity");

                Pr = cp * viscosity / conductivity;
                Re = rhoRef * velocityRef * lRef / viscosity;
            } else {
                throw new IOException("either the Prandtl and Reynolds numbers, "
                        + "or dynamic viscosity and thermal conductivity must be specified");
            }

            // far field Reynolds
            double machInf = Math.sqrt(2 / (kapa - 1) * (Math.pow((1 / pOut), (kapa - 1) / kapa) - 1));
            double rhoInf = Math.pow(1 + ((kapa - 1) / 2) * machInf * machInf, 1 / (1 - kapa));
            double uInf = machInf * Math.sqrt((kapa * pOut) / rhoInf);
            ReInf = rhoInf * uInf * lRef / (rhoRef * velocityRef * lRef / Re);
        } else {
            Re = -1;  // temporarely
            Pr = -1;
        }
        
        System.out.println("---- notable physical parameters: ----");
        System.out.printf("heat capacity ratio %.3f\n", kapa);
        if (isDiffusive) {
            System.out.printf("Reynolds number     %.3e\n", Re);
            System.out.printf("Prandtl number      %.3f\n", Pr);
        }
        System.out.println("--------------------------------------");
    }

    @Override
    public double[] constInitCondition() {
        if (isInletSupersonic) {
            return WIn;
        } else {
            double machIn = Math.sqrt(2 / (kapa - 1) * (Math.pow((1 / pOut), (kapa - 1) / kapa) - 1));
            double rhoIn = Math.pow(1 + ((kapa - 1) / 2) * machIn * machIn, 1 / (1 - kapa));
            double VIn = machIn * Math.sqrt((kapa * pOut) / rhoIn);
            double uIn = VIn * Math.cos(attackAngleAlfa) * Math.cos(attackAngleBeta);
            double vIn = VIn * Math.sin(attackAngleAlfa) * Math.cos(attackAngleBeta);
            double wIn = VIn * Math.sin(attackAngleAlfa) * Math.sin(attackAngleBeta);
            double EIn = pOut / (kapa - 1) + 0.5 * rhoIn * VIn * VIn;

            return new double[]{rhoIn, rhoIn * uIn, rhoIn * vIn, rhoIn * wIn, EIn};
        }
    }
    
    @Override
    public double[] sourceTerm(double[] W, double[] dW, ElementData elem) { // zdrojovy clen
        W[0] = limiteRho(W[0]);

        double[] sour = new double[nEqs];
        double[] Fcentrifugal = Mat.times(Mat.cross(omega, Mat.cross(omega, elem.currentX)), -W[0]);
        double[] Vref = new double[]{W[1] / W[0], W[2] / W[0], W[3] / W[0]};
        double[] Fcoriolis = Mat.times(Mat.cross(omega, Vref), -2.0 * W[0]);
        sour[1] = Fcentrifugal[0] + Fcoriolis[0];
        sour[2] = Fcentrifugal[1] + Fcoriolis[1];
        sour[3] = Fcentrifugal[2] + Fcoriolis[2];
        
        return sour;
    }    
    
    @Override
    public double[] boundaryValue(double[] WL, double[] n, int TT, ElementData elem) {
        WL[0] = limiteRho(WL[0]);

        double[] WR = new double[nEqs];
        double p = pressure(WL, elem.currentX);
        switch (TT) {
            case (BoundaryType.WALL): // stena
                if (isDiffusive) {
                    WR[0] = WL[0];
                    WR[1] = 0;
                    WR[2] = 0;
                    WR[3] = 0;
                    WR[4] = p / (kapa - 1);
                } else {
                    System.arraycopy(WL, 0, WR, 0, nEqs);
                }
                break;

            case (BoundaryType.STATOR): // stator
                if (isDiffusive) {
                    double[] uRot = Mat.cross(omegaStator, elem.currentX);
                    WR[0] = WL[0];
                    WR[1] = WR[0] * uRot[0];
                    WR[2] = WR[0] * uRot[1];
                    WR[3] = WR[0] * uRot[2];
                    WR[4] = p / (kapa - 1) + (WR[1] * WR[1] + WR[2] * WR[2] + WR[3] * WR[3]) / (2 * WR[0]) - WR[0] * Math.pow(Mat.L2Norm(Mat.cross(omega, elem.currentX)), 2) / 2;
                } else {
                    System.arraycopy(WL, 0, WR, 0, nEqs);
                }
                break;

            case (BoundaryType.ROTOR): // rotor
                if (isDiffusive) {
                    double[] uRot = Mat.cross(omegaRotor, Mat.minusVec(elem.currentX, rotorExcentricity));
                    WR[0] = WL[0];
                    WR[1] = WR[0] * uRot[0];
                    WR[2] = WR[0] * uRot[1];
                    WR[3] = WR[0] * uRot[2];
                    WR[4] = p / (kapa - 1) + (WR[1] * WR[1] + WR[2] * WR[2] + WR[3] * WR[3]) / (2 * WR[0]) - WR[0] * Math.pow(Mat.L2Norm(Mat.cross(omega, elem.currentX)), 2) / 2;
                } else {
                    System.arraycopy(WL, 0, WR, 0, nEqs);
                }
                break;

            case (BoundaryType.INLET):
                if (WIn[0] == -1) { // subsonicky vstup
                    double Minl;
                    if (p > pIn0) {
                        Minl = 0;
                    } else {
                        Minl = Math.sqrt((2 / (kapa - 1)) * (-1 + Math.pow(pIn0 / p, (kapa - 1) / kapa)));
                    }
                    double[] uRot = new double[3];
                    if (swirlProfileRadius != null) {
                        double[] uRotR = Mat.cross(omegaRotor, elem.currentX);
                        double[] uRotS = Mat.cross(omegaStator, elem.currentX);
                        double[] uRotIn = Mat.cross(omegaInlet, elem.currentX);
                        double rR = swirlProfileRadius[0]; // rotor radius
                        double rS = swirlProfileRadius[1]; // stator radius
                        double rIn = (rR + rS) / 2;
                        double x = Mat.L2Norm(elem.currentX);
                        for (int i = 0; i < 3; i++) {
                            uRot[i] = uRotR[i]*(x-rIn)*(x-rS)/(rR-rIn)/(rR-rS) + uRotIn[i]*(x-rR)*(x-rS)/(rIn-rR)/(rIn-rS) + uRotS[i]*(x-rR)*(x-rIn)/(rS-rR)/(rS-rIn);
                        }
                    } else {
                        uRot = Mat.cross(omegaInlet, elem.currentX);
                    }
                    double Rinl = rhoIn0 * Math.pow((1 + ((kapa - 1) / 2) * Minl * Minl), 1 / (1 - kapa));
                    double Vinl = Minl * Math.sqrt((kapa * p) / Rinl);
                    double uinl = Vinl * Math.cos(attackAngleAlfa) * Math.cos(attackAngleBeta) + uRot[0];
                    double vinl = Vinl * Math.sin(attackAngleAlfa) * Math.cos(attackAngleBeta) + uRot[1];
                    double winl = Vinl * Math.sin(attackAngleAlfa) * Math.sin(attackAngleBeta) + uRot[2];
                    double Einl = p / (kapa - 1) + 0.5 * Rinl * Vinl * Vinl - 0*Rinl * Math.pow(Mat.L2Norm(Mat.cross(omega, elem.currentX)), 2) / 2;
                    WR[0] = Rinl;
                    WR[1] = Rinl * uinl;
                    WR[2] = Rinl * vinl;
                    WR[3] = Rinl * winl;
                    WR[4] = Einl;
                } else { // supersonicky vstup
                    System.arraycopy(WIn, 0, WR, 0, nEqs);
                }
                break;

            case (BoundaryType.OUTLET):
                double ro = WL[0];
                double uo = WL[1] / WL[0];
                double vo = WL[2] / WL[0];
                double wo = WL[3] / WL[0];
                double ao = Math.sqrt(kapa * p / ro);
                double Mo = Math.sqrt(uo * uo + vo * vo + wo * wo) / ao;
                double Eo,
                 pout;
                if (Mo < 1) { // subsonicky vystup
                    pout = pOut;
                } else { // supersonicky vystup
                    pout = p;
                }
                Eo = pout / (kapa - 1) + ro * (uo * uo + vo * vo + wo * wo) / 2 - ro * Math.pow(Mat.L2Norm(Mat.cross(omega, elem.currentX)), 2) / 2;
                WR[0] = ro;
                WR[1] = ro * uo;
                WR[2] = ro * vo;
                WR[3] = ro * wo;
                WR[4] = Eo;
                break;

            case (BoundaryType.INVISCID_WALL): // nevazka stena
                System.arraycopy(WL, 0, WR, 0, nEqs);
                break;
        }
        return WR;
    }
    
    @Override
    public double[] numericalConvectiveFlux(double WL[], double WR[], double[] n, int TT, ElementData elem) {
        WL[0] = limiteRho(WL[0]);
        WR[0] = limiteRho(WR[0]);

        double[] fn = new double[nEqs];
        double p;

        switch (TT) {
            case (BoundaryType.WALL):
            case (BoundaryType.INVISCID_WALL):
            case (BoundaryType.STATOR):
            case (BoundaryType.ROTOR): // stena
                p = pressure(WL, elem.currentX);
                fn[0] = 0;
                fn[1] = p * n[0];
                fn[2] = p * n[1];
                fn[3] = p * n[2];
                fn[4] = 0;
                break;
                

            case (BoundaryType.INLET): // vstup
                fn = convectiveFlux(WR, n, elem);
                break;

            case (BoundaryType.OUTLET): // vystup
                fn = convectiveFlux(WR, n, elem);
                break;

            default: // vnitrni stena
                double pL = pressure(WL, elem.currentX);
                double pR = pressure(WR, elem.currentX);
                double aL = Math.sqrt(kapa * pL / WL[0]);
                double aR = Math.sqrt(kapa * pR / WR[0]);
                double VnL = WL[1] / WL[0] * n[0] + WL[2] / WL[0] * n[1] + WL[3] / WL[0] * n[2];
                double VnR = WR[1] / WR[0] * n[0] + WR[2] / WR[0] * n[1] + WR[3] / WR[0] * n[2];
                double[] fcL = convectiveFlux(WL, n, elem);
                double[] fcR = convectiveFlux(WR, n, elem);
                double S = Math.max(Math.abs(VnL) + aL, Math.abs(VnR) + aR);
                for (int j = 0; j < nEqs; j++) {
                    fn[j] = (fcL[j] + fcR[j] - S * (WR[j] - WL[j])) / 2;
                }
                break;
        }
        return fn;
    }

    @Override
    public double[] convectiveFlux(double[] W, double[] n, ElementData elem) {
        W[0] = limiteRho(W[0]);

        double[] f = new double[nEqs];
        double V = W[1] / W[0] * n[0] + W[2] / W[0] * n[1] + W[3] / W[0] * n[2];
        double p = pressure(W, elem.currentX);
        f[0] = W[0] * V;
        f[1] = W[1] * V + p * n[0];
        f[2] = W[2] * V + p * n[1];
        f[3] = W[3] * V + p * n[2];
        f[4] = (W[4] + p) * V;
        return f;
    }
    
    @Override
    public double[] numericalDiffusiveFlux(double Wc[], double dWc[], double[] n, int TT, ElementData elem) {
        Wc[0] = limiteRho(Wc[0]);

        double[] fvn = diffusiveFlux(Wc, dWc, n, elem);

        if (TT < 0) {
            if (TT == BoundaryType.WALL || TT == BoundaryType.STATOR || TT == BoundaryType.ROTOR) {
                double[] W = boundaryValue(Wc, n, TT, elem);
                fvn[4] = fvn[1] * W[1] / W[0] + fvn[2] * W[2] / W[0] + fvn[3] * W[3] / W[0];
            } else {
                for (int j = 0; j < nEqs; j++) {
                    fvn[j] = 0;
                }
            }
        }
        return fvn;
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

        double p = pressure(W, elem.currentX);
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
        flux[0] = 0;
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
    public double pressure(double[] W) {
        return (kapa - 1) * (W[4] - (W[1] * W[1] + W[2] * W[2] + W[3] * W[3]) / (2 * W[0]));
    }

    @Override
	public double[] stressVector(double[] W, double[] dW, double[] normal) {	
		double p = pressure(W);
		
		double[] stressVector = new double[dim];
		for (int d = 0; d < dim; ++d) {
			stressVector[d] -= p * normal[d];
		}
		
		return stressVector;
	}
    
    @Override
    public double maxEigenvalue(double[] W, ElementData elem) {
        W[0] = limiteRho(W[0]);
        double p = pressure(W);
        double a = Math.sqrt(kapa * p / W[0]);
        double u = Math.sqrt(W[1] * W[1] + W[2] * W[2] + W[3] * W[3]) / W[0];
        return u + a;
    }

    public double pressure(double[] W, double[] r) {
        W[0] = limiteRho(W[0]);
        double p = (kapa - 1) * (W[4] - (W[1] * W[1] + W[2] * W[2] + W[3] * W[3]) / (2 * W[0]) + W[0] * Math.pow(Mat.L2Norm(Mat.cross(omega, r)), 2) / 2);
        if (p < pTol) {
            p = pTol * Math.exp((p - pTol) / pTol);
        }
        return p;
    }

    double limiteRho(double rho) {
        if (rho < rhoTol) {
            return rhoTol * Math.exp((rho - rhoTol) / rhoTol);
        } else {
            return rho;
        }
    }

    @Override
    public double[] combineShockSensors(double[] shock){
        for(int m = 1; m < nEqs; m++){
            shock[m] = shock[0]; // all shock sensors are acording density
        }
        return shock;
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

    public double getTRef() {
        return tRef;
    }
    
    @Override
    public boolean isIPFace(int TT) {
        return TT == BoundaryType.WALL || TT == BoundaryType.STATOR || TT == BoundaryType.ROTOR;
    }
    
    @Override
    public double[] getResults(double[] W, double[] dW, double[] X, String name) {
        double[] uRot;
        switch (name.toLowerCase()) {
            case "mach":
                double absVelocity = .0;
                for (int d = 0; d < dim; ++d) {
                    absVelocity += W[d + 1] * W[d + 1];
                }
                absVelocity = Math.sqrt(absVelocity) / W[0];

                double a = Math.sqrt(kapa * pressure(W) / W[0]);
                return new double[]{absVelocity / a};

            case "density":
                return new double[]{rhoRef * W[0]};

            case "xVelocity":
                uRot = Mat.cross(omega, X);
                return new double[]{velocityRef * (W[1] / W[0] + uRot[0])};

            case "yVelocity":
                uRot = Mat.cross(omega, X);
                return new double[]{velocityRef * (W[2] / W[0] + uRot[1])};

            case "zVelocity":
                uRot = Mat.cross(omega, X);
                return new double[]{velocityRef * (W[3] / W[0] + uRot[2])};

            case "velocity":
                uRot = Mat.cross(omega, X);
                double[] velocity = new double[dim];
                for (int i = 0; i < dim; i++) {
                    velocity[i] = velocityRef * (W[i + 1] / W[0] + uRot[i]);
                }
                return velocity;

            case "temperature":
                throw new UnsupportedOperationException("undefined value" + name);

            case "energy":
                return new double[]{pRef * W[dim + 1]};

            case "pressure":
                return new double[]{pRef * pressure(W, X)};

            default:
                throw new UnsupportedOperationException("undefined value " + name);
        }
    }
}
