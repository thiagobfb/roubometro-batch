package br.com.roubometro.config;

import br.com.roubometro.application.service.CategoryLookupService;
import br.com.roubometro.application.service.MunicipalityLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

@Slf4j
@RequiredArgsConstructor
public class LookupInitializerListener implements StepExecutionListener {

    private final CategoryLookupService categoryLookupService;
    private final MunicipalityLookupService municipalityLookupService;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        log.info("Initializing lookup caches before step: {}", stepExecution.getStepName());
        categoryLookupService.initialize();
        municipalityLookupService.initialize();
        log.info("Lookup caches initialized: categories={}, municipalities={}",
                categoryLookupService.getCacheSize(), municipalityLookupService.getCacheSize());
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        log.info("Step {} completed: status={}, readCount={}, writeCount={}, skipCount={}",
                stepExecution.getStepName(),
                stepExecution.getExitStatus().getExitCode(),
                stepExecution.getReadCount(),
                stepExecution.getWriteCount(),
                stepExecution.getSkipCount());
        return stepExecution.getExitStatus();
    }
}
