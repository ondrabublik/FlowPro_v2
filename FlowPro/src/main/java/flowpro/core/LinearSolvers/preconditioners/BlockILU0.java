/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.core.LinearSolvers.preconditioners;

import flowpro.api.Mat;
import flowpro.core.LinearSolvers.SparseMatrix;
import flowpro.core.element.Element;
import flowpro.core.Parameters;
import flowpro.core.element.Implicit;
import java.util.Arrays;

/**
 * Block ILU(0) preconditioner. One block contains all degrees of freedom of one
 * element, the sparsity pattern of the factorisation is the block pattern of
 * the matrix (element and its face neighbours).
 *
 * @author obublik
 */
public class BlockILU0 extends Preconditioner {

    int nThreads;
    // elements in the order of their degrees of freedom (see ElementOrdering),
    // the block rows and columns below are positions in this array
    Element[] elems;
    // position of the element (by its index in the mesh) in elems
    int[] rank;
    int nElem;
    // column blocks of block row i (ascending positions, including i)
    int[][] cols;
    // position of the diagonal block in cols[i]
    int[] diag;
    // blocks[i][k][r][c] = entry in row r of element i and column c of element cols[i][k],
    // after factor() the blocks left of the diagonal hold L, the others U
    double[][][][] blocks;
    // inverses of the diagonal blocks of U
    double[][][] diagInverse;
    double[][] y;

    BlockILU0(Parameters par) {
        nThreads = par.nThreads;
    }

    @Override
    public void setMatrix(SparseMatrix A) {
        Element[] meshElems = A.getElems();
        nElem = meshElems.length;
        Integer[] byDofs = new Integer[nElem];
        for (int i = 0; i < nElem; i++) {
            byDofs[i] = i;
        }
        Arrays.sort(byDofs, (a, b) -> Integer.compare(meshElems[a].gIndex[0], meshElems[b].gIndex[0]));
        elems = new Element[nElem];
        rank = new int[nElem];
        for (int r = 0; r < nElem; r++) {
            elems[r] = meshElems[byDofs[r]];
            rank[byDofs[r]] = r;
        }

        cols = new int[nElem][];
        diag = new int[nElem];
        blocks = new double[nElem][][][];
        diagInverse = new double[nElem][][];
        y = new double[nElem][];

        for (int i = 0; i < nElem; i++) {
            Element elem = elems[i];
            if (!elem.insideComputeDomain) {
                cols[i] = new int[0];
                blocks[i] = new double[0][][];
                continue;
            }
            int[] row = new int[elem.nFaces + 1];
            int s = 0;
            row[s++] = i;
            for (int k = 0; k < elem.nFaces; k++) {
                int t = elem.TT[k];
                if (t > -1 && meshElems[t].insideComputeDomain && !contains(row, s, rank[t])) {
                    row[s++] = rank[t];
                }
            }
            cols[i] = Arrays.copyOf(row, s);
            Arrays.sort(cols[i]);
            diag[i] = Arrays.binarySearch(cols[i], i);

            int n = blockSize(i);
            blocks[i] = new double[s][][];
            for (int k = 0; k < s; k++) {
                blocks[i][k] = new double[n][blockSize(cols[i][k])];
            }
            y[i] = new double[n];
        }
    }

    @Override
    public void factor() {
        // copy the current values of the matrix, the element Jacobi matrices are
        // stored transposed (ADiag[column][row], ANeighs[k].A[column][row])
        flowpro.core.parallel.ParallelLoop.forEach(nElem, nThreads, i -> copyBlocks(i));

        for (int i = 0; i < nElem; i++) {
            int[] row = cols[i];
            if (row.length == 0) {
                continue;
            }
            for (int kk = 0; kk < diag[i]; kk++) { // blocks left of the diagonal, ascending
                int k = row[kk];
                double[][] L = Mat.times(blocks[i][kk], diagInverse[k]);
                blocks[i][kk] = L;
                for (int jj = kk + 1; jj < row.length; jj++) {
                    int kj = Arrays.binarySearch(cols[k], row[jj]);
                    if (kj >= 0) {
                        subtractProduct(blocks[i][jj], L, blocks[k][kj]);
                    }
                }
            }
            diagInverse[i] = Mat.invert(blocks[i][diag[i]]);
            for (double[] r : diagInverse[i]) {
                for (double v : r) {
                    if (!Double.isFinite(v)) {
                        throw new ArithmeticException("singular diagonal block of element "
                                + elems[i].index + " during block ILU process");
                    }
                }
            }
        }
    }

