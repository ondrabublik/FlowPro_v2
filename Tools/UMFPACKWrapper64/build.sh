#!/bin/bash
# Builds FlowPro/lib/libUMFPACKWrapperLib.dll (UMFPACK with 64-bit indices) with MSYS2 MinGW64
# (packages mingw-w64-x86_64-gcc and mingw-w64-x86_64-suitesparse).
cd "$(dirname "$0")"
export PATH="/c/msys64/mingw64/bin:$PATH"
J="${JAVA_HOME:-C:/Program Files/Java/jdk-21}/include"
g++ -O2 -shared -o libUMFPACKWrapperLib.dll umfpackwrapper_UmfpackWrapper.cpp \
    -I"$J" -I"$J/win32" -IC:/msys64/mingw64/include/suitesparse -LC:/msys64/mingw64/lib -lumfpack \
    -static-libgcc -static-libstdc++ -Wl,--add-stdcall-alias
