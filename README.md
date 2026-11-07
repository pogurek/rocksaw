## RockSaw

TravisCI: [![Build Status](https://travis-ci.org/mlaccetti/rocksaw.png?branch=master)](https://travis-ci.org/mlaccetti/rocksaw)

A fork of RockSaw (http://www.savarese.com/software/rocksaw/) that includes Maven support.

### About

RockSaw is a simple API for performing network I/O with raw
sockets in Java.

IPv6 support was graciously funded by ByteSphere Technologies
(www.bytesphere.com).

Commercial support is provided by Savarese Software Research
Corporation (www.savarese.com).

### Requirements

RockSaw is pure Java: it calls the operating system's socket functions
through the Foreign Function & Memory API (final in Java 22), so there is
no JNI library to compile or ship.

  - Java 22 or greater, 64-bit JVM.
  - Linux (tested on x86_64), macOS or Windows. The macOS and Windows
    bindings are written from the system headers but have not been tested
    yet; reports are welcome.

### Compiling

`mvn clean package` builds the JAR. No C compiler, Makefile or JAVA_HOME
setup is needed.

The sources are in `src/main/java/com/savarese/rocksaw/net`:

  - `RawSocket.java` is the public API.
  - `Platform.java`, `PosixPlatform.java` and `WindowsPlatform.java` bind the
    native socket functions, constants and struct layouts for each OS.

### Running

RockSaw makes native calls, so grant it native access or the JVM prints
warnings (and future JDKs will refuse):

  ```
  java --enable-native-access=ALL-UNNAMED -cp ... YourApp
  ```

Executable JARs can instead declare `Enable-Native-Access: ALL-UNNAMED` in
their manifest; the `jar-with-dependencies` artifact already does.

Raw sockets need root/administrator rights. On Linux the `CAP_NET_RAW`
capability is enough. Without privileges `TestPing` is skipped; to run it
on Linux without root, use a user and network namespace:

  ```
  unshare -rn sh -c 'ip link set lo up && mvn -o test'
  ```

### Licensing

RockSaw is
  Copyright 2004-2007 by Daniel F. Savarese
  Copyright 2007-2009 by Savarese Software Research Corporation
and licensed under the Apache License 2.0 as described in the files:

  LICENSE
  NOTICE

### Notes

On most operating systems, you must have root access or administrative
privileges to use raw sockets.  If you are running a firewall, you will have to make sure it allows ICMP requests through.

The API is minimalist, yet functional. Don't hesitate to submit patches
that enhance the functionality.


### Contact

http://www.savarese.com/contact.html
