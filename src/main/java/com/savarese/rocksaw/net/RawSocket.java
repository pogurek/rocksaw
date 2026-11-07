/* Copyright 2004-2008 Daniel F. Savarese
 * Copyright 2009-2014 Savarese Software Research Corporation
 *
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

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;

/**
 * <p>The RawSocket class provides a strictly utilitarian API for
 * performing I/O with IPv4 and IPv6 raw sockets.  The API is
 * minimalist, yet functional.</p>
 *
 * <p>The native socket functions are called through the Java 22
 * Foreign Function &amp; Memory API, so no JNI library is needed.
 * Linux, macOS and Windows on 64-bit JVMs are supported.  Grant native
 * access to avoid JVM warnings, e.g. with
 * {@code --enable-native-access=ALL-UNNAMED}.</p>
 *
 * <p>Read and write timeouts throw {@link java.net.SocketTimeoutException},
 * which is a subclass of {@link InterruptedIOException}.</p>
 *
 * <p>Socket options should not be set until the socket has been
 * opened.</p>
 *
 * <p><em>Important!  On most operating systems, you must have root
 * access or administrative privileges to use raw sockets.</em>  On Linux,
 * the {@code CAP_NET_RAW} capability is enough.</p>
 */
public class RawSocket implements Closeable {

  private static final Platform platform = Platform.INSTANCE;

  /**
   * A protocol family constant for {@link #open} indicating IPv4.
   * <em>This should be moved to another class.</em>
   */
  public static final int PF_INET = platform.afInet();

  /**
   * A protocol family constant for {@link #open} indicating IPv6.
   * <em>This should be moved to another class.</em>
   */
  public static final int PF_INET6 = platform.afInet6();

  private static final int UNDEFINED = -1;

  private long socket = UNDEFINED;
  private int family = UNDEFINED;
  private int sendTimeout, receiveTimeout;
  private boolean useSelectTimeout;

  /** Creates an uninitialized socket. */
  public RawSocket() {
  }

  /**
   * Tests if the socket has been opened.
   *
   * @return True if the socket is open.
   */
  public boolean isOpen() {
    return socket != UNDEFINED;
  }

  private int getScopeId(InetAddress address) {
    if (family == PF_INET6 && address instanceof Inet6Address inet6) {
      return inet6.getScopeId();
    }
    return 0;
  }

  /**
   * <p>Returns the protocol number corresponding to the given protocol name.
   * For example, {@code getProtocolByName("icmp");} should return 1 and
   * {@code getProtocolByName("udp");} should return 17.  The native system
   * protocol database is used to look up the protocol numbers.</p>
   *
   * <p><em>This method really belongs in another class, probably in
   * vserv-tcpip, but is currently included here as a convenience.  It
   * may be moved elsewhere in the 1.0 release API.</em></p>
   *
   * @return The protocol number corresponding to the given protocol name.
   *         If the protocol name cannot be found, returns a negative value.
   */
  public static final int getProtocolByName(String name) {
    return platform.protocolByName(name);
  }

  /**
   * <p>Returns by out parameter the address of the network interface that
   * will be used to send a packet to the given destination.  This
   * works on Windows only and is necessary in order to compute ICMPv6
   * checksums.</p>
   *
   * <p><em>This method really belongs in another class,, but is
   * currently included here for expediency.  It may be moved
   * elsewhere in the 1.0 release API.</em></p>
   *
   * @param destination The address of the destination.
   * @param source A byte array in which to store the returned source
   * address.  On platforms other than Microsoft Windows, the array is
   * left unchanged.
   * @exception IOException If an I/O error occurs.
   */
  public void getSourceAddressForDestination(InetAddress destination,
                                             byte[] source)
    throws IOException
  {
    platform.sourceAddressForDestination(socket, family,
                                         destination.getAddress(), source);
  }

  /**
   * Opens a raw socket.
   *
   * @param protocolFamily The protocol family of the socket (e.g.,
   * {@link #PF_INET} or {@link #PF_INET6}).
   * @param protocol The protocol within the protocol family.  {@link
   * #getProtocolByName} should be used to obtain protocol numbers.
   * @exception IllegalStateException If the object instance is
   * already open.
   * @exception IOException If an error occurs while opening the socket.
   */
  public void open(int protocolFamily, int protocol)
    throws IllegalStateException, IOException
  {
    if (isOpen()) {
      throw new IllegalStateException();
    }
    socket = platform.socket(protocolFamily, protocol);
    family = protocolFamily;
  }

