package flowpro.core;

import flowpro.api.DomainTransformationObject;
import flowpro.api.FlowProProperties;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.Arrays;

/**
 *
 * @author ales
 */
public class Parameters implements Serializable {

    public static final String NETWORK_PARAM_FILE = "network/parameters.txt";
    public static final String PC_LIST_FILE = "network/pcNames.txt";

    public FlowProProperties props;

    public final double h = 1e-8;
//    public static final double R_eps = 1e-2;  // tolerance pro hodnotu hustoty

    public final boolean continueComputation;
    public final double residuum;
    public final double tEnd;
    public final int steps;         // pocet casovych kroku
    public final int saveRate;
    public final double timeSaveRate;
    public final boolean animation;
    public final boolean curvedBoundary;

    public double cfl;        // max CFL cislo
    public boolean varyCFL;
    public double maxCFL;     // upper limit of the adaptive CFL
	public double dt;
	public boolean isTimeStepFixed;
    public int order;         // rad metody v prostoru
    public final int timeOrder;         // rad metody v case
    public final int nThreads;      // pocet vlaken
    public final int newtonIters;   // pocet vnitrnich iteraci    
    public final double newtonIterTol;
    public final double meshScale; // mesh scale
    public final double lRef; // reference length
    public final boolean[] scaleDims; // dimensions to scale

    // transformation object
    public DomainTransformationObject domainTransformationObject;

    // integration rules
    public int volumeQuardatureOrder;
    public int faceQuardatureOrder;
    // true when the quadrature order is not given explicitly and is derived
    // from the (possibly local) order of the elements
    public boolean volumeQuadratureOrderAuto;
    public boolean faceQuadratureOrderAuto;

    // solver type
    public String linearSolver;
    public String preconditioner;
    public String elementOrdering; // numbering of the degrees of freedom
    public double iterativeSolverTol;

    // single time / dual time
    public String spatialMethod;
    public String timeMethod;
    public String parallelSolverType;
    public String parallelPreconditioner;

    // dynamics parameters
    public boolean movingMesh;
    public boolean gcl; // discrete geometric conservation law on a moving mesh
    public boolean positivityLimiter; // positivity preserving limiter (Zhang, Shu)
    public String movementType;
    public double bodyLength; // length of body in z direction

    // dynamics model
    public String dynamicsModel;
    public String meshDeformationType;

    /* parallel mode */
    public final boolean parallelMode;
    public final int overlap;
    public final int schwarzIters;
    public final double schwarzTol;
    public final int slavePort;
    public final int fetcherPort;
    public final String pcFilterFile;
    public final String publicKeyFile;

    // solution monitor
    public String solutionMonitor;
    public boolean solutionMonitorOn;
    public boolean solutionAverage; // solution average in elementData

    // external field
    public boolean externalField;

    /**
     * @return true if the parameter is defined and its value is not empty
     * (template files contain lines such as "pIn = % inlet pressure", and an
     * empty value given on the command line, -p CFL=, removes the parameter)
     */
    private boolean has(String key) {
        return props.containsKey(key) && !ParameterRegistry.stripComment(props.getProperty(key)).isEmpty();
    }

