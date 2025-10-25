# Start

A particle simulation app leveraging java's incubator vector api. 

Features simulating tens of millions of particles depending on cpu up to hundreds of millions for the most powerful.

* Reset particle positions using keys 1 (square), 2 (multi point square), 3 (circular).
* Press 4 to load an image 
* Press space to slow down particles
* Pan with right click

## Build using JDK

Building requires enabling the incubator vector api like so.

```sh
javac --release 25 ParticleSim.java &&  java --add-modules jdk.incubator.vector --enable-preview ParticleSim  
```

You can build a jar that can be run using one of the launchers in the build folder like so.

```sh
jar --create --file ParticleSim.jar --main-class ParticleSim *.class
```

And then run it just like the file making sure to pass in the right args. 

```sh
java --add-modules jdk.incubator.vector --enable-preview -jar ParticleSim.jar
```

## Run using JBang (easiest option - no setup required)

The simplest way to run this is with JBang. No need to install JDK, clone the repo, or manage dependencies - JBang handles everything automatically.

1. Download JBang from [jbang.dev](https://www.jbang.dev/download/)
2. Run directly from GitHub:

```sh
jbang https://github.com/dgerrells/how-fast-is-it/blob/main/java-land/ParticleSim.java
```

That's it! JBang will automatically download the correct JDK version and run ParticleSim with all the right flags.

Of course if you have the repo downloaded you just do:

```
jbang ParticleSim.java
```

