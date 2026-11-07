## Testing: running TestPing without root (Linux)

`TestPing` sends real ICMP echo requests through a raw socket. On Linux, raw
sockets need root or the `CAP_NET_RAW` capability. A plain `mvn test` run
as a normal user skips `TestPing`.

There are two ways to run it without root:

  1. **Network namespace:** no setup, but it only pings loopback.
  2. **Capability-enabled `java-raw`:** one-time setup with sudo, then raw
     sockets work for any Java program you start with `java-raw`.

Build the project first:

```bash
mvn clean package
```

### Option 1: user and network namespace

`unshare -rn` runs Maven as a fake root inside a private network namespace,
where it has `CAP_NET_RAW` for that namespace only:

```bash
unshare -rn sh -c "ip link set lo up && mvn -o -Dmaven.repo.local=$HOME/.m2/repository test"
```

  - `ip link set lo up`: the new namespace starts with loopback down.
  - `-o`: there is no network in the namespace, so Maven must work offline.
    The dependencies must already be in `~/.m2` from an earlier build.
  - `-Dmaven.repo.local=...`: inside the namespace you appear as root, so
    Maven would look for `/root/.m2` and fail. `$HOME` is expanded before
    `unshare` runs, so it points at your own repository.

Check that `target/surefire-reports/rocksaw.TestPing.txt` shows `Skipped: 0`.

### Option 2: `java-raw`, a capability-enabled copy of java

Linux has no group that grants raw-socket access. Instead, you can make a
copy of the `java` launcher that has `CAP_NET_RAW` and that only members of
a `rawsock` group can run.

**Security note:** any Java code run with `java-raw` can open raw sockets,
not just RockSaw.

#### One-time setup

```bash
# 1. Create the group and add yourself (log out and back in afterwards)
sudo groupadd rawsock
sudo usermod -aG rawsock "$USER"

# 2. Copy java and give the copy CAP_NET_RAW
JDK_BIN=$(dirname "$(readlink -f "$(which java)")")
sudo cp "$JDK_BIN/java" "$JDK_BIN/java-raw"
sudo chown root:rawsock "$JDK_BIN/java-raw"
sudo chmod 750 "$JDK_BIN/java-raw"
sudo setcap cap_net_raw+ep "$JDK_BIN/java-raw"

# 3. Let the linker find libjli.so
#    Programs with file capabilities ignore the $ORIGIN library path that the
#    java launcher relies on. Without this step java-raw fails with
#    "error while loading shared libraries: libjli.so".
echo "$(dirname "$JDK_BIN")/lib" | sudo tee /etc/ld.so.conf.d/java-raw.conf
sudo ldconfig
```

Check the setup:

```bash
id | grep rawsock            # you are in the group
getcap "$JDK_BIN/java-raw"   # prints cap_net_raw=ep
ldconfig -p | grep libjli    # lists libjli.so
"$JDK_BIN/java-raw" -version
```

Optionally, put `java-raw` on your PATH, for example in `~/.bashrc`:

```bash
alias java-raw="$JDK_BIN/java-raw"   # replace $JDK_BIN with the actual path
```

The setup belongs to one JDK. If you upgrade or remove that JDK, repeat
steps 2 and 3 for the new one.

#### Running TestPing with java-raw

Maven starts tests with plain `java`, so run JUnit directly instead. From
the project root, after `mvn package`:

```bash
M=~/.m2/repository
CP=target/classes:target/test-classes
CP=$CP:$M/com/github/mlaccetti/vserv-tcpip/0.9.2.1/vserv-tcpip-0.9.2.1.jar
CP=$CP:$M/junit/junit/4.12/junit-4.12.jar
CP=$CP:$M/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
CP=$CP:$M/org/slf4j/slf4j-api/1.7.19/slf4j-api-1.7.19.jar
CP=$CP:$M/org/slf4j/slf4j-simple/1.7.19/slf4j-simple-1.7.19.jar

java-raw --enable-native-access=ALL-UNNAMED \
    -Dorg.slf4j.simpleLogger.defaultLogLevel=debug \
    -cp "$CP" \
    org.junit.runner.JUnitCore rocksaw.TestPing
```

`-Dorg.slf4j.simpleLogger.defaultLogLevel=debug` prints each ping. Without it
you only see the result line. Expected output:

```
JUnit version 4.12
.[Time-limited test] DEBUG rocksaw.TestPing - PING localhost (127.0.0.1) 56 (84) bytes of data).
[Time-limited test] DEBUG rocksaw.TestPing - 64 bytes from localhost (/127.0.0.1): icmp_seq=0 ttl=64 time=9.260425 ms
...
[Time-limited test] DEBUG rocksaw.TestPing - 64 bytes from localhost (/127.0.0.1): icmp_seq=4 ttl=64 time=0.706163 ms

Time: 4.457

OK (1 test)
```

The classes must be compiled for a Java version that `java-raw`'s JDK can
run. The build targets `--release 22`, so any JDK 22 or newer works.
