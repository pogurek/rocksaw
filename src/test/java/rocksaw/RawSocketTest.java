package rocksaw;

import com.savarese.rocksaw.net.RawSocket;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Checks of the FFM bindings that do not need raw-socket privileges. */
public class RawSocketTest {

  @Test
  public void protocolFamiliesAreDistinctAndPositive() {
    assertTrue(RawSocket.PF_INET > 0);
    assertTrue(RawSocket.PF_INET6 > 0);
    assertNotEquals(RawSocket.PF_INET, RawSocket.PF_INET6);
  }

  @Test
  public void protocolLookup() {
    assertEquals(1, RawSocket.getProtocolByName("icmp"));
    assertEquals(17, RawSocket.getProtocolByName("udp"));
    assertTrue(RawSocket.getProtocolByName("no-such-protocol") < 0);
  }

  @Test
  public void newSocketIsClosedAndCloseIsIdempotent() throws IOException {
    RawSocket socket = new RawSocket();
    assertFalse(socket.isOpen());
    socket.close();
    socket.close();
  }

  @Test(expected = IllegalStateException.class)
  public void bindRequiresOpenSocket() throws IOException {
    new RawSocket().bind(InetAddress.getLoopbackAddress());
  }

  @Test(expected = IllegalArgumentException.class)
  public void readRejectsBadLength() throws IOException {
    new RawSocket().read(new byte[4], 2, 4);
  }

  /** Opening either succeeds (privileged) or fails with the OS error text. */
  @Test
  public void openReportsNativeErrorOrSucceeds() throws IOException {
    try (RawSocket socket = new RawSocket()) {
      try {
        socket.open(RawSocket.PF_INET, RawSocket.getProtocolByName("icmp"));
      } catch (IOException e) {
        assertNotEquals("", e.getMessage());
        return;
      }
      assertTrue(socket.isOpen());
      socket.setReceiveTimeout(1234);
      assertEquals(1234, socket.getReceiveTimeout());
      socket.setSendBufferSize(65536);
      assertTrue(socket.getSendBufferSize() > 0);
      socket.setIPHeaderInclude(true);
      assertTrue(socket.getIPHeaderInclude());
    }
  }
}