    public Parameters(String parameterFilePath, boolean parallelMode, URL[] jarURLList) throws IOException {
        try {
            this.parallelMode = parallelMode;
            props = new FlowProProperties();
            props.load(parameterFilePath);

            continueComputation = props.getBoolean("continueComputation");
            steps = props.getInt("steps");

            if (has("residuum")) {
                residuum = props.getDouble("residuum");
            } else {
                residuum = -1.0;
            }
            
            if (has("meshScale")) {
                meshScale = props.getDouble("meshScale");
            } else {
                meshScale = 1;
            }
            
            if (has("lRef")) {
                lRef = props.getDouble("lRef");
            } else {
                lRef = 1;
            }

            if (has("scaleDims")) {
                scaleDims = props.getBooleanArray("scaleDims");
            } else {
                scaleDims = new boolean[3];
                Arrays.fill(scaleDims, Boolean.TRUE);
            }
            
            if (has("tEnd")) {
                tEnd = props.getDouble("tEnd");
            } else {
                tEnd = Double.MAX_VALUE;
            }

            if (has("saveRate")) {
                saveRate = props.getInt("saveRate");
            } else {
                saveRate = Integer.MAX_VALUE;
            }
            
            if (has("timeSaveRate")) {
                timeSaveRate = props.getDouble("timeSaveRate");
            } else {
                timeSaveRate = Double.MAX_VALUE;
            }

            if (has("animation")) {
                animation = props.getBoolean("animation");
            } else {
                animation = false;
            }

            if (has("spatialMethod")) {
                spatialMethod = props.getString("spatialMethod");
            } else {
                flowpro.core.Mesh.SpatialMethodType.help();
                throw new IOException("parameter spatialMethod must be defined");
            }

            if (has("order")) {
                order = props.getInt("order");
            } else {
                order = -1;
            }

            if (has("timeOrder")) {
                timeOrder = props.getInt("timeOrder");
            } else {
                timeOrder = 1;
            }

            if (has("timeMethod")) {
                timeMethod = props.getString("timeMethod");
            } else {
                flowpro.core.element.Element.TimeIntegrationElementType.help();
                throw new IOException("parameter timeMethod must be defined");
            }

            volumeQuardatureOrder = order;
            volumeQuadratureOrderAuto = true;
            if (has("volumeQuardatureOrder")) {
                volumeQuardatureOrder = props.getInt("volumeQuardatureOrder");
                volumeQuadratureOrderAuto = false;
            }

            faceQuardatureOrder = order;
            faceQuadratureOrderAuto = true;
            if (has("faceQuardatureOrder")) {
                faceQuardatureOrder = props.getInt("faceQuardatureOrder");
                faceQuadratureOrderAuto = false;
            }

			if (has("CFL")) {
				isTimeStepFixed = false;				
				cfl = props.getDouble("CFL");
				if (cfl <= 0) {
					varyCFL = true;
					cfl = 1;
				} else {
					varyCFL = false;
				}
				maxCFL = has("maxCFL") ? props.getDouble("maxCFL") : 1e4;
			} else if (has("dt")) {
				isTimeStepFixed = true;
				dt = props.getDouble("dt");
			} else {
				throw new IOException("either parameter CFL or dt must be defined");
			}

            nThreads = props.getInt("threads");
            newtonIters = props.getInt("newtonIters");

            if (has("newtonIterTol")) {
                newtonIterTol = props.getDouble("newtonIterTol");
            } else {
                newtonIterTol = 1e-4;
            }

            // domain transformation object
            domainTransformationObject = null;
            if (has("domainTransformationObject")) {
                domainTransformationObject = (new DomainTransformationObjectFactory()).getDomainTransformationObject(parameterFilePath, jarURLList);
            }

            // dynamics parameters
            movingMesh = false;
            if (has("movingMesh")) {
                movingMesh = props.getBoolean("movingMesh");
            }
            gcl = has("gcl") ? props.getBoolean("gcl") : true;
            positivityLimiter = has("positivityLimiter") ? props.getBoolean("positivityLimiter") : true;

            // iterative solver setting
            if (has("linearSolver")) {
                linearSolver = props.getString("linearSolver");
            } else {
                flowpro.core.LinearSolvers.LinearSolver.LinearSolverType.help();
                throw new IOException("parameter linearSolver must be defined");
            }

            if (has("preconditioner")) {
                preconditioner = props.getString("preconditioner");
            } else {
                flowpro.core.LinearSolvers.preconditioners.Preconditioner.PreconditionerType.help();
                throw new IOException("parameter preconditioner must be defined");
            }

            elementOrdering = has("elementOrdering") ? props.getString("elementOrdering").trim().toLowerCase() : "rcm";
            try {
                ElementOrdering.Type.valueOf(elementOrdering);
            } catch (IllegalArgumentException ex) {
                ElementOrdering.Type.help();
                throw new IOException("unknown element ordering " + elementOrdering);
            }

            if (has("iterativeSolverTol")) {
                iterativeSolverTol = props.getDouble("iterativeSolverTol");
            } else {
                iterativeSolverTol = 1e-2;
            }

            if (parallelMode) {
                if (has("parallelSolverType")) {
                    parallelSolverType = props.getString("parallelSolverType");
                } else {
                    flowpro.core.solver.MasterSolver.MasterSolverType.help();
                    throw new IOException("parameter parallelSolverType must be defined");
                }

                if (has("parallelPreconditioner")) {
                    parallelPreconditioner = props.getString("parallelPreconditioner");
                } else {
                    flowpro.core.DistributedLinearSolver.preconditioner.ParallelPreconditioner.ParallelPreconditionerType.help();
                    throw new IOException("parameter parallelPreconditioner must be defined");
                }
            }

            if (has("curvedBoundary")) {
                curvedBoundary = props.getBoolean("curvedBoundary");
            } else {
                curvedBoundary = false;
            }

            if (has("dynamicsModel")) {
                dynamicsModel = props.getString("dynamicsModel");
            } else {
                dynamicsModel = "none";
            }

            if (has("meshDeformationType")) {
                meshDeformationType = props.getString("meshDeformationType");
            } else {
                meshDeformationType = "none";
            }

            /* parallel mode */
            if (parallelMode) {
                overlap = props.getInt("overlap");

                if (has("schwarzIters")) {
                    schwarzIters = props.getInt("schwarzIters");
                } else {
                    schwarzIters = 1;
                }

                if (has("schwarzTol")) {
                    schwarzTol = props.getDouble("schwarzTol");
                } else {
                    schwarzTol = 1e-6;
                }
            } else {
                overlap = -1;
                schwarzIters = -1;
                schwarzTol = 0.0;
            }

            solutionMonitorOn = false;
            if (has("solutionMonitor")) {
                solutionMonitor = props.getString("solutionMonitor");
                solutionMonitorOn = true;
            } else {
                solutionMonitor = null;
            }

            solutionAverage = false;
            if (has("solutionAverage")) {
                solutionAverage = props.getBoolean("solutionAverage");
            }

        } catch (IOException ex) {
            throw new IOException("file " + parameterFilePath
                    + " has a wrong format: " + ex.getMessage());
        }

        try {
            if (parallelMode) {
                FlowProProperties netProps = new FlowProProperties();
                netProps.load(NETWORK_PARAM_FILE);
                slavePort = netProps.getInt("port");
                fetcherPort = netProps.getInt("fetcherPort");
                pcFilterFile = netProps.getString("pcFilterFile");
                publicKeyFile = netProps.getString("publicKeyFile");
            } else {
                slavePort = -1;
                fetcherPort = -1;
                pcFilterFile = null;
                publicKeyFile = null;
            }
        } catch (IOException ex) {
            throw new IOException("file " + NETWORK_PARAM_FILE
                    + " has a wrong format: " + ex.getMessage());
        }
    }

    /**
     * Sets the global order of accuracy (e.g. the maximal order from file
     * order.txt), the automatic quadrature orders are updated as well.
     */
    public void setOrder(int order) {
        this.order = order;
        if (volumeQuadratureOrderAuto) {
            volumeQuardatureOrder = order;
        }
        if (faceQuadratureOrderAuto) {
            faceQuardatureOrder = order;
        }
    }

    /**
     * @param elemOrder order of accuracy of the element
     * @return order of the volume quadrature which is to be used for the
     * element, unless the order is given explicitly it equals to the order of
     * the element (previously the global order was used, which was -1 when
     * the order was given only in file order.txt)
     */
    public int volumeQuadratureOrder(int elemOrder) {
        return volumeQuadratureOrderAuto ? elemOrder : volumeQuardatureOrder;
    }

    public int faceQuadratureOrder(int elemOrder) {
        return faceQuadratureOrderAuto ? elemOrder : faceQuardatureOrder;
    }
}
