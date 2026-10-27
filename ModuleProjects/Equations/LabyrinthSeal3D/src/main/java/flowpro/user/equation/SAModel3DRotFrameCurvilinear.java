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

/**
 *
 * @author obublik
 */
public class SAModel3DRotFrameCurvilinear extends NavierStokes3DRotFrame {
    
    double vtIn; // turbulence intensity at the inlet

    // model turbulence
    int model;
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

    public void init(FlowProProperties props) throws IOException {

        // inlet
        vtIn = props.getDouble("vtIn");

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

        super.init(props);
        this.nEqs = 6;
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

            return new double[]{rhoIn, rhoIn * uIn, rhoIn * vIn, rhoIn * wIn, EIn, rhoIn * vtIn};
        }
    }

    //  nevazky tok stenou _____________________________________________________
    @Override
    public double[] numericalConvectiveFlux(double WL[], double WR[], double[] n, int TT, ElementData elem) {
        WL[0] = limiteRho(WL[0]);
        WR[0] = limiteRho(WR[0]);

        double[] fn = new double[nEqs];
        double p;

        switch (TT) {
            case (NavierStokes3DRotFrame.BoundaryType.WALL):
            case (NavierStokes3DRotFrame.BoundaryType.INVISCID_WALL):
            case (NavierStokes3DRotFrame.BoundaryType.STATOR):
            case (NavierStokes3DRotFrame.BoundaryType.ROTOR): // stena
                p = pressure(WL, elem.currentX);
                fn[0] = 0;
                fn[1] = p * n[0];
                fn[2] = p * n[1];
                fn[3] = p * n[2]/elem.currentX[1];
                fn[4] = 0;
                fn[5] = 0;
                break;

            case (NavierStokes3DRotFrame.BoundaryType.INLET): // vstup
                fn = convectiveFlux(WR, n, elem);
                break;

            case (NavierStokes3DRotFrame.BoundaryType.OUTLET): // vystup
                fn = convectiveFlux(WR, n, elem);
                break;

            default: // vnitrni stena
                double pL = pressure(WL, elem.currentX[1]);
                double pR = pressure(WR, elem.currentX[1]);
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

        double r = elem.currentX[1];
        double[] f = new double[nEqs];
        double V = W[1] / W[0] * n[0] + W[2] / W[0] * n[1] + W[3] / W[0] * n[2];
        double p = pressure(W, elem.currentX[1]);
        f[0] = W[0] * W[1] / W[0] * n[0];
        f[1] = W[1] * W[1] / W[0] * n[0] + p * n[0];
        f[2] = W[2] * W[1] / W[0] * n[0];
        f[3] = W[3] * W[1] / W[0] * n[0];
        f[4] = (W[4] + p) * W[1] / W[0] * n[0];
        f[5] = W[5] * W[1] / W[0] * n[0];
        
        f[0] += W[0] * W[2] / W[0] * n[1];
        f[1] += W[1] * W[2] / W[0] * n[1];
        f[2] += W[2] * W[2] / W[0] * n[1] + p * n[1];
        f[3] += W[3] * W[2] / W[0] * n[1];
        f[4] += (W[4] + p) * W[2] / W[0] * n[1];
        f[5] += W[5] * W[2] / W[0] * n[1];
        
        f[0] += (W[0] * W[3] / W[0] * n[2]) / r;
        f[1] += (W[1] * W[3] / W[0] * n[2]) / r;
        f[2] += (W[2] * W[3] / W[0] * n[2]) / r;
        f[3] += (W[3] * W[3] / W[0] * n[2] + p * n[2]) / r;
        f[4] += ((W[4] + p) * W[3] / W[0] * n[2]) / r;
        f[5] += (W[5] * W[3] / W[0] * n[2]) / r;

        return f;
    }

    @Override
    public double[] diffusiveFlux(double[] W, double[] dW, double[] n, ElementData elem) {
        W[0] = limiteRho(W[0]);

        double r = elem.currentX[1];
        double[] fvn = new double[nEqs];
        double rho = W[0];
        double u = W[1] / rho;
        double v = W[2] / rho;
        double w = W[3] / rho;
        double rx = dW[0];
        double ry = dW[nEqs];
        double rz = dW[2 * nEqs];
        double ux = 1 / rho * (dW[1] - rx * u);
        double uy = 1 / rho * (dW[nEqs + 1] - ry * u);
        double uz = 1 / rho * (dW[2 * nEqs + 1] - rz * u);
        double vx = 1 / rho * (dW[2] - rx * v);
        double vy = 1 / rho * (dW[nEqs + 2] - ry * v);
        double vz = 1 / rho * (dW[2 * nEqs + 2] - rz * v);
        double wx = 1 / rho * (dW[3] - rx * w);
        double wy = 1 / rho * (dW[nEqs + 3] - ry * w);
        double wz = 1 / rho * (dW[2 * nEqs + 3] - rz * w);
        double Ex = dW[4];
        double Ey = dW[nEqs + 4];
        double Ez = dW[2 * nEqs + 4];
        double p = pressure(W, elem.currentX);
        double px = (kapa - 1) * (Ex - 0.5 * rx * (u * u + v * v + w * w) - rho * (u * ux + v * vx + w * wx));
        double py = (kapa - 1) * (Ey - 0.5 * ry * (u * u + v * v + w * w) - rho * (u * uy + v * vy + w * wy));
        double pz = (kapa - 1) * (Ez - 0.5 * rz * (u * u + v * v + w * w) - rho * (u * uz + v * vz + w * wz));
        double prx = (rho * px - p * rx) / (rho * rho);
        double pry = (rho * py - p * ry) / (rho * rho);
        double prz = (rho * pz - p * rz) / (rho * rho);

        double vt = W[5] / rho;
        double vtx = 1 / rho * (dW[5] - rx * vt);
        double vty = 1 / rho * (dW[nEqs + 5] - ry * vt);
        double vtz = 1 / rho * (dW[2 * nEqs + 5] - rz * vt);

        double Sxx = 1.0 / 3 * (2*ux - vy  - (wz + v) / r);  // ux - 1.0 / 3 * (ux + vy + wz);
        double Syy = 1.0 / 3 * (-ux + 2*vy - (wz + v) / r);  // vy - 1.0 / 3 * (ux + vy + wz);
        double Szz = 1.0 / 3 * (-ux - vy + 2*(wz + v) / r);  // wz - 1.0 / 3 * (ux + vy + wz);
        double Sxy = 0.5 * (vx + uy);
        double Sxz = 0.5 * (wx + uz/r);
        double Syz = 0.5 * (wy + (vz-w)/r);

        double txx = 2 * Sxx;
        double tyy = 2 * Syy;
        double tzz = 2 * Szz;
        double txy = 2 * Sxy;
        double txz = 2 * Sxz;
        double tyz = 2 * Syz;

        // turbulence
        if (vt < 0) {
            vt = 0;
        }

        double xi = rho * vt; // vt/v
        double fv1 = xi * xi * xi / (xi * xi * xi + cv1 * cv1 * cv1);
        double mut = rho * vt * fv1;
        double Txx = 2 * mut * Sxx;
        double Tyy = 2 * mut * Syy;
        double Tzz = 2 * mut * Szz;
        double Txy = 2 * mut * Sxy;
        double Txz = 2 * mut * Sxz;
        double Tyz = 2 * mut * Syz;

        fvn[0] = 0;
        fvn[1] = 1 / Re * (txx + Txx) * n[0];
        fvn[2] = 1 / Re * (txy + Txy) * n[0];
        fvn[3] = 1 / Re * (txz + Txz) * n[0];
        fvn[4] = (1 / Re * (u * (txx + Txx) + v * (txy + Txy) + w * (txz + Txz) + kapa / (kapa - 1) * (1 / Pr + mut / Prt) * prx)) * n[0];
        fvn[5] = 1 / (Re * sigma) * (1 + rho * vt) * vtx * n[0];

        fvn[1] = fvn[1] + 1 / Re * (txy + Txy) * n[1];
        fvn[2] = fvn[2] + 1 / Re * (tyy + Tyy) * n[1];
        fvn[3] = fvn[3] + 1 / Re * (tyz + Tyz) * n[1];
        fvn[4] = fvn[4] + (1 / Re * (u * (txy + Txy) + v * (tyy + Tyy) + w * (tyz + Tyz) + kapa / (kapa - 1) * (1 / Pr + mut / Prt) * pry)) * n[1];
        fvn[5] = fvn[5] + 1 / (Re * sigma) * (1 + rho * vt) * vty * n[1];

        fvn[1] = (fvn[1] + 1 / Re * (txz + Txz) * n[2]) / r;
        fvn[2] = (fvn[2] + 1 / Re * (tyz + Tyz) * n[2]) / r;
        fvn[3] = (fvn[3] + 1 / Re * (tzz + Tzz) * n[2]) / r;
        fvn[4] = (fvn[4] + (1 / Re * (u * (txz + Txz) + v * (tyz + Tyz) + w * (tzz + Tzz) + kapa / (kapa - 1) * (1 / Pr + mut / Prt) * prz)) * n[2]) / r;
        fvn[5] = (fvn[5] + 1 / (Re * sigma) * (1 + rho * vt) * vtz * n[2]) / r;
        return fvn;
    }

    @Override
    public double[] sourceTerm(double[] W, double[] dW, ElementData elem) { // zdrojovy clen
        W[0] = limiteRho(W[0]);
        double r = elem.currentX[1];

        // rot frame
        double[] sour = new double[nEqs];
        double Fcentrifugal = -omega[0] * omega[0] * elem.currentX[1] * W[0];
        sour[1] = 0;
        sour[2] = -2.0 * W[3] * omega[0] + Fcentrifugal;
        sour[3] = 2.0 * W[2] * omega[0];
        
        // sa model
        double rho = W[0];
        double u = W[1] / rho;
        double v = W[2] / rho;
        double w = W[3] / rho;
        double rx = dW[0];
        
        double ry = dW[nEqs];
        double rz = dW[2 * nEqs];
        double ux = 1 / rho * (dW[1] - rx * u);
        double uy = 1 / rho * (dW[nEqs + 1] - ry * u);
        double uz = 1 / rho * (dW[2 * nEqs + 1] - rz * u);
        double vx = 1 / rho * (dW[2] - rx * v);
        double vy = 1 / rho * (dW[nEqs + 2] - ry * v);
        double vz = 1 / rho * (dW[2 * nEqs + 2] - rz * v);
        double wx = 1 / rho * (dW[3] - rx * w);
        double wy = 1 / rho * (dW[nEqs + 3] - ry * w);
        double wz = 1 / rho * (dW[2 * nEqs + 3] - rz * w);

        double vt = W[5] / rho;
        double vtx = 1 / rho * (dW[5] - rx * vt);
        double vty = 1 / rho * (dW[nEqs + 5] - ry * vt);
        double vtz = 1 / rho * (dW[2 * nEqs + 5] - rz * vt);

        double Sxx = 1.0 / 3 * (2*ux - vy  - (wz + v) / rho);  // ux - 1.0 / 3 * (ux + vy + wz);
        double Syy = 1.0 / 3 * (-ux + 2*vy - (wz + v) / rho);  // vy - 1.0 / 3 * (ux + vy + wz);
        double Szz = 1.0 / 3 * (-ux - vy + 2*(wz + v) / rho);  // wz - 1.0 / 3 * (ux + vy + wz);
        double Sxy = 0.5 * (vx + uy);
        double Sxz = 0.5 * (wx + uz/rho);
        double Syz = 0.5 * (wy + (vz-w)/rho);
        double Smag = Math.sqrt(Sxx * Sxx + Syy * Syy + Szz * Szz + 2 * Sxy * Sxy + 2 * Sxz * Sxz + 2 * Syz * Syz);

        double txx = 2 * Sxx;
        double tyy = 2 * Syy;
        double tzz = 2 * Szz;
        double txy = 2 * Sxy;
        double txz = 2 * Sxz;
        double tyz = 2 * Syz;
        
        // turbulence
        if (vt < 0) {
            vt = 0;
        }

        double D = elem.currentWallDistance;

        double xi = rho * vt; // vt/v
        double ft2 = 0; //ct3*Math.exp(-ct4*xi*xi);
        double ft1 = 0;
        double fv1 = xi * xi * xi / (xi * xi * xi + cv1 * cv1 * cv1);
        double fv2 = 1 - xi / (1 + xi * fv1);
        double Om = Math.sqrt((uy - vx) * (uy - vx) + (uz - wx) * (uz - wx) + (wy - vz) * (wy - vz));
//         double S = Om + C_prod*min(0,Om-Smag) + 1/Re*vt/(ka*ka*D*D)*fv2;
        double S = Om + 1 / Re * vt / (ka * ka * D * D) * fv2;
        double rt = vt / (Re * S * ka * ka * D * D);
        if (rt > 10) {
            rt = 10;
        }
        double g = rt + cw2 * (Math.pow(rt, 6.0) - rt);
        double fw = g * Math.pow((1 + Math.pow(cw3, 6.0)) / (Math.pow(g, 6.0) + Math.pow(cw3, 6.0)), 1.0 / 6);

        sour[5] = sour[5] + 1 / Re * rho * cb2 * (vtx * vtx + vty * vty + vtz * vtz) + cb1 * (1 - ft2) * rho * S * vt - 1 / Re * (cw1 * fw - cb1 / (ka * ka) * ft2) / rho * (rho * vt / D) * (rho * vt / D); // + r*Re*ft1*dU*dU;

        
        // curvilinear
        sour[0] -= (rho*v) / r;
        sour[1] -= (rho*u*v - txy) / r;
        sour[2] -= (rho*v*v - rho*w*w - tyy - tzz) / r;
        sour[3] -= (rho*v*w - 2*tyz) / r;
        sour[4] -= (W[4] * W[3] / W[0] - u*txy - v*tyy - w*tyz) / r;
        
        return sour;
    }

    @Override
    public double[] boundaryValue(double[] WL, double[] n, int TT, ElementData elem) {
        WL[0] = limiteRho(WL[0]);

        double[] WR = new double[nEqs];
        double p = pressure(WL, elem.currentX[1]);
        switch (TT) {
            case (NavierStokes3DRotFrame.BoundaryType.WALL): // stena
                if (isDiffusive) {
                    WR[0] = WL[0];
                    WR[1] = 0;
                    WR[2] = 0;
                    WR[3] = 0;
                    WR[4] = p / (kapa - 1);
                    WR[5] = 0;
                } else {
                    System.arraycopy(WL, 0, WR, 0, nEqs);
                }
                break;

            case (NavierStokes3DRotFrame.BoundaryType.STATOR): // stator
                if (isDiffusive) {
                    double uRot = omegaStator[0] * elem.currentX[1];
                    WR[0] = WL[0];
                    WR[1] = 0;
                    WR[2] = 0;
                    WR[3] = WR[0] * uRot;
                    WR[4] = p / (kapa - 1) + (WR[1] * WR[1] + WR[2] * WR[2] + WR[3] * WR[3]) / (2 * WR[0]) - WR[0] * Math.pow(omega[0] * elem.currentX[1], 2) / 2;
                    WR[5] = 0;
                } else {
                    System.arraycopy(WL, 0, WR, 0, nEqs);
                }
                break;

            case (NavierStokes3DRotFrame.BoundaryType.ROTOR): // rotor
                if (isDiffusive) {
                    double uRot = omegaRotor[0] * (elem.currentX[1] - rotorExcentricity[1]);
                    WR[0] = WL[0];
                    WR[1] = 0;
                    WR[2] = 0;
                    WR[3] = WR[0] * uRot;
                    WR[4] = p / (kapa - 1) + (WR[1] * WR[1] + WR[2] * WR[2] + WR[3] * WR[3]) / (2 * WR[0]) - WR[0] * Math.pow(omega[0] * elem.currentX[1], 2) / 2;
                    WR[5] = 0;
                } else {
                    System.arraycopy(WL, 0, WR, 0, nEqs);
                }
                break;

            case (NavierStokes3DRotFrame.BoundaryType.INLET):
                if (WIn[0] == -1) { // subsonicky vstup
                    double Minl;
                    if (p > pIn0) {
                        Minl = 0;
                    } else {
                        Minl = Math.sqrt((2 / (kapa - 1)) * (-1 + Math.pow(pIn0 / p, (kapa - 1) / kapa)));
                    }
                    double uRot = 0;
                    if (swirlProfileRadius != null) {
                        double uRotR = omegaRotor[0] * elem.currentX[1];
                        double uRotS = omegaStator[0] * elem.currentX[1];
                        double uRotIn = omegaInlet[0] * elem.currentX[1];
                        double rR = swirlProfileRadius[0]; // rotor radius
                        double rS = swirlProfileRadius[1]; // stator radius
                        double rIn = (rR + rS) / 2;
                        double x = elem.currentX[1];
                        uRot = uRotR*(x-rIn)*(x-rS)/(rR-rIn)/(rR-rS) + uRotIn*(x-rR)*(x-rS)/(rIn-rR)/(rIn-rS) + uRotS*(x-rR)*(x-rIn)/(rS-rR)/(rS-rIn);
                    } else {
                        uRot = omegaInlet[0] * elem.currentX[1];
                    }
                    double Rinl = rhoIn0 * Math.pow((1 + ((kapa - 1) / 2) * Minl * Minl), 1 / (1 - kapa));
                    double Vinl = Minl * Math.sqrt((kapa * p) / Rinl);
                    double uinl = Vinl * Math.cos(attackAngleAlfa) * Math.cos(attackAngleBeta);
                    double vinl = Vinl * Math.sin(attackAngleAlfa) * Math.cos(attackAngleBeta);
                    double winl = Vinl * Math.sin(attackAngleAlfa) * Math.sin(attackAngleBeta) + uRot;
                    double Einl = p / (kapa - 1) + 0.5 * Rinl * Vinl * Vinl - 0*Rinl * Math.pow(omega[0] *  elem.currentX[1], 2) / 2;
                    WR[0] = Rinl;
                    WR[1] = Rinl * uinl;
                    WR[2] = Rinl * vinl;
                    WR[3] = Rinl * winl;
                    WR[4] = Einl;
                    WR[5] = Rinl * vtIn;
                } else { // supersonicky vstup
                    WR[0] = WIn[0];
                    WR[1] = WIn[1];
                    WR[2] = WIn[2];
                    WR[3] = WIn[3];
                    WR[4] = WIn[4];
                    WR[5] = WIn[0] * WIn[5];
                }
                break;

            case (NavierStokes3DRotFrame.BoundaryType.OUTLET):
                double ro = WL[0];
                double uo = WL[1] / WL[0];
                double vo = WL[2] / WL[0];
                double wo = WL[3] / WL[0];
                double vtOut = WL[5] / WL[0];
                double ao = Math.sqrt(kapa * p / ro);
                double Mo = Math.sqrt(uo * uo + vo * vo + wo * wo) / ao;
                double Eo,
                 pout;
                if (Mo < 1) { // subsonicky vystup
                    pout = pOut;
                } else { // supersonicky vystup
                    pout = p;
                }
                Eo = pout / (kapa - 1) + ro * (uo * uo + vo * vo + wo * wo) / 2 - ro * Math.pow(omega[0] * elem.currentX[1], 2) / 2;
                WR[0] = ro;
                WR[1] = ro * uo;
                WR[2] = ro * vo;
                WR[3] = ro * wo;
                WR[4] = Eo;
                WR[5] = ro * vtOut;
                break;

            case (NavierStokes3DRotFrame.BoundaryType.INVISCID_WALL): // nevazka stena
                System.arraycopy(WL, 0, WR, 0, nEqs);
                break;
        }
        return WR;
    }

    public double pressure(double[] W, double r) {
        W[0] = limiteRho(W[0]);
        double p = (kapa - 1) * (W[4] - (W[1] * W[1] + W[2] * W[2] + W[3] * W[3]) / (2 * W[0]) + W[0] * Math.pow(omega[0] * r, 2) / 2);
        if (p < pTol) {
            p = pTol * Math.exp((p - pTol) / pTol);
        }
        return p;
    }
    
    @Override
    public boolean isSourcePresent() {
        return true;
    }

    @Override
    public boolean isIPFace(int TT) {
        if (TT == NavierStokes3DRotFrame.BoundaryType.WALL || TT == NavierStokes3DRotFrame.BoundaryType.STATOR || TT == NavierStokes3DRotFrame.BoundaryType.ROTOR) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public double[] getResults(double[] W, double[] dW, double[] X, String name) {
        double uRot;
        switch (name) {
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
                return new double[]{velocityRef * (W[1] / W[0])};

            case "yVelocity":
                return new double[]{velocityRef * (W[2] / W[0])};

            case "zVelocity":
                uRot = omega[0] *  X[1];
                return new double[]{velocityRef * (W[3] / W[0] + uRot)};

            case "velocity":
                uRot = omega[0] *  X[1];
                double[] velocity = new double[dim];
                velocity[0] = velocityRef * (W[1] / W[0]);
                velocity[1] = velocityRef * (W[2] / W[0]);
                velocity[2] = velocityRef * (W[3] / W[0] + uRot);

                return velocity;

            case "temperature":
                throw new UnsupportedOperationException("undefined value" + name);

            case "energy":
                return new double[]{pRef * W[dim + 1]};

            case "pressure":
                return new double[]{pRef * pressure(W)};

            default:
                throw new UnsupportedOperationException("undefined value " + name);
        }
    }
}

