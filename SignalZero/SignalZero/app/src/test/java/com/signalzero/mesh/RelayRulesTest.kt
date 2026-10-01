package com.signalzero.mesh

import com.signalzero.mesh.RelayRules.Verdict
import org.junit.Assert.*
import org.junit.Test

class RelayRulesTest {
    private val now = 1_000_000L
    private fun pkt(ttl: Int = 5, hop: Int = 0, exp: Long = now + 10_000) =
        MeshPacket("abc1234567890abcdef", "s", "r", "MSG", "cipher", now - 1, exp, ttl, hop, "sig")

    @Test fun acceptsValidPacket() = assertEquals(Verdict.ACCEPT, RelayRules.check(pkt(), now, false, 0, 500))
    @Test fun dropsDuplicate() = assertEquals(Verdict.DROP_DUPLICATE, RelayRules.check(pkt(), now, true, 0, 500))
    @Test fun dropsExpired() = assertEquals(Verdict.DROP_EXPIRED, RelayRules.check(pkt(exp = now - 1), now, false, 0, 500))
    @Test fun dropsZeroTtl() = assertEquals(Verdict.DROP_TTL, RelayRules.check(pkt(ttl = 0), now, false, 0, 500))
    @Test fun dropsTooManyHops() = assertEquals(Verdict.DROP_HOPS, RelayRules.check(pkt(hop = MeshPacket.MAX_HOPS), now, false, 0, 500))
    @Test fun dropsOversize() = assertEquals(Verdict.DROP_SIZE, RelayRules.check(pkt(), now, false, 0, MeshPacket.MAX_BYTES + 1))
    @Test fun dropsWhenStoreFull() = assertEquals(Verdict.DROP_STORE_FULL, RelayRules.check(pkt(), now, false, MeshPacket.MAX_STORE, 500))

    @Test fun forwardingDecrementsTtlAndCountsHops() {
        val f = pkt(ttl = 3, hop = 1).forwarded()
        assertEquals(2, f.ttl); assertEquals(2, f.hopCount)
    }

    /** A -> B -> C -> B: the second arrival at B is a duplicate, so the packet cannot loop forever. */
    @Test fun loopIsStoppedBySeenSet() {
        val seen = HashSet<String>(); val p = pkt()
        fun arrive(): Verdict { val v = RelayRules.check(p, now, p.packetId in seen, 0, 500); if (v == Verdict.ACCEPT) seen += p.packetId; return v }
        assertEquals(Verdict.ACCEPT, arrive()); assertEquals(Verdict.DROP_DUPLICATE, arrive())
    }

    @Test fun ttlAlwaysEndsTheJourney() {
        var p = pkt(ttl = MeshPacket.DEFAULT_TTL); var hops = 0
        while (RelayRules.check(p, now, false, 0, 500) == Verdict.ACCEPT) { p = p.forwarded(); hops++ }
        assertTrue(hops <= MeshPacket.MAX_HOPS)
    }

    @Test fun signedDataExcludesMutableFields() =
        assertEquals(pkt(ttl = 5, hop = 0).signedData(), pkt(ttl = 2, hop = 3).signedData())

    @Test fun jsonRoundTrip() { val p = pkt(); assertEquals(p, MeshPacket.fromJson(p.toJson())) }
}
