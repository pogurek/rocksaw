/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.savarese.com/software/ApacheLicense-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.savarese.rocksaw.net;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** Linux and macOS, through the C library's BSD socket functions. */
@SuppressWarnings("restricted") // FFM downcalls are this class's purpose
final class PosixPlatform extends Platform {

  /** The constants that differ between POSIX systems. */
  record Constants(int afInet, int afInet6, boolean hasSockaddrLength,
                   int solSocket, int soSndbuf, int soRcvbuf,
                   int soSndtimeo, int soRcvtimeo, int soBindToDevice,
                   int ipHdrincl, int eagain, ValueLayout usecLayout,
                   ValueLayout nfdsLayout) {

    /** Linux on 64-bit x86, ARM, RISC-V, s390x and POWER. */
    static Constants linux(String arch) {
      boolean power = arch.startsWith("ppc");
      return new Constants(2, 10, false,
                           1, 7, 8,
                           power ? 19 : 21, power ? 18 : 20, 25,
                           3, 11, JAVA_LONG, JAVA_LONG);
    }

    /** macOS (sockaddr starts with a length byte; suseconds_t is 32 bits). */
    static Constants macos() {
      return new Constants(2, 30, true,
                           0xffff, 0x1001, 0x1002,
                           0x1005, 0x1006, -1,
                           2, 35, JAVA_INT, JAVA_INT);
    }
  }

  private static final short POLLIN = 0x1;
  private static final short POLLOUT = 0x4;
  private static final short POLLERR = 0x8;
  private static final short POLLNVAL = 0x20;
  private static final long TIMEVAL_SIZE = 16;

  private final Constants c;

  private final MethodHandle socket, bind, close, setsockopt, getsockopt, poll,
                             recvfrom, sendto, getprotobyname, strerror;

  PosixPlatform(Constants constants) {
    super("errno");
    c = constants;

    SymbolLookup libc = linker.defaultLookup();

    socket = downcall(libc, "socket",
      FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    bind = downcall(libc, "bind",
      FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    close = downcall(libc, "close",
      FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    setsockopt = downcall(libc, "setsockopt",
      FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    getsockopt = downcall(libc, "getsockopt",
      FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    poll = downcall(libc, "poll",
      FunctionDescriptor.of(JAVA_INT, ADDRESS, c.nfdsLayout(), JAVA_INT));
    recvfrom = downcall(libc, "recvfrom",
      FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT,
                            ADDRESS, ADDRESS));
    sendto = downcall(libc, "sendto",
      FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT,
                            ADDRESS, JAVA_INT));
    getprotobyname = plainDowncall(libc, "getprotobyname",
      FunctionDescriptor.of(ADDRESS, ADDRESS));
    strerror = plainDowncall(libc, "strerror",
      FunctionDescriptor.of(ADDRESS, JAVA_INT));
  }

  @Override int afInet() { return c.afInet(); }
  @Override int afInet6() { return c.afInet6(); }

  @Override
  void putFamily(MemorySegment sockaddr, int family) {
    if (c.hasSockaddrLength()) {
      sockaddr.set(JAVA_BYTE, 0, (byte) sockaddr.byteSize());
      sockaddr.set(JAVA_BYTE, 1, (byte) family);
    } else {
      putShortFamily(sockaddr, family);
    }
  }

  @Override
  long socket(int family, int protocol) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      int fd;
      try {
        fd = (int) socket.invokeExact(err, family, SOCK_RAW, protocol);
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (fd < 0) {
        throw ioError(err);
      }
      return fd;
    }
  }

  @Override
  void bind(long fd, int family, byte[] address, int scopeId) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      MemorySegment sa = sockaddr(arena, family, address, scopeId);
      int result;
      try {
        result = (int) bind.invokeExact(err, (int) fd, sa, (int) sa.byteSize());
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (result != 0) {
        throw ioError(err);
      }
    }
  }

  @Override
  void bindDevice(long fd, String device) throws IOException {
    if (c.soBindToDevice() < 0) {
      throw new UnsupportedOperationException(
        "SO_BINDTODEVICE is not supported on this platform");
    }
    try (Arena arena = Arena.ofConfined()) {
      byte[] name = device.getBytes(StandardCharsets.UTF_8);
      MemorySegment value = arena.allocate(name.length + 1);
      MemorySegment.copy(name, 0, value, JAVA_BYTE, 0, name.length);
      setOption(arena, fd, c.solSocket(), c.soBindToDevice(), value, name.length);
    }
  }

  @Override
  void close(long fd) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      int result;
      try {
        result = (int) close.invokeExact(err, (int) fd);
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (result != 0) {
        throw ioError(err);
      }
    }
  }

