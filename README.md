# FlowProPackage
This is a root projects for [FlowPro](https://github.com/ondrabublik/FlowPro) and its modules. FlowProPackage includes [FlowPro](https://github.com/ondrabublik/FlowPro) itself, an interface [FlowProAPI](https://github.com/ondrabublik/FlowProAPI) according to which a user can create his or her modules and finally some basic modules which are implemented according to [FlowProAPI](https://github.com/ondrabublik/FlowProAPI).

FlowProPackage is not necessary for the use of 
[FlowPro](https://github.com/ondrabublik/FlowPro). 
[FlowPro](https://github.com/ondrabublik/FlowPro) can be used on its own, however FlowProPackage makes it easier to build [FlowPro](https://github.com/ondrabublik/FlowPro) along with its modules all at once.

## Built With
* [Gradle](https://gradle.org/)

## Prerequisites
* [Git](https://git-scm.com/)  
* Java 17 or newer  
* [Gradle](https://gradle.org/)

## Clone, build and run
```
git clone --recursive https://github.com/ondrabublik/FlowProPackage.git FlowProPackage
cd FlowProPackage
gradlew build
cd FlowPro
java -jar FlowPro.jar run -g examples/GAMM -s default
```

Other commands: `java -jar FlowPro.jar help` (`check`, `list`, `params`, `-p KEY=VALUE`, ...).

## GUI tools (version 2)
* `FlowProControl.jar` – the main GUI: tasks in `simulations/GUI` (one mesh and several computations per task),
  2D mesh generator, editing of parameters, running, monitoring and stopping computations, preview of results
* `MeshGenerator2D.jar` – the standalone 2D mesh generator (the same editor as in FlowProControl) and its batch mode

The changes of version 2 are described (in Czech) in [ZMENY_V2.md](ZMENY_V2.md).
