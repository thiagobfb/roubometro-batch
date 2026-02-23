package br.com.roubometro.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.stereotype.Component;

@Component
public class NewFileDecider implements JobExecutionDecider {

    private static final Logger log = LoggerFactory.getLogger(NewFileDecider.class);

    public static final String PROCESS = "PROCESS";
    public static final String SKIP = "SKIP";

    @Override
    public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
        boolean newFileAvailable = jobExecution.getExecutionContext().containsKey("newFileAvailable")
                && Boolean.TRUE.equals(jobExecution.getExecutionContext().get("newFileAvailable"));

        String decision = newFileAvailable ? PROCESS : SKIP;
        log.info("NewFileDecider: newFileAvailable={}, decision={}", newFileAvailable, decision);
        return new FlowExecutionStatus(decision);
    }
}
