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

/**
 *
 * @author obublik
 */
class BlockJacobiInversion extends Preconditioner {

    SparseMatrix A;
    int n, nThreads;
    double[][][] diagonalInverse;
    Element[] elems;

    BlockJacobiInversion(Parameters par) {
        nThreads = par.nThreads;
    }

    @Override
    public void setMatrix(SparseMatrix A) {
        this.A = A;
        n = A.getDofs();
        elems = A.getElems();
        diagonalInverse = new double[elems.length][][];
    }

    @Override
    public void factor() {
        flowpro.core.parallel.ParallelLoop.forEach(elems.length, nThreads, i -> {
            if (elems[i].insideComputeDomain) {
                diagonalInverse[i] = Mat.invert(((Implicit) elems[i].ti).ADiag);
            }
        });
    }

    @Override
    public void apply(double[] x, double[] b) {
        flowpro.core.parallel.ParallelLoop.forEach(elems.length, nThreads, i -> {
            Element elem = elems[i];
            if (elem.insideComputeDomain) {
                int[] glob = elem.gIndex;
                double[][] M = diagonalInverse[i];
                for (int j = 0; j < glob.length; j++) {
                    double sum = 0;
                    for (int k = 0; k < glob.length; k++) {
                        sum += M[k][j] * b[glob[k]];
                    }
                    x[glob[j]] = sum;
                }
            }
        });
    }

}
