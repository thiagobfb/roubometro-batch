package br.com.roubometro.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class BatchJobConfig {

    @Bean
    public Job roubometroDataSyncJob(
            JobRepository jobRepository,
            Step dataAcquisitionStep,
            Step dataProcessingStep,
            Step finalizationStep,
            NewFileDecider newFileDecider
    ) {
        log.info("Configuring job: roubometroDataSyncJob (flow: dataAcquisition -> decider -> [process|skip] -> finalization)");
        return new JobBuilder("roubometroDataSyncJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(dataAcquisitionStep)
                .next(newFileDecider)
                .on(NewFileDecider.PROCESS).to(dataProcessingStep).next(finalizationStep)
                .from(newFileDecider).on(NewFileDecider.SKIP).to(finalizationStep)
                .from(newFileDecider).on("*").to(finalizationStep)
                .end()
                .build();
    }
}
