@echo off
rem Graphical generator of 2D unstructured triangular meshes for FlowPro
cd /d "%~dp0"
start "" javaw -jar MeshGenerator2D.jar
