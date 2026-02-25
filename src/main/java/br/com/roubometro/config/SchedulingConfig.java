package br.com.roubometro.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "roubometro.schedule.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
@RequiredArgsConstructor
public class SchedulingConfig {

    private final JobLauncher jobLauncher;
    private final Job roubometroDataSyncJob;

    @Scheduled(cron = "${roubometro.schedule.cron}")
    public void runJob() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();

            log.info("Scheduled job execution starting...");
            jobLauncher.run(roubometroDataSyncJob, params);
        } catch (Exception e) {
            log.error("Scheduled job execution failed", e);
        }
    }
}