  /**
   * Binds a local network address to a previously opened raw socket.
   * On most operating systems, binding a raw socket to an address
   * causes only packets with a destination matching the address to be
   * delivered to the socket.  Also, the kernel will set the source
   * address of outbound packets to the bound address (unless {@link
   * #setIPHeaderInclude setIPHeaderInclude(true)} has been called).
   *
   * @param address The address to bind.
   * @exception IllegalStateException If the socket has not been opened first.
   * @exception IOException If the address cannot be bound.
   */
  public void bind(InetAddress address)
    throws IllegalStateException, IOException
  {
    if (!isOpen()) {
      throw new IllegalStateException();
    }
    platform.bind(socket, family, address.getAddress(), getScopeId(address));
  }

  /**
   * Binds a network device (e.g., eth0) to a previously opened raw socket.
   * This is implemented currently only for Linux using the SO_BINDTODEVICE
   * socket option.  Sent packets will leave through the bound device and
   * only packets arriving via the device will be delivered to the socket.
   *
   * @param device The name of the device to bind (e.g., "eth0").
   * Passing a zero-length string will remove the current binding.
   * The loopback interface ("lo") and device aliases (e.g., "eth0:1")
   * cannot be bound.
   * @exception IllegalStateException If the socket has not been opened first.
   * @exception UnsupportedOperationException If binding a device
   * name is not supported on the runtime platform.
   * @exception IOException If the device cannot be bound.
   */
  public void bindDevice(String device)
    throws UnsupportedOperationException, IllegalStateException, IOException
  {
    if (!isOpen()) {
      throw new IllegalStateException();
    }
    platform.bindDevice(socket, device);
  }

  /**
   * Closes the socket.  Closing a socket that is not open has no effect.
   *
   * @exception IOException If an I/O error occurs.
   */
  @Override
  public void close() throws IOException {
    if (!isOpen()) {
      return;
    }
    long s = socket;
    socket = UNDEFINED;
    family = UNDEFINED;
    platform.close(s);
  }

  /**
   * Sets or unsets the IP_HDRINCL socket option.  Setting this option
   * causes IPv4 packet writes to expect the entire IP packet,
   * starting from the header.  The default behavior is to only expect
   * the data payload.  This option is valid only for IPv4 sockets.
   *
   * @param on True if headers should be included, false if not.
   * @exception SocketException If the option setting could not be altered.
   */
  public void setIPHeaderInclude(boolean on) throws SocketException {
    platform.setIPHeaderInclude(socket, on);
  }

  /**
   * Retrieves the current setting of the IP_HDRINCL option.
   *
   * @return True if the IP_HDRINCL option is set, false if not.
   * @exception SocketException If the option value could not be retrieved.
   */
  public boolean getIPHeaderInclude() throws SocketException {
    return platform.getIPHeaderInclude(socket);
  }

  /**
   * Sets the send buffer size (SO_SNDBUF).
   *
   * @param size The size of the send buffer.
   * @exception SocketException If the option value could not be set.
   */
  public void setSendBufferSize(int size) throws SocketException {
    platform.setBufferSize(socket, true, size);
  }

  /**
   * Retrieves the send buffer size (SO_SNDBUF).
   *
   * @return The size of the send buffer.
   * @exception SocketException If the option value could not be retrieved.
   */
  public int getSendBufferSize() throws SocketException {
    return platform.getBufferSize(socket, true);
  }

  /**
   * Sets the receive buffer size (SO_RCVBUF).
   *
   * @param size The size of the receive buffer.
   * @exception SocketException If the option value could not be set.
   */
  public void setReceiveBufferSize(int size) throws SocketException {
    platform.setBufferSize(socket, false, size);
  }

  /**
   * Retrieves the receive buffer size (SO_RCVBUF).
   *
   * @return The size of the receive buffer.
   * @exception SocketException If the option value could not be retrieved.
   */
  public int getReceiveBufferSize() throws SocketException {
    return platform.getBufferSize(socket, false);
  }

  /**
   * <p>Sets whether or not socket send/receive timeouts should be
   * emulated by waiting for readiness with the POSIX {@code poll}
   * function ({@code WSAPoll} on Windows).  Not all platforms support
   * socket send/receive timeouts and this method provides a means to
   * reproduce the same effect.</p>
   *
   * @param useSelect true if {@code poll} should be used to
   * implement timeouts, false if not.
   */
  public void setUseSelectTimeout(boolean useSelect) {
    useSelectTimeout = useSelect;
  }

  /**
   * <p>Determines whether or not socket send/receive timeouts are
   * emulated by using {@code poll}.  The default value is false.</p>
   *
   * @return True if send/receive timeouts are emulated with poll,
   * false if not.
   */
  public boolean getUseSelectTimeout() {
    return useSelectTimeout;
  }

  /**
   * Sets the send timeout (SO_SNDTIMEO).  A timeout of zero indicates
   * an infinite timeout.  A negative timeout is undefined.
   *
   * @param timeout The send timeout in milliseconds.
   * @exception SocketException If the option value could not be set.
   */
  public void setSendTimeout(int timeout) throws SocketException {
    sendTimeout = timeout;

    if (!getUseSelectTimeout()) {
      platform.setTimeout(socket, true, timeout);
    }
  }

