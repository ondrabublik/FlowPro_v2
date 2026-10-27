package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.Equation;
import flowpro.api.FlowProProperties;
import java.io.FileOutputStream;
import java.io.IOException;

public abstract class Aerodynamics implements Equation {

    protected class BoundaryType {

        static final int WALL = -1;
        static final int INLET = -2;
        static final int OUTLET = -3;
        static final int INVISCID_WALL = -4;
        static final int FREE_INLET_OUTLET = -5;
    }

    // tolerance pro hodnotu hustoty a tlaku
    protected static final double RHO_TOL = 1e-2;

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
    protected double etaRef;
    double cp, cv;

    // inlet boundary condition
    protected boolean isInletSupersonic;
    // subsonic inlet boundary condition
    protected final double pIn0 = 1; // static pressure
    protected final double rhoIn0 = 1; // static density
    protected double[] attackAngle; // angle of attack       
    // supersonic inlet boundary condition
    protected double[] WIn;

    // outlet boundary condition
    protected double pOut; // pressure

    // numerical flux
    protected String numericalFluxType;

    // time and time step
    protected double t, dt;
	
	
//	abstract public double pressure(double[] W);

    @Override
    public void setState(double dt, double t) {
        this.dt = dt;
        this.t = t;
    }

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public int nEqs() {
        return nEqs;
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
	public double[] stressVector(double[] W, double[] dW, double[] normal) {	
		double p = pressure(W);
		
		double[] stressVector = new double[dim];
		for (int d = 0; d < dim; ++d) {
			stressVector[d] -= p * normal[d];
		}
		
		return stressVector;
	}

    public void init(FlowProProperties props, int dim, int nEqs, boolean isDiffusive) throws IOException {
        this.nEqs = nEqs;
        this.dim = dim;
        this.isDiffusive = isDiffusive;

        // heat capacity ratio
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

        // inlet and outlet
        if (isInletSupersonic) {
            pRef = props.getDouble("pIn");

            if (props.containsKey("rhoIn")) {
                rhoRef = props.getDouble("rhoIn");
            } else if (props.containsKey("TIn")) {
                double TIn = props.getDouble("TIn");
                rhoRef = pRef / (cv * (kapa - 1) * TIn);
            } else {
                throw new IOException("either temperature or density "
                        + "must be prescribed at the inlet");
            }
        } else { // subsonic inlet
            boolean farFieldBC = props.containsKey("pInf") && props.containsKey("machInf")
                    && (props.containsKey("TInf") ^ props.containsKey("rhoInf"));
            boolean stagnationBC = props.containsKey("pIn0") && props.containsKey("pOut")
                    && (props.containsKey("TIn0") ^ props.containsKey("rhoIn0"));
            if (farFieldBC && !stagnationBC) {
                double pInf = props.getDouble("pInf");
                double machInf = props.getDouble("machInf");
                double rhoInf;
                if (props.containsKey("rhoInf")) {
                    rhoInf = props.getDouble("rhoInf");
                } else {
                    double TInf = props.getDouble("TInf");
                    rhoInf = pInf / (cv * (kapa - 1) * TInf);
                }
                
                pRef = pInf * stagnationStaticPressureRatio(machInf);
                rhoRef = rhoInf * stagnationStaticDensityRatio(machInf);
                
                // outlet
                pOut = pIn0 / stagnationStaticPressureRatio(machInf);                
            } else if (stagnationBC && !farFieldBC) {
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
                
                pOut = props.getDouble("pOut") / pRef;
            } else {
                throw new IOException("either stagnation boundary conditions (pOut, pIn0 and rhoIn0 or TIn0) or "
                            + "far-field boundary conditions (machInf, pInf and rhoInf or TInf) must be prescribed");
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
        attackAngle = null;
        if (props.containsKey("attackAngle")) {
            switch (dim) {
                case 1:
                    attackAngle = null;
                case 2:
                case 3:
                    String var = "attackAngle";
                    attackAngle = props.getDoubleArray(var);
                    if (attackAngle.length != (dim - 1)) {
                        throw new IOException("variable " + var + " must be a vector of " + (dim - 1)
                                + " (= dimension - 1) entries");
                    }
                    break;

                default:
                    throw new IOException("only 1, 2 or 3 dimensions are supported");
            }
        }

        WIn = new double[nEqs];
        if (isInletSupersonic) {
            double velocityIn = props.getDouble("vIn") / velocityRef;

            double[] vIn = new double[dim];
            switch (dim) {
                case 1:
                    vIn[0] = velocityIn;
                    break;

                case 2:
                    vIn[0] = velocityIn * Math.cos(attackAngle[0]);
                    vIn[1] = velocityIn * Math.sin(attackAngle[0]);
                    break;
            }

            WIn[0] = 1;
            for (int d = 0; d < dim; ++d) {
                WIn[d + 1] = vIn[d];
            }

            if (nEqs > dim + 1) {
                double veloSqr = 0;
                for (int d = 0; d < dim; ++d) {
                    veloSqr += vIn[d] * vIn[d];
                }
                double EIn = 1 / (kapa - 1) + 0.5 * veloSqr;
                WIn[3] = EIn;
            }
        } else {
            WIn[0] = -1;   // temporarely                
        }        

        // parameters for the viscous flow
        double trueRe = -1;
        if (isDiffusive) {
            // far-field Reynolds
            double machInf = Math.sqrt(2 / (kapa - 1) * (Math.pow((pIn0 / pOut), (kapa - 1) / kapa) - 1));           
            double rhoInf = rhoRef / stagnationStaticDensityRatio(machInf);
            double pInf = pRef / stagnationStaticPressureRatio(machInf);
            double aInf = Math.sqrt(kapa * pInf / rhoInf);
            double uInf = machInf * aInf;
//            double rhoInf = rhoIn0 / stagnationStaticDensityRatio(machInf);
//            double aInf = Math.sqrt(kapa * pOut / rhoInf);
//            double uInf = machInf * aInf;
//            ReInf = Re * rhoInf * uInf;
            
            if (props.containsKey("reynolds") && props.containsKey("prandtl")
                    && !props.containsKey("viscosity") && !props.containsKey("conductivity")) {

                trueRe = props.getDouble("reynolds");
                Re = trueRe * (rhoRef * velocityRef) / (rhoInf * uInf);
                Pr = props.getDouble("prandtl");
            } else if (props.containsKey("viscosity") && props.containsKey("conductivity")) {
                double viscosity = props.getDouble("viscosity");
                etaRef = viscosity;
                double conductivity = props.getDouble("conductivity");

                Pr = cp * viscosity / conductivity;
                Re = rhoRef * velocityRef * lRef / viscosity;
                trueRe = rhoInf * uInf * lRef / viscosity;
            } else {
                throw new IOException("either the Prandtl and Reynolds numbers, "
                        + "or dynamic viscosity and thermal conductivity must be specified");
            }                        
        } else {
            Re = -1;  // temporarely
            Pr = -1;
        }

        // flux type
        numericalFluxType = "default";
        if (props.containsKey("numericalFlux")) {
            numericalFluxType = props.getString("numericalFlux");
        }

        System.out.println("---- notable physical parameters: ----");
        System.out.printf("heat capacity ratio %.3f\n", kapa);
        if (isDiffusive) {
            System.out.printf("Reynolds number     %.3e\n", trueRe);
            System.out.printf("Prandtl number      %.3f\n", Pr);
        }
        System.out.println("--------------------------------------");
    }
    
    protected double stagnationStaticPressureRatio(double mach) {
        return Math.pow(1 + (kapa - 1) / 2 * mach * mach, kapa / (kapa - 1));
    }
    
    protected double stagnationStaticDensityRatio(double mach) {
        return Math.pow(1 + (kapa - 1) / 2 * mach * mach, 1 / (kapa - 1));
    }

    @Override
    public double maxEigenvalue(double[] W, ElementData elem) {
        W[0] = limiteRho(W[0]);
        double p = pressure(W);
        double a = Math.sqrt(kapa * p / W[0]);
        double v = .0;
        for (int d = 0; d < dim; ++d) {
            v += W[d + 1] * W[d + 1];
        }
        v = Math.sqrt(v) / W[0];

        return v + a;
    }

    public double limiteRho(double rho) {
        if (rho < RHO_TOL) {
            return RHO_TOL * Math.exp((rho - RHO_TOL) / RHO_TOL);
        } else {
            return rho;
        }
    }

    @Override
    public boolean isIPFace(int TT) {
        return (TT == BoundaryType.WALL);
    }

    @Override
    public void saveReferenceValues(String filePath) throws IOException {
        FlowProProperties output = new FlowProProperties();

        output.setProperty("l", Double.toString(lRef));
        output.setProperty("p", Double.toString(pRef));
        output.setProperty("rho", Double.toString(rhoRef));
        output.setProperty("v", Double.toString(velocityRef));
        output.setProperty("t", Double.toString(tRef));

        // far field
        double machInf = Math.sqrt(2 / (kapa - 1) * (Math.pow((1 / pOut), (kapa - 1) / kapa) - 1));
        double rhoInf = Math.pow(1 + ((kapa - 1) / 2) * machInf * machInf, 1 / (1 - kapa));
        double uInf = machInf * Math.sqrt((kapa * pOut) / rhoInf);
        output.setProperty("machInf", Double.toString(machInf));
        output.setProperty("rhoInf", Double.toString(rhoInf));
        output.setProperty("uInf", Double.toString(uInf));

        output.store(new FileOutputStream(filePath), null);
    }

    @Override
    public double[] combineShockSensors(double[] shock){
        for(int m = 1; m < nEqs; m++){
            shock[m] = shock[0]; // all shock sensors are acording density
        }
        return shock;
    }
    
    @Override
    public double[] getReferenceValues() {
        return new double[]{lRef, pRef, rhoRef, velocityRef, tRef};
    }

    @Override
    public double[] getResults(double[] W, double[] dW, double[] X, String name) {
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

            case "xvelocity":
                return new double[]{velocityRef * W[1] / W[0]};

            case "yvelocity":
                if (dim > 1) {
                    return new double[]{velocityRef * W[2] / W[0]};
                } else {
                    throw new UnsupportedOperationException("quantity \"" + name
                            + "\" is only available in two or three dimensions");
                }

            case "zvelocity":
                if (dim > 2) {
                    return new double[]{velocityRef * W[3] / W[0]};
                } else {
                    throw new UnsupportedOperationException("quantity \"" + name
                            + "\" is only available in three dimensions");
                }

            case "velocity":
                double[] velocity = new double[dim];
                for (int i = 0; i < dim; i++) {
                    velocity[i] = velocityRef * W[i + 1] / W[0];
                }
                return velocity;
                
            case "vorticity":
                if (dim == 2) {
                    double dvdx = (dW[2] * W[0] - W[2] * dW[0]) / (W[0]*W[0]);
                    double dudy = (dW[nEqs + 1] * W[0] - W[1] * dW[nEqs]) / (W[0]*W[0]);
                    return new double[] {velocityRef / lRef * (dvdx - dudy)};                
                } else {
                    throw new UnsupportedOperationException("quantity \"" + name
                            + "\" is only available in two dimensions");
                }

            case "pressure":
                return new double[]{pRef * pressure(W)};

//            case "vorticity":
//                double rho = W[0];
//                double[] velocityAux = new double[dim];
//                for (int d = 0; d < dim; ++d) {
//                    velocityAux[d] = W[d + 1] / rho;
//                }
//                double[] velocityJac = new double[dim * dim];
//                for (int d = 0; d < dim; ++d) {
//                    for (int f = 0; f < dim; ++f) {
//                        velocityJac[dim * d + f] = (dW[f * nEqs + d + 1] - dW[f * nEqs] * velocityAux[d]) / rho;
//                    }
//                }
//                switch (dim) {
//                    case 2:
//                        return new double[]{0, 0, velocityJac[dim * 1 + 0] - velocityJac[dim * 0 + 1]};
//                    case 3:
//                        return new double[]{velocityJac[dim * 2 + 1] - velocityJac[dim * 1 + 2], velocityJac[dim * 0 + 2] - velocityJac[dim * 2 + 0], velocityJac[dim * 1 + 0] - velocityJac[dim * 0 + 1]};
//                    default:
//                        throw new UnsupportedOperationException("quantity \"" + name
//                                + "\" is not available in one dimension");
//                }
            default:
                throw new UnsupportedOperationException("unknown quantity \"" + name + "\"");
        }
    }
}
