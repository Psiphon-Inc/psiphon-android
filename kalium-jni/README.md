# kalium-jni - Java JNI binding to the Networking and Cryptography (NaCl) library 

A Java JNI binding (to allow for Java and Android integration) to [Networking and Cryptography](http://nacl.cr.yp.to/) library by [Daniel J. Bernstein](http://cr.yp.to/djb.html). All the hard work of making a portable NaCl API version was done by [Frank Denis](https://github.com/jedisct1) on [libsodium](https://github.com/jedisct1/libsodium) and kalium was totally inspired by [Tony Arcieri's](https://github.com/tarcieri) work with [RbNaCl](https://github.com/cryptosphere/rbnacl) and [kalium](https://github.com/abstractj/kalium).   


## Requirements

* JDK 6 or [higher](http://www.oracle.com/technetwork/java/javase/downloads/index.html)
* [Apache Maven](http://maven.apache.org/guides/getting-started/)

## Installation

### libsodium

kalium-jni is implemented using [jni](http://docs.oracle.com/javase/6/docs/technotes/guides/jni/) and [swig](http://www.swig.org/) to bind the shared libraries from [libsodium](https://github.com/jedisct1/libsodium). For a more detailed explanation, please refer to [RbNaCl's documentation](https://github.com/cryptosphere/rbnacl/blob/master/README.md).

OS X users can get libsodium via [homebrew](http://mxcl.github.com/homebrew/) with: 

    brew install libsodium 

### kalium-jni installation

    git clone https://github.com/joshjdevl/kalium-jni && cd kalium-jni
    cd jni
    ./installswig.sh
    ./compile.sh
    cd ../
    mvn clean install
    
Add as a Maven dependency at your project:

    <dependency>
        <groupId>org.abstractj.kalium</groupId>
        <artifactId>kalium-jni</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <scope>compile</scope>
    </dependency>
        
    
### Notes

kalium-jni is a work in progress, feedback, bug reports and patches are always welcome.

[Docker build](https://github.com/joshjdevl/docker-libsodium)

[Docker container](https://index.docker.io/u/joshjdevl/docker-libsodium/)


### Issues / Improvements / Help Seeked

if anyone understands travis-ci and how to configure maven tests to find jni libraries, please submit a patch or email me
[travis-ci build](https://travis-ci.org/joshjdevl/kalium-jni/)

the blake tests have been commented out, there were some strange jni errors

Everything has been tested and working on ubuntu 12.04 32bit and 64 bit, macos, and Android

