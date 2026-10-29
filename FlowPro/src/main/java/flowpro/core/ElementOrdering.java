package flowpro.core;

import flowpro.core.element.Element;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ordering of the elements for the numbering of the degrees of freedom. The
 * elements themselves (and thus the output files) keep their indexes, only the
 * order of the unknowns in the linear system changes. The incomplete
 * factorisations (ILU0, block ILU0) are sensitive to the ordering.
 *
 * @author obublik
 */
public class ElementOrdering {

    public enum Type {
        none, rcm;

        public static void help() {
            System.out.println("********************************");
            System.out.println("HELP for parameter elementOrdering");
            System.out.println("list of possible values:");
            System.out.println(Arrays.asList(Type.values()));
            System.out.println("********************************");
        }
    }

    /**
     * @return the indexes of the elements in the order in which their degrees of
     * freedom are numbered
     */
    public static int[] order(Element[] elems, String type) {
        switch (Type.valueOf(type.trim().toLowerCase())) {
            case rcm:
                return reverseCuthillMcKee(elems);
            default:
                int[] order = new int[elems.length];
                for (int i = 0; i < order.length; i++) {
                    order[i] = i;
                }
                return order;
        }
    }

    /**
     * Reverse Cuthill-McKee ordering of the graph of the face neighbours, every
     * connected component starts from a pseudo-peripheral element.
     */
    public static int[] reverseCuthillMcKee(Element[] elems) {
        int n = elems.length;
        int[][] adjacency = new int[n][];
        int[] degree = new int[n];
        for (int i = 0; i < n; i++) {
            List<Integer> neighbours = new ArrayList<>();
            for (int t : elems[i].TT) {
                if (t > -1 && t != i && !neighbours.contains(t)) {
                    neighbours.add(t);
                }
            }
            adjacency[i] = neighbours.stream().mapToInt(Integer::intValue).toArray();
            degree[i] = adjacency[i].length;
        }
        // neighbours are visited in the order of increasing degree
        for (int i = 0; i < n; i++) {
            adjacency[i] = Arrays.stream(adjacency[i]).boxed()
                    .sorted((a, b) -> Integer.compare(degree[a], degree[b]))
                    .mapToInt(Integer::intValue).toArray();
        }

        int[] order = new int[n];
        boolean[] visited = new boolean[n];
        int count = 0;
        while (count < n) {
            // the unvisited element of the minimal degree, then the farthest one from it
            int start = -1;
            for (int i = 0; i < n; i++) {
                if (!visited[i] && (start == -1 || degree[i] < degree[start])) {
                    start = i;
                }
            }
            start = pseudoPeripheral(adjacency, visited, start);

            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            visited[start] = true;
            while (!queue.isEmpty()) {
                int e = queue.poll();
                order[count++] = e;
                for (int t : adjacency[e]) {
                    if (!visited[t]) {
                        visited[t] = true;
                        queue.add(t);
                    }
                }
            }
        }

        // reverse
        for (int i = 0; i < n / 2; i++) {
            int tmp = order[i];
            order[i] = order[n - 1 - i];
            order[n - 1 - i] = tmp;
        }
        return order;
    }

    // repeated breadth first search, the last level with the minimal degree,
    // until the eccentricity does not grow
    private static int pseudoPeripheral(int[][] adjacency, boolean[] excluded, int start) {
        int node = start;
        int eccentricity = -1;
        for (int iter = 0; iter < 10; iter++) {
            int[] level = new int[adjacency.length];
            Arrays.fill(level, -1);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(node);
            level[node] = 0;
            int last = node;
            while (!queue.isEmpty()) {
                int e = queue.poll();
                for (int t : adjacency[e]) {
                    if (!excluded[t] && level[t] == -1) {
                        level[t] = level[e] + 1;
                        queue.add(t);
                        if (level[t] > level[last]
                                || (level[t] == level[last] && adjacency[t].length < adjacency[last].length)) {
                            last = t;
                        }
                    }
                }
            }
            if (level[last] <= eccentricity) {
                break;
            }
            eccentricity = level[last];
            node = last;
        }
        return node;
    }
}
