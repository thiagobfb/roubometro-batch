package br.com.roubometro.config;

import br.com.roubometro.application.service.CategoryLookupService;
import br.com.roubometro.application.service.MunicipalityLookupService;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

public class LookupInitializerListener implements StepExecutionListener {

    private final CategoryLookupService categoryLookupService;
    private final MunicipalityLookupService municipalityLookupService;

    public LookupInitializerListener(
            CategoryLookupService categoryLookupService,
            MunicipalityLookupService municipalityLookupService
    ) {
        this.categoryLookupService = categoryLookupService;
        this.municipalityLookupService = municipalityLookupService;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        categoryLookupService.initialize();
        municipalityLookupService.initialize();
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        return stepExecution.getExitStatus();
    }
}