    @Override
    public void apply(double[] x, double[] b) {
        // forward substitution L*y = b
        for (int i = 0; i < nElem; i++) {
            int[] row = cols[i];
            if (row.length == 0) {
                continue;
            }
            double[] yi = y[i];
            int[] gi = elems[i].gIndex;
            for (int r = 0; r < yi.length; r++) {
                yi[r] = b[gi[r]];
            }
            for (int kk = 0; kk < diag[i]; kk++) {
                double[][] L = blocks[i][kk];
                double[] yk = y[row[kk]];
                for (int r = 0; r < yi.length; r++) {
                    double sum = 0;
                    for (int c = 0; c < yk.length; c++) {
                        sum += L[r][c] * yk[c];
                    }
                    yi[r] -= sum;
                }
            }
        }

        // backward substitution U*x = y
        for (int i = nElem - 1; i >= 0; i--) {
            int[] row = cols[i];
            if (row.length == 0) {
                continue;
            }
            double[] rhs = y[i].clone();
            for (int jj = diag[i] + 1; jj < row.length; jj++) {
                double[][] U = blocks[i][jj];
                int[] gj = elems[row[jj]].gIndex;
                for (int r = 0; r < rhs.length; r++) {
                    double sum = 0;
                    for (int c = 0; c < gj.length; c++) {
                        sum += U[r][c] * x[gj[c]];
                    }
                    rhs[r] -= sum;
                }
            }
            double[][] iU = diagInverse[i];
            int[] gi = elems[i].gIndex;
            for (int r = 0; r < rhs.length; r++) {
                double sum = 0;
                for (int c = 0; c < rhs.length; c++) {
                    sum += iU[r][c] * rhs[c];
                }
                x[gi[r]] = sum;
            }
        }
    }

    private void copyBlocks(int i) {
        int[] row = cols[i];
        if (row.length == 0) {
            return;
        }
        for (double[][] block : blocks[i]) {
            for (double[] r : block) {
                Arrays.fill(r, 0.0);
            }
        }
        Element elem = elems[i];
        Implicit ti = (Implicit) elem.ti;
        addTransposed(blocks[i][diag[i]], ti.ADiag);
        for (int k = 0; k < elem.nFaces; k++) {
            int t = elem.TT[k];
            if (t > -1 && elems[rank[t]].insideComputeDomain) {
                addTransposed(blocks[i][Arrays.binarySearch(row, rank[t])], ti.ANeighs[k].A);
            }
        }
    }

    private int blockSize(int i) {
        return elems[i].nBasis * elems[i].getNEqs();
    }

    // B += At^T
    private static void addTransposed(double[][] B, double[][] At) {
        for (int r = 0; r < B.length; r++) {
            for (int c = 0; c < B[r].length; c++) {
                B[r][c] += At[c][r];
            }
        }
    }

    // C -= A * B
    private static void subtractProduct(double[][] C, double[][] A, double[][] B) {
        for (int r = 0; r < C.length; r++) {
            for (int m = 0; m < B.length; m++) {
                double a = A[r][m];
                if (a != 0) {
                    for (int c = 0; c < C[r].length; c++) {
                        C[r][c] -= a * B[m][c];
                    }
                }
            }
        }
    }

    private static boolean contains(int[] a, int n, int value) {
        for (int i = 0; i < n; i++) {
            if (a[i] == value) {
                return true;
            }
        }
        return false;
    }
}
