// JNI wrapper of UMFPACK with 64-bit indices (umfpack_dl_*): the factorisation of large
// systems is not limited by the 32-bit integer workspace of umfpack_di_*.
// On failure the solution is filled with NaN, FlowPro then reduces the CFL number.
#include <jni.h>
#include <cmath>
#include <cstdio>
#include <vector>

#include "umfpack.h"
#include "umfpackwrapper_UmfpackWrapper.h"

JNIEXPORT void JNICALL Java_umfpackwrapper_UmfpackWrapper_solveUmfpack
(JNIEnv * env, jobject obj, jintArray Iarr, jintArray Jarr, jdoubleArray Harr, jdoubleArray RHSarr, jdoubleArray xoutarr) {
    jsize n = env->GetArrayLength(RHSarr);
    jsize nnz = env->GetArrayLength(Iarr);
    jsize ncol = env->GetArrayLength(Jarr);

    jint *Ai32 = env->GetIntArrayElements(Iarr, NULL);
    jint *Ap32 = env->GetIntArrayElements(Jarr, NULL);
    std::vector<int64_t> Ai(Ai32, Ai32 + nnz);
    std::vector<int64_t> Ap(Ap32, Ap32 + ncol);
    env->ReleaseIntArrayElements(Iarr, Ai32, JNI_ABORT);
    env->ReleaseIntArrayElements(Jarr, Ap32, JNI_ABORT);

    double *b = env->GetDoubleArrayElements(RHSarr, NULL);
    double *Ax = env->GetDoubleArrayElements(Harr, NULL);
    std::vector<double> x(n, NAN);

    double Control[UMFPACK_CONTROL];
    umfpack_dl_defaults(Control);
    void *Symbolic = NULL;
    void *Numeric = NULL;
    int status = umfpack_dl_symbolic(n, n, Ap.data(), Ai.data(), Ax, &Symbolic, Control, NULL);
    if (status == UMFPACK_OK) {
        status = umfpack_dl_numeric(Ap.data(), Ai.data(), Ax, Symbolic, &Numeric, Control, NULL);
        // a (numerically) singular matrix still gives a usable factorisation
        if (status == UMFPACK_OK || status == UMFPACK_WARNING_singular_matrix) {
            status = umfpack_dl_solve(UMFPACK_A, Ap.data(), Ai.data(), Ax, x.data(), b, Numeric, Control, NULL);
        }
    }
    if (status < 0) {
        fprintf(stderr, "UMFPACK error %d\n", status);
        fflush(stderr);
        std::fill(x.begin(), x.end(), NAN);
    }
    if (Symbolic) umfpack_dl_free_symbolic(&Symbolic);
    if (Numeric) umfpack_dl_free_numeric(&Numeric);

    env->ReleaseDoubleArrayElements(RHSarr, b, JNI_ABORT);
    env->ReleaseDoubleArrayElements(Harr, Ax, JNI_ABORT);
    env->SetDoubleArrayRegion(xoutarr, 0, n, x.data());
}
