package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.openwcs.flow.client.FlowProperties;
import org.openwcs.flow.client.HttpDeviceClient;
import org.openwcs.flow.client.NoAdapterException;
import org.springframework.web.client.RestClient;

/**
 * Unit-tests the device-task target resolution: emulator mode routes every family to the single
 * equipment-emulator; real mode routes by family to the per-family adapter.
 */
class HttpDeviceClientTest {

    private HttpDeviceClient client(FlowProperties props) {
        // The RestClient.Builder and emulator-mode client are unused by resolveBaseUrl.
        return new HttpDeviceClient(RestClient.builder(), props, () -> false);
    }

    private FlowProperties props() {
        FlowProperties p = new FlowProperties();
        p.getAdapters().put("ASRS", "http://asrs:9096");
        p.getAdapters().put("CONVEYOR", "http://conveyor:9091");
        p.setEmulatorUrl("http://emulator:9097");
        return p;
    }

    @Test
    void emulatorModeRoutesEveryFamilyToTheEmulator() {
        HttpDeviceClient c = client(props());
        assertThat(c.resolveBaseUrl("ASRS", true)).isEqualTo("http://emulator:9097");
        assertThat(c.resolveBaseUrl("AUTOSTORE", true)).isEqualTo("http://emulator:9097");
    }

    @Test
    void realModeRoutesByFamilyToTheAdapter() {
        HttpDeviceClient c = client(props());
        assertThat(c.resolveBaseUrl("ASRS", false)).isEqualTo("http://asrs:9096");
        assertThat(c.resolveBaseUrl("CONVEYOR", false)).isEqualTo("http://conveyor:9091");
    }

    @Test
    void realModeWithNoAdapterForFamilyThrows() {
        HttpDeviceClient c = client(props());
        assertThatThrownBy(() -> c.resolveBaseUrl("AMR", false)).isInstanceOf(NoAdapterException.class);
    }

    @Test
    void emulatorModeWithNoEmulatorUrlThrows() {
        FlowProperties p = props();
        p.setEmulatorUrl("");
        assertThatThrownBy(() -> client(p).resolveBaseUrl("ASRS", true)).isInstanceOf(NoAdapterException.class);
    }
}
