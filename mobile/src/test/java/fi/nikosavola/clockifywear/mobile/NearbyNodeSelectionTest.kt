package fi.nikosavola.clockifywear.mobile

import com.google.android.gms.wearable.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Node] is an interface (not a final Play Services class), so it's fakeable with a plain class -
 * no Play Services runtime needed to exercise [nearbyNodeId].
 */
private class FakeNode(
  private val id: String,
  private val nearby: Boolean,
  private val displayName: String = id,
) : Node {
  override fun getId(): String = id

  override fun getDisplayName(): String = displayName

  override fun isNearby(): Boolean = nearby
}

class NearbyNodeSelectionTest {
  @Test
  fun `no nodes returns null`() {
    assertNull(nearbyNodeId(emptyList()))
  }

  @Test
  fun `no nearby nodes returns null even if some are cloud-reachable`() {
    // This is the case that matters: FILTER_REACHABLE alone would include a cloud-reachable-but-
    // not-Bluetooth-connected node, and sending the key there sends it into a black hole - the
    // phone would only find out 90 seconds later, via Timeout.
    val nodes = listOf(FakeNode("a", nearby = false), FakeNode("b", nearby = false))

    assertNull(nearbyNodeId(nodes))
  }

  @Test
  fun `a nearby node among non-nearby ones is selected regardless of position`() {
    val nodes =
      listOf(
        FakeNode("a", nearby = false),
        FakeNode("b", nearby = true),
        FakeNode("c", nearby = false),
      )

    assertEquals("b", nearbyNodeId(nodes))
  }

  @Test
  fun `the first nearby node is selected when more than one is nearby`() {
    val nodes = linkedSetOf(FakeNode("a", nearby = true), FakeNode("b", nearby = true))

    assertEquals("a", nearbyNodeId(nodes))
  }
}
