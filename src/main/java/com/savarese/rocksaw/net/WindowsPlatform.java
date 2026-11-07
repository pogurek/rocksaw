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
import java.lang.invoke.MethodHandle;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * 64-bit Windows, through Winsock 2 (ws2_32.dll).  A SOCKET is a 64-bit
 * handle, lengths are 32-bit ints and timeouts are plain milliseconds.
 */
@SuppressWarnings("restricted") // FFM downcalls are this class's purpose
final class WindowsPlatform extends Platform {

  private static final int AF_INET = 2;
  private static final int AF_INET6 = 23;
  private static final int SOL_SOCKET = 0xffff;
  private static final int SO_SNDBUF = 0x1001;
  private static final int SO_RCVBUF = 0x1002;
  private static final int SO_SNDTIMEO = 0x1005;
  private static final int SO_RCVTIMEO = 0x1006;
  private static final int IP_HDRINCL = 2;
  private static final int SIO_ROUTING_INTERFACE_QUERY = 0xC8000014;
  private static final int WSAEWOULDBLOCK = 10035;
  private static final int WSAETIMEDOUT = 10060;
  private static final short POLLRDNORM = 0x0100;
  private static final short POLLWRNORM = 0x0010;
  private static final short POLLERR = 0x0001;
  private static final short POLLNVAL = 0x0004;
  private static final int FORMAT_MESSAGE_FROM_SYSTEM = 0x00001000;
  private static final int FORMAT_MESSAGE_IGNORE_INSERTS = 0x00000200;
  private static final int WSADATA_SIZE = 512; // 408 bytes on x64, rounded up

  private final MethodHandle socket, bind, closesocket, setsockopt, getsockopt,
                             wsaPoll, recvfrom, sendto, getprotobyname,
                             wsaIoctl, formatMessage;

