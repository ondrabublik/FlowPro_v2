package flowpro.core;

import flowpro.api.Dynamics;
import flowpro.api.Mat;
import flowpro.api.Equation;
import flowpro.api.SolutionMonitor;
import flowpro.core.curvedBoundary.CurvedBoundary;
import flowpro.core.quadrature.QuadratureCentral;
import flowpro.core.curvedBoundary.FaceCurvature;
import flowpro.core.parallel.Domain;
import flowpro.core.elementType.ElementType;
import flowpro.core.meshDeformation.*;
import litempi.MPIException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import flowpro.api.FlowProProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import flowpro.core.solver.MasterSolver;
import flowpro.core.solver.SlaveSolver;
import javax.script.ScriptException;

/**
 *
 * @author ales
 */
public class FlowProMain {

    private static URL[] jarURLList;
    private static final Logger LOG = LoggerFactory.getLogger(FlowProMain.class);

    public static final String ARG_FILE_NAME = "args.txt";
    public static final String PARAMETER_FILE_NAME = "parameters.txt";
    public static final String STATE_FILE_NAME = "state.txt";
    public static final String REF_VALUE_FILE_NAME = "referenceValues.txt";

    public final String meshPath;
    public final String simulationPath;
    public final String geometryName;
    public final String simulationName;

    /**
     * Reads the geometry and the simulation from file args.txt.
     */
    public FlowProMain() throws IOException {
        this(null, null);
    }

    /**
     * @param geometryName name of the geometry (directory in simulations), if
     * null it is read from file args.txt
     * @param simulationName name of the simulation (subdirectory of the
     * geometry), if null it is read from file args.txt
     */
    public FlowProMain(String geometryName, String simulationName) throws IOException {
        if (geometryName == null || simulationName == null) {
            String[] fromFile = readArgsFile();
            if (geometryName == null) {
                geometryName = fromFile[0];
            }
            if (simulationName == null) {
                simulationName = fromFile[1];
            }
        }
        this.geometryName = geometryName;
        this.simulationName = simulationName;
        LOG.info("geometry: {}, simulation: {}", geometryName, simulationName);

        meshPath = "simulations/" + geometryName + "/mesh/";
        simulationPath = "simulations/" + geometryName + "/" + simulationName + "/";
        if (!new File(simulationPath + PARAMETER_FILE_NAME).isFile()) {
            throw new FileNotFoundException("file " + simulationPath + PARAMETER_FILE_NAME + " does not exist"
                    + " (use 'java -jar FlowPro.jar list' to list the available simulations)");
        }
    }

