package br.com.roubometro.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.batch.core.*;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBatchTest
class JobIntegrationTest extends AbstractBatchIntegrationTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().port(8089));
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
        // Clean monthly_stats and batch tables between tests
        jdbcTemplate.update("DELETE FROM monthly_stats");
        jdbcTemplate.update("DELETE FROM batch_job_execution_report");
        jdbcTemplate.update("DELETE FROM batch_file_metadata");
    }

    private String loadCsvFixture() throws IOException {
        return Files.readString(
                Path.of("src/test/resources/fixtures/sample_valid.csv"),
                java.nio.charset.Charset.forName("ISO-8859-1")
        );
    }

    @Test
    void happyPath_downloadsAndProcessesCsv() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/Arquivos/BaseMunicipioMensal.csv"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(loadCsvFixture())
                        .withHeader("Content-Type", "text/csv")));

        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addLong("timestamp", System.currentTimeMillis())
                        .toJobParameters()
        );

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify data was written
        var statsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monthly_stats", Integer.class
        );
        assertThat(statsCount).isGreaterThan(0);

        // Verify file metadata was recorded
        var metadataCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_file_metadata WHERE processed = 1", Integer.class
        );
        assertThat(metadataCount).isEqualTo(1);

        // Verify report was created
        var reportCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_job_execution_report WHERE status = 'COMPLETED'", Integer.class
        );
        assertThat(reportCount).isEqualTo(1);
    }

    @Test
    void rerunSameFile_skipsProcessing() throws Exception {
        String csv = loadCsvFixture();
        wireMock.stubFor(get(urlPathEqualTo("/Arquivos/BaseMunicipioMensal.csv"))
                .willReturn(aResponse().withStatus(200).withBody(csv)));

        // First run
        jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addLong("timestamp", System.currentTimeMillis())
                        .toJobParameters()
        );

        int statsAfterFirst = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM monthly_stats", Integer.class);
        assertThat(statsAfterFirst).isGreaterThan(0);

        // Second run (same file)
        JobExecution secondExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addLong("timestamp", System.currentTimeMillis() + 1)
                        .toJobParameters()
        );

        assertThat(secondExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify SKIPPED report was created
        var skippedReports = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_job_execution_report WHERE status = 'SKIPPED'", Integer.class
        );
        assertThat(skippedReports).isGreaterThanOrEqualTo(1);

        // Same data count (idempotent)
        int statsAfterSecond = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM monthly_stats", Integer.class);
        assertThat(statsAfterSecond).isEqualTo(statsAfterFirst);
    }

    @Test
    void portalDown_jobFails() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/Arquivos/BaseMunicipioMensal.csv"))
                .willReturn(aResponse().withStatus(500)));

        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addLong("timestamp", System.currentTimeMillis())
                        .toJobParameters()
        );

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    void idempotency_twoRunsSameResult() throws Exception {
        String csv = loadCsvFixture();

        // Simulate two different files with same content by resetting metadata between runs
        wireMock.stubFor(get(urlPathEqualTo("/Arquivos/BaseMunicipioMensal.csv"))
                .willReturn(aResponse().withStatus(200).withBody(csv)));

        jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addLong("timestamp", System.currentTimeMillis())
                        .toJobParameters()
        );

        int countAfterFirst = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM monthly_stats", Integer.class);

        // Clear metadata so the second run thinks it's a new file
        jdbcTemplate.update("DELETE FROM batch_file_metadata");

        jobLauncherTestUtils.launchJob(
                new JobParametersBuilder()
                        .addLong("timestamp", System.currentTimeMillis() + 1)
                        .toJobParameters()
        );

        int countAfterSecond = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM monthly_stats", Integer.class);

        // Same number of records (ON DUPLICATE KEY UPDATE)
        assertThat(countAfterSecond).isEqualTo(countAfterFirst);
    }
}