  @Override
  void setIPHeaderInclude(long fd, boolean on) throws SocketException {
    setIntOption(fd, IPPROTO_IP, c.ipHdrincl(), on ? 1 : 0);
  }

  @Override
  boolean getIPHeaderInclude(long fd) throws SocketException {
    return getIntOption(fd, IPPROTO_IP, c.ipHdrincl()) != 0;
  }

  @Override
  void setBufferSize(long fd, boolean send, int size) throws SocketException {
    setIntOption(fd, c.solSocket(), send ? c.soSndbuf() : c.soRcvbuf(), size);
  }

  @Override
  int getBufferSize(long fd, boolean send) throws SocketException {
    return getIntOption(fd, c.solSocket(), send ? c.soSndbuf() : c.soRcvbuf());
  }

  @Override
  void setTimeout(long fd, boolean send, int milliseconds) throws SocketException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment tv = arena.allocate(TIMEVAL_SIZE, 8);
      tv.set(JAVA_LONG, 0, milliseconds / 1000);
      putUsec(tv, (milliseconds % 1000) * 1000L);
      setOption(arena, fd, c.solSocket(), send ? c.soSndtimeo() : c.soRcvtimeo(),
                tv, (int) TIMEVAL_SIZE);
    }
  }

  @Override
  int getTimeout(long fd, boolean send) throws SocketException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment tv = arena.allocate(TIMEVAL_SIZE, 8);
      getOption(arena, fd, c.solSocket(), send ? c.soSndtimeo() : c.soRcvtimeo(), tv);
      return (int) (tv.get(JAVA_LONG, 0) * 1000 + getUsec(tv) / 1000);
    }
  }

  private void putUsec(MemorySegment tv, long usec) {
    if (c.usecLayout() == JAVA_INT) {
      tv.set(JAVA_INT, 8, (int) usec);
    } else {
      tv.set(JAVA_LONG, 8, usec);
    }
  }

  private long getUsec(MemorySegment tv) {
    return c.usecLayout() == JAVA_INT ? tv.get(JAVA_INT, 8) : tv.get(JAVA_LONG, 8);
  }

  @Override
  void awaitReady(long fd, boolean read, int milliseconds) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      // struct pollfd { int fd; short events; short revents; }
      MemorySegment pfd = arena.allocate(8, 4);
      pfd.set(JAVA_INT, 0, (int) fd);
      pfd.set(JAVA_SHORT, 4, read ? POLLIN : POLLOUT);

      int result;
      try {
        // invoke, not invokeExact: nfds_t is 32 bits on macOS, 64 on Linux.
        result = (int) poll.invoke(err, pfd, 1, milliseconds);
      } catch (Throwable t) {
        throw fatal(t);
      }

      if (result < 0) {
        throw ioError(err);
      }
      if (result == 0) {
        throw new SocketTimeoutException(
          (read ? "Receive" : "Send") + " timed out");
      }
      if ((pfd.get(JAVA_SHORT, 6) & (POLLERR | POLLNVAL)) != 0) {
        throw new IOException("Socket error");
      }
    }
  }

  @Override
  int recvfrom(long fd, byte[] data, int offset, int length, int family,
               byte[] address) throws IOException {
    boolean inet6 = isInet6(family);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      MemorySegment buffer = arena.allocate(Math.max(length, 1));
      MemorySegment sa = MemorySegment.NULL;
      MemorySegment saLength = MemorySegment.NULL;

      if (address != null) {
        sa = arena.allocate(SOCKADDR_STORAGE_SIZE, 8);
        saLength = arena.allocate(JAVA_INT);
        saLength.set(JAVA_INT, 0, SOCKADDR_STORAGE_SIZE);
      }

      long n;
      try {
        n = (long) recvfrom.invokeExact(err, (int) fd, buffer, (long) length, 0,
                                        sa, saLength);
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (n < 0) {
        throw ioError(err);
      }

      MemorySegment.copy(buffer, JAVA_BYTE, 0, data, offset, (int) n);
      if (address != null) {
        readAddress(sa, inet6, address);
      }
      return (int) n;
    }
  }

  @Override
  int sendto(long fd, byte[] data, int offset, int length, int family,
             byte[] address, int scopeId) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      MemorySegment sa = sockaddr(arena, family, address, scopeId);
      MemorySegment buffer = arena.allocate(Math.max(length, 1));
      MemorySegment.copy(data, offset, buffer, JAVA_BYTE, 0, length);

      long n;
      try {
        n = (long) sendto.invokeExact(err, (int) fd, buffer, (long) length, 0,
                                      sa, (int) sa.byteSize());
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (n < 0) {
        throw ioError(err);
      }
      return (int) n;
    }
  }

  @Override
  synchronized int protocolByName(String name) {
    // getprotobyname returns a pointer to static storage; hence synchronized.
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment proto;
      try {
        proto = (MemorySegment) getprotobyname.invokeExact(arena.allocateFrom(name));
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (proto.equals(MemorySegment.NULL)) {
        return -1;
      }
      // struct protoent { char *p_name; char **p_aliases; int p_proto; }
      return proto.reinterpret(24).get(JAVA_INT, 16);
    }
  }

  @Override
  void sourceAddressForDestination(long fd, int family, byte[] destination,
                                   byte[] source) {
    // Only needed on Windows; the kernel computes ICMPv6 checksums here.
  }

  @Override
  String errorMessage(int code) {
    MemorySegment message;
    try {
      message = (MemorySegment) strerror.invokeExact(code);
    } catch (Throwable t) {
      throw fatal(t);
    }
    return message.equals(MemorySegment.NULL)
      ? "errno " + code
      : message.reinterpret(Integer.MAX_VALUE).getString(0);
  }

  @Override
  boolean isTimeout(int code) {
    return code == c.eagain();
  }

  // -------------------------------------------------------------------------

  private void setIntOption(long fd, int level, int option, int value)
    throws SocketException
  {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment v = arena.allocate(JAVA_INT);
      v.set(JAVA_INT, 0, value);
      setOption(arena, fd, level, option, v, Integer.BYTES);
    }
  }

  private int getIntOption(long fd, int level, int option) throws SocketException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment v = arena.allocate(JAVA_INT);
      getOption(arena, fd, level, option, v);
      return v.get(JAVA_INT, 0);
    }
  }

  private void setOption(Arena arena, long fd, int level, int option,
                         MemorySegment value, int length) throws SocketException {
    MemorySegment err = errorSlot(arena);
    int result;
    try {
      result = (int) setsockopt.invokeExact(err, (int) fd, level, option, value, length);
    } catch (Throwable t) {
      throw fatal(t);
    }
    if (result != 0) {
      throw socketError(err);
    }
  }

  private void getOption(Arena arena, long fd, int level, int option,
                         MemorySegment value) throws SocketException {
    MemorySegment err = errorSlot(arena);
    MemorySegment length = arena.allocate(JAVA_INT);
    length.set(JAVA_INT, 0, (int) value.byteSize());
    int result;
    try {
      result = (int) getsockopt.invokeExact(err, (int) fd, level, option, value, length);
    } catch (Throwable t) {
      throw fatal(t);
    }
    if (result != 0) {
      throw socketError(err);
    }
  }
}