    private static String[] readArgsFile() throws IOException {
        File file = new File(ARG_FILE_NAME);
        if (!file.exists()) {
            String geo = "examples/GAMM";
            String sim = "default";
            try (PrintWriter writer = new PrintWriter(ARG_FILE_NAME, "UTF-8")) {
                writer.println(geo + " " + sim);
            }
            LOG.warn("file " + ARG_FILE_NAME + " does not exist, creating it with geometry '" + geo
                    + "' and simulation '" + sim + "'");
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(ARG_FILE_NAME))) {
            String line = reader.readLine();
            if (line == null || line.trim().isEmpty()) {
                throw new IOException("file " + ARG_FILE_NAME + " is empty");
            }
            String[] args = line.trim().split("\\s+");
            if (args.length != 2) {
                throw new IOException("file " + ARG_FILE_NAME
                        + " must contain one line with exactly two arguments: geometry simulation");
            }
            return args;
        }
    }

    /**
     * Parsed command line: command, positional arguments and options which
     * are common for all the commands.
     */
    static class CommandLine {

        String command;
        final List<String> positional = new ArrayList<>();
        final List<String> rest = new ArrayList<>(); // arguments which are not options of FlowProMain
        String geometry;
        String simulation;
        Integer domains;
        final Map<String, String> overrides = new LinkedHashMap<>();

        static CommandLine parse(String[] args) {
            if (args == null || args.length == 0) {
                throw new IllegalArgumentException("no command was specified");
            }
            CommandLine cl = new CommandLine();
            cl.command = args[0].toLowerCase();
            cl.rest.add(args[0]);
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-g":
                    case "--geometry":
                        cl.geometry = value(args, ++i, arg);
                        break;
                    case "-s":
                    case "--simulation":
                        cl.simulation = value(args, ++i, arg);
                        break;
                    case "-d":
                    case "--domains":
                        cl.domains = parseInt(value(args, ++i, arg), arg);
                        break;
                    case "-p":
                    case "--set":
                        String keyValue = value(args, ++i, arg);
                        int eq = keyValue.indexOf('=');
                        if (eq <= 0) {
                            throw new IllegalArgumentException("option " + arg + " expects key=value, got " + keyValue);
                        }
                        cl.overrides.put(keyValue.substring(0, eq).trim(), keyValue.substring(eq + 1).trim());
                        break;
                    default:
                        if (arg.startsWith("--geometry=")) {
                            cl.geometry = arg.substring(11);
                        } else if (arg.startsWith("--simulation=")) {
                            cl.simulation = arg.substring(13);
                        } else {
                            if (!arg.startsWith("-")) {
                                cl.positional.add(arg);
                            }
                            cl.rest.add(arg);
                        }
                }
            }
            return cl;
        }

        private static String value(String[] args, int i, String option) {
            if (i >= args.length) {
                throw new IllegalArgumentException("option " + option + " requires a value");
            }
            return args[i];
        }

        private static int parseInt(String str, String what) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(what + " must be an integer, got " + str);
            }
        }

        int positionalInt(int index, int defaultValue, String what) {
            if (index < positional.size()) {
                return parseInt(positional.get(index), what);
            }
            return defaultValue;
        }
    }

    public static void main(String args[]) {
        System.out.println();
        System.out.println();
        System.out.println("FFFFF  L       OOOO   W    W    W  PPPPP   RRRRR    OOOO ");
        System.out.println("F      L      O    O  W   W W   W  P    P  R    R  O    O");
        System.out.println("FFFFF  L      O    O   W  W W  W   PPPPP   RRRRR   O    O");
        System.out.println("F      L      O    O   W W   W W   P       R   R   O    O");
        System.out.println("F      LLLLL   OOOO     W     W    P       R    R   OOOO ");
        System.out.println();
        System.out.println("---------------------------------------------------------");
        System.out.println("Welcome to the opensource CFD software FlowPro!          ");
        System.out.println("---------------------------------------------------------");
        System.out.println();

        int exitCode;
        try {
            exitCode = run(args);
        } catch (IllegalArgumentException ex) {
            LOG.error("input arguments have a wrong format: {}", ex.getMessage());
            printUsage();
            exitCode = 2;
        } catch (MPIException | IOException ex) {
            LOG.error("{}", ex.getMessage(), ex);
            exitCode = 1;
        } catch (RuntimeException ex) {
            LOG.error("computation failed: {}", ex.toString(), ex);
            exitCode = 1;
        }
        // a non-zero exit code allows scripts and the GUI to detect the failure
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String args[]) throws IOException, MPIException {
        CommandLine cl = CommandLine.parse(args);

        switch (cl.command) {
            case "help":
            case "-h":
            case "--help":
                printUsage();
                return 0;

            case "params": // description of the parameters
                ParameterRegistry.printHelp();
                return 0;

            case "list": // list of geometries and simulations
                listSimulations();
                return 0;

            default:
                break;
        }

        LOG.info("starting FlowPro...");
        jarURLList = getJarURLList("modules");

        FlowProMain dgfem;
        MasterSolver solver;
        switch (cl.command) {
            case "master": // legacy form: master nDomains
            case "run": {
                int nDomains = cl.domains != null ? cl.domains : cl.positionalInt(0, 0, "number of domains");
                dgfem = new FlowProMain(cl.geometry, cl.simulation);
                dgfem.applyOverrides(cl.overrides);
                solver = dgfem.solverFactory(false, nDomains);
                Solution solution = solver.solve();
                solver.saveData(solution);
                return 0;
            }

            case "check": { // validates the parameters and the mesh, does not compute
                dgfem = new FlowProMain(cl.geometry, cl.simulation);
                dgfem.applyOverrides(cl.overrides);
                int nDomains = cl.domains != null ? cl.domains : 0;
                return dgfem.check(nDomains > 0) ? 0 : 1;
            }

            case "slave": {
                if (cl.positional.size() < 2) {
                    throw new IllegalArgumentException("usage: slave port parallelSolverType");
                }
                int slavePort = Integer.parseInt(cl.positional.get(0));
                String parallelSolverType = cl.positional.get(1);
                SlaveSolver slave = SlaveSolver.factory(parallelSolverType, slavePort);
                LOG.info("mesh was received and initialised");
                slave.solve();
                return 0;
            }

            case "postprocessing": { // plot data, the options are parsed by ResultsPlot
                dgfem = new FlowProMain(cl.geometry, cl.simulation);
                ResultsPlot plot = new ResultsPlot(dgfem.meshPath, dgfem.simulationPath, cl.rest.toArray(new String[0]), jarURLList);
                plot.generateResults();
                LOG.info("results were generated into " + dgfem.simulationPath + "output directory");
                return 0;
            }

            case "optimalisationexport": {
                if (cl.positional.isEmpty()) {
                    throw new IllegalArgumentException("usage: optimalisationExport type");
                }
                dgfem = new FlowProMain(cl.geometry, cl.simulation);
                dgfem.applyOverrides(cl.overrides);
                solver = dgfem.solverFactory(true, 0);
                new OptimisationToolExport(solver, dgfem.simulationPath, cl.positional.get(0).toLowerCase(), jarURLList).export();
                LOG.info("Optimalisation arrays were exported..");
                return 0;
            }

            case "testdynamicmodel": {
                if (cl.positional.isEmpty()) {
                    throw new IllegalArgumentException("usage: testDynamicModel dt");
                }
                dgfem = new FlowProMain(cl.geometry, cl.simulation);
                dgfem.applyOverrides(cl.overrides);
                solver = dgfem.solverFactory(false, 0);
                solver.testDynamic(Double.parseDouble(cl.positional.get(0)), 0);
                return 0;
            }

            case "getparameters": { // fields of class Parameters
                dgfem = new FlowProMain(cl.geometry, cl.simulation);
                System.out.println();
                System.out.println("FlowPro parameters:");
                Class par = new Parameters(dgfem.simulationPath + PARAMETER_FILE_NAME, false, jarURLList).getClass();
                Field[] fields = par.getFields();
                for (Field field : fields) {
                    System.out.println(field.getName() + ": " + field.getGenericType());
                }
                return 0;
            }

            default:
                throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    static void printUsage() {
        System.out.println("usage: java -jar FlowPro.jar <command> [options]");
        System.out.println();
        System.out.println("commands:");
        System.out.println("  run                      run the computation (locally, or in parallel with --domains N)");
        System.out.println("  master N                 the same as run, N = number of domains (0 = local computation)");
        System.out.println("  check                    validate parameters.txt and the mesh, nothing is computed");
        System.out.println("  list                     list geometries and simulations in directory simulations");
        System.out.println("  params                   describe the parameters of file parameters.txt");
        System.out.println("  postprocessing [names] [-fFORMAT -iITER -pPRECISION -sSMOOTHING -nSUBNAME]");
        System.out.println("  slave PORT TYPE          slave of the parallel computation");
        System.out.println("  optimalisationExport TYPE, testDynamicModel DT, getParameters, help");
        System.out.println();
        System.out.println("options:");
        System.out.println("  -g, --geometry NAME      geometry (directory simulations/NAME), default from args.txt");
        System.out.println("  -s, --simulation NAME    simulation (directory simulations/GEOMETRY/NAME), default from args.txt");
        System.out.println("  -d, --domains N          number of domains for the parallel computation");
        System.out.println("  -p, --set KEY=VALUE      overrides a value from parameters.txt, may be repeated");
        System.out.println();
        System.out.println("example: java -jar FlowPro.jar run -g examples/GAMM -s default -p CFL=10 -p steps=200");
    }

    static void listSimulations() {
        File root = new File("simulations");
        List<String> geometries = new ArrayList<>();
        collectGeometries(root, "", geometries);
        if (geometries.isEmpty()) {
            System.out.println("no geometry found in directory " + root.getAbsolutePath());
            return;
        }
        for (String geometry : geometries) {
            StringBuilder sims = new StringBuilder();
            File[] dirs = new File(root, geometry).listFiles(File::isDirectory);
            if (dirs != null) {
                Arrays.sort(dirs);
                for (File dir : dirs) {
                    if (new File(dir, PARAMETER_FILE_NAME).isFile()) {
                        sims.append(sims.length() > 0 ? ", " : "").append(dir.getName());
                    }
                }
            }
            System.out.println(geometry + ": " + sims);
        }
    }

    /**
     * geometry = directory containing subdirectory mesh
     */
    private static void collectGeometries(File dir, String prefix, List<String> geometries) {
        File[] dirs = dir.listFiles(File::isDirectory);
        if (dirs == null) {
            return;
        }
        Arrays.sort(dirs);
        for (File sub : dirs) {
            String name = prefix.isEmpty() ? sub.getName() : prefix + "/" + sub.getName();
            if (new File(sub, "mesh").isDirectory()) {
                geometries.add(name);
            } else if (prefix.split("/").length < 3) {
                collectGeometries(sub, name, geometries);
            }
        }
    }

    /**
     * Values given on the command line replace the values of parameters.txt
     * for all the objects which read the file.
     */
    void applyOverrides(Map<String, String> overrides) {
        if (!overrides.isEmpty()) {
            FlowProProperties.setOverrides(simulationPath + PARAMETER_FILE_NAME, overrides);
            LOG.info("parameters overridden from the command line: {}", overrides);
        }
    }

    /**
     * Validates the parameters and the mesh files.
     *
     * @return true if no problem was found
     */
    boolean check(boolean parallelMode) throws IOException {
        boolean ok = true;
        FlowProProperties props = new FlowProProperties();
        props.load(simulationPath + PARAMETER_FILE_NAME);
        List<String> warnings = ParameterRegistry.validate(props, parallelMode);
        for (String warning : warnings) {
            LOG.warn(warning);
        }
        ok &= warnings.isEmpty();

        for (String file : new String[]{"vertices.txt", "elements.txt", "elementType.txt"}) {
            if (!new File(meshPath + file).isFile()) {
                LOG.error("mesh file " + meshPath + file + " is missing");
                ok = false;
            }
        }
        if (!new File(meshPath + "neighbors.txt").isFile() && !new File(meshPath + "boundaryType.txt").isFile()) {
            LOG.error("either " + meshPath + "neighbors.txt or " + meshPath + "boundaryType.txt has to exist");
            ok = false;
        }
        if (ok) {
            // load the equation, the parameters and the mesh as the solver does, but compute nothing
            try {
                new EquationFactory().getEquation(simulationPath + PARAMETER_FILE_NAME, jarURLList);
                new Parameters(simulationPath + PARAMETER_FILE_NAME, parallelMode, jarURLList);
                double[][] PXY = Mat.loadDoubleMatrix(meshPath + "vertices.txt");
                int[][] TP = Mat.loadIntMatrix(meshPath + "elements.txt");
                int[] type = Mat.loadIntArray(meshPath + "elementType.txt");
                if (TP.length != type.length) {
                    LOG.error("elements.txt has " + TP.length + " rows, but elementType.txt has " + type.length);
                    ok = false;
                }
                for (int i = 0; i < TP.length && ok; i++) {
                    for (int v : TP[i]) {
                        if (v < 0 || v >= PXY.length) {
                            LOG.error("element " + i + " refers to vertex " + v + " which does not exist");
                            ok = false;
                            break;
                        }
                    }
                }
                LOG.info("mesh: {} vertices, {} elements", PXY.length, TP.length);
            } catch (IOException ex) {
                LOG.error(ex.getMessage());
                ok = false;
            }
        }
        LOG.info(ok ? "check passed" : "check found problems");
        return ok;
    }

    private MasterSolver solverFactory(boolean optimalisation, int nDomains) throws IOException, MPIException {
        boolean parallelMode = false;
        if (nDomains > 0) {
            parallelMode = true;
        }
        Equation eqn = (new EquationFactory()).getEquation(simulationPath + PARAMETER_FILE_NAME, jarURLList);   // read physical parameters
        Parameters par = new Parameters(simulationPath + PARAMETER_FILE_NAME, parallelMode, jarURLList); // read numerical parameters
        for (String warning : ParameterRegistry.validate(par.props, parallelMode)) {
            LOG.warn(warning);
        }

        LOG.info("loading data...");
        // load matrices defining the mesh
        double[][] PXY = Mat.loadDoubleMatrix(meshPath + "vertices.txt"); // mesh vertices coordinates
        if (par.meshScale != 1 || par.lRef != 1) {
			for (double[] PXY1 : PXY) {
				for (int j = 0; j < PXY1.length; j++) {
					if (par.scaleDims[j]) {
						PXY1[j] *= par.meshScale / par.lRef;
					}
				}
			}
            LOG.info("Mesh was scaled (meshScale/lR"
                    + ""
                    + "ef) with parameters: meshScale = " + par.meshScale + " and lRef = " + par.lRef);
        }

        double[][] UXY = null;
        if (par.movingMesh && (par.continueComputation || optimalisation)) {
            try {
                UXY = Mat.loadDoubleMatrix(simulationPath + "UXY.txt"); // mesh vertices velocity
                LOG.info("Mesh velocities were loades!");
            } catch (FileNotFoundException ex) {
                LOG.warn("Mesh velocities file UXY.txt was not found. Velocities were set to zero!");
            }
        }

        int[] elemsType = Mat.loadIntArray(meshPath + "elementType.txt");   // element type
        int[][] TP = Mat.loadIntMatrix(meshPath + "elements.txt"); // inexes of points defining element
        LOG.info("Mesh info: " + PXY.length + "-vertices, " + TP.length + "-elements.");

        int[][] TT;
        try {
            TT = Mat.loadIntMatrix(meshPath + "neighbors.txt");  // indexes of element neigborhoods
        } catch (FileNotFoundException ex) {
            LOG.info("creating neighbor matrix");
            int[][] boundary = Mat.loadIntMatrix(meshPath + "boundaryType.txt");
            TT = createNeighborMatrix(elemsType, TP, boundary);
            Mat.save(TT, meshPath + "neighbors.txt");
        }
        int nElems = TP.length;  // number of elements in the mesh

        int[] elemsOrder;
        try {
            elemsOrder = Mat.loadIntArray(simulationPath + "order.txt");
            LOG.info("reading local order of spatial accuracy from file " + "order.txt");
			if (par.order >= 1) {
				LOG.warn("both local and global order of accurecy was defined; using local order from file order.txt");
			}
			par.setOrder(Mat.max(elemsOrder));
        } catch (FileNotFoundException ex) {
            if (par.order < 1) {
                throw new IOException("neither global nor local order of spatial accuracy defined, "
                        + " either define parameter order in file " + simulationPath + PARAMETER_FILE_NAME
                        + " or create file " + "order.txt" + " in simulation path");
            }
            elemsOrder = new int[nElems];
            LOG.info("file " + simulationPath + "order.txt" + " not found"
                    + ", setting global order of spatial accuracy to " + par.order);
            Arrays.fill(elemsOrder, par.order);
        }

        // curved boundary computation
        FaceCurvature[] fCurv;
        if (par.curvedBoundary) {
            fCurv = CurvedBoundary.modifyMesh(elemsType, PXY, TP, TT);
            CurvedBoundary.saveMesh(meshPath, elemsType, TP, TT);
        } else {
            fCurv = new FaceCurvature[nElems];
            //elemsType = firstDigit(elemsType);
        }

        // domain partition for parallel computing
        int[] elem2DomMap;
        if (parallelMode) {
            try {
                elem2DomMap = Mat.loadIntArray(simulationPath + "part.txt");
            } catch (FileNotFoundException ex) {
                LOG.info("generating domain partition");
                elem2DomMap = generateMeshPartition(elemsType, PXY, TP, nDomains);
                Mat.save(elem2DomMap, simulationPath + "part.txt");
            }
        } else {
            elem2DomMap = new int[nElems];
        }

        // wall distance for turbulence models
        double[] wallDistance;
        try {
            wallDistance = Mat.loadDoubleArray(meshPath + "wallDistance.txt");
        } catch (FileNotFoundException ex) {
            LOG.info("generating wall distance function");
            wallDistance = generateWallDistanceFunction(eqn, elemsType, PXY, TP, TT);
            Mat.save(wallDistance, meshPath + "wallDistance.txt");
        }

        // body boundaries for ALE computation
        int[][] TEale;
        if (par.movingMesh) {
            LOG.info("moving mesh computation - ALE description");
            try {
                TEale = Mat.loadIntMatrix(meshPath + "neighborsALE.txt");
            } catch (FileNotFoundException ex) {
                LOG.info("creating neighborALE matrix");
                int[][] boundaryALE = Mat.loadIntMatrix(meshPath + "boundaryTypeALE.txt");
                TEale = createNeighborALEMatrix(elemsType, TP, TT, boundaryALE);
                Mat.save(TEale, meshPath + "neighborsALE.txt");
            }
        } else {
            LOG.info("static mesh computation - Eulerian description");
            TEale = Mat.allocSameIntMatrix(TT);
        }

        // shifting for periodicity boundary condition
        int[][] TEshift;
        double[][] shift = null;
        try {
            TEshift = Mat.loadIntMatrix(meshPath + "TEshift.txt");
            shift = Mat.loadDoubleMatrix(meshPath + "shift.txt");
            // the shift has to be scaled in the same way as the vertices
            if (par.meshScale != 1 || par.lRef != 1) {
				for (double[] shift1 : shift) {
					for (int j = 0; j < shift1.length; j++) {
						if (par.scaleDims[j]) {
							shift1[j] *= par.meshScale / par.lRef;
						}
					}
				}
            }
            LOG.info("periodic boundary found");
        } catch (FileNotFoundException ex) {
            TEshift = Mat.allocSameIntMatrix(TT);
            LOG.info("no periodic boundary found");
        }

        int nDoms = Mat.max(elem2DomMap) + 1;

        printInfo(eqn, par);

        // loading external field
        par.externalField = false;
        double[][] externalField = null;
        try {
            externalField = Mat.loadDoubleMatrix(meshPath + "externalField.txt");
            par.externalField = true;
            LOG.info("external field found");
        } catch (FileNotFoundException ex) {
            LOG.info("external field not found ");
        }

        // load initial condition
        double initW[][];
        State state = new State(simulationPath + STATE_FILE_NAME, par);
        if (optimalisation) {
            initW = Mat.loadDoubleMatrix(simulationPath + "We.txt");
        } else if (par.continueComputation) {
            state.load();
            if (state.hasOrderChanged()) {
                initW = Mat.loadDoubleMatrix(simulationPath + "W.txt");
            } else {
                try {
                    initW = Mat.loadDoubleMatrix(simulationPath + "We.txt");
                } catch (FileNotFoundException ex) {
                    LOG.warn("file " + simulationPath + "We.txt" + " not found, "
                            + "reading " + simulationPath + "W.txt" + " instead");
                    initW = Mat.loadDoubleMatrix(simulationPath + "W.txt");
                }
            }
        } else {
            try {
                initW = Mat.loadDoubleMatrix(simulationPath + "initW.txt");
                LOG.info("init conditions were loaded from file initW.txt ");
            } catch (IOException e) {
                String script = simulationPath + "initScript.js";
                if (new File(script).isFile()) {
                    try {
                        initW = (new InitConditionScript(PXY, TP, eqn.nEqs(), script)).getInitW();
                        LOG.info("init conditions were computed by script initScript.js");
                    } catch (ScriptException | NoSuchMethodException | FileNotFoundException ex) {
                        // a broken script is an error, the computation must not silently start from a constant state
                        throw new IOException("script " + script + " failed: " + ex.getMessage(), ex);
                    }
                } else {
                    LOG.info("neither initW.txt nor initScript.js found, constant init condition of the model is used");
                    double[] w = eqn.constInitCondition();
                    initW = replicate(w, nElems, eqn.nEqs());
                }
            }
        }

        // create solution monitor
        SolutionMonitor solMonitor = null;
        if (par.solutionMonitorOn) {
            solMonitor = (new SolutionMonitorFactory()).getSolutionMonitor(simulationPath + PARAMETER_FILE_NAME, jarURLList);
        }

        // create file for residuum
        if (!par.continueComputation) {
            try {
				try (PrintWriter writer = new PrintWriter(simulationPath + "residuum.txt")) {
					writer.print("");
				}
            } catch (IOException ioe) {
                LOG.error("error while creating a new empty file residuum.txt");
            }
        }

        // load Gaussian quadrature
        QuadratureCentral qRules = new QuadratureCentral();

        LOG.info("initialising mesh...");
        // initialising ALE objects
        Deformation dfm = new DeformationFactory().getDeformation(par, eqn, PXY, TP, TEale);
        Dynamics dyn = null;
        if (par.movingMesh) {
            dyn = (new DynamicsFactory()).getDynamicsModel(simulationPath + PARAMETER_FILE_NAME, jarURLList, dfm.nBodies, simulationPath, meshPath, eqn);
            dfm.setCenters(dyn.getCenter());
            dfm.calculateBlendingFunctions(PXY, generateBoundaryDistanceFunction(elemsType, PXY, TP, TT), TP, TT, TEale, elemsType, meshPath);
        }

        Domain domain = new Domain(elemsOrder, elemsType, TT, TP, TEale, TEshift, fCurv, initW, elem2DomMap, nDoms, par.overlap, PXY.length);
        Mesh[] mesh = new Mesh[nDoms];
        for (int d = 0; d < nDoms; ++d) {
            LOG.info("domain " + d + " from " + (nDoms - 1) + ":");
            Domain.Subdomain subdom = domain.getSubdomain(d);
            mesh[d] = new Mesh(eqn, dfm, par, solMonitor, qRules, PXY, UXY, subdom.elemsOrder, wallDistance, externalField, subdom.elemsType,
                    subdom.TP, subdom.TT, subdom.TEale, subdom.TEshift, shift, subdom.fCurv, subdom.initW, subdom);
            if (!parallelMode) {
                mesh[d].init();
            }
        }

        memoryInfo();
        return MasterSolver.factory(simulationPath, mesh, dyn, eqn, par, state, domain);
    }

    public static String millisecsToTime(long nanoseconds) {
        long seconds = nanoseconds / 1000;

        int hours = (int) seconds / 3600;
        int minutes = (int) seconds / 60 - hours * 60;
        seconds = (int) seconds - minutes * 60 - hours * 3600;

        if (hours == 0 && minutes == 0) {
            return String.format("%02ds", seconds);
        } else if (hours == 0) {
            return String.format("%02dm %02ds", minutes, seconds);
        } else {
            return String.format("%02dh %02dm %02ds", hours, minutes, seconds);
        }
    }

    private static void printInfo(Equation eqn, Parameters par) {
        LOG.info("CFL: {}, order: {}, threads: {}", par.cfl, par.order, par.nThreads);
        StringBuilder strBuilder = new StringBuilder();
        strBuilder.append("steps: ").append(par.steps);
        if (par.residuum > 0) {
            strBuilder.append(", ").append("residuum: ").append(par.residuum);
        }
        if (par.tEnd < 1e8) {
            strBuilder.append(", ").append("tEnd: ").append(par.tEnd);
        }
        LOG.info(strBuilder.toString());
    }

    private static double[][] replicate(double[] initW, int nElems, int nEqs) {
        double[][] W = new double[nElems][];
        for (int i = 0; i < nElems; ++i) {
            W[i] = new double[nEqs];
            for (int j = 0; j < nEqs; ++j) {
                W[i][j] = initW[j];
            }
        }
        return W;
    }

    public int[][] createNeighborMatrix(int[] type, int[][] TP, int[][] boundary) {

        // neighbors finder
        HashMap<String, Object> map = new HashMap<>();

        int nRows = TP.length;
        // creating elementTypes
        ElementType[] elemType = new ElementType[nRows];
        for (int i = 0; i < nRows; i++) {
            elemType[i] = ElementType.elementTypeFactory(type[i], 0, 0, 0);
        }

        // saving face indexes
        for (int i = 0; i < nRows; i++) {
            int nFaces = elemType[i].numberOfEdges();
            for (int k = 0; k < nFaces; k++) {
                int[] faceIndexes = elemType[i].getFaceIndexes(k);
                for (int j = 0; j < faceIndexes.length; j++) {
                    faceIndexes[j] = TP[i][faceIndexes[j]];
                }
                Arrays.sort(faceIndexes);
                String key = "";
                for (int j = 0; j < faceIndexes.length; j++) {
                    key = key + Integer.toString(faceIndexes[j]) + "$";
                }
                FaceIndexes face = (FaceIndexes) map.get(key);
                if (face == null) {
                    face = new FaceIndexes(i);
                    map.put(key, face);
                } else {
                    face.i2 = i;
                }
            }
        }

        // loading face indexes
        int[][] TT = new int[nRows][];
        for (int i = 0; i < nRows; i++) {
            int nFaces = elemType[i].numberOfEdges();
            TT[i] = new int[nFaces];
            Arrays.fill(TT[i], -1000);
            for (int k = 0; k < nFaces; k++) {
                int[] faceIndexes = elemType[i].getFaceIndexes(k);
                for (int j = 0; j < faceIndexes.length; j++) {
                    faceIndexes[j] = TP[i][faceIndexes[j]];
                }
                Arrays.sort(faceIndexes);
                String key = "";
                for (int j = 0; j < faceIndexes.length; j++) {
                    key = key + Integer.toString(faceIndexes[j]) + "$";
                }
                FaceIndexes face = (FaceIndexes) map.get(key);
                if (face.i1 == i) {
                    if (face.i2 != -1000) {
                        TT[i][k] = face.i2;
                    }
                } else {
                    TT[i][k] = face.i1;
                }
            }
        }

        // boundary
        HashMap<String, Integer> boundMap = new HashMap<>();
        int nBoundRows = boundary.length;
        for (int i = 0; i < nBoundRows; i++) {
            int[] faceIndexes = new int[boundary[i].length - 1];
            for (int j = 0; j < boundary[i].length - 1; j++) {
                faceIndexes[j] = boundary[i][j + 1];
            }
            Arrays.sort(faceIndexes);
            String key = "";
            for (int j = 0; j < faceIndexes.length; j++) {
                key = key + Integer.toString(faceIndexes[j]) + "$";
            }
            boundMap.put(key, boundary[i][0]);
        }

        for (int i = 0; i < nRows; i++) {
            for (int k = 0; k < TT[i].length; k++) {
                if (TT[i][k] == -1000) {
                    int[] faceIndexes = elemType[i].getFaceIndexes(k);
                    for (int j = 0; j < faceIndexes.length; j++) {
                        faceIndexes[j] = TP[i][faceIndexes[j]];
                    }
                    Arrays.sort(faceIndexes);
                    String key = "";
                    for (int j = 0; j < faceIndexes.length; j++) {
                        key = key + Integer.toString(faceIndexes[j]) + "$";
                    }
                    Integer index = boundMap.get(key);
                    if (index != null) {
                        TT[i][k] = index;
                    } else {
                        TT[i][k] = -1;
                    }
                }
            }
        }

        return TT;
    }

    public int[][] createNeighborALEMatrix(int[] type, int[][] TP, int[][] TT, int[][] boundary) {

        int nRows = TP.length;
        // creating elementTypes
        ElementType[] elemType = new ElementType[nRows];
        for (int i = 0; i < nRows; i++) {
            elemType[i] = ElementType.elementTypeFactory(type[i], 0, 0, 0);
        }

        int[][] TEale = new int[TT.length][];

        HashMap<String, Integer> boundMap = new HashMap<>();
        int nBoundRows = boundary.length;
        for (int i = 0; i < nBoundRows; i++) {
            int[] faceIndexes = new int[boundary[i].length - 1];
            for (int j = 0; j < boundary[i].length - 1; j++) {
                faceIndexes[j] = boundary[i][j + 1];
            }
            Arrays.sort(faceIndexes);
            String key = "";
            for (int j = 0; j < faceIndexes.length; j++) {
                key = key + Integer.toString(faceIndexes[j]) + "$";
            }
            boundMap.put(key, boundary[i][0]);
        }

        for (int i = 0; i < TT.length; i++) {
            TEale[i] = new int[TT[i].length];
            for (int k = 0; k < TT[i].length; k++) {
                //if (TT[i][k] < 0) {
                int[] faceIndexes = elemType[i].getFaceIndexes(k);
                for (int j = 0; j < faceIndexes.length; j++) {
                    faceIndexes[j] = TP[i][faceIndexes[j]];
                }
                Arrays.sort(faceIndexes);
                String key = "";
                for (int j = 0; j < faceIndexes.length; j++) {
                    key = key + Integer.toString(faceIndexes[j]) + "$";
                }
                Integer index = boundMap.get(key);
                if (index != null) {
                    TEale[i][k] = index;
                } else {
                    TEale[i][k] = 0;
                }
                //}
            }
        }
        return TEale;
    }

    static class FaceIndexes {

        int i1, i2;

        FaceIndexes(int i) {
            i1 = i;
            i2 = -1000;
        }
    }

    public double[] generateWallDistanceFunction(Equation eqn, int[] type, double[][] PXY, int[][] TP, int[][] TT) {
        double[] wallDistance = generateDistanceFunction(type, PXY, TP, TT, eqn::isIPFace);
        LOG.info("wall distance generated");
        return wallDistance;
    }

    /**
     * Distance of mesh vertices from the boundary of the domain (faces with
     * TT &lt; 0). Previously all the faces were taken into account and thus the
     * function was zero everywhere.
     */
    public double[] generateBoundaryDistanceFunction(int[] type, double[][] PXY, int[][] TP, int[][] TT) {
        double[] boundaryDistance = generateDistanceFunction(type, PXY, TP, TT, tt -> tt < 0);
        LOG.info("boundary distance generated");
        return boundaryDistance;
    }

    /**
     * Computes an approximation of the distance of every vertex from the faces
     * selected by isSourceFace. The distance is measured along the edges of the
     * mesh elements (Bellman-Ford relaxation), thus it slightly overestimates
     * the euclidean distance.
     *
     * @param isSourceFace predicate applied on the neighbour index TT[i][k] of
     * the face
     */
    static double[] generateDistanceFunction(int[] type, double[][] PXY, int[][] TP, int[][] TT, java.util.function.IntPredicate isSourceFace) {
        int nElem = TT.length;
        int nPoints = PXY.length;
        int dim = PXY[0].length;
        double[] distance = new double[nPoints];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        for (int i = 0; i < nElem; i++) {
            ElementType elemType = ElementType.elementTypeFactory(type[i], 0, 0, 0);
            for (int k = 0; k < TT[i].length; k++) {
                if (isSourceFace.test(TT[i][k])) {
                    for (int faceIndex : elemType.getFaceIndexes(k)) {
                        distance[TP[i][faceIndex]] = 0;
                    }
                }
            }
        }

        // relaxation, converges in at most nPoints sweeps
        boolean changed = true;
        for (int iter = 0; changed && iter <= nPoints; iter++) {
            changed = false;
            for (int i = 0; i < nElem; i++) {
                for (int j = 0; j < TP[i].length; j++) {
                    int pj = TP[i][j];
                    for (int k = 0; k < TP[i].length; k++) {
                        int pk = TP[i][k];
                        if (j != k && distance[pk] < distance[pj]) {
                            double L = 0;
                            for (int d = 0; d < dim; d++) {
                                L += (PXY[pj][d] - PXY[pk][d]) * (PXY[pj][d] - PXY[pk][d]);
                            }
                            double y = distance[pk] + Math.sqrt(L);
                            if (y < distance[pj]) {
                                distance[pj] = y;
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        for (int i = 0; i < nPoints; i++) {
            if (Double.isInfinite(distance[i])) {
                LOG.warn("distance function could not be computed for some vertices, "
                        + "probably no face of the required type (e.g. wall) is defined");
                Arrays.fill(distance, 1e30);
                break;
            }
        }
        return distance;
    }

    /**
     * Simple partitioning of the mesh into nDoms domains: elements are sorted
     * according to the coordinate in the direction in which the mesh has the
     * largest extent and split into nDoms groups of (almost) the same size.
     */
    public int[] generateMeshPartition(int[] elemsType, double[][] PXY, int[][] TP, int nDoms) {
        int nElems = TP.length;
        int[] part = new int[nElems];
        int dim = PXY[0].length;

        // finding largest dimension of mesh
        int largestDim = 0;
        double largestDist = -1;
        for (int d = 0; d < dim; d++) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double[] point : PXY) {
                min = Math.min(min, point[d]);
                max = Math.max(max, point[d]);
            }
            if (max - min > largestDist) {
                largestDist = max - min;
                largestDim = d;
            }
        }

        // generating elements centers and sorting
        double[] elemCenter = new double[nElems];
        int[] indexes = new int[nElems];
        for (int i = 0; i < nElems; i++) {
            for (int j = 0; j < TP[i].length; j++) {
                elemCenter[i] += PXY[TP[i][j]][largestDim];
            }
            elemCenter[i] /= TP[i].length;
            indexes[i] = i;
        }
        Mat.quicksort(elemCenter, indexes, 0, elemCenter.length - 1);

        // make partitioning, domain d gets elements with sorted index in
        // [d * nElems / nDoms, (d + 1) * nElems / nDoms)
        for (int i = 0; i < nElems; i++) {
            part[indexes[i]] = (int) ((long) i * nDoms / nElems);
        }
        return part;
    }

    public static URL[] getJarURLList(String s) throws IOException {
        URL[] u = null;

        File currentDir = new File(s); // current directory
        ArrayList<URL> URLs = new ArrayList();
        System.out.print("Found libraries: ");
        addDirectoryContents(currentDir, URLs);
        System.out.println();
        u = new URL[URLs.size()];
        URLs.toArray(u);

        return u;
    }

    public static void addDirectoryContents(File dir, ArrayList<URL> URLs) {
        try {
            File[] files = dir.listFiles();
            for (File file : files) {
                if (file.isDirectory()) {
                    addDirectoryContents(file, URLs);
                } else {
                    URLs.add(file.toURI().toURL());
                    System.out.print(file.getName() + ", ");
                }
            }
        } catch (Exception e) {
            System.out.println("Error " + e);
        }
    }

    public static void memoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long allocatedMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        LOG.info("free memory: " + freeMemory / 1024 / 1024 + "Mb");
        LOG.info("allocated memory: " + allocatedMemory / 1024 / 1024 + "Mb");
        LOG.info("max memory: " + maxMemory / 1024 / 1024 + "Mb");
        LOG.info("total free memory: " + (freeMemory + (maxMemory - allocatedMemory)) / 1024 / 1024 + "Mb");
    }
}
