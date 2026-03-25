package br.com.roubometro.config;

import br.com.roubometro.application.processor.EstatisticaItemProcessor;
import br.com.roubometro.application.service.CategoryLookupService;
import br.com.roubometro.application.service.MunicipalityLookupService;
import br.com.roubometro.application.step.DataAcquisitionTasklet;
import br.com.roubometro.application.step.FinalizationTasklet;
import br.com.roubometro.domain.exception.CsvParsingException;
import br.com.roubometro.domain.exception.DataIntegrityException;
import br.com.roubometro.domain.model.CsvEstatisticaRow;
import org.springframework.batch.item.file.FlatFileParseException;
import br.com.roubometro.domain.model.MonthlyStat;
import br.com.roubometro.infrastructure.writer.ListUnpackingItemWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

@Slf4j
@Configuration
public class StepConfig {

    @Bean
    public Step dataAcquisitionStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DataAcquisitionTasklet dataAcquisitionTasklet
    ) {
        log.info("Configuring step: dataAcquisitionStep");
        return new StepBuilder("dataAcquisitionStep", jobRepository)
                .tasklet(dataAcquisitionTasklet, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    @StepScope
    public EstatisticaItemProcessor estatisticaItemProcessor(
            CategoryLookupService categoryLookupService,
            MunicipalityLookupService municipalityLookupService,
            @Value("${roubometro.batch.include-zero-values:false}") boolean includeZeroValues,
            @Value("#{jobExecutionContext['csvFileName'] ?: 'unknown'}") String sourceFile
    ) {
        log.info("Creating EstatisticaItemProcessor: sourceFile={}, includeZeroValues={}", sourceFile, includeZeroValues);
        return new EstatisticaItemProcessor(
                categoryLookupService, municipalityLookupService, includeZeroValues, sourceFile
        );
    }

    @Bean
    public Step dataProcessingStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            FlatFileItemReader<CsvEstatisticaRow> csvItemReader,
            EstatisticaItemProcessor estatisticaItemProcessor,
            JdbcBatchItemWriter<MonthlyStat> monthlyStatJdbcWriter,
            CategoryLookupService categoryLookupService,
            MunicipalityLookupService municipalityLookupService,
            AppProperties appProperties
    ) {
        log.info("Configuring step: dataProcessingStep (chunkSize={}, skipLimit={})",
                appProperties.batch().chunkSize(), appProperties.batch().skipLimit());

        ListUnpackingItemWriter<MonthlyStat> unpackingWriter = new ListUnpackingItemWriter<>(monthlyStatJdbcWriter);

        return new StepBuilder("dataProcessingStep", jobRepository)
                .<CsvEstatisticaRow, List<MonthlyStat>>chunk(appProperties.batch().chunkSize(), transactionManager)
                .reader(csvItemReader)
                .processor(estatisticaItemProcessor)
                .writer(unpackingWriter)
                .faultTolerant()
                .skipLimit(appProperties.batch().skipLimit())
                .skip(CsvParsingException.class)
                .skip(DataIntegrityException.class)
                .skip(NumberFormatException.class)
                .skip(FlatFileParseException.class)
                .retryLimit(3)
                .retry(DeadlockLoserDataAccessException.class)
                .retry(TransientDataAccessException.class)
                .listener(new LookupInitializerListener(categoryLookupService, municipalityLookupService))
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    public Step finalizationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            FinalizationTasklet finalizationTasklet
    ) {
        log.info("Configuring step: finalizationStep");
        return new StepBuilder("finalizationStep", jobRepository)
                .tasklet(finalizationTasklet, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }
}
