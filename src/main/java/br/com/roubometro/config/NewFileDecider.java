package br.com.roubometro.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NewFileDecider implements JobExecutionDecider {

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
