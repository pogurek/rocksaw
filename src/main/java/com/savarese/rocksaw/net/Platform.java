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
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The operating system's socket API, called through the Foreign Function &amp;
 * Memory API (final in Java 22).  This replaces the old JNI library: each
 * subclass binds the C socket functions of one platform and hides that
 * platform's constants, struct layouts and error reporting.
 *
 * <p>Only 64-bit JVMs are supported.</p>
 */
@SuppressWarnings("restricted") // FFM downcalls are this class's purpose
abstract sealed class Platform permits PosixPlatform, WindowsPlatform {

  /** The platform the JVM is running on. */
  static final Platform INSTANCE = create();

  static final int SOCK_RAW = 3;
  static final int IPPROTO_IP = 0;

  static final int SOCKADDR_IN_SIZE = 16;
  static final int SOCKADDR_IN6_SIZE = 28;
  static final int SOCKADDR_STORAGE_SIZE = 128;

  private static Platform create() {
    if (ADDRESS.byteSize() != 8) {
      throw new UnsupportedOperationException("RockSaw requires a 64-bit JVM");
    }

    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

    if (os.startsWith("linux")) {
      return new PosixPlatform(PosixPlatform.Constants.linux(arch));
    } else if (os.startsWith("mac") || os.startsWith("darwin")) {
      return new PosixPlatform(PosixPlatform.Constants.macos());
    } else if (os.startsWith("windows")) {
      return new WindowsPlatform();
    }

    throw new UnsupportedOperationException("RockSaw does not support " + os);
  }

  final Linker linker = Linker.nativeLinker();
  private final Linker.Option captureError;
  private final StructLayout captureLayout = Linker.Option.captureStateLayout();
  private final long errorOffset;

  /**
   * @param errorName the thread-local error the platform reports failures
   * through: {@code "errno"} or {@code "WSAGetLastError"}.
   */
  Platform(String errorName) {
    captureError = Linker.Option.captureCallState(errorName);
    errorOffset =
      captureLayout.byteOffset(MemoryLayout.PathElement.groupElement(errorName));
  }

  // -------------------------------------------------------------------------
  // Operations, one per old JNI function.

  abstract int afInet();
  abstract int afInet6();

  abstract long socket(int family, int protocol) throws IOException;
  abstract void bind(long socket, int family, byte[] address, int scopeId)
    throws IOException;
  /** @throws UnsupportedOperationException if the platform lacks SO_BINDTODEVICE. */
  abstract void bindDevice(long socket, String device) throws IOException;
  abstract void close(long socket) throws IOException;

  abstract void setIPHeaderInclude(long socket, boolean on) throws SocketException;
  abstract boolean getIPHeaderInclude(long socket) throws SocketException;
  abstract void setBufferSize(long socket, boolean send, int size)
    throws SocketException;
  abstract int getBufferSize(long socket, boolean send) throws SocketException;
  abstract void setTimeout(long socket, boolean send, int milliseconds)
    throws SocketException;
  abstract int getTimeout(long socket, boolean send) throws SocketException;

  /**
   * Waits until the socket is ready for reading or writing.
   *
   * @throws SocketTimeoutException if it is not ready in time.
   */
  abstract void awaitReady(long socket, boolean read, int milliseconds)
    throws IOException;

  /** @param address receives the source address; may be null. */
  abstract int recvfrom(long socket, byte[] data, int offset, int length,
                        int family, byte[] address) throws IOException;
  abstract int sendto(long socket, byte[] data, int offset, int length,
                      int family, byte[] address, int scopeId)
    throws IOException;

  /** @return the protocol number, or -1 if the name is unknown. */
  abstract int protocolByName(String name);

  /** Fills {@code source} on Windows; does nothing elsewhere. */
  abstract void sourceAddressForDestination(long socket, int family,
                                            byte[] destination, byte[] source)
    throws IOException;

  abstract String errorMessage(int code);
  abstract boolean isTimeout(int code);

  /** Writes the address family field(s) at the start of a sockaddr. */
  abstract void putFamily(MemorySegment sockaddr, int family);

  // -------------------------------------------------------------------------
  // Shared helpers.

  /** A downcall that records the error code of the call. */
  MethodHandle downcall(SymbolLookup lookup, String name,
                        FunctionDescriptor descriptor) {
    return linker.downcallHandle(find(lookup, name), descriptor, captureError);
  }

  /** A downcall that does not record an error code. */
  MethodHandle plainDowncall(SymbolLookup lookup, String name,
                             FunctionDescriptor descriptor) {
    return linker.downcallHandle(find(lookup, name), descriptor);
  }

  private static MemorySegment find(SymbolLookup lookup, String name) {
    return lookup.find(name)
      .orElseThrow(() -> new UnsatisfiedLinkError("Native symbol not found: " + name));
  }

  /** Allocates the segment that a downcall stores its error code in. */
  MemorySegment errorSlot(Arena arena) {
    return arena.allocate(captureLayout);
  }

  int error(MemorySegment errorSlot) {
    return errorSlot.get(JAVA_INT, errorOffset);
  }

  IOException ioError(MemorySegment errorSlot) {
    int code = error(errorSlot);
    return isTimeout(code)
      ? new SocketTimeoutException(errorMessage(code))
      : new IOException(errorMessage(code));
  }

  SocketException socketError(MemorySegment errorSlot) {
    return new SocketException(errorMessage(error(errorSlot)));
  }

  /** Wraps the {@code Throwable} that {@code invokeExact} declares. */
  static RuntimeException fatal(Throwable t) {
    if (t instanceof RuntimeException e) {
      return e;
    }
    if (t instanceof Error e) {
      throw e;
    }
    return new IllegalStateException(t);
  }

  boolean isInet6(int family) throws IOException {
    if (family == afInet6()) {
      return true;
    }
    if (family == afInet()) {
      return false;
    }
    throw new IOException("Unsupported protocol family: " + family);
  }

  /** Builds a sockaddr_in or sockaddr_in6 for the given address. */
  MemorySegment sockaddr(Arena arena, int family, byte[] address, int scopeId)
    throws IOException
  {
    boolean inet6 = isInet6(family);
    int addressLength = inet6 ? 16 : 4;

    if (address.length != addressLength) {
      throw new IllegalArgumentException(
        "Address length " + address.length + " does not match socket family");
    }

    // Arena allocations are zero-filled, so port, flow info, etc. are 0.
    MemorySegment sa =
      arena.allocate(inet6 ? SOCKADDR_IN6_SIZE : SOCKADDR_IN_SIZE, 4);

    putFamily(sa, family);
    MemorySegment.copy(address, 0, sa, JAVA_BYTE, inet6 ? 8 : 4, addressLength);

    if (inet6) {
      sa.set(JAVA_INT, 24, scopeId);
    }

    return sa;
  }

  /** Copies the address out of a sockaddr_in or sockaddr_in6. */
  static void readAddress(MemorySegment sockaddr, boolean inet6, byte[] address) {
    MemorySegment.copy(sockaddr, JAVA_BYTE, inet6 ? 8 : 4, address, 0,
                       inet6 ? 16 : 4);
  }

  /** Writes the family as a native-order 16-bit value (Linux, Windows). */
  static void putShortFamily(MemorySegment sockaddr, int family) {
    sockaddr.set(JAVA_SHORT, 0, (short) family);
  }
}
