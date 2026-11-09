package com.vmlive.networktoolkit;

import android.content.Context;
import android.test.InstrumentationTestCase;
import com.vmlive.networktoolkit.core.NetworkTools.Cancellation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Device tests avoid scanning peers: the only selected address is the device's actual own IPv4. */
@SuppressWarnings("deprecation")
public final class LanAndroidTest extends InstrumentationTestCase {
    public void testOwnAddressScanReportsOnlyRealLocalDevice() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        String detected = LanDiscovery.defaultCidr(context);
        assertFalse("The selected Android test device requires an active private IPv4 network", detected.isEmpty());
        String ip = detected.substring(0, detected.indexOf('/'));
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        String report = LanDiscovery.scan(context, ip + "/32", new Cancellation(), events::add);
        assertTrue(report, report.contains("在线设备：1"));
        assertTrue(report, report.contains("已扫描：1/1"));
        assertTrue(report, report.contains("进度：100%"));
        int local = 0;
        for (String event : events) {
            if (!event.startsWith("device|")) continue;
            String[] fields = event.split("\\|", -1);
            assertEquals(6, fields.length);
            assertEquals(ip, fields[1]);
            assertEquals("本机", fields[2]);
            assertEquals("", fields[3]); // Known local address is not a fabricated latency measurement.
            assertEquals("local", fields[4]);
            local++;
        }
        assertEquals("Only the own device should be emitted once", 1, local);
        assertTrue(events.toString(), events.contains("scan|1|1|1"));
    }

    public void testCancelledBonjourNeverStartsDiscovery() throws Exception {
        Cancellation cancelled = new Cancellation(); cancelled.cancel();
        try {
            LanDiscovery.bonjour(getInstrumentation().getTargetContext(), cancelled, null);
            fail("A cancelled request must not start mDNS discovery");
        } catch (CancellationException expected) { /* before accessing the NSD service */ }
    }

    public void testCancelledSsdpNeverSendsMulticast() throws Exception {
        Cancellation cancelled = new Cancellation(); cancelled.cancel();
        try {
            LanDiscovery.ssdp(getInstrumentation().getTargetContext(), cancelled, null);
            fail("A cancelled request must not acquire Wi-Fi lock or send packets");
        } catch (CancellationException expected) { /* before acquiring lock or creating socket */ }
    }
}
