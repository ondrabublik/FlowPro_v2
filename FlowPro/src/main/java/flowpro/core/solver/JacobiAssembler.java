/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.core.solver;

import flowpro.core.Parameters;
import flowpro.core.element.Element;
import flowpro.core.element.Implicit;

/**
 *
 * @author obublik
 */
public class JacobiAssembler {

    public Element[] elems;
    private final Parameters par;
    double dt, dto;

    public JacobiAssembler(Element[] elems, Parameters par) {
        this.elems = elems;
        this.par = par;
    }

    // vytvoreni vlaken, paralelni sestaveni lokalnich matic a plneni globalni matice
    public void assemble(double dt, double dto) {
        this.dt = dt;
        this.dto = dto;
        prepareResiduum(elems, par.nThreads);
        flowpro.core.parallel.ParallelLoop.forEach(elems.length, par.nThreads, i -> {
            if (elems[i].insideComputeDomain) {
                ((Implicit) elems[i].ti).assembleJacobiMatrix(dt, dto);
            }
        });
        releaseResiduum(elems, par.nThreads);
    }

    /**
     * Only the right hand side (residuum of the time step), without the Jacobi
     * matrix.
     */
    public void assembleResiduum(double dt, double dto) {
        prepareResiduum(elems, par.nThreads);
        flowpro.core.parallel.ParallelLoop.forEach(elems.length, par.nThreads, i -> {
            if (elems[i].insideComputeDomain) {
                ((Implicit) elems[i].ti).assembleResiduum(dt, dto);
            }
        });
        releaseResiduum(elems, par.nThreads);
    }

    // see Element.prepareResiduum
    public static void prepareResiduum(Element[] elems, int nThreads) {
        flowpro.core.parallel.ParallelLoop.forEach(elems.length, nThreads, i -> elems[i].prepareResiduum());
    }

    public static void releaseResiduum(Element[] elems, int nThreads) {
        flowpro.core.parallel.ParallelLoop.forEach(elems.length, nThreads, i -> elems[i].releaseResiduum());
    }

}
