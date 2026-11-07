#
# RockSaw Testing Container Container
#
# Runs a container with Java 22 and Maven for building/testing RockSaw.
#
# Building the container:
# $ docker build -t mlaccetti/rocksaw-dev .
#
# Interactive usage:
# $ docker run -v $(pwd):/opt/rocksaw -it --rm mlaccetti/rocksaw-dev /bin/bash
#
# If you want to cache Maven dependencies between runs, you can change the run command:
# $ docker run -v $(pwd):/opt/rocksaw -v ~/.m2:/root/.m2 -it --rm mlaccetti/rocksaw-dev /bin/bash
#
# Automated build usage:
# $ docker run -v $(pwd):/opt/rocksaw --rm mlaccetti/rocksaw-dev
#
# Automated build usage with Maven cache:
# $ docker run -v $(pwd):/opt/rocksaw -v ~/.m2:/root/.m2 --rm mlaccetti/rocksaw-dev

FROM maven:3.9-eclipse-temurin-22

# No C toolchain needed: RockSaw uses the Java 22 FFM API.
# Containers run as root with CAP_NET_RAW by default, so TestPing runs.

WORKDIR /opt/rocksaw

CMD ["mvn", "-B", "clean", "test"]
