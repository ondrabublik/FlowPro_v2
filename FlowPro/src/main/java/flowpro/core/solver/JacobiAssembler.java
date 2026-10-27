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
        flowpro.core.parallel.ParallelLoop.forEach(elems.length, par.nThreads, i -> {
            if (elems[i].insideComputeDomain) {
                ((Implicit) elems[i].ti).assembleJacobiMatrix(dt, dto);
            }
        });
    }

}
