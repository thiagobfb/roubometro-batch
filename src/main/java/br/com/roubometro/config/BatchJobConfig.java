package br.com.roubometro.config;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        return new JobBuilder("roubometroDataSyncJob", jobRepository)
                .start(dataAcquisitionStep)
                .next(newFileDecider)
                .on(NewFileDecider.PROCESS).to(dataProcessingStep).next(finalizationStep)
                .from(newFileDecider).on(NewFileDecider.SKIP).to(finalizationStep)
                .from(newFileDecider).on("*").to(finalizationStep)
                .end()
                .build();
    }
}
