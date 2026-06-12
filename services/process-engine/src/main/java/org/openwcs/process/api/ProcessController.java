package org.openwcs.process.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.openwcs.process.api.ProcessDtos.InstanceView;
import org.openwcs.process.api.ProcessDtos.ProcessDefinitionView;
import org.openwcs.process.api.ProcessDtos.StartInstanceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BPMN process API (build.md §7): deploy admin-designed process definitions, list them, and
 * start / inspect running instances. Service tasks in a process originate WCS work via delegates
 * (e.g. {@code dispatchDeviceTask}).
 */
@RestController
@RequestMapping("/api/process")
public class ProcessController {

    private static final Logger log = LoggerFactory.getLogger(ProcessController.class);

    private final RepositoryService repository;
    private final RuntimeService runtime;
    private final HistoryService history;

    public ProcessController(RepositoryService repository, RuntimeService runtime, HistoryService history) {
        this.repository = repository;
        this.runtime = runtime;
        this.history = history;
    }

    @GetMapping("/definitions")
    public List<ProcessDefinitionView> definitions() {
        return repository.createProcessDefinitionQuery().latestVersion().orderByProcessDefinitionKey().asc().list()
                .stream().map(ProcessController::toView).toList();
    }

    /** Deploy a BPMN 2.0 definition (raw XML body). */
    @PostMapping(value = "/definitions", consumes = MediaType.APPLICATION_XML_VALUE)
    public List<ProcessDefinitionView> deploy(@RequestParam(defaultValue = "process") String name,
                                              @RequestBody String bpmnXml) {
        Deployment deployment = repository.createDeployment()
                .name(name)
                .addString(name + ".bpmn20.xml", bpmnXml)
                .deploy();
        List<ProcessDefinition> definitions =
                repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).list();
        for (ProcessDefinition d : definitions) {
            log.info("process definition deployed: key {} version {} (deployment '{}'): service tasks in it can now originate WCS work",
                    d.getKey(), d.getVersion(), name);
        }
        return definitions.stream().map(ProcessController::toView).toList();
    }

    @PostMapping("/instances")
    public InstanceView start(@Valid @RequestBody StartInstanceRequest request) {
        Map<String, Object> vars = request.variables() == null ? Map.of() : request.variables();
        ProcessInstance instance = runtime.startProcessInstanceByKey(
                request.processKey(), request.businessKey(), vars);
        log.info("process instance {} started: definition {} for business key {} ({} variables{})",
                instance.getId(), instance.getProcessDefinitionKey(), instance.getBusinessKey(),
                vars.size(), instance.isEnded() ? ", ran to completion synchronously" : "");
        return new InstanceView(instance.getId(), instance.getProcessDefinitionKey(),
                instance.getBusinessKey(), instance.isEnded());
    }

    @GetMapping("/instances/{id}")
    public ResponseEntity<InstanceView> instance(@PathVariable String id) {
        ProcessInstance active = runtime.createProcessInstanceQuery().processInstanceId(id).singleResult();
        if (active != null) {
            return ResponseEntity.ok(new InstanceView(active.getId(), active.getProcessDefinitionKey(),
                    active.getBusinessKey(), false));
        }
        HistoricProcessInstance done = history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult();
        if (done != null) {
            return ResponseEntity.ok(new InstanceView(done.getId(), done.getProcessDefinitionKey(),
                    done.getBusinessKey(), done.getEndTime() != null));
        }
        return ResponseEntity.notFound().build();
    }

    private static ProcessDefinitionView toView(ProcessDefinition d) {
        return new ProcessDefinitionView(d.getId(), d.getKey(), d.getName(), d.getVersion());
    }
}