  /**
   * Retrieves the send timeout (SO_SNDTIMEO).
   *
   * @return The send timeout in milliseconds.
   * @exception SocketException If the option value could not be set.
   */
  public int getSendTimeout() throws SocketException {
    return getUseSelectTimeout()
      ? sendTimeout
      : platform.getTimeout(socket, true);
  }

  /**
   * Sets the receive timeout (SO_RCVTIMEO).  A timeout of zero indicates
   * an infinite timeout.  A negative timeout is undefined.
   *
   * @param timeout The receive timeout in milliseconds.
   * @exception SocketException If the option value could not be set.
   */
  public void setReceiveTimeout(int timeout) throws SocketException {
    receiveTimeout = timeout;

    if (!getUseSelectTimeout()) {
      platform.setTimeout(socket, false, timeout);
    }
  }

  /**
   * Retrieves the receive timeout (SO_RCVTIMEO).
   *
   * @return The receive timeout in milliseconds.
   * @exception SocketException If the option value could not be set.
   */
  public int getReceiveTimeout() throws SocketException {
    return getUseSelectTimeout()
      ? receiveTimeout
      : platform.getTimeout(socket, false);
  }

  /**
   * Reads packet data from the socket.  IPv4 ({@link #PF_INET})
   * packets will be delivered in their entirety, including the IP
   * header.  IPv6 ({@link #PF_INET6}) packet data will not include
   * the IPV6 header.
   *
   * @param data The buffer in which to store the packet data.
   * @param offset The offset into the buffer where the data should
   *               be stored.
   * @param length The number of bytes to read.
   * @param address A byte array in which to store the source address
   * of the received packet.  It may be null if you don't want to
   * retrieve the source address.  Otherwise, it must be the right
   * size to store the address (e.g., 4 bytes for an IPv4 address).
   * @exception IllegalArgumentException If the offset or lengths are
   * invalid or if the address parameter is the wrong length.
   * @exception IOException If an I/O error occurs.
   * @exception InterruptedIOException If the read operation times out.
   * @return The number of bytes read.
   */
  public int read(byte[] data, int offset, int length, byte[] address)
    throws IllegalArgumentException, IOException, InterruptedIOException
  {
    if (offset < 0 || length < 0 || length > data.length - offset) {
      throw new IllegalArgumentException("Invalid offset or length.");
    }

    if (address != null &&
        ((family == PF_INET && address.length != 4) ||
         (family == PF_INET6 && address.length != 16))) {
      throw new IllegalArgumentException("Invalid address length.");
    }

    if (getUseSelectTimeout() && receiveTimeout > 0) {
      platform.awaitReady(socket, true, receiveTimeout);
    }

    return platform.recvfrom(socket, data, offset, length, family, address);
  }

  /** Same as {@code read(data, offset, length, null);} */
  public int read(byte[] data, int offset, int length)
    throws IllegalArgumentException, IOException, InterruptedIOException
  {
    return read(data, offset, length, null);
  }

  /** Same as {@code read(data, 0, data.length, address);} */
  public int read(byte[] data, byte[] address)
    throws IOException, InterruptedIOException
  {
    return read(data, 0, data.length, address);
  }

  /** Same as {@code read(data, 0, data.length, null);} */
  public int read(byte[] data)
    throws IOException, InterruptedIOException
  {
    return read(data, 0, data.length, null);
  }

  /**
   * Writes packet data to the socket.  The data should not include
   * the IP header.  IPv4 ({@link #PF_INET}) sockets may set the
   * IP_HDRINCL option with {@link #setIPHeaderInclude}, in which case the
   * packet data should include the IP header.
   *
   * @param address The destination to write to.
   * @param data The buffer from which to copy the packet data.
   * @param offset The offset into the buffer where the data starts.
   * @param length The number of bytes to write.
   * @exception IllegalArgumentException If the offset or lengths are
   * invalid, or the address does not match the socket's protocol family.
   * @exception IOException If an I/O error occurs.
   * @exception InterruptedIOException If the write operation times out.
   * @return The number of bytes written.
   */
  public int write(InetAddress address, byte[] data, int offset, int length)
    throws IllegalArgumentException, IOException, InterruptedIOException
  {
    if (offset < 0 || length < 0 || length > data.length - offset) {
      throw new IllegalArgumentException("Invalid offset or length.");
    }

    if (getUseSelectTimeout() && sendTimeout > 0) {
      platform.awaitReady(socket, false, sendTimeout);
    }

    return platform.sendto(socket, data, offset, length, family,
                           address.getAddress(), getScopeId(address));
  }

  /** Same as {@code write(address, data, 0, data.length);} */
  public int write(InetAddress address, byte[] data)
    throws IOException, InterruptedIOException
  {
    return write(address, data, 0, data.length);
  }

}
