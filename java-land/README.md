# Start

A particle simulation app leveraging java's incubator vector api. 

Features simulating tens of millions of particles depending on cpu up to hundreds of millions for the most powerful.

* Reset particle positions using keys 1 (square), 2 (multi point square), 3 (circular).
* Press 4 to load an image 
* Press space to slow down particles
* Pan with right click

## Build

Building requires enabling the incubator vector api like so.

```sh
javac --release 25 ParticleSim.java &&  java --add-modules jdk.incubator.vector --enable-preview ParticleSim  
```
