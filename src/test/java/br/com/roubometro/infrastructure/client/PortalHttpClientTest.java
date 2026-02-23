package br.com.roubometro.infrastructure.client;

import br.com.roubometro.config.AppProperties;
import br.com.roubometro.domain.exception.PortalAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortalHttpClientTest {

    private PortalHttpClient client;

    @BeforeEach
    void setUp() {
        var portal = new AppProperties.PortalProperties(
                "https://www.ispdados.rj.gov.br/Arquivos/BaseMunicipioMensal.csv",
                "https://www.ispdados.rj.gov.br/estatistica.html",
                5000, 10000, 1, 100
        );
        var batch = new AppProperties.BatchProperties(50, 100, "/tmp/test", "ISO-8859-1", ";", false);
        var schedule = new AppProperties.ScheduleProperties("-", false);
        var props = new AppProperties(portal, batch, schedule);
        client = new PortalHttpClient(props);
    }

    @Test
    void rejectsNonWhitelistedHost() {
        assertThatThrownBy(() -> client.download("https://evil.com/malware.csv"))
                .isInstanceOf(PortalAccessException.class)
                .hasMessageContaining("not in whitelist");
    }

    @Test
    void rejectsIpLiteralUrl() {
        assertThatThrownBy(() -> client.download("http://127.0.0.1/Arquivos/data.csv"))
                .isInstanceOf(PortalAccessException.class)
                .hasMessageContaining("not in whitelist");
    }

    @Test
    void rejectsFtpScheme() {
        assertThatThrownBy(() -> client.download("ftp://www.ispdados.rj.gov.br/file.csv"))
                .isInstanceOf(PortalAccessException.class)
                .hasMessageContaining("Only HTTP/HTTPS");
    }

    @Test
    void rejectsInvalidUrl() {
        assertThatThrownBy(() -> client.download("not a url"))
                .isInstanceOf(PortalAccessException.class);
    }

    @Test
    void acceptsWhitelistedHost() {
        // This should not throw for URL validation — it may fail on actual HTTP call
        // but the host validation itself should pass
        // We test this by checking that the exception is NOT about whitelist
        try {
            client.download("https://www.ispdados.rj.gov.br/nonexistent");
        } catch (PortalAccessException e) {
            // Expected: connection failure, but NOT a whitelist error
            org.assertj.core.api.Assertions.assertThat(e.getMessage())
                    .doesNotContain("not in whitelist");
        }
    }
}
