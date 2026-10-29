/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.core.solver;

import flowpro.api.Dynamics;
import flowpro.api.Equation;
import flowpro.core.Mesh;
import flowpro.core.Parameters;
import flowpro.core.Solution;
import flowpro.core.State;
import flowpro.core.parallel.AppInfo;
import flowpro.core.parallel.Domain;
import flowpro.core.parallel.Fetcher;
import flowpro.core.parallel.IpAddressReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import litempi.MPIException;
import litempi.MPIMaster;
import org.apache.commons.lang3.tuple.MutablePair;

/**
 *
 * @author obublik
 */
abstract public class MasterSolver {

	public static final String APP_NAME = "app";

	abstract public Solution solve() throws MPIException, IOException;

	abstract public void saveData(Solution sol) throws IOException;

	abstract public Mesh getMesh();

	abstract public void testDynamic(double dt, int newtonIter) throws IOException;

	/**
	 * Name of the file whose presence in the simulation directory asks the
	 * running computation to save the results and stop (used by the GUI).
	 */
	public static final String STOP_FILE_NAME = "STOP";

	/**
	 * @param simulationPath
	 * @return true if file STOP exists in the simulation directory, the file
	 * is deleted so that the next run is not affected
	 */
	public static boolean isStopRequested(String simulationPath) {
		File stopFile = new File(simulationPath + STOP_FILE_NAME);
		if (stopFile.exists()) {
			stopFile.delete();
			return true;
		}
		return false;
	}

	/**
	 * @param t current physical time
	 * @param timeSaveRate
	 * @return index of the next time level at which the results are to be
	 * saved, i.e. results are saved when t > index * timeSaveRate
	 */
	public static int firstTimeSaveIndex(double t, double timeSaveRate) {
		if (timeSaveRate <= 0 || timeSaveRate == Double.MAX_VALUE) {
			return 1;
		}
		return (int) Math.min(Integer.MAX_VALUE, Math.floor(t / timeSaveRate) + 1);
	}

	/**
	 * Appends one line (residuum, time, CPU time) into file residuum.txt.
	 */
	public static void appendResiduum(String simulationPath, double residuum, double t, double CPU) {
		appendResiduum(simulationPath, residuum, t, CPU, null);
	}

	/**
	 * Appends one line (residuum, time, CPU time and the relative residua of the
	 * individual equations, if given) into file residuum.txt.
	 */
	public static void appendResiduum(String simulationPath, double residuum, double t, double CPU, double[] equationResiduum) {
		try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(simulationPath + "residuum.txt", true)))) {
			StringBuilder line = new StringBuilder();
			line.append(residuum).append(' ').append(t).append(' ').append(CPU);
			if (equationResiduum != null) {
				for (double r : equationResiduum) {
					line.append(' ').append(r);
				}
			}
			out.println(line);
		} catch (IOException ex) {
			org.slf4j.LoggerFactory.getLogger(MasterSolver.class).warn("residuum could not be saved: " + ex.getMessage());
		}
	}

	/**
	 * L2 norms of the spatial residuum R(W) of the individual equations over the
	 * elements of the computational domain (see Element.l2NormsSquared).
	 */
	public static double[] residuumNorms(flowpro.core.element.Element[] elems, int nThreads) {
		double[][] elemNorms = new double[elems.length][];
		flowpro.core.parallel.ParallelLoop.forEach(elems.length, nThreads, i -> {
			if (elems[i].insideComputeDomain) {
				elemNorms[i] = elems[i].l2NormsSquared(elems[i].ti.spatialResiduum);
			}
		});
		double[] norms = null;
		for (double[] en : elemNorms) {
			if (en != null) {
				if (norms == null) {
					norms = new double[en.length];
				}
				for (int m = 0; m < en.length; m++) {
					norms[m] += en[m];
				}
			}
		}
		if (norms != null) {
			for (int m = 0; m < norms.length; m++) {
				norms[m] = Math.sqrt(norms[m]);
			}
		}
		return norms;
	}

	private static MPIMaster distributeJobs(int nSlaves, Parameters par) throws IOException, MPIException {
		IpAddressReader ipAddressReader = new IpAddressReader(par.pcFilterFile, Parameters.PC_LIST_FILE);
		List<MutablePair<String, Integer>> nodeList = ipAddressReader.getNodeList();
		Map<String, String> ip2nodeNameMap = ipAddressReader.getIp2NodeNameMap();

		// Integer objects must not be compared by ==
		Comparator<MutablePair<String, Integer>> comparator = (n1, n2) -> Integer.compare(n1.getValue(), n2.getValue());
		int maxSlavesPerNode = Collections.max(nodeList, comparator).getValue();

		String[] argArr = new String[maxSlavesPerNode];
		for (int i = 0; i < maxSlavesPerNode; i++) {
			argArr[i] = "slave " + (par.slavePort + i) + " " + par.parallelSolverType;
		}
		AppInfo appInfo = new AppInfo("FlowPro.jar", argArr, "app_");

		File zippedApp = new File("FlowPro.zip");
		Fetcher fetcher = new Fetcher(nSlaves, ip2nodeNameMap, par.fetcherPort, par.publicKeyFile);
		List<MutablePair<String, Integer>> reachedNodes = fetcher.establishConnections(nodeList);
		fetcher.fetch(zippedApp, appInfo);

		List<InetSocketAddress> inetList = new ArrayList<>();
		reachedNodes.forEach((n) -> {
			for (int i = 0; i < n.getValue(); i++) {
				inetList.add(new InetSocketAddress(n.getKey(), par.slavePort + i));
			}
		});
		InetSocketAddress[] inetAddresses = inetList.toArray(new InetSocketAddress[0]);

		return new MPIMaster(inetAddresses, par.slavePort, ip2nodeNameMap);
	}

	public enum MasterSolverType {
		ksp, schwartz;

		public static void help() {
			System.out.println("********************************");
			System.out.println("HELP for parameter parallelSolverType");
			System.out.println("list of possible values:");
			System.out.println(Arrays.asList(MasterSolverType.values()));
			System.out.println("********************************");
		}
	}

	public static MasterSolver factory(String simulationPath, Mesh[] meshes, Dynamics dyn,
			Equation eqn, Parameters par, State state, Domain domain) throws IOException, MPIException {

		MasterSolver masterSolver = null;
		if (par.parallelMode) {
			MPIMaster mpi = distributeJobs(domain.nDoms, par);
			try {
				MasterSolverType masterSolverType = MasterSolverType.valueOf(par.parallelSolverType.toLowerCase());
				switch (masterSolverType) {
					case ksp:
						masterSolver = new KSPSolver(mpi, simulationPath, meshes, dyn, eqn, par, state, domain);
						break;
					case schwartz:
						masterSolver = new SchwartzImplicitSolver(mpi, simulationPath, meshes, dyn, eqn, par, state, domain);
						break;
				}
			} catch (IllegalArgumentException ex) {
				MasterSolverType.help();
				throw new IOException("unknown master solver " + par.parallelSolverType.toLowerCase());
			}
			return masterSolver;
		} else {
			String solverType = ((meshes[0].getElems())[0].ti).getLocalSolverType();
			switch (solverType) {
				case "localimplicit":
					return new LocalImplicitSolver(simulationPath, meshes, dyn, eqn, par, state, domain);

				case "localexplicit":
					return new LocalExplicitSolver(simulationPath, meshes, dyn, eqn, par, state, domain);
				default:
					throw new IOException("unknown solver " + solverType);
			}
		}
	}
}
