package flowpro.user.equation;

import flowpro.api.ElementData;
import flowpro.api.FlowProProperties;
import java.io.IOException;
import java.util.Arrays;

public class NavierStokesVelocityInlet extends NavierStokes {
	
//<<<<<<< HEAD
	protected double height = 0.41;
	
	protected enum InletType {
		VELOCITY, PARABOLA, PARABOLA2
	}
	
	protected InletType inletType;
	
	@Override
	public void init(FlowProProperties props) throws IOException {
        dim = props.getInt("dimension");
		nEqs = dim + 2;        
        isDiffusive = props.getBoolean("isFlowViscous");

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

		boolean velocityInletBC = false;
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
			velocityInletBC = props.containsKey("vIn");
			
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
            } else if (velocityInletBC) {				
				if (props.containsKey("inletType")) {
					String type = props.getString("inletType");
					if (InletType.VELOCITY.name().toLowerCase().equals(type)) {
						inletType = InletType.VELOCITY;
					} else if (InletType.PARABOLA.name().toLowerCase().equals(type)) {
						inletType = InletType.PARABOLA;
					} else if (InletType.PARABOLA2.name().toLowerCase().equals(type)) {
						inletType = InletType.PARABOLA2;
					} else {
						throw new IOException("unknown inlet type \'" + type + "\'");
					}
				} else {
					inletType = InletType.VELOCITY;
				}
				
				if (props.containsKey("rhoIn")) {
					rhoRef = props.getDouble("rhoIn");
				} else if (props.containsKey("TIn")) {
					double TIn = props.getDouble("TIn");
					rhoRef = pRef / (cv * (kapa - 1) * TIn);
				} else {
					throw new IOException("either temperature or density "
							+ "must be prescribed at the inlet");
				}
				
				
				double vRef = props.getDouble("vIn");
				pRef = rhoRef * vRef * vRef;
				
				double vIn = 1;
				double rhoIn = 1;
				double mach = props.getDouble("mach");
				double soundSpeed = vIn / mach;
				double p = rhoIn * soundSpeed * soundSpeed / kapa;
				pOut = p;				
				
//				pOut = props.getDouble("pOut");
			} else {
                throw new IOException("either stagnation boundary conditions (pOut, pIn0 and rhoIn0 or TIn0) or "
                            + "far-field boundary conditions (machInf, pInf and rhoInf or TInf) must be prescribed");
            }
        }

        // other reference values
        if (props.containsKey("lRef")) {
            lRef = props.getDouble("lRef");
//=======
//	double vInlet;
//	
//	@Override
//	public void init(FlowProProperties props) throws IOException {
//        super.init(props);
//        
//        // acceleration
//        if(props.containsKey("vInlet")){
//            vInlet = props.getDouble("vInlet");
//>>>>>>> 999c0327c8c523126197d574d7620db004b66e69
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
				
				if (velocityInletBC) {
					trueRe = props.getDouble("reynolds");
					Re = trueRe;
				} else {
					trueRe = props.getDouble("reynolds");
					Re = trueRe * (rhoRef * velocityRef) / (rhoInf * uInf);
				}
                Pr = props.getDouble("prandtl");
            } else if (props.containsKey("viscosity") && props.containsKey("conductivity")) {
                double viscosity = props.getDouble("viscosity");
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
	
	@Override
    public double[] boundaryValue(double[] WL, double[] n, int TT, ElementData elem) {
		
		switch (TT) {
			case BoundaryType.INLET:
				WL[0] = limiteRho(WL[0]);
				double[] WR = new double[nEqs];
				
				if (!isInletSupersonic) { // subsonic inlet
//<<<<<<< HEAD
					double p = pressure(WL);
					double y = elem.currentX[1];
					
					double inletVelocity = 1;
					if (inletType == InletType.PARABOLA || inletType == InletType.PARABOLA2) {
						inletVelocity = 1.5 * y * (height-y) / (height*height/4);
					}
					
					double t = elem.currentT * tRef;
					if (inletType == InletType.PARABOLA2 && t < 2.0) {
						inletVelocity *= (1 - Math.cos(Math.PI / 2 * t)) / 2;
					}
					double rhoIn = 1;
					
//=======
//					double p = pressure(WL);				
//					double inletVelocity = vInlet/velocityRef; 
//					double rhoIn = 1;				               
//>>>>>>> 999c0327c8c523126197d574d7620db004b66e69
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
				
				return WR;
			
			default:
				return super.boundaryValue(WL, n, TT, elem);
		}          
    }
	
	@Override
    public double[] constInitCondition() {		
        if (isInletSupersonic) {
            return WIn;
        } else {
//<<<<<<< HEAD
			double p = pOut;			
			double inletVelocity = 1;
			double rhoIn = 1;
			
			if (inletType == InletType.PARABOLA2) {
				inletVelocity = 0;
			}
			
//=======
//			double p = pOut;
//			double inletVelocity = vInlet/velocityRef;
//			double rhoIn = 1;				               
//>>>>>>> 999c0327c8c523126197d574d7620db004b66e69
			double E = p / (kapa - 1) + 0.5 * rhoIn * inletVelocity * inletVelocity;
			
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
                    vIn[0] = inletVelocity;
                    break;
                case 2:
                    vIn[0] = inletVelocity * Math.cos(alpha);
                    vIn[1] = inletVelocity * Math.sin(alpha);
                    break;
                case 3:
                    vIn[0] = inletVelocity * Math.cos(alpha) * Math.cos(beta);
                    vIn[1] = inletVelocity * Math.sin(alpha) * Math.cos(beta);
                    vIn[2] = inletVelocity * Math.sin(beta);
            }

            double[] W = new double[nEqs];
            W[0] = rhoIn;
            for (int d = 0; d < dim; ++d) {
                W[d + 1] = rhoIn * vIn[d];
            }
            W[dim + 1] = E;

            return W;
        }
    }
}
