package io.zeebe.cloud.events.router;

import com.salaboy.cloudevents.helper.CloudEventsHelper;
import io.cloudevents.CloudEvent;
import io.cloudevents.json.Json;
import io.cloudevents.v03.AttributesImpl;
import io.zeebe.client.api.worker.JobClient;
import io.zeebe.cloudevents.ZeebeCloudEventExtension;
import io.zeebe.cloudevents.ZeebeCloudEventsHelper;
import io.zeebe.spring.client.ZeebeClientLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@Slf4j
public class ZeebeCloudEventsRouterController {

    @Autowired
    private ZeebeClientLifecycle zeebeClient;


    @Autowired
    private CloudEventsZeebeMappingsService mappingsService;

    @Autowired
    private JobClient jobClient;

    @GetMapping("/status")
    public String getStatus() {
        log.info("> Broker Contact Point: " + zeebeClient.getConfiguration().getBrokerContactPoint());
        log.info("> Plain Text Connection Enabled: " + zeebeClient.getConfiguration().isPlaintextConnectionEnabled());
        return "{ \"zeebe.broker.contactPoint\": " + zeebeClient.getConfiguration().getBrokerContactPoint() + ", " +
                "\"plainTextConnection\":" + zeebeClient.getConfiguration().isPlaintextConnectionEnabled() + "}";
    }

    @GetMapping("/jobs")
    public String printPendingJobs() {
        Map<String, Map<String, Set<String>>> jobs = mappingsService.getAllPendingJobs();
        return jobs.keySet().stream()
                .map(key -> key + "=" + jobs.get(key))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    @GetMapping("/messages")
    public String messages() {
        Map<String, Set<String>> allExpectedBPMNMessages = mappingsService.getAllMessages();
        return allExpectedBPMNMessages.keySet().stream()
                .map(key -> key + "=" + allExpectedBPMNMessages.get(key))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    @PostMapping("/")
    public String recieveCloudEvent(@RequestHeader Map<String, String> headers, @RequestBody Object body) {
        CloudEvent<AttributesImpl, String> cloudEvent = ZeebeCloudEventsHelper.parseZeebeCloudEventFromRequest(headers, body);

        final String json = Json.encode(cloudEvent);
        log.debug("Cloud Event: " + json);

        ZeebeCloudEventExtension zeebeCloudEventExtension = (ZeebeCloudEventExtension) cloudEvent.getExtensions().get("zeebe");
        if (zeebeCloudEventExtension != null) {
            String workflowKey = zeebeCloudEventExtension.getWorkflowKey();
            String workflowInstanceKey = zeebeCloudEventExtension.getWorkflowInstanceKey();
            String jobKey = zeebeCloudEventExtension.getJobKey();

            Set<String> pendingJobs = mappingsService.getPendingJobsForWorkflowKey(workflowKey).get(workflowInstanceKey);
            if (pendingJobs != null) {
                if (!pendingJobs.isEmpty()) {
                    if (pendingJobs.contains(jobKey)) {
                        //@TODO: deal with Optionals for Data
                        jobClient.newCompleteCommand(Long.valueOf(jobKey)).variables(cloudEvent.getData().get()).send().join();
                        mappingsService.removePendingJobFromWorkflow(workflowKey, workflowInstanceKey, jobKey);
                    } else {
                        log.error("Job Key: " + jobKey + " not found");
                        throw new IllegalStateException("Job Key: " + jobKey + " not found");
                    }
                } else {
                    log.error("This workflow instance key: " + workflowInstanceKey + " doesn't have any pending jobs");
                    throw new IllegalStateException("This workflow instance key: " + workflowInstanceKey + " doesn't have any pending jobs");
                }
            } else {
                log.error("Workflow instance key: " + workflowInstanceKey + " not found");
                throw new IllegalStateException("Workflow instance key: " + workflowInstanceKey + " not found");
            }
        } else {
            throw new IllegalStateException("Cloud Event recieved doesn't have Zeebe Extension, which is required to complete a job");
        }

        // @TODO: decide on return types
        return "OK!";
    }

    @PostMapping("/workflows")
    public void addStartWorkflowCloudEventMapping(@RequestBody WorkflowByCloudEvent wbce) {
        mappingsService.registerStartWorkflowByCloudEvent(wbce);
    }

    @GetMapping("/workflows")
    public Map<String, WorkflowByCloudEvent> getStartWorkflowCloudEventMapping() {
        return mappingsService.getStartWorkflowByCloudEvents();
    }

    @PostMapping("/workflow")
    public void startWorkflow(@RequestHeader Map<String, String> headers, @RequestBody Map<String, String> body) {
        CloudEvent<AttributesImpl, String> cloudEvent = CloudEventsHelper.parseFromRequest(headers, body);
        WorkflowByCloudEvent workflowByCloudEvent = mappingsService.getStartWorkflowByCloudEvent(cloudEvent.getAttributes().getType());
        if (workflowByCloudEvent.getBpmnProcessId() != null && !workflowByCloudEvent.getBpmnProcessId().equals("")) {
            //@TODO: deal with empty body for variables
            if (workflowByCloudEvent.getVersion() == null || workflowByCloudEvent.getVersion().equals("")) {
                zeebeClient.newCreateInstanceCommand().bpmnProcessId(workflowByCloudEvent.getBpmnProcessId())
                        .latestVersion().variables(body)
                        .send()
                        .join();
            } else {
                zeebeClient.newCreateInstanceCommand().bpmnProcessId(workflowByCloudEvent.getBpmnProcessId())
                        .version(Integer.valueOf(workflowByCloudEvent.getVersion()))
                        .variables(body)
                        .send()
                        .join();
            }
        } else if (workflowByCloudEvent.getWorkflowKey() != null && !workflowByCloudEvent.getWorkflowKey().equals("")) {
            zeebeClient.newCreateInstanceCommand().workflowKey(Long.valueOf(workflowByCloudEvent.getWorkflowKey()))
                    .variables(body)
                    .send()
                    .join();
        } else {
            log.error("No workflow was started with: " + workflowByCloudEvent.toString());
        }
    }

    @PostMapping("/messages")
    public void addExpectedMessage(@RequestBody MessageForWorkflowKey messageForWorkflowKey) {
        //@TODO: Next step check and advertise which messages are expected by which workflows
        //       This can be scanned on Deploy Workflow, and we can use that to register the workflow as a consumer of events
        mappingsService.addMessageForWorkflowKey(messageForWorkflowKey.getWorkflowKey(), messageForWorkflowKey.getMessageName());
    }

    @PostMapping("/message")
    public String recieveCloudEventForMessage(@RequestHeader Map<String, String> headers, @RequestBody Object body) {
        CloudEvent<AttributesImpl, String> cloudEvent = ZeebeCloudEventsHelper.parseZeebeCloudEventFromRequest(headers, body);
        final String json = Json.encode(cloudEvent);
        log.info("Cloud Event: " + json);

        //@TODO: deal with empty type and no correlation key.
        String cloudEventType = cloudEvent.getAttributes().getType();
        String correlationKey = ((ZeebeCloudEventExtension) cloudEvent.getExtensions().get("zeebe")).getCorrelationKey();

        //@TODO: deal with optional for Data, for empty Data
        zeebeClient.newPublishMessageCommand()
                .messageName(cloudEventType)
                .correlationKey(correlationKey)
                .variables(cloudEvent.getData().get())
                .send().join();

        // @TODO: decide on return types
        return "OK!";
    }
}