  WindowsPlatform() {
    super("WSAGetLastError");

    SymbolLookup ws2 = SymbolLookup.libraryLookup("ws2_32", Arena.global());
    SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

    socket = downcall(ws2, "socket",
      FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT));
    bind = downcall(ws2, "bind",
      FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT));
    closesocket = downcall(ws2, "closesocket",
      FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    setsockopt = downcall(ws2, "setsockopt",
      FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    getsockopt = downcall(ws2, "getsockopt",
      FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    wsaPoll = downcall(ws2, "WSAPoll",
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    recvfrom = downcall(ws2, "recvfrom",
      FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT,
                            ADDRESS, ADDRESS));
    sendto = downcall(ws2, "sendto",
      FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT,
                            ADDRESS, JAVA_INT));
    getprotobyname = plainDowncall(ws2, "getprotobyname",
      FunctionDescriptor.of(ADDRESS, ADDRESS));
    wsaIoctl = downcall(ws2, "WSAIoctl",
      FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT,
                            ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    formatMessage = plainDowncall(kernel32, "FormatMessageW",
      FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
                            ADDRESS, JAVA_INT, ADDRESS));

    MethodHandle wsaStartup = plainDowncall(ws2, "WSAStartup",
      FunctionDescriptor.of(JAVA_INT, JAVA_SHORT, ADDRESS));
    MethodHandle wsaCleanup = plainDowncall(ws2, "WSACleanup",
      FunctionDescriptor.of(JAVA_INT));

    int result;
    try (Arena arena = Arena.ofConfined()) {
      result = (int) wsaStartup.invokeExact((short) 0x0202, arena.allocate(WSADATA_SIZE, 8));
    } catch (Throwable t) {
      throw fatal(t);
    }
    if (result != 0) {
      throw new UnsatisfiedLinkError("WSAStartup failed: " + errorMessage(result));
    }

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        int _ = (int) wsaCleanup.invokeExact();
      } catch (Throwable ignored) {
        // Exiting anyway.
      }
    }, "rocksaw-wsacleanup"));
  }

  @Override int afInet() { return AF_INET; }
  @Override int afInet6() { return AF_INET6; }

  @Override
  void putFamily(MemorySegment sockaddr, int family) {
    putShortFamily(sockaddr, family);
  }

  @Override
  long socket(int family, int protocol) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      long s;
      try {
        s = (long) socket.invokeExact(err, family, SOCK_RAW, protocol);
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (s == -1L) { // INVALID_SOCKET
        throw ioError(err);
      }
      return s;
    }
  }

  @Override
  void bind(long s, int family, byte[] address, int scopeId) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      MemorySegment sa = sockaddr(arena, family, address, scopeId);
      int result;
      try {
        result = (int) bind.invokeExact(err, s, sa, (int) sa.byteSize());
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (result != 0) {
        throw ioError(err);
      }
    }
  }

  @Override
  void bindDevice(long s, String device) {
    throw new UnsupportedOperationException(
      "SO_BINDTODEVICE is not supported on Windows");
  }

  @Override
  void close(long s) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      int result;
      try {
        result = (int) closesocket.invokeExact(err, s);
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (result != 0) {
        throw ioError(err);
      }
    }
  }

  @Override
  void setIPHeaderInclude(long s, boolean on) throws SocketException {
    setIntOption(s, IPPROTO_IP, IP_HDRINCL, on ? 1 : 0);
  }

  @Override
  boolean getIPHeaderInclude(long s) throws SocketException {
    return getIntOption(s, IPPROTO_IP, IP_HDRINCL) != 0;
  }

  @Override
  void setBufferSize(long s, boolean send, int size) throws SocketException {
    setIntOption(s, SOL_SOCKET, send ? SO_SNDBUF : SO_RCVBUF, size);
  }

  @Override
  int getBufferSize(long s, boolean send) throws SocketException {
    return getIntOption(s, SOL_SOCKET, send ? SO_SNDBUF : SO_RCVBUF);
  }

  @Override
  void setTimeout(long s, boolean send, int milliseconds) throws SocketException {
    setIntOption(s, SOL_SOCKET, send ? SO_SNDTIMEO : SO_RCVTIMEO, milliseconds);
  }

  @Override
  int getTimeout(long s, boolean send) throws SocketException {
    return getIntOption(s, SOL_SOCKET, send ? SO_SNDTIMEO : SO_RCVTIMEO);
  }

  @Override
  void awaitReady(long s, boolean read, int milliseconds) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      // WSAPOLLFD { SOCKET fd; SHORT events; SHORT revents; }
      MemorySegment pfd = arena.allocate(16, 8);
      pfd.set(JAVA_LONG, 0, s);
      pfd.set(JAVA_SHORT, 8, read ? POLLRDNORM : POLLWRNORM);

      int result;
      try {
        result = (int) wsaPoll.invokeExact(err, pfd, 1, milliseconds);
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
      if ((pfd.get(JAVA_SHORT, 10) & (POLLERR | POLLNVAL)) != 0) {
        throw new IOException("Socket error");
      }
    }
  }

  @Override
  int recvfrom(long s, byte[] data, int offset, int length, int family,
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

      int n;
      try {
        n = (int) recvfrom.invokeExact(err, s, buffer, length, 0, sa, saLength);
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (n < 0) {
        throw ioError(err);
      }

      MemorySegment.copy(buffer, JAVA_BYTE, 0, data, offset, n);
      if (address != null) {
        readAddress(sa, inet6, address);
      }
      return n;
    }
  }

  @Override
  int sendto(long s, byte[] data, int offset, int length, int family,
             byte[] address, int scopeId) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      MemorySegment sa = sockaddr(arena, family, address, scopeId);
      MemorySegment buffer = arena.allocate(Math.max(length, 1));
      MemorySegment.copy(data, offset, buffer, JAVA_BYTE, 0, length);

      int n;
      try {
        n = (int) sendto.invokeExact(err, s, buffer, length, 0, sa,
                                     (int) sa.byteSize());
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (n < 0) {
        throw ioError(err);
      }
      return n;
    }
  }

  @Override
  synchronized int protocolByName(String name) {
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
      // struct protoent { char *p_name; char **p_aliases; short p_proto; }
      return proto.reinterpret(24).get(JAVA_SHORT, 16);
    }
  }

  @Override
  void sourceAddressForDestination(long s, int family, byte[] destination,
                                   byte[] source) throws IOException {
    boolean inet6 = isInet6(family);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      MemorySegment sa = sockaddr(arena, family, destination, 0);
      MemorySegment ifc = arena.allocate(SOCKADDR_STORAGE_SIZE, 8);
      MemorySegment returned = arena.allocate(JAVA_INT);

      int result;
      try {
        result = (int) wsaIoctl.invokeExact(err, s, SIO_ROUTING_INTERFACE_QUERY,
                                            sa, (int) sa.byteSize(),
                                            ifc, SOCKADDR_STORAGE_SIZE, returned,
                                            MemorySegment.NULL, MemorySegment.NULL);
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (result != 0) {
        throw ioError(err);
      }

      boolean ifcInet6 = ifc.get(JAVA_SHORT, 0) == AF_INET6;
      if (ifcInet6 != inet6 || source.length < (inet6 ? 16 : 4)) {
        throw new IOException("Unexpected routing interface address");
      }
      readAddress(ifc, inet6, source);
    }
  }

  @Override
  String errorMessage(int code) {
    try (Arena arena = Arena.ofConfined()) {
      int chars = 512;
      MemorySegment buffer = arena.allocate(chars * 2L, 2);
      int length;
      try {
        length = (int) formatMessage.invokeExact(
          FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
          MemorySegment.NULL, code, 0, buffer, chars, MemorySegment.NULL);
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (length == 0) {
        return "Winsock error " + code;
      }
      return buffer.getString(0, StandardCharsets.UTF_16LE).strip();
    }
  }

  @Override
  boolean isTimeout(int code) {
    return code == WSAETIMEDOUT || code == WSAEWOULDBLOCK;
  }

  // -------------------------------------------------------------------------

  private void setIntOption(long s, int level, int option, int value)
    throws SocketException
  {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      MemorySegment v = arena.allocate(JAVA_INT);
      v.set(JAVA_INT, 0, value);
      int result;
      try {
        result = (int) setsockopt.invokeExact(err, s, level, option, v, Integer.BYTES);
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (result != 0) {
        throw socketError(err);
      }
    }
  }

  private int getIntOption(long s, int level, int option) throws SocketException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      MemorySegment v = arena.allocate(JAVA_INT);
      MemorySegment length = arena.allocate(JAVA_INT);
      length.set(JAVA_INT, 0, Integer.BYTES);
      int result;
      try {
        result = (int) getsockopt.invokeExact(err, s, level, option, v, length);
      } catch (Throwable t) {
        throw fatal(t);
      }
      if (result != 0) {
        throw socketError(err);
      }
      return v.get(JAVA_INT, 0);
    }
  }
}